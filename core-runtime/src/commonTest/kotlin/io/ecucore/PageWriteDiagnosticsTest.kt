package io.ecucore

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PageWriteDiagnosticsTest {
    private val expected = ByteArray(94) { ((it * 7) % 251).toByte() }

    @Test
    fun readBackIdenticoNaoTemShift() {
        assertNull(PageWriteDiagnostics.detectShift(expected, expected.copyOf()))
    }

    @Test
    fun lixoNoInicioDoReadBackViraShiftPositivo() {
        // Caso de campo (Speeduino 202103, USB): bytes estranhos antes do quadro deslocam a página.
        val junk = byteArrayOf(40, -39, 0, 0, 0)
        val actual = junk + expected.copyOf(expected.size - junk.size)
        assertEquals(junk.size, PageWriteDiagnostics.detectShift(expected, actual))
    }

    @Test
    fun bytesPerdidosNoInicioViramShiftNegativo() {
        val actual = expected.copyOfRange(3, expected.size) + byteArrayOf(0, 0, 0)
        assertEquals(-3, PageWriteDiagnostics.detectShift(expected, actual))
    }

    @Test
    fun conteudoRealmenteDiferenteNaoGeraShift() {
        val actual = ByteArray(expected.size) { ((it * 13 + 5) % 251).toByte() }
        assertNull(PageWriteDiagnostics.detectShift(expected, actual))
    }

    @Test
    fun readBackNuloOuTabelaMinusculaNaoGeraShift() {
        assertNull(PageWriteDiagnostics.detectShift(expected, null))
        assertNull(PageWriteDiagnostics.detectShift(ByteArray(4), ByteArray(4)))
    }
}
