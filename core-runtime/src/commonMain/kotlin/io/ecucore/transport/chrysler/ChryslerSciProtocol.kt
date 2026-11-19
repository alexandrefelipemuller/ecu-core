package io.ecucore.transport.chrysler

/**
 * Protocolo SCI "OBD1" das PCMs Chrysler/Jeep da família SBEC (1991-95).
 *
 * Linha serial a 7812,5 baud, 8N1, lógica invertida, 5 V. A PCM ecoa cada byte recebido,
 * então o host manda UM byte por vez e espera o eco. Fatos de protocolo (IDs, escalas,
 * sequências) reunidos a partir de ChryslerScanner e xj-scanner; validados em UM carro
 * (Jeep Cherokee XJ 1994, 4.0L, PCM 56030117) - outras PCMs podem divergir.
 */
object ChryslerSciProtocol {
    const val BAUD = 7812.5

    // Comandos (primeiro byte enviado à PCM)
    const val CMD_READ_CODES = 0x10
    const val CMD_ACTUATOR = 0x13
    const val CMD_READ_PARAM = 0x14
    const val CMD_READ_MEMORY = 0x15
    const val CMD_ERASE_CODES = 0x17
    const val CMD_HOLD_IDLE = 0x19
    const val CMD_SWITCH_TEST = 0x1A
    const val CMD_RESET_FUEL_TRIM = 0x23
    const val STOP_BYTE = 0x00

    const val CODES_END_NONE = 0xFE
    const val CODES_END_MARK = 0xFD

    /** Endereço do número de peça (4 bytes em dígitos BCD, ex.: 56 03 01 17). */
    const val PART_NUMBER_ADDRESS = 0x8002
    const val PART_NUMBER_LENGTH = 4

    // IDs de parâmetros do comando 0x14
    const val PID_O2_UPSTREAM_V = 0x02
    const val PID_COOLANT_F = 0x05
    const val PID_TPS_V = 0x07
    const val PID_BATTERY_V = 0x0A
    const val PID_MAP_PSI = 0x0B
    const val PID_IAC_STEPS = 0x0C
    const val PID_LTFT = 0x0E
    const val PID_RPM = 0x11
    const val PID_SYNC = 0x12
    const val PID_SPARK_ADVANCE = 0x15
    const val PID_IAT_F = 0x1B
    const val PID_VEHICLE_SPEED = 0x41

    fun swapNibbles(b: Int): Int = ((b shl 4) and 0xF0) or ((b shr 4) and 0x0F)

    fun volts(raw: Int): Double = raw * 0.0196
    fun batteryVolts(raw: Int): Double = raw * 0.0625
    fun tempF(raw: Int): Double = 1.8 * (raw - 128) + 32
    fun tempC(raw: Int): Double = (tempF(raw) - 32) / 1.8
    fun psi(raw: Int): Double = raw * 0.059756
    fun rpm(raw: Int): Int = raw * 32
    fun sparkAdvanceDeg(raw: Int): Double = raw * 0.5
    fun inSync(raw: Int): Boolean = (raw and 0x10) != 0

    /**
     * Trim de longo prazo (PID 0E): 128 = sem correção; cada contagem = 1/384 do combustível
     * (~0,26 %); a PCM só move entre 108 e 168. NÃO é signed/percentual direto - a fórmula do
     * ChryslerScanner (bit de sinal) inverte o sentido (raw 110 viraria "+21,6 %" quando é -4,7 %).
     */
    fun fuelTrimCountsFrom128(raw: Int): Int = raw - 128
    fun fuelTrimPercent(raw: Int): Double = ((256 + raw) / 384.0 - 1.0) * 100.0

    fun o2State(raw: Int): String = when (raw) {
        0xA0 -> "LEAN"
        0xB1 -> "RICH"
        0xFF -> "CENTER"
        else -> "N/A"
    }

    /** PID 0x3B (e 0x57): NÃO responde na PCM de teste; "closed loop" só se infere do O2. */
    fun fuelSystemStatus(raw: Int): String {
        val names = listOf("OPEN LOOP", "CLOSED LOOP", "OPEN LOOP / DRIVE", "OPEN LOOP / DTC", "CLOSED LOOP / DTC")
        return names.filterIndexed { bit, _ -> raw and (1 shl bit) != 0 }.joinToString(" | ").ifEmpty { "N/A" }
    }

    private val CRUISE_CUTOUT = listOf(
        "ON/OFF SW", "SPEED SEN", "RPM LIMIT", "BRAKE SW", "P/N SW", "RPM/SPEED", "CLUTCH", "DTC PRESENT",
        "KEY OFF", "ACTIVE", "CLUTCH UP", "N/A", "SW DTC", "CANCEL SW", "TPS LIMP-IN", "12V DTC",
    )
    private val CRUISE_STATE = listOf(
        "ON/OFF SW", "SPEED SEN", "RPM LIMIT", "BRAKE SW", "P/N SW", "RPM/SPEED", "CLUTCH", "DTC PRESENT",
        "ALLOWED", "ACTIVE", "CLUTCH UP", "N/A", "SW DTC", "CANCEL SW", "TPS LIMP-IN", "12V DTC",
    )

