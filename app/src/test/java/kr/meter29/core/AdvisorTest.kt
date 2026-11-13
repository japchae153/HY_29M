package kr.meter29.core

import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdvisorTest {
    private val zone = ZoneAhead("횡단보도", 250)

    private fun state(
        speedKmh: Double? = 70.0,
        beam: Beam = Beam.LOW,
        wet: Boolean = false,
        zone: ZoneAhead? = this.zone,
        nowMs: Long = 0L
    ) = DriveState(speedKmh, beam, wet, zone, nowMs)

    // 마른 노면 공식의 역함수로 경계 거리의 속도를 만든다.
    private fun speedForNeed(needM: Double): Double {
        val muG = 0.7 * 9.81
        return (sqrt(muG * muG + 2.0 * muG * needM) - muG) * 3.6
    }

    @Test fun advisor_staysQuiet_whenSpeedUnknown() {
        val advisor = Advisor()
        assertEquals(Advice.Quiet, advisor.update(state(speedKmh = null)))
        assertTrue((advisor.update(state()) as Advice.Warn).speak)
        assertEquals(Advice.Quiet, advisor.update(state(speedKmh = null)))
    }

    @Test fun advisor_quiet_whenNoZone() {
        val advisor = Advisor()
        assertEquals(Advice.Quiet, advisor.update(state(70.0, Beam.LOW, zone = null)))
        advisor.update(state())
        assertEquals(Advice.Quiet, advisor.update(state(zone = null)))
    }

    @Test fun advisor_warns_lowBeam70() {
        assertEquals(Advice.Warn(zone, 29, 47, true), Advisor().update(state()))
    }

    @Test fun advisor_quiet_highBeam50() {
        assertEquals(28, Math.round(stoppingDistanceM(50.0, false)).toInt())
        assertEquals(Advice.Quiet, Advisor().update(state(50.0, Beam.HIGH)))
    }

    @Test fun advisor_hysteresis_noFlicker() {
        val advisor = Advisor()
        assertTrue(advisor.update(state()) is Advice.Warn)
        assertEquals(Advice.Warn(zone, 29, 27, false), advisor.update(state(speedForNeed(27.0))))
        assertEquals(Advice.Quiet, advisor.update(state(speedForNeed(25.0))))
    }

    @Test fun advisor_voiceOncePerZone() {
        val advisor = Advisor()
        assertTrue((advisor.update(state(nowMs = 0L)) as Advice.Warn).speak)
        assertFalse((advisor.update(state(nowMs = 1000L)) as Advice.Warn).speak)
        assertTrue((advisor.update(state(zone = ZoneAhead("학교", 200), nowMs = 25000L)) as Advice.Warn).speak)
        assertFalse((advisor.update(state(zone = ZoneAhead("교차로", 200), nowMs = 35000L)) as Advice.Warn).speak)
    }

    @Test fun advisor_wetIncreasesNeed() {
        assertEquals(Advice.Quiet, Advisor().update(state(50.0, Beam.LOW)))
        assertEquals(Advice.Warn(zone, 29, 38, true), Advisor().update(state(50.0, Beam.LOW, wet = true)))
    }

    @Test fun advisor_roundsBeforeComparingAndClearsAtThreeMeters() {
        val advisor = Advisor()
        assertEquals(Advice.Quiet, advisor.update(state(speedForNeed(29.4))))
        assertEquals(Advice.Warn(zone, 29, 30, true), advisor.update(state(speedForNeed(29.6))))
        assertEquals(Advice.Quiet, advisor.update(state(speedForNeed(26.0))))
    }

    @Test fun advisor_quiet_whenHeadlightsOff() {
        // 전조등이 꺼져 있으면(주간) 판정하지 않는다
        assertEquals(Advice.Quiet, Advisor().update(state(70.0, Beam.OFF)))
    }

    @Test fun advisor_warns_highBeamWhenNeedExceedsVisibility() {
        val result = Advisor().update(state(120.0, Beam.HIGH)) as Advice.Warn
        assertEquals(79, result.visibleM)
        assertTrue(result.needM > result.visibleM)
    }

    @Test fun advisor_speaksAgain_forSameZoneAfterTenMinutes() {
        val advisor = Advisor()
        assertTrue((advisor.update(state(nowMs = 0L)) as Advice.Warn).speak)
        advisor.update(state(zone = null, nowMs = 60_000L))
        assertFalse((advisor.update(state(nowMs = 599_999L)) as Advice.Warn).speak)
        advisor.update(state(zone = null, nowMs = 600_500L))
        assertTrue((advisor.update(state(nowMs = 600_000L + 1_000L)) as Advice.Warn).speak)
    }

    @Test fun advisor_keysVoiceByZoneId_notName() {
        val advisor = Advisor()
        val schoolA = ZoneAhead("어린이보호구역", 200, id = "a")
        val schoolB = ZoneAhead("어린이보호구역", 200, id = "b")
        assertTrue((advisor.update(state(zone = schoolA, nowMs = 0L)) as Advice.Warn).speak)
        advisor.update(state(zone = null, nowMs = 25_000L))
        assertTrue((advisor.update(state(zone = schoolB, nowMs = 30_000L)) as Advice.Warn).speak)
    }

    @Test fun advisor_voiceAllowsExactlyTwentySeconds() {
        val advisor = Advisor()
        assertTrue((advisor.update(state(nowMs = 0L)) as Advice.Warn).speak)
        assertFalse((advisor.update(state(zone = ZoneAhead("학교", 200), nowMs = 19999L)) as Advice.Warn).speak)
        assertTrue((advisor.update(state(zone = ZoneAhead("교차로", 200), nowMs = 20000L)) as Advice.Warn).speak)
    }

    @Test fun advisor_doesNotRepeatVoiceAfterLeavingOrClearingZone() {
        val advisor = Advisor()
        advisor.update(state())
        advisor.update(state(speedKmh = 0.0, nowMs = 1000L))
        assertFalse((advisor.update(state(nowMs = 25000L)) as Advice.Warn).speak)
        advisor.update(state(zone = null, nowMs = 26000L))
        assertFalse((advisor.update(state(zone = zone.copy(distanceM = 100), nowMs = 50000L)) as Advice.Warn).speak)
    }

    @Test fun advisor_doesNotRetryVoiceSuppressedOnFirstWarning() {
        val advisor = Advisor()
        advisor.update(state())
        val other = ZoneAhead("학교", 200)
        assertFalse((advisor.update(state(zone = other, nowMs = 10000L)) as Advice.Warn).speak)
        assertFalse((advisor.update(state(zone = other, nowMs = 30000L)) as Advice.Warn).speak)
    }

    @Test fun advisor_marksFirstWarningPerZone_evenWhenVoiceSuppressed() {
        val advisor = Advisor()
        val first = advisor.update(state(nowMs = 0L)) as Advice.Warn
        assertTrue(first.speak && first.firstForZone)
        // 다른 지점, 10초 뒤: 음성 간격(20초) 때문에 말하지는 않지만 그 지점의 첫 경고로 기록돼야 한다
        val other = advisor.update(state(zone = ZoneAhead("학교", 200, id = "school"), nowMs = 10_000L)) as Advice.Warn
        assertFalse(other.speak)
        assertTrue(other.firstForZone)
        // 같은 지점 다음 초: 첫 경고 아님
        val again = advisor.update(state(zone = ZoneAhead("학교", 190, id = "school"), nowMs = 11_000L)) as Advice.Warn
        assertFalse(again.firstForZone)
    }
}
