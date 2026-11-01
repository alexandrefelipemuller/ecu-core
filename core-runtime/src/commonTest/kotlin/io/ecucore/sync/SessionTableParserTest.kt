package io.ecucore.sync

import io.ecucore.model.Algorithm
import io.ecucore.model.EngineConstants
import io.ecucore.model.EngineStroke
import io.ecucore.model.EcuFamily
import io.ecucore.model.InjectorPortType
import io.ecucore.model.InjectorStaging
import io.ecucore.model.MapSampleMethod
import io.ecucore.model.VeTable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SessionTableParserTest {

    @Test
    fun `parses speeduino ve table from generic session pages`() {
        val expected = VeTable.createDefault().withLoadType(VeTable.LoadType.TPS)
        val engineConstants = EngineConstants(
            reqFuel = 8.5f,
            algorithm = Algorithm.ALPHA_N,
            squirtsPerCycle = 2,
            injectorStaging = InjectorStaging.ALTERNATING,
            engineStroke = EngineStroke.FOUR_STROKE,
            numberOfCylinders = 4,
            injectorPortType = InjectorPortType.PORT,
            numberOfInjectors = 4,
            stoichiometricRatio = 14.7f,
            mapSampleMethod = MapSampleMethod.CYCLE_AVERAGE,
        )

        val parsed = SessionTableParser.parseVeTable(
            ecuFamily = EcuFamily.SPEEDUINO,
            pages = mapOf(2 to expected.toByteArray()),
            engineConstants = engineConstants,
        )

        assertNotNull(parsed)
        assertEquals(VeTable.LoadType.TPS, parsed.loadType)
        assertEquals(expected.values, parsed.values)
        assertEquals(expected.rpmBins, parsed.rpmBins)
        assertEquals(expected.loadBins, parsed.loadBins)
    }

    @Test
    fun `dwell table so existe no speeduino - pagina 0x0c do MS2 nao vira dwell map`() {
        val page12 = ByteArray(1024) { (it * 7).toByte() }
        assertNull(SessionTableParser.parseDwellTable(EcuFamily.MS2, mapOf(12 to page12), engineConstants = null))
        assertNull(SessionTableParser.parseDwellTable(EcuFamily.MS3, mapOf(12 to page12), engineConstants = null))
        assertNull(SessionTableParser.parseDwellTable(EcuFamily.MEGASPEED, mapOf(12 to page12), engineConstants = null))
    }
}
