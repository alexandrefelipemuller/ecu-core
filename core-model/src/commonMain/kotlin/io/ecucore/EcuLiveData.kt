package io.ecucore

/**
 * Dados em tempo real do Speeduino.
 */
data class EcuLiveData(
    val secl: Int,
    val rpm: Int,
    val coolantTemp: Int,
    val intakeTemp: Int,
    val mapPressure: Int,
    val tps: Int,
    val batteryVoltage: Double,
    val advance: Int,
    val o2: Int,
    val engineStatus: Int,
    val sparkStatus: Int,
    val candidateSpeedKph: Int? = null,
    val candidateAccelPedalPosPct: Int? = null,
    val candidateGear: Int? = null,
    val candidateThrottleAngleDeg: Double? = null,
    val candidateIgnitionAdvanceDeg: Double? = null,
    val candidateInjectionDurationMs: Double? = null,
    val candidateInjectionDurationMirrorMs: Double? = null,
    val gpsSpeedKph: Double? = null,
    val gpsLatitude: Double? = null,
    val gpsLongitude: Double? = null,
    /** ADC bruto do TPS (0..1023), só MS2/Extra. */
    val tpsAdc: Int? = null,
    val outputChannelBlockSize: Int = 0,
    val outputChannelData: ByteArray? = null,
    /** Carga calculada do motor (OBD2 PID 04), 0-100%. */
    val engineLoadPct: Int? = null,
    /** Ajuste de combustível de curto prazo, banco 1 (PID 06), em %. */
    val shortFuelTrimPct: Double? = null,
    /** Ajuste de combustível de longo prazo, banco 1 (PID 07), em %. */
    val longFuelTrimPct: Double? = null,
    /** Tensão da sonda O2 banco 1 sensor 1 (PID 14), em volts. */
    val o2UpstreamVolts: Double? = null,
    /** Tensão da sonda O2 banco 1 sensor 2 (PID 15), em volts. */
    val o2DownstreamVolts: Double? = null,
    /**
     * Canais que a ECU não informa. Os campos não-nulos correspondentes (ex.: `batteryVoltage`,
     * `advance`, `o2`) carregam só um valor padrão e NÃO devem ser exibidos como medidos.
     * Valores: "battery", "advance", "o2".
     */
    val unavailableChannels: Set<String> = emptySet(),
) {
    val isEngineRunning: Boolean
        get() = (engineStatus and 0x01) != 0

    val hasSpark: Boolean
        get() = (sparkStatus and 0x01) != 0
}
