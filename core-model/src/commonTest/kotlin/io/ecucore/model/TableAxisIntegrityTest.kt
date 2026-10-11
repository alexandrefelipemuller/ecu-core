package io.ecucore.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Regressão do bug de campo (2026-10-10): tabela VE/AFR lida na sincronização veio com eixos
 * RPM/carga "indo e voltando" e nenhum alerta foi emitido. Esta checagem NÃO pode sair do app.
 */
class TableAxisIntegrityTest {

    private val goodRpm = listOf(800, 1000, 1200, 1500, 1800, 2200, 2600, 3000)
    private val goodLoad = listOf(30, 36, 46, 56, 66, 76, 86, 96)

    @Test
    fun `eixos crescentes sao integros`() {
        assertFalse(TableAxisIntegrity.isCorrupted(goodRpm, goodLoad))
        assertTrue(TableAxisIntegrity.problems(goodRpm, goodLoad).isEmpty())
    }

    @Test
    fun `eixos do screenshot corrompido sao detectados`() {
        // Valores reais do mapa quebrado reportado em campo
        val brokenRpm = listOf(1200, 1500, 1900, 2700, 3400, 4200, 9600, 5800, 6600)
        val brokenLoad = listOf(16, 104, 64, 24, 112, 72, 40, 8, 104, 72, 48, 16, 8)
        val problems = TableAxisIntegrity.problems(brokenRpm, brokenLoad)
        assertEquals(2, problems.size)
        assertTrue(TableAxisIntegrity.isCorrupted(brokenRpm, brokenLoad))
    }

    @Test
    fun `um unico salto invalida o eixo`() {
        val rpm = goodRpm.toMutableList().also { it[4] = 900 }
        assertTrue(TableAxisIntegrity.isCorrupted(rpm, goodLoad))
    }

    @Test
    fun `valores repetidos nao sao estritamente crescentes`() {
        assertTrue(TableAxisIntegrity.isCorrupted(goodRpm, listOf(30, 30, 46, 56, 66, 76, 86, 96)))
    }
}
