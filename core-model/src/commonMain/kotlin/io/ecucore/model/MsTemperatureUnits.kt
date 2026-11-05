package io.ecucore.model

import kotlin.concurrent.Volatile

/**
 * Unidade das temperaturas (CLT/MAT) no live data da família MS2/MS3/MegaSpeed lido com o comando
 * `A` cru, que é o que o app usa nessas famílias (`EcuProtocol.readLiveData`).
 *
 * O outpc interno é sempre °F×10, e a conversão para °C é `(raw - 320) * 0.05555`, como no ramo
 * CELSIUS do .ini. Mas no modo "compat" (comandos sem envelope, como o `A` do MegaView), com o bit
 * `Temp_Units`/`tempUnits` da página 1 em 1, o firmware reescreve mat/clt como °C×10 antes de
 * enviar. Ver `ms3-source/ms3/serial.c`:
 * `if (compat && (ram4.Temp_Units & 1)) { ... ((outpc.mat - 320) * 5) / 9 ... }`.
 * O `r` com envelope (TunerStudio) continua recebendo °F×10, por isso o TS mostra certo.
 *
 * Isso foi visto em campo em 2026-11, num MicroSquirt com MS2/Extra 3.4.4. O TunerStudio mostrava
 * CLT 75 °C / MAT 8 °C e o app mostrava 24 °C / −13 °C, exatamente `(x − 32) / 1,8`.
 *
 * Se algum dia o live data MS passar a usar o `r` com envelope, este ajuste deixa de valer.
 *
 * O estado é global, como as definições de runtime de [EcuOutputChannels]. É atualizado pelo
 * client quando lê a página 1 e volta ao padrão (°F) ao desconectar.
 */
object MsTemperatureUnits {
    /** Offset do bit `tempUnits` (bit 0) na página 1 (tabela 0x04) do MS2/MegaSpeed. */
    const val MS2_PAGE1_OFFSET = 599

    /** Offset do bit `tempUnits` (bit 0) na página 1 (tabela 0x04) do MS3. */
    const val MS3_PAGE1_OFFSET = 103

    const val FAHRENHEIT_SCALE = 0.05555
    const val FAHRENHEIT_TRANSLATE = -320.0
    const val CELSIUS_SCALE = 0.1

    @Volatile
    var celsius: Boolean = false

    fun offsetFor(family: EcuFamily): Int? = when (family) {
        EcuFamily.MS2, EcuFamily.MEGASPEED -> MS2_PAGE1_OFFSET
        EcuFamily.MS3 -> MS3_PAGE1_OFFSET
        else -> null
    }

    /** Lê o bit `tempUnits` de um trecho da página 1 que começa em [pageOffset]; null se fora do trecho. */
    fun decodeFlag(family: EcuFamily, data: ByteArray, pageOffset: Int = 0): Boolean? {
        val offset = offsetFor(family) ?: return null
        val index = offset - pageOffset
        if (index !in data.indices) return null
        return (data[index].toInt() and 0x01) != 0
    }

    fun rawToCelsius(raw: Int, celsius: Boolean = this.celsius): Double =
        if (celsius) raw * CELSIUS_SCALE else (raw + FAHRENHEIT_TRANSLATE) * FAHRENHEIT_SCALE

    /** Troca scale/translate de um canal de temperatura °F×10 pelo equivalente °C×10, se ativo. */
    fun isFahrenheitTemperature(scale: Double, translate: Double): Boolean =
        scale == FAHRENHEIT_SCALE && translate == FAHRENHEIT_TRANSLATE
}
