package kr.meter29

import ai.pleos.playground.vehicle.Vehicle
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.Log
import kr.meter29.core.Advice
import kr.meter29.core.Advisor
import kr.meter29.core.Beam
import kr.meter29.core.DriveState
import kr.meter29.core.LatLng
import kr.meter29.core.ZoneAhead
import kr.meter29.core.ZonePoint
import kr.meter29.core.nearestAhead
import kr.meter29.core.parseZones
import kr.meter29.core.stoppingDistanceM
import kr.meter29.core.visibleDistanceM
import kr.meter29.core.warnSpeech
import kr.meter29.output.Cues
import kr.meter29.vehicle.Signals
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.roundToInt

/** 화면이 그리는 값 한 묶음. */
data class UiState(
    val speedKmh: Double?,
    val beam: Beam,
    val wet: Boolean,
    val parked: Boolean,
    val zone: ZoneAhead?,
    val visibleM: Int?,
    val needM: Int?,
    val warning: Boolean,
)

/** 정차 후 돌아보기에 쓰는 기록(그 지점에서 처음 경고한 순간). */
data class NightEvent(val timeMs: Long, val zoneName: String, val speedKmh: Int, val visibleM: Int, val needM: Int)

/**
 * 1초마다 차량 신호 → 판정 엔진 → 알림(조명·음성)을 돌리는 포그라운드 서비스.
 * 화면이 꺼져 있어도 주행 중 계속 판정한다.
 */
class GuardService : Service() {

    // 판정 루프와 지점 파일 읽기는 메인 스레드 밖에서 돈다(4MB 파일 읽기로 화면이 멈추지 않게).
    private val worker = HandlerThread("m29-guard").apply { start() }
    private val handler = Handler(worker.looper)
    private lateinit var vehicle: Vehicle
    private lateinit var signals: Signals
    private lateinit var cues: Cues
    private val advisor = Advisor()
    @Volatile private var zones: List<ZonePoint> = emptyList()

    private val tick = object : Runnable {
        override fun run() {
            runCatching { step() }.onFailure { Log.w(TAG, "step", it) }
            handler.postDelayed(this, 1_000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        startForegroundCompat()
        vehicle = Vehicle(applicationContext).also { it.initialize() }
        signals = Signals(applicationContext, vehicle).also { it.start() }
        cues = Cues(applicationContext, vehicle).also { it.start() }
        handler.post {
            val t0 = System.currentTimeMillis()
            val loaded = runCatching { parseZones(assets.open("zones.json").bufferedReader().readText()) }
                .getOrElse { Log.i(TAG, "zones.json 없음 — 내비 주의지점만 사용"); emptyList() }
            zones = loaded + zones   // 읽는 사이 추가된 시연 지점은 유지
            Log.i(TAG, "zones loaded: ${loaded.size} in ${System.currentTimeMillis() - t0}ms")
        }
        handler.post(tick)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 개발·시연용: 위험 지점을 하나 추가한다(adb로만 보냄). 이름에 '시연'이 들어가야 한다.
        intent?.getStringExtra(EXTRA_DEMO_ZONE)?.let { arg ->
            val parts = arg.split(",")
            if (parts.size == 3 && parts[2].contains("시연")) {
                val p = ZonePoint(parts[2], LatLng(parts[0].toDouble(), parts[1].toDouble()), "demo")
                zones = zones + p
                Log.i(TAG, "demo zone added: $p")
            }
        }
        return START_STICKY
    }

    private var ticks = 0L

    private fun step() {
        val now = System.currentTimeMillis()
        if (ticks++ % 5 == 0L) signals.refreshHighBeamSwitch()
        val pos = signals.pos
        val publicZone = pos?.let { nearestAhead(it, signals.heading, zones) }
        val zone = listOfNotNull(publicZone, signals.cautionAhead(now)).minByOrNull { it.distanceM }
        val s = DriveState(signals.speedKmh, signals.beam, signals.wet, zone, now)
        val advice = advisor.update(s)

        when (advice) {
            is Advice.Warn -> {
                cues.show()
                if (advice.speak) {
                    val text = warnSpeech(advice, s.beam, s.wet, autoHighBeam = signals.autoHighBeam)
                    Log.i(TAG, "WARN speak: $text")
                    cues.speak(text)
                }
                if (advice.firstForZone) {
                    Log.i(TAG, "WARN zone: ${advice.zone.name} ${advice.zone.distanceM}m (speak=${advice.speak})")
                    events.add(NightEvent(now, advice.zone.name, (s.speedKmh ?: 0.0).roundToInt(), advice.visibleM, advice.needM))
                }
            }
            Advice.Quiet -> cues.clear()
        }

        val ui = UiState(
            speedKmh = s.speedKmh,
            beam = s.beam,
            wet = s.wet,
            parked = signals.parked,
            zone = zone,
            visibleM = if (s.beam == Beam.OFF) null else visibleDistanceM(s.beam).roundToInt(),
            needM = s.speedKmh?.let { stoppingDistanceM(it, s.wet).roundToInt() },
            warning = advice is Advice.Warn,
        )
        last = ui
        listener?.invoke(ui)
        Log.d(TAG, "state $ui pos=$pos heading=${signals.heading}")
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        worker.quitSafely()
        runCatching { cues.release() }
        runCatching { signals.stop() }
        runCatching { vehicle.release() }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundCompat() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "야간 보행자 안내", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val n: Notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("밤길 보행자 위험 지점을 살피는 중")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, n)
        }
    }

    companion object {
        private const val TAG = "m29.guard"
        private const val CHANNEL = "guard"
        const val EXTRA_DEMO_ZONE = "demo_zone"
        @Volatile var listener: ((UiState) -> Unit)? = null
        @Volatile var last: UiState? = null
        val events = CopyOnWriteArrayList<NightEvent>()
    }
}
