package io.ecucore.model

/**
 * Teste de saídas da MegaSpeed MS2-X (bomba, bicos, bobinas, portas extras, atuador de lenta),
 * conforme `[ControllerCommands]` e os diálogos `outputtest*`/`iactest` do .ini da CostaMegaSpeed.
 *
 * Cada comando é uma escrita MS2 `w` na tabela 7, offset 0x0208, com 3 bytes: chave 0x3039 + código.
 * Os acionamentos só funcionam com o modo de teste ativo (`status3 & 8`), e o .ini só deixa ativar o
 * modo de teste com `rpm == 0`. Este arquivo é puro (sem I/O): o que pode ser decidido sem a ECU
 * (catálogo, validação de parâmetros e o guard de segurança) fica aqui e é testado.
 */
object MegaSpeedOutputTest {
    const val COMMAND_TABLE: Int = 0x07
    const val COMMAND_OFFSET: Int = 0x0208
    const val KEY_HIGH: Int = 0x30
    const val KEY_LOW: Int = 0x39

    /** Página 0x08 (page 4 do .ini): parâmetros do teste (testop_*, testdwell, testint, testpw...). */
    const val PARAMS_PAGE: Int = 0x08

    /** `status3` no bloco de saída (offset 80); bit 3 = modo de teste ativo. */
    const val STATUS3_OFFSET: Int = 80
    const val TEST_MODE_BIT: Int = 0x08
}

enum class MegaSpeedTestKind { MODE, FUEL_PUMP, SPARK, INJECTOR, AUX_PORT, IDLE_ACTUATOR }

/** Porta extra testável (`outputtest12..17` do .ini). Pulsado fica de fora: o .ini o comenta. */
enum class MegaSpeedAuxPort(val channel: Int, val label: String) {
    D15(12, "Porta D15"),
    IAC1(13, "Porta IAC1"),
    IAC2(14, "Porta IAC2"),
    JS10(15, "Porta JS10"),
    JS11(16, "Porta JS11"),
    FIDLE(17, "Porta FIDLE"),
    ;

    /** cmdtestNNoff = 0x80 + (NN-10)*4; on = +3 (ex.: canal 12 → off 0x88, on 0x8B). */
    val offCode: Int get() = 0x80 + (channel - 10) * 4
    val onCode: Int get() = offCode + 3
}

data class MegaSpeedTestCommand(
    val code: Int,
    val label: String,
    val kind: MegaSpeedTestKind,
    /** Comandos "desligar" são sempre permitidos: levam a ECU ao estado seguro. */
    val isOff: Boolean,
    /** Tempo máximo ligado antes do watchdog desligar tudo. 0 para comandos que não ligam nada. */
    val autoOffMs: Long = 0L,
) {
    /** Bytes enviados em MegaSpeedOutputTest.COMMAND_TABLE/COMMAND_OFFSET. */
    fun data(): ByteArray = byteArrayOf(
        MegaSpeedOutputTest.KEY_HIGH.toByte(),
        MegaSpeedOutputTest.KEY_LOW.toByte(),
        code.toByte(),
    )

    companion object {
        val ENTER_TEST_MODE = MegaSpeedTestCommand(0x01, "Ativar modo de teste", MegaSpeedTestKind.MODE, isOff = false)
        val STOP_TEST_MODE = MegaSpeedTestCommand(0x00, "Desativar modo de teste", MegaSpeedTestKind.MODE, isOff = true)

        val FUEL_PUMP_ON = MegaSpeedTestCommand(0x05, "Bomba de combustível ligada", MegaSpeedTestKind.FUEL_PUMP, false, 10_000L)
        val FUEL_PUMP_OFF = MegaSpeedTestCommand(0x06, "Bomba de combustível desligada", MegaSpeedTestKind.FUEL_PUMP, true)

        val SPARK_PULSE = MegaSpeedTestCommand(0x02, "Pulsar bobina", MegaSpeedTestKind.SPARK, false, 15_000L)
        val INJECTOR_PULSE = MegaSpeedTestCommand(0x03, "Pulsar injetor", MegaSpeedTestKind.INJECTOR, false, 15_000L)
        val INJECTOR_SPARK_OFF = MegaSpeedTestCommand(0x07, "Desligar bobinas e injetores", MegaSpeedTestKind.INJECTOR, true)

        val IDLE_OFF = MegaSpeedTestCommand(0x08, "Parar teste do atuador de lenta", MegaSpeedTestKind.IDLE_ACTUATOR, true)
        val IDLE_HOME = MegaSpeedTestCommand(0x09, "Atuador de lenta: posição inicial", MegaSpeedTestKind.IDLE_ACTUATOR, false, 15_000L)
        val IDLE_ON = MegaSpeedTestCommand(0x0A, "Atuador de lenta: posição alvo", MegaSpeedTestKind.IDLE_ACTUATOR, false, 15_000L)

        fun auxOn(port: MegaSpeedAuxPort) =
            MegaSpeedTestCommand(port.onCode, "${port.label} ligada", MegaSpeedTestKind.AUX_PORT, false, 5_000L)

        fun auxOff(port: MegaSpeedAuxPort) =
            MegaSpeedTestCommand(port.offCode, "${port.label} desligada", MegaSpeedTestKind.AUX_PORT, true)

        /**
         * Sequência de parada total, na ordem de envio: tudo desligado e, por último, sai do modo de
         * teste. Usada pelo botão de parada, pelo watchdog e ao sair da tela.
         */
        val STOP_ALL: List<MegaSpeedTestCommand> =
            listOf(FUEL_PUMP_OFF, INJECTOR_SPARK_OFF, IDLE_OFF) +
                MegaSpeedAuxPort.entries.map { auxOff(it) } +
                STOP_TEST_MODE
    }
}