    /** PID 0x20: nibble alto = motivo do último corte, baixo = estado atual (8/9 = ok). */
    fun cruiseStatus(raw: Int): String {
        val last = CRUISE_CUTOUT[raw shr 4]
        val now = CRUISE_STATE[raw and 0x0F]
        return if ((raw and 0x0F) in 8..9) "$now (last cutout $last)" else "DENIED $now (last cutout $last)"
    }

    /** Parâmetro do comando 0x14. [defaultOn] = lido no ciclo padrão de live data. */
    data class Param(val pid: Int, val name: String, val unit: String, val scale: String, val defaultOn: Boolean)

    /** Catálogo para telas de PID/diagnóstico; decodificação numérica fica nas funções acima. */
    val PARAMS: List<Param> = listOf(
        Param(0x11, "Engine speed", "rpm", "raw x 32", true),
        Param(0x05, "Coolant temp", "F", "1.8*(raw-128)+32", true),
        Param(0x1B, "Intake air temp", "F", "1.8*(raw-128)+32", true),
        Param(0x07, "Throttle position sensor", "V", "raw x 0.0196", true),
        Param(0x0B, "MAP", "psi", "raw x 0.059756", true),
        Param(0x02, "Upstream O2", "V", "raw x 0.0196", true),
        Param(0x0A, "Battery voltage", "V", "raw x 0.0625", true),
        Param(0x15, "Spark advance", "deg", "raw x 0.5", true),
        Param(0x0C, "Target IAC position", "steps", "raw", true),
        Param(0x41, "Vehicle speed", "mph", "raw (reads 2x on test car)", true),
        Param(0x0E, "Long-term fuel trim", "counts", "raw-128; 1 count = 1/384 fuel", true),
        Param(0x08, "Minimum TPS voltage", "V", "raw x 0.0196", false),
        Param(0x06, "Coolant sensor voltage", "V", "raw x 0.0196", false),
        Param(0x1C, "IAT sensor voltage", "V", "raw x 0.0196", false),
        Param(0x40, "MAP sensor voltage", "V", "raw x 0.0196", false),
        Param(0x0F, "Barometric pressure", "psi", "raw x 0.059756", false),
        Param(0x24, "Target charging voltage", "V", "raw x 0.0625", false),
        Param(0x42, "Upstream O2 state", "", "A0 lean, B1 rich, FF center", false),
        Param(0x12, "Cam/crank sync", "", "bit 4 = in sync", false),
        Param(0x09, "Knock sensor voltage", "V", "raw x 0.0196", false),
        Param(0x2D, "Ambient/battery temp", "F", "1.8*(raw-128)+32", false),
        Param(0x01, "Battery temp sensor voltage", "V", "raw x 0.0196", false),
        Param(0x45, "MAP vacuum", "psi", "raw x 0.059756", false),
        Param(0x47, "Spark advance (copy)", "deg", "raw x 0.5", false),
        Param(0x46, "Delta throttle position", "V", "raw x 0.0196", false),
        Param(0x0D, "Idle fuel adaptation", "counts", "raw, 128 = neutral", false),
        Param(0x10, "Same byte as engine speed", "rpm/32", "raw", false),
        Param(0x13, "Key-on cycles since error 1", "", "raw", false),
        Param(0x1E, "Key-on cycles since error 2", "", "raw", false),
        Param(0x1F, "Key-on cycles since error 3", "", "raw", false),
        Param(0x1D, "Cruise set speed", "mph", "raw x 0.5", false),
        Param(0x20, "Cruise status", "", "nibbles, see cruiseStatus()", false),
        Param(0x27, "Theft alarm status", "", "raw", false),
        Param(0x22, "Unknown 22", "", "raw", false),
        Param(0x23, "Unknown 23", "", "raw", false),
    )

    /** Testes de atuador (`13 <id>`; `13 00` para). Lista do 6 cilindros SBEC. */
    val ACTUATOR_TESTS: Map<Int, String> = mapOf(
        0x01 to "ignition coil 1", 0x02 to "ignition coil 2", 0x03 to "ignition coil 3",
        0x04 to "injector 1", 0x05 to "injector 2", 0x06 to "injector 3",
        0x1D to "injector 4", 0x1E to "injector 5", 0x1F to "injector 6",
        0x07 to "idle air control motor", 0x08 to "radiator fan relay", 0x09 to "A/C clutch relay",
        0x0A to "auto shutdown relay", 0x0B to "evap purge solenoid", 0x0C to "cruise control solenoids",
        0x0D to "alternator field", 0x0E to "tachometer output", 0x0F to "torque converter clutch relay",
        0x10 to "EGR solenoid", 0x14 to "all solenoids / relays", 0x16 to "transmission O/D solenoid",
        0x1A to "cruise control vent solenoid", 0x1B to "cruise control vacuum solenoid",
        0x1C to "ASD fuel system", 0x2C to "cruise control 12V feed", 0x33 to "fuel pump relay",
    )

    /** Número de peça (BCD em 4 bytes) como texto, ex.: 56 03 01 17 -> "56030117". */
    fun partNumber(bytes: List<Int>): String = bytes.joinToString("") { it.toString(16).padStart(2, '0') }
}
