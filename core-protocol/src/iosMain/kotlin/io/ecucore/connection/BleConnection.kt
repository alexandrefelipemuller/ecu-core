package io.ecucore.connection

import io.ecucore.shared.Logger
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.CoreBluetooth.CBCentralManager
import platform.CoreBluetooth.CBCentralManagerDelegateProtocol
import platform.CoreBluetooth.CBCharacteristic
import platform.CoreBluetooth.CBCharacteristicPropertyWriteWithoutResponse
import platform.CoreBluetooth.CBCharacteristicWriteWithResponse
import platform.CoreBluetooth.CBCharacteristicWriteWithoutResponse
import platform.CoreBluetooth.CBManagerStatePoweredOn
import platform.CoreBluetooth.CBPeripheral
import platform.CoreBluetooth.CBPeripheralDelegateProtocol
import platform.CoreBluetooth.CBService
import platform.CoreBluetooth.CBUUID
import platform.Foundation.*
import platform.darwin.NSObject
import platform.posix.usleep
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * BLE-UART bridge connection (HM-10/Nordic-UART-style modules), implementing the real
 * [ISpeeduinoConnection] contract via CoreBluetooth — unlike the desktopApp's `BleManager`
 * (which only drives the Connection screen's scan/picker UI), this class is what
 * [io.ecucore.SpeeduinoClient] actually talks to.
 *
 * CoreBluetooth is delegate/callback-driven and BLE writes are chunked to the negotiated MTU
 * instead of being one continuous stream. [connect] bridges that with `suspendCancellableCoroutine`
 * (fine, since it's a suspend function); [send]/[receive] are plain blocking calls per
 * [ISpeeduinoConnection], so incoming notification bytes are pushed into the shared
 * [ByteStreamBuffer] from [peripheral]'s delegate callback and [receive] just polls it — the same
 * bridge [NativeTcpConnection] uses for its background socket-reading loop.
 */
@OptIn(ExperimentalForeignApi::class)
class BleConnection(
    private val deviceId: String,
    private val deviceNameFilter: String?,
    private val uartServiceUuid: String = DEFAULT_UART_SERVICE_UUID,
    private val rxCharUuid: String = DEFAULT_TX_CHAR_UUID,
    private val txCharUuid: String = DEFAULT_TX_CHAR_UUID,
    private val timeoutMs: Int = 8000
) : ISpeeduinoConnection {

    companion object {
        private const val TAG = "BleConnectionIOS"
        const val DEFAULT_UART_SERVICE_UUID = "0000FFE0-0000-1000-8000-00805F9B34FB"
        const val DEFAULT_TX_CHAR_UUID = "0000FFE1-0000-1000-8000-00805F9B34FB"
        private const val FALLBACK_MAX_WRITE_CHUNK = 20 // BLE 4.0 minimum ATT MTU payload

        suspend fun scanDevices(timeoutMs: Long): List<BleDevice> = BleDeviceScanner.scan(timeoutMs)
    }

    private var centralManager: CBCentralManager? = null
    private var peripheral: CBPeripheral? = null
    private var rxCharacteristic: CBCharacteristic? = null
    private var txCharacteristic: CBCharacteristic? = null

    private val inbound = ByteStreamBuffer()
    private var connected = false
    private var onConnectionStateChanged: ((Boolean) -> Unit)? = null
    private var onError: ((String) -> Unit)? = null

    private var poweredOnContinuation: CancellableContinuation<Unit>? = null
    private var connectContinuation: CancellableContinuation<Unit>? = null

    private val delegate = object : NSObject(), CBCentralManagerDelegateProtocol, CBPeripheralDelegateProtocol {
        override fun centralManagerDidUpdateState(central: CBCentralManager) {
            if (central.state == CBManagerStatePoweredOn) {
                poweredOnContinuation?.resume(Unit)
                poweredOnContinuation = null
            }
        }

        override fun centralManager(central: CBCentralManager, didConnectPeripheral: CBPeripheral) {
            didConnectPeripheral.delegate = this
            didConnectPeripheral.discoverServices(listOf(CBUUID.UUIDWithString(uartServiceUuid)))
        }

        override fun centralManager(central: CBCentralManager, didFailToConnectPeripheral: CBPeripheral, error: NSError?) {
            failConnect(error?.localizedDescription ?: "Falha ao conectar BLE")
        }

        override fun peripheral(peripheral: CBPeripheral, didDiscoverServices: NSError?) {
            if (didDiscoverServices != null) {
                failConnect("Falha ao descobrir serviços BLE: ${didDiscoverServices.localizedDescription}")
                return
            }
            val services = peripheral.services.orEmpty().filterIsInstance<CBService>()
            if (services.isEmpty()) {
                failConnect("Nenhum serviço UART encontrado no dispositivo")
                return
            }
            val charUuids = listOf(
                CBUUID.UUIDWithString(rxCharUuid),
                CBUUID.UUIDWithString(txCharUuid),
            ).distinctBy { it.UUIDString }
            services.forEach { service -> peripheral.discoverCharacteristics(charUuids, service) }
        }

        override fun peripheral(
            peripheral: CBPeripheral,
            didDiscoverCharacteristicsForService: CBService,
            error: NSError?
        ) {
            if (error != null) {
                failConnect("Falha ao descobrir characteristics BLE: ${error.localizedDescription}")
                return
            }
            val characteristics = didDiscoverCharacteristicsForService.characteristics
                .orEmpty()
                .filterIsInstance<CBCharacteristic>()

            characteristics.forEach { c ->
                val uuidString = c.UUID.UUIDString
                if (uuidString.equals(rxCharUuid, ignoreCase = true)) rxCharacteristic = c
                if (uuidString.equals(txCharUuid, ignoreCase = true)) txCharacteristic = c
            }
            // Many HM-10-style modules expose a single characteristic used for both directions.
            if (rxCharacteristic == null) rxCharacteristic = characteristics.firstOrNull()
            if (txCharacteristic == null) txCharacteristic = characteristics.firstOrNull()

            val rx = rxCharacteristic
            val tx = txCharacteristic
            if (rx == null || tx == null) {
                failConnect("Characteristic UART não encontrada")
                return
            }

            peripheral.setNotifyValue(true, rx)
            this@BleConnection.peripheral = peripheral
            connected = true
            Logger.i(TAG, "BLE conectado e characteristics prontas")
            connectContinuation?.resume(Unit)
            connectContinuation = null
        }

        override fun peripheral(
            peripheral: CBPeripheral,
            didUpdateValueForCharacteristic: CBCharacteristic,
            error: NSError?
        ) {
            if (error != null) {
                Logger.w(TAG, "Erro em notificação BLE: ${error.localizedDescription}")
                return
            }
            val data = didUpdateValueForCharacteristic.value ?: return
            inbound.append(data.toByteArray())
        }
    }

    private fun failConnect(message: String) {
        Logger.e(TAG, message)
        connected = false
        val pending = connectContinuation
        connectContinuation = null
        if (pending != null) {
            pending.resumeWithException(Exception(message))
        } else {
            onError?.invoke(message)
        }
    }

    override suspend fun connect() {
        if (connected) return
        Logger.i(TAG, "Conectando BLE a $deviceId (${deviceNameFilter ?: "sem filtro de nome"})")

        val manager = centralManager ?: CBCentralManager(delegate, null).also { centralManager = it }

        if (manager.state != CBManagerStatePoweredOn) {
            suspendCancellableCoroutine<Unit> { cont ->
                poweredOnContinuation = cont
            }
        }

        val identifier = NSUUID(uUIDString = deviceId)
        val known = manager.retrievePeripheralsWithIdentifiers(listOf(identifier)).filterIsInstance<CBPeripheral>()
        val target = known.firstOrNull()
            ?: throw Exception("Dispositivo BLE não encontrado (id=$deviceId); refaça o scan e tente novamente")

        try {
            suspendCancellableCoroutine<Unit> { cont ->
                connectContinuation = cont
                manager.connectPeripheral(target, null)
            }
        } catch (e: Exception) {
            connected = false
            onConnectionStateChanged?.invoke(false)
            throw e
        }

        onConnectionStateChanged?.invoke(true)
    }

    override fun disconnect() {
        connected = false
        peripheral?.let { p -> centralManager?.cancelPeripheralConnection(p) }
        peripheral = null
        rxCharacteristic = null
        txCharacteristic = null
        inbound.clear()
        onConnectionStateChanged?.invoke(false)
    }

    override fun send(data: ByteArray) {
        val p = peripheral ?: throw Exception("Não conectado")
        val tx = txCharacteristic ?: throw Exception("Characteristic de escrita BLE indisponível")
        if (!connected) throw Exception("Não conectado")

        ConnectionTrace.tx("ble", data)

        // Not every BLE-UART bridge enables the "write without response" characteristic
        // property (many ESP32 GATT server sketches default to plain "write", which requires
        // a response) — assuming WriteWithoutResponse unconditionally would silently drop or
        // reject every write on those firmwares. Detect it instead of hardcoding one type.
        val supportsWriteWithoutResponse =
            (tx.properties and CBCharacteristicPropertyWriteWithoutResponse) != 0uL
        val writeType = if (supportsWriteWithoutResponse) {
            CBCharacteristicWriteWithoutResponse
        } else {
            CBCharacteristicWriteWithResponse
        }

        val negotiated = p.maximumWriteValueLengthForType(writeType).toInt()
        val maxChunk = if (negotiated > 0) negotiated else FALLBACK_MAX_WRITE_CHUNK

        var offset = 0
        while (offset < data.size) {
            if (writeType == CBCharacteristicWriteWithoutResponse) {
                var spins = 0
                while (!p.canSendWriteWithoutResponse && spins < 200) {
                    usleep(2_000u)
                    spins++
                }
            }
            val end = minOf(offset + maxChunk, data.size)
            val chunk = data.copyOfRange(offset, end)
            p.writeValue(chunk.toNSData(), tx, writeType)
            offset = end
        }
    }

    override fun receive(size: Int): ByteArray {
        if (!connected) throw Exception("Não conectado")
        val result = inbound.read(length = size, timeoutMs = timeoutMs.toLong())
        ConnectionTrace.rx("ble", result)
        if (size > 0 && result.size < size) {
            throw Exception("Timeout: expected $size bytes, received ${result.size}")
        }
        if (size <= 0 && result.isEmpty()) {
            throw Exception("Timeout: no data received")
        }
        return result
    }

    override fun isConnected(): Boolean = connected

    override fun getConnectionInfo(): String {
        return "BLE: ${deviceNameFilter ?: deviceId} (${if (connected) "conectado" else "desconectado"})"
    }

    override fun setOnConnectionStateChanged(callback: (Boolean) -> Unit) {
        onConnectionStateChanged = callback
    }

    override fun setOnError(callback: (String) -> Unit) {
        onError = callback
    }

    override fun clearInputBuffer() {
        inbound.clear()
    }

    override fun abortPendingRead() {
        inbound.abort()
    }

    // BLE-UART bridges wrap plain legacy Speeduino serial framing, not the CRC-based modern
    // protocol — mirrors Android's SpeeduinoBluetoothConnection, whose classic-BT SPP link has
    // the same legacy-first behavior for the same reason (no direct TCP-like framed channel).
    override fun supportsModernProtocol(): Boolean = false
    override fun supportsModernProtocolFallback(): Boolean = true
    override fun prefersLegacyProtocol(): Boolean = true
    override fun legacyFirmwareHandshakeAttempts(): Int = 5
    override fun legacyFirmwareHandshakeRetryDelayMs(): Long = 220L

    override fun close() {
        disconnect()
    }
}

data class BleDevice(
    val id: String,
    val name: String?,
)

/** Standalone scanner backing [BleConnection.scanDevices] — a temporary CBCentralManager that
 *  only lives for the duration of one scan call. */
@OptIn(ExperimentalForeignApi::class)
private object BleDeviceScanner {
    suspend fun scan(timeoutMs: Long): List<BleDevice> {
        val found = linkedMapOf<String, BleDevice>()
        val delegate = object : NSObject(), CBCentralManagerDelegateProtocol {
            override fun centralManagerDidUpdateState(central: CBCentralManager) {
                if (central.state == CBManagerStatePoweredOn) {
                    central.scanForPeripheralsWithServices(null, null)
                }
            }

            override fun centralManager(
                central: CBCentralManager,
                didDiscoverPeripheral: CBPeripheral,
                advertisementData: Map<Any?, *>,
                RSSI: NSNumber,
            ) {
                val id = didDiscoverPeripheral.identifier.UUIDString
                found[id] = BleDevice(id = id, name = didDiscoverPeripheral.name)
            }
        }
        val manager = CBCentralManager(delegate, null)
        kotlinx.coroutines.delay(timeoutMs)
        manager.stopScan()
        return found.values.toList()
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun ByteArray.toNSData(): NSData {
    if (isEmpty()) return NSData()
    return usePinned { pinned -> NSData.dataWithBytes(pinned.addressOf(0), size.toULong()) }
}

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray {
    val length = this.length.toInt()
    if (length == 0) return ByteArray(0)
    return this.bytes?.reinterpret<kotlinx.cinterop.ByteVar>()?.readBytes(length) ?: ByteArray(0)
}