/** Estado lido do live data logo antes de qualquer acionamento. */
data class MegaSpeedTestStatus(
    val rpm: Int,
    val testModeActive: Boolean,
) {
    companion object {
        /** null se o bloco de saída é curto demais para conter status3 (não dá para confirmar o estado). */
        fun fromOutputChannels(rpm: Int, data: ByteArray?): MegaSpeedTestStatus? {
            if (data == null || data.size <= MegaSpeedOutputTest.STATUS3_OFFSET) return null
            val status3 = data[MegaSpeedOutputTest.STATUS3_OFFSET].toInt() and 0xFF
            return MegaSpeedTestStatus(
                rpm = rpm,
                testModeActive = status3 and MegaSpeedOutputTest.TEST_MODE_BIT != 0,
            )
        }
    }
}

/**
 * Parâmetros do teste de bobina/injetor na página 0x08. Valores brutos, como no .ini:
 * dwell = 0.1 ms/bit, intervalo = 0.128 ms/bit, largura do pulso = 0.000666 ms/bit.
 */
data class MegaSpeedTestParams(
    /** testop_coil [0:1]: 0 desligado, 1 um. */
    val coilMode: Int = 0,
    /** testop_inj [5:6]: 0 desligado, 1 um, 3 todos. */
    val injectorMode: Int = 0,
    /** testsel_coil [4:6]: 0..4 = Spark A..E. */
    val coilSelect: Int = 0,
    /** testsel_inj [0:1]: 0..3 = Injetor 1..4. */
    val injectorSelect: Int = 0,
    val dwellRaw: Int = 0,
    val intervalRaw: Int = 0,
    val pulseWidthRaw: Int = 0,
    val pulseCount: Int = 0,
) {
    /** Motivo da recusa ou null. Limites mais conservadores que os do .ini, de propósito. */
    fun validateForCoil(): String? = when {
        coilMode != 1 -> "Modo de teste de bobina desligado"
        coilSelect !in 0..4 -> "Saída de bobina inválida"
        dwellRaw !in MIN_DWELL_RAW..MAX_DWELL_RAW ->
            "Dwell fora de 0,1 a 4,0 ms (limite do app; o .ini aceita até 25,5 ms e isso pode queimar a bobina)"
        intervalRaw !in MIN_INTERVAL_RAW..MAX_INTERVAL_RAW -> "Intervalo fora de 20 a 1000 ms"
        else -> null
    }

    fun validateForInjector(): String? = when {
        injectorMode != 1 && injectorMode != 3 -> "Modo de teste de injetor desligado"
        injectorSelect !in 0..3 -> "Injetor inválido"
        pulseWidthRaw !in MIN_PULSE_RAW..MAX_PULSE_RAW -> "Tempo de abertura fora de 0,2 a 10 ms"
        pulseCount !in 1..MAX_PULSE_COUNT -> "Número de pulsos fora de 1 a $MAX_PULSE_COUNT"
        intervalRaw !in MIN_INTERVAL_RAW..MAX_INTERVAL_RAW -> "Intervalo fora de 20 a 1000 ms"
        else -> null
    }

    /** Aplica sobre os bytes da página 0x08 preservando os demais bits (ex.: testop_fp, testop_pwm). */
    fun applyTo(page: ByteArray): ByteArray {
        require(page.size >= PARAMS_END) { "Página 0x08 curta demais: ${page.size}" }
        val d = page.copyOf()
        var op = d[OFFSET_OP].toInt() and 0xFF
        op = (op and 0b1001_1100) or (coilMode and 0x03) or ((injectorMode and 0x03) shl 5)
        d[OFFSET_OP] = op.toByte()
        d[OFFSET_DWELL] = dwellRaw.coerceIn(0, 255).toByte()
        putU16(d, OFFSET_INTERVAL, intervalRaw)
        putU16(d, OFFSET_PULSE_WIDTH, pulseWidthRaw)
        putU16(d, OFFSET_PULSE_COUNT, pulseCount)
        val sel = d[OFFSET_SELECT].toInt() and 0xFF
        d[OFFSET_SELECT] = ((sel and 0b1000_1100) or (injectorSelect and 0x03) or ((coilSelect and 0x07) shl 4)).toByte()
        return d
    }

    companion object {
        const val OFFSET_OP = 2
        const val OFFSET_DWELL = 3
        const val OFFSET_INTERVAL = 4
        const val OFFSET_PULSE_WIDTH = 6
        const val OFFSET_PULSE_COUNT = 8
        const val OFFSET_SELECT = 10
        const val PARAMS_END = 11

        const val MIN_DWELL_RAW = 1      // 0.1 ms
        const val MAX_DWELL_RAW = 40     // 4.0 ms
        // 20 ms / 0.128 = 156.25 e 1000 ms / 0.128 = 7812.5: a tela mostra "20 a 1000 ms", então as
        // bordas arredondadas (156 e 7813) precisam ser aceitas.
        const val MIN_INTERVAL_RAW = 156 // ~20 ms
        const val MAX_INTERVAL_RAW = 7813 // ~1000 ms
        const val MIN_PULSE_RAW = 300    // ~0.2 ms
        const val MAX_PULSE_RAW = 15015  // ~10 ms
        const val MAX_PULSE_COUNT = 1000

        fun fromPage(page: ByteArray): MegaSpeedTestParams {
            require(page.size >= PARAMS_END) { "Página 0x08 curta demais: ${page.size}" }
            fun u8(i: Int) = page[i].toInt() and 0xFF
            fun u16(i: Int) = (u8(i) shl 8) or u8(i + 1)
            return MegaSpeedTestParams(
                coilMode = u8(OFFSET_OP) and 0x03,
                injectorMode = (u8(OFFSET_OP) shr 5) and 0x03,
                coilSelect = (u8(OFFSET_SELECT) shr 4) and 0x07,
                injectorSelect = u8(OFFSET_SELECT) and 0x03,
                dwellRaw = u8(OFFSET_DWELL),
                intervalRaw = u16(OFFSET_INTERVAL),
                pulseWidthRaw = u16(OFFSET_PULSE_WIDTH),
                pulseCount = u16(OFFSET_PULSE_COUNT),
            )
        }

        private fun putU16(d: ByteArray, offset: Int, value: Int) {
            val v = value.coerceIn(0, 0xFFFF)
            d[offset] = (v shr 8).toByte()
            d[offset + 1] = v.toByte()
        }
    }
}

