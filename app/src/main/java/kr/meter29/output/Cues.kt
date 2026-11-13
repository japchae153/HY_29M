package kr.meter29.output

import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.OnInitializeListener
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.control.LightSwitch
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import java.util.Locale

/**
 * 알림 출력: 실내 앰비언트 조명(호박색)과 한 문장 음성.
 * 조명은 경고 전 원래 값(켜짐 여부·색·밝기)을 저장했다가 경고가 풀리면 그대로 되돌린다.
 * 원래 값은 기기 저장소에도 남겨, 경고 중에 앱이 강제로 꺼져도 다음 시작 때 되돌린다.
 * 밤에 눈부시지 않게 밝기는 올리지 않고 색만 바꾼다(조명이 꺼져 있었을 때만 밝기 40으로 켬).
 */
class Cues(private val ctx: Context, private val vehicle: Vehicle) {

    private data class Saved(val switch: LightSwitch?, val color: Int?, val brightness: Int?)

    private var areas: List<SeatArea> = listOf(SeatArea(SeatValue.ROW_1_LEFT))
    private val saved = mutableMapOf<SeatArea, Saved>()
    @Volatile private var showing = false
    private var tts: TextToSpeech? = null
    @Volatile private var ttsReady = false
    private val prefs: SharedPreferences = ctx.getSharedPreferences("m29.cues", Context.MODE_PRIVATE)

    fun start() {
        // 차가 지원하는 앰비언트 영역을 받은 뒤에 지난 실행의 남은 호박색을 되돌린다(영역이 틀리면 설정이 거부됨)
        vehicle.getLight().checkAmbientLightBrightnessColorCapabilities(
            { _, list -> if (list.isNotEmpty()) areas = list; restoreLeftover() },
            { Log.w(TAG, "ambient capability", it); restoreLeftover() },
        )
        runCatching {
            val t = TextToSpeech(ctx, Mode.ON_DEVICE)
            tts = t
            t.initialize(object : OnInitializeListener {
                override fun onSuccess() {
                    runCatching { t.setLocale(Locale.KOREA) }
                    ttsReady = true
                }
                override fun onFailure(type: ErrorType) { Log.w(TAG, "tts init $type") }
            })
        }.onFailure { Log.w(TAG, "tts", it) }
    }

    /** 호박색으로 바꾼다. 이미 바꿔 둔 상태면 아무것도 하지 않는다. */
    fun show() {
        if (showing) return
        showing = true
        val light = vehicle.getLight()
        for (a in areas) {
            light.getAmbientLightSwitch(a, { sw ->
                light.getAmbientLightBrightnessColor(a, { color ->
                    light.getAmbientLightBrightness(a, { br ->
                        remember(a, Saved(sw, color, br))
                    }, { remember(a, Saved(sw, color, null)) })
                }, { remember(a, Saved(sw, null, null)) })
            }, { remember(a, Saved(null, null, null)) })
        }
    }

    private fun remember(a: SeatArea, v: Saved) {
        saved[a] = v
        val i = areas.indexOf(a)
        prefs.edit().putBoolean("pending", true)
            .putString("switch$i", v.switch?.name)
            .putInt("color$i", v.color ?: Int.MIN_VALUE)
            .putInt("brightness$i", v.brightness ?: Int.MIN_VALUE)
            .apply()
        applyWarning(a, wasOff = v.switch == LightSwitch.OFF)
    }

    private fun applyWarning(a: SeatArea, wasOff: Boolean) {
        if (!showing) return
        val light = vehicle.getLight()
        if (wasOff) {
            light.setAmbientLightSwitch(a, LightSwitch.ON, { _, _ -> }, { Log.w(TAG, "ambient switch", it) })
            light.setAmbientLightBrightness(a, 40, { _, _ -> }, { Log.w(TAG, "ambient brightness", it) })
        }
        light.setAmbientLightBrightnessColor(a, AMBER, { _, _ -> }, { Log.w(TAG, "ambient color", it) })
    }

    /** 지난번 경고 중에 앱이 꺼져 호박색이 남았으면 저장해 둔 원래 값으로 되돌린다. */
    private fun restoreLeftover() {
        if (!prefs.getBoolean("pending", false)) return
        val light = vehicle.getLight()
        areas.forEachIndexed { i, a ->
            prefs.getInt("color$i", Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE }?.let {
                light.setAmbientLightBrightnessColor(a, it, { _, _ -> }, { e -> Log.w(TAG, "leftover color", e) })
            }
            prefs.getInt("brightness$i", Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE }?.let {
                light.setAmbientLightBrightness(a, it, { _, _ -> }, { e -> Log.w(TAG, "leftover brightness", e) })
            }
            prefs.getString("switch$i", null)?.let { name ->
                runCatching { LightSwitch.valueOf(name) }.getOrNull()?.let {
                    light.setAmbientLightSwitch(a, it, { _, _ -> }, { e -> Log.w(TAG, "leftover switch", e) })
                }
            }
        }
        prefs.edit().putBoolean("pending", false).apply()
        Log.i(TAG, "restored ambient left over from last run")
    }

    /** 원래 조명으로 되돌린다. */
    fun clear() {
        if (!showing) return
        showing = false
        val light = vehicle.getLight()
        for ((a, s) in saved) {
            s.color?.let { light.setAmbientLightBrightnessColor(a, it, { _, _ -> }, { e -> Log.w(TAG, "restore color", e) }) }
            s.brightness?.let { light.setAmbientLightBrightness(a, it, { _, _ -> }, { e -> Log.w(TAG, "restore brightness", e) }) }
            s.switch?.let { light.setAmbientLightSwitch(a, it, { _, _ -> }, { e -> Log.w(TAG, "restore switch", e) }) }
        }
        saved.clear()
        prefs.edit().putBoolean("pending", false).apply()
    }

    fun speak(text: String) {
        val t = tts
        if (t == null || !ttsReady) { Log.w(TAG, "tts not ready: $text"); return }
        t.speak(text, true)
    }

    fun release() {
        clear()
        runCatching { tts?.stop(); tts?.release() }
    }

    companion object {
        private const val TAG = "m29.cues"
        const val AMBER = 0xFFFFA000.toInt()
    }
}
