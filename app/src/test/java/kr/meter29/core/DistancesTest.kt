package kr.meter29.core

import org.junit.Assert.assertEquals
import org.junit.Test

class DistancesTest {
    @Test fun visibleDistance_lowBeam() {
        assertEquals(29.0, visibleDistanceM(Beam.LOW), 0.0)
    }

    @Test fun visibleDistance_highBeam() {
        assertEquals(79.0, visibleDistanceM(Beam.HIGH), 0.0)
    }

    @Test fun visibleDistance_beamOff() {
        assertEquals(16.0, visibleDistanceM(Beam.OFF), 0.0)
    }

    @Test fun stoppingDistance_stopped() {
        assertEquals(0.0, stoppingDistanceM(0.0, false), 0.0)
    }

    @Test fun stoppingDistance_dry50() {
        assertEquals(27.9, stoppingDistanceM(50.0, false), 0.2)
    }

    @Test fun stoppingDistance_dry70() {
        assertEquals(47.0, stoppingDistanceM(70.0, false), 0.2)
    }

    @Test fun stoppingDistance_wet50() {
        assertEquals(38.5, stoppingDistanceM(50.0, true), 0.2)
    }
}
