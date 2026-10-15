package io.ecucore.model

/**
 * Loggers de dente/gatilho/composto da MegaSpeed (tabelas de comunicação 0xF0..0xF3).
 *
 * Fonte do formato: `ms3_ign_in.c`, `isr_ign.s` e `serial.c` do código-fonte da MS3 (a MS2Extra da
 * MegaSpeed usa os mesmos IDs de tabela para os loggers customizados 0xF4..0xF8 do .ini da Costa).
 * O .ini NÃO descreve os loggers padrão 0xF0..0xF3; o formato abaixo é INFERIDO do código da MS3 e
 * ainda precisa ser confirmado em uma ECU MegaSpeed real.
 *
 * Buffer de 1024 bytes com registros de 3 bytes (341 no máximo); o último byte (0x3FF) guarda a
 * versão do formato. Em cada registro: byte 0 = bits 0..3 são os bits 16..19 do tempo e os bits 4..7
 * são flags; bytes 1..2 (big endian) são os 16 bits baixos do tempo. Tempo = ticks de 0,667 µs.
 */
enum class MegaSpeedLoggerKind(val table: Int, val label: String) {
    /** 0xF0: intervalo entre dentes. */
    TOOTH(0xF0, "Dentes"),

    /** 0xF1: tempo previsto para o próximo pulso (dtpred); só os 20 bits, sem flags. */
    TRIGGER(0xF1, "Gatilho"),

    /** 0xF2: tempo desde a borda anterior (primária ou secundária) + nível dos pinos. */
    COMPOSITE(0xF2, "Composto"),

    /** 0xF3: como o composto, mas circular e só avança com sincronismo (para ver perda de sync). */
    SYNC_COMPOSITE(0xF3, "Composto (erro de sync)"),
}

data class MegaSpeedLogRecord(
    /** Tempo bruto de 20 bits, em ticks de 0,667 µs. */
    val ticks: Int,
    /** Nibble alto do byte 0 (flags); significado depende do logger, veja os acessores. */
    val flags: Int,
) {
    val micros: Double get() = ticks * MegaSpeedLogDecoder.TICK_US

    /** TOOTH e COMPOSITE: bit 0x10 = sincronizado. */
    val synced: Boolean get() = flags and 0x1 != 0

    /** TOOTH: 0x20 = trigger 2 ativo. COMPOSITE: 0x20 = twin trigger. */
    val secondFlag: Boolean get() = flags and 0x2 != 0

    /** COMPOSITE: nível do pino primário no momento da borda. TOOTH: semi-sync. */
    val bit6: Boolean get() = flags and 0x4 != 0

    /** COMPOSITE: nível do pino secundário. TOOTH: semi-sync 2. */
    val bit7: Boolean get() = flags and 0x8 != 0
}

data class MegaSpeedLogCapture(
    val kind: MegaSpeedLoggerKind,
    val records: List<MegaSpeedLogRecord>,
    /** Byte 0x3FF do buffer. O código da MS3 grava 1; outro valor indica formato desconhecido. */
    val formatVersion: Int,
) {
    val formatKnown: Boolean get() = formatVersion == MegaSpeedLogDecoder.KNOWN_VERSION
}

object MegaSpeedLogDecoder {
    const val BUFFER_SIZE = 1024
    const val RECORD_SIZE = 3
    const val MAX_RECORDS = 340 // 0x3FC / 3: o buffer termina antes dos bytes de ponteiro/versão
    const val KNOWN_VERSION = 1

    /** Timer de 1,5 MHz (0,6667 µs/tick), o mesmo de looptime/NoiseFilterLen no .ini. */
    const val TICK_US = 2.0 / 3.0

    private const val POINTER_OFFSET = 0x3FD
    private const val VERSION_OFFSET = 0x3FF

