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
}
