package kr.meter29.core

data class DriveState(
    val speedKmh: Double?,
    val beam: Beam,
    val wet: Boolean,
    val zone: ZoneAhead?,
    val nowMs: Long
)

sealed interface Advice {
    object Quiet : Advice
    data class Warn(
        val zone: ZoneAhead,
        val visibleM: Int,
        val needM: Int,
        val speak: Boolean,
        /** 이 지점에서 처음 경고한 순간(10분 안 재경고 제외). 음성이 간격 때문에 막혀도 true. */
        val firstForZone: Boolean = speak,
    ) : Advice
}

class Advisor {
    private var warning = false
    /** 지점 id → 그 지점에서 처음 경고한 시각. 같은 지점은 10분 안에 다시 말하지 않는다. */
    private val warnedAt = mutableMapOf<String, Long>()
    private var lastSpeakMs: Long? = null

    fun update(s: DriveState): Advice {
        val speed = s.speedKmh
        val zone = s.zone
        // 속도를 모르거나, 위험 지점이 없거나, 전조등이 꺼진(주간) 상태면 판정하지 않는다.
        if (speed == null || zone == null || s.beam == Beam.OFF) {
            warning = false
            return Advice.Quiet
        }

        val visible = Math.round(visibleDistanceM(s.beam)).toInt()
        val need = Math.round(stoppingDistanceM(speed, s.wet)).toInt()
        warning = if (warning) need > visible - 3 else need > visible
        if (!warning) return Advice.Quiet

        val firstAt = warnedAt[zone.id]
        val firstWarning = firstAt == null || s.nowMs - firstAt >= ZONE_REPEAT_MS
        if (firstWarning) warnedAt[zone.id] = s.nowMs
        val previousSpeak = lastSpeakMs
        val speak = firstWarning && (previousSpeak == null || s.nowMs - previousSpeak >= MIN_GAP_MS)
        if (speak) lastSpeakMs = s.nowMs
        return Advice.Warn(zone, visible, need, speak, firstWarning)
    }

    private companion object {
        const val MIN_GAP_MS = 20_000L
        const val ZONE_REPEAT_MS = 600_000L
    }
}