    fun decode(kind: MegaSpeedLoggerKind, buffer: ByteArray): MegaSpeedLogCapture {
        require(buffer.size >= BUFFER_SIZE) { "Buffer de logger deve ter $BUFFER_SIZE bytes, recebeu ${buffer.size}" }
        fun u8(i: Int) = buffer[i].toInt() and 0xFF

        val all = (0 until MAX_RECORDS).map { n ->
            val o = n * RECORD_SIZE
            val b0 = u8(o)
            MegaSpeedLogRecord(
                ticks = ((b0 and 0x0F) shl 16) or (u8(o + 1) shl 8) or u8(o + 2),
                flags = b0 ushr 4,
            )
        }
        // Buffer é zerado ao armar: o primeiro registro todo zero marca o fim (delta 0 não ocorre).
        val used = all.indexOfFirst { it.ticks == 0 && it.flags == 0 }.let { if (it < 0) all.size else it }

        val ordered = if (kind == MegaSpeedLoggerKind.SYNC_COMPOSITE) {
            val pointer = (u8(POINTER_OFFSET) shl 8) or u8(POINTER_OFFSET + 1)
            // Circular: o ponteiro aponta o próximo ponto a ser escrito, ou seja, o mais antigo.
            // Só vale se for um limite de registro válido e o buffer já tiver dado a volta.
            val start = pointer / RECORD_SIZE
            if (pointer % RECORD_SIZE == 0 && pointer in 0 until MAX_RECORDS * RECORD_SIZE &&
                all.none { it.ticks == 0 && it.flags == 0 }
            ) {
                all.drop(start) + all.take(start)
            } else {
                all.take(used)
            }
        } else {
            all.take(used)
        }
        return MegaSpeedLogCapture(kind, ordered, u8(VERSION_OFFSET))
    }
}

/** Resumo numérico de uma captura. Sem interpretação além do que o formato garante. */
data class MegaSpeedLogSummary(
    val count: Int,
    val minMicros: Double,
    val maxMicros: Double,
    val medianMicros: Double,
    val syncedRatio: Double?,
    /** Índices cujo intervalo passa de 1,5x a mediana: candidatos a dente faltante (só TOOTH). */
    val longGapIndices: List<Int>,
    /** Rotação estimada pelo intervalo mediano e o total de dentes da roda (só TOOTH, se informado). */
    val estimatedRpm: Int?,
)

object MegaSpeedLogAnalysis {
    /** [wheelTeeth] = dentes totais da roda contando os faltantes (ex.: 36 para 36-1). */
    fun summarize(capture: MegaSpeedLogCapture, wheelTeeth: Int? = null): MegaSpeedLogSummary? {
        val records = capture.records
        if (records.isEmpty()) return null
        val sorted = records.map { it.micros }.sorted()
        val median = sorted[sorted.size / 2]
        val isTooth = capture.kind == MegaSpeedLoggerKind.TOOTH
        val longGaps = if (isTooth && median > 0) {
            records.withIndex().filter { it.value.micros > median * 1.5 }.map { it.index }
        } else {
            emptyList()
        }
        val hasSyncFlag = capture.kind != MegaSpeedLoggerKind.TRIGGER
        val rpm = if (isTooth && wheelTeeth != null && wheelTeeth > 0 && median > 0) {
            (60_000_000.0 / (median * wheelTeeth)).toInt()
        } else {
            null
        }
        return MegaSpeedLogSummary(
            count = records.size,
            minMicros = sorted.first(),
            maxMicros = sorted.last(),
            medianMicros = median,
            syncedRatio = if (hasSyncFlag) records.count { it.synced }.toDouble() / records.size else null,
            longGapIndices = longGaps,
            estimatedRpm = rpm,
        )
    }

    /** CSV para exportar: índice, microssegundos, ticks e flags. */
    fun toCsv(capture: MegaSpeedLogCapture): String = buildString {
        appendLine("index,micros,ticks,flags")
        capture.records.forEachIndexed { i, r ->
            appendLine("$i,${(r.micros * 10).toLong() / 10.0},${r.ticks},${r.flags}")
        }
    }
}
