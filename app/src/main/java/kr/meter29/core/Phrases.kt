package kr.meter29.core

fun beamLabel(beam: Beam): String = when (beam) {
    Beam.LOW -> "하향등"
    Beam.HIGH -> "상향등"
    Beam.OFF -> "주간주행등"
}

/**
 * 음성 안내 문구. 숫자(보이는 거리·멈추는 거리)는 화면 막대에만 두고, 음성은 짧게 상황과 할 일만 말한다.
 * 29m 같은 기준값을 차 안에서 단정하지 않기 위함(공단 캠페인 시험 값, 공식 시험결과 아님).
 * @param autoHighBeam 하이빔 보조(AUTO)가 켜져 있으면 하향등은 차가 이미 판단해 낮춘 것이므로 상향등을 권하지 않는다.
 */
fun warnSpeech(w: Advice.Warn, beam: Beam, wet: Boolean, autoHighBeam: Boolean = false): String {
    val wetPhrase = if (wet) "젖은 길이라 " else ""
    // 상향등은 맞은편 차·앞차가 없을 때만 쓸 수 있다(도로교통법 시행령 제20조) — 앱은 이를 모르므로 확인을 권한다
    val action = if (beam == Beam.LOW && !autoHighBeam) {
        " 맞은편 차와 앞차가 없으면 상향등을 쓸 수 있는지 확인하세요."
    } else {
        " 속도를 줄이세요."
    }
    return "${w.zone.name}, ${w.zone.distanceM}미터 앞. " +
        "${wetPhrase}지금 속도로는 ${beamLabel(beam)}으로 보이는 거리 안에서 멈추기 어렵습니다." + action
}
