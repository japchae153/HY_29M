package kr.meter29

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.TextView
import kr.meter29.core.Beam
import kr.meter29.core.beamLabel
import kr.meter29.ui.GapBarView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 주행 중: 막대 두 개 + 한 줄. 정차(P) 중: 오늘 밤 보이는 거리보다 빨랐던 곳 목록.
 */
class MainActivity : Activity() {

    private lateinit var title: TextView
    private lateinit var bars: GapBarView
    private lateinit var line: TextView
    private lateinit var review: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(12, 14, 16))
            setPadding(80, 60, 80, 60)
        }
        title = TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 40f
            setTextColor(Color.WHITE)
        }
        bars = GapBarView(this)
        line = TextView(this).apply {
            textSize = 30f
            setTextColor(Color.rgb(230, 232, 228))
            gravity = Gravity.START
        }
        review = TextView(this).apply {
            textSize = 26f
            setTextColor(Color.rgb(200, 204, 200))
        }
        root.addView(title, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(bars, LinearLayout.LayoutParams(MATCH_PARENT, 320))
        root.addView(line, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(review, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 40 })
        setContentView(root)

        requestNeededPermissions()
    }

    private fun requestNeededPermissions() {
        val need = PERMISSIONS.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (need.isEmpty()) startGuard() else requestPermissions(need.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        startGuard()
    }

    private fun startGuard() {
        val i = Intent(this, GuardService::class.java)
        // 개발·시연용: adb shell am start -n kr.meter29/.MainActivity --es demo_zone "위도,경도,시연용 이름"
        intent?.getStringExtra(GuardService.EXTRA_DEMO_ZONE)?.let { i.putExtra(GuardService.EXTRA_DEMO_ZONE, it) }
        startForegroundService(i)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.hasExtra(GuardService.EXTRA_DEMO_ZONE)) startGuard()
    }

    override fun onResume() {
        super.onResume()
        GuardService.listener = { s -> runOnUiThread { render(s) } }
        GuardService.last?.let { render(it) }
    }

    override fun onPause() {
        GuardService.listener = null
        super.onPause()
    }

    private fun render(s: UiState) {
        bars.visibleM = s.visibleM
        bars.needM = s.needM
        bars.warning = s.warning
        val speed = s.speedKmh?.roundToInt()?.let { "${it}km/h" } ?: "속도 확인 중"
        val beam = if (s.beam == Beam.OFF) "전조등 꺼짐(판정 안 함)" else beamLabel(s.beam)
        val wet = if (s.wet) " · 젖은 노면" else ""
        val zone = s.zone?.let { " · ${it.name} ${it.distanceM}m 앞" } ?: ""
        line.text = "$speed · $beam$wet$zone"
        line.setTextColor(if (s.warning) Color.rgb(255, 176, 32) else Color.rgb(230, 232, 228))

        // 정차 중에만 돌아보기 목록을 보여 준다(주행 중 화면은 막대와 한 줄).
        review.text = if (s.parked) reviewText() else ""
    }

    private fun reviewText(): String {
        val list = GuardService.events
        if (list.isEmpty()) return "오늘 밤 보이는 거리보다 빨랐던 곳은 없습니다."
        val fmt = SimpleDateFormat("HH:mm", Locale.KOREA)
        val rows = list.joinToString("\n") {
            "${fmt.format(Date(it.timeMs))}  ${it.zoneName} — ${it.speedKmh}km/h, 보이는 ${it.visibleM}m / 필요 ${it.needM}m"
        }
        return "오늘 밤 ${list.size}곳에서 보이는 거리보다 빨랐습니다.\n$rows"
    }

    companion object {
        private val PERMISSIONS = listOf(
            "android.car.permission.CAR_SPEED",
            "android.car.permission.CAR_POWERTRAIN",
            "android.permission.ACCESS_FINE_LOCATION",
            "android.permission.POST_NOTIFICATIONS",
            "pleos.car.permission.CAR_LIGHTS",
            "pleos.car.permission.CAR_WINDOWS",
            "pleos.car.permission.FUSED_LOCATION",
            "pleos.car.permission.NAVI_ROUTE",
            "pleos.car.permission.TTS_SERVICE",
        )
    }
}