sealed interface MegaSpeedTestDecision {
    data object Allowed : MegaSpeedTestDecision
    data class Refused(val reason: String) : MegaSpeedTestDecision
}

/**
 * Regras de segurança, aplicadas ANTES de enviar qualquer comando que acione algo.
 * Falha para o lado seguro: se o estado da ECU não pôde ser lido, não aciona.
 */
object MegaSpeedOutputTestGuard {
    fun evaluate(
        command: MegaSpeedTestCommand,
        status: MegaSpeedTestStatus?,
        params: MegaSpeedTestParams?,
    ): MegaSpeedTestDecision {
        // Desligar nunca é bloqueado.
        if (command.isOff) return MegaSpeedTestDecision.Allowed

        if (status == null) {
            return refuse("Não foi possível confirmar o estado da ECU (RPM e modo de teste)")
        }
        if (command.kind == MegaSpeedTestKind.IDLE_ACTUATOR) {
            // Como no .ini, o teste do atuador de lenta funciona com motor ligado ou desligado.
            return MegaSpeedTestDecision.Allowed
        }
        if (status.rpm != 0) {
            return refuse("Motor com leitura de rotação (${status.rpm} RPM): teste só com motor parado")
        }
        if (command.kind == MegaSpeedTestKind.MODE) {
            return if (status.testModeActive) refuse("Modo de teste já está ativo") else MegaSpeedTestDecision.Allowed
        }
        if (!status.testModeActive) {
            return refuse("Ative o modo de teste antes de acionar saídas")
        }
        val needsParams = command.kind == MegaSpeedTestKind.SPARK || command.kind == MegaSpeedTestKind.INJECTOR
        if (needsParams && params == null) {
            return refuse("Parâmetros do teste não lidos da ECU")
        }
        val reason = when (command.kind) {
            MegaSpeedTestKind.SPARK -> params?.validateForCoil()
            MegaSpeedTestKind.INJECTOR -> params?.validateForInjector()
            else -> null
        }
        return if (reason == null) MegaSpeedTestDecision.Allowed else refuse(reason)
    }

    private fun refuse(reason: String) = MegaSpeedTestDecision.Refused(reason)
}
