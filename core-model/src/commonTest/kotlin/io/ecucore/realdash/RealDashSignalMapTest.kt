package io.ecucore.realdash

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RealDashSignalMapTest {
    @Test fun gaugeMathAppliesSimpleFormulas() {
        assertEquals(2.0, applyGaugeMath("=V/1000", 2000.0), 1e-9)
        assertEquals(30.0, applyGaugeMath("= V * 3", 10.0), 1e-9)
        assertEquals(7.0, applyGaugeMath("=sqrt(V)", 7.0), 1e-9) // fórmula desconhecida é ignorada
    }

    @Test fun signalMapKnowsRpmAndIgnoresUnknown() {
        assertEquals("rpm", RealDashSignalMap.signalFor(37))
        assertNull(RealDashSignalMap.signalFor(99999))
    }
}
