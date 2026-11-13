package kr.meter29.core

import org.junit.Assert.assertEquals
import org.junit.Test

class PhrasesTest {
    @Test fun warnSpeech_lowBeam70() {
        val warning = Advisor().update(
            DriveState(70.0, Beam.LOW, false, ZoneAhead("횡단보도", 250), 0L)
        ) as Advice.Warn
        assertEquals(
            "횡단보도, 250미터 앞. 지금 속도로는 하향등으로 보이는 거리 안에서 멈추기 어렵습니다. 맞은편 차와 앞차가 없으면 상향등을 쓸 수 있는지 확인하세요.",
            warnSpeech(warning, Beam.LOW, false)
        )
    }

    @Test fun warnSpeech_highBeamWet() {
        val warning = Advice.Warn(ZoneAhead("횡단보도", 250), 79, 100, true)
        assertEquals(
            "횡단보도, 250미터 앞. 젖은 길이라 지금 속도로는 상향등으로 보이는 거리 안에서 멈추기 어렵습니다. 속도를 줄이세요.",
            warnSpeech(warning, Beam.HIGH, true)
        )
    }

    @Test fun warnSpeech_beamOff() {
        val warning = Advice.Warn(ZoneAhead("횡단보도", 250), 16, 47, true)
        assertEquals(
            "횡단보도, 250미터 앞. 지금 속도로는 주간주행등으로 보이는 거리 안에서 멈추기 어렵습니다. 속도를 줄이세요.",
            warnSpeech(warning, Beam.OFF, false)
        )
    }

    @Test fun beamLabel_matchesSpecification() {
        assertEquals("하향등", beamLabel(Beam.LOW))
        assertEquals("상향등", beamLabel(Beam.HIGH))
        assertEquals("주간주행등", beamLabel(Beam.OFF))
    }

    @Test fun warnSpeech_lowBeamWithAutoHighBeam_saysSlowDownOnly() {
        // 하이빔 보조(AUTO)가 켜져 있는데 하향등이면 차가 이미 판단해 낮춘 것 → 상향등 권고 대신 감속만
        val warning = Advice.Warn(ZoneAhead("횡단보도", 250), 29, 47, true)
        assertEquals(
            "횡단보도, 250미터 앞. 지금 속도로는 하향등으로 보이는 거리 안에서 멈추기 어렵습니다. 속도를 줄이세요.",
            warnSpeech(warning, Beam.LOW, false, autoHighBeam = true)
        )
    }
}
