package kr.meter29.vehicle

import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.model.LocationData
import ai.pleos.playground.navi.constants.CautionPointType
import ai.pleos.playground.navi.data.CautionPoint
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.constant.area.WindowValue
import ai.pleos.playground.vehicle.constant.control.LightState
import ai.pleos.playground.vehicle.constant.control.LightSwitch
import ai.pleos.playground.vehicle.constant.control.WiperState
import ai.pleos.playground.vehicle.listener.LightStateListener
import ai.pleos.playground.vehicle.listener.WiperStateListener
import android.annotation.SuppressLint
import android.car.Car
import android.car.VehicleGear
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.util.Log
import kr.meter29.core.Beam
import kr.meter29.core.LatLng
import kr.meter29.core.ZoneAhead
import kr.meter29.core.bearingDeg
import kr.meter29.core.haversineM

/**
 * 차량 신호를 모아 두는 곳. 값은 콜백이 올 때마다 갱신되고, 서비스가 1초마다 읽는다.
 * - 전조등·와이퍼: Pleos Vehicle SDK
 * - 차속·기어: AAOS CarPropertyManager (Pleos 문서 안내대로)
 * - 위치: Pleos FusedLocation SDK, 안 되면 안드로이드 GPS
 * - 위험 지점: Pleos NaviHelper 주의지점
 */
class Signals(private val ctx: Context, val vehicle: Vehicle) {

    @Volatile var speedKmh: Double? = null; private set
    @Volatile var gear: Int? = null; private set
    @Volatile private var lowBeamOn = false
    @Volatile private var highBeamOn = false
    @Volatile var wet = false; private set
    /** 상향등 스위치가 AUTO(하이빔 보조)인지. 변화 구독 API가 없어 주기적으로 다시 읽는다. */
    @Volatile var autoHighBeam = false; private set
    @Volatile var pos: LatLng? = null; private set
    @Volatile var heading: Double? = null; private set
    @Volatile private var caution: ZoneAhead? = null
    @Volatile private var cautionAtMs = 0L

    val beam: Beam
        get() = when {
            highBeamOn -> Beam.HIGH
            lowBeamOn -> Beam.LOW
            else -> Beam.OFF
        }

    val parked: Boolean get() = gear == VehicleGear.GEAR_PARK

    /** 내비 주의지점은 5초 안에 다시 오지 않으면 지나간 것으로 본다. */
    fun cautionAhead(nowMs: Long): ZoneAhead? =
        caution?.takeIf { nowMs - cautionAtMs < 5_000L }

    private var car: Car? = null
    private var cpm: CarPropertyManager? = null
    private var navi: NaviHelper? = null
    private var fused: FusedLocation? = null
    private var locationManager: LocationManager? = null
    private val front = WindowArea(WindowValue.FRONT_WINDSHIELD)

    private val lowBeamListener = object : LightStateListener {
        override fun onLightStateUpdated(state: LightState?) { lowBeamOn = state == LightState.ON }
        override fun onFailed(e: Exception) { Log.w(TAG, "low beam listener", e) }
    }
    private val highBeamListener = object : LightStateListener {
        override fun onLightStateUpdated(state: LightState?) { highBeamOn = state == LightState.ON }
        override fun onFailed(e: Exception) { Log.w(TAG, "high beam listener", e) }
    }
    private val wiperListener = object : WiperStateListener {
        override fun onWiperStateUpdated(state: WiperState?) { wet = state == WiperState.ON }
        override fun onFailed(e: Exception) { Log.w(TAG, "wiper listener", e) }
    }
    private val carCallback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(v: CarPropertyValue<*>) {
            when (v.propertyId) {
                VehiclePropertyIds.PERF_VEHICLE_SPEED ->
                    speedKmh = (v.value as Float).toDouble() * 3.6
                VehiclePropertyIds.GEAR_SELECTION -> gear = v.value as Int
            }
        }
        override fun onErrorEvent(propId: Int, zone: Int) { Log.w(TAG, "car property error $propId") }
    }
    private val fusedListener = object : FusedLocationListener {
        override fun onChanged(data: LocationData?) {
            if (data == null) return
            onPosition(LatLng(data.latitude, data.longitude), data.heading.toDouble())
        }
        override fun onError(e: Exception) { Log.w(TAG, "fused location error", e) }
    }
    private val gpsListener = LocationListener { loc: Location ->
        onPosition(LatLng(loc.latitude, loc.longitude), if (loc.hasBearing()) loc.bearing.toDouble() else null)
    }
    private val naviListener = object : NaviHelperEventListener {
        override fun onCautionPointInfo(points: List<CautionPoint>) {
            val near = points
                .filter { it.type in PEDESTRIAN_TYPES && it.remainDistance in 0..300 }
                .minByOrNull { it.remainDistance } ?: return
            caution = ZoneAhead(labelOf(near.type), near.remainDistance, id = "navi:${near.id}")
            cautionAtMs = System.currentTimeMillis()
        }
    }

    fun start() {
        val light = vehicle.getLight()
        light.registerLowBeamLightState(lowBeamListener)
        light.registerHighBeamLightState(highBeamListener)
        light.getCurrentLowBeamLightState({ lowBeamOn = it == LightState.ON }, { Log.w(TAG, "low beam", it) })
        light.getCurrentHighBeamLightState({ highBeamOn = it == LightState.ON }, { Log.w(TAG, "high beam", it) })
        refreshHighBeamSwitch()
        vehicle.getWiper().registerWiperState(front, wiperListener)
        vehicle.getWiper().getCurrentWiperState(front, { wet = it == WiperState.ON }, { Log.w(TAG, "wiper", it) })

        runCatching {
            val c = Car.createCar(ctx)
            car = c
            val m = c.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
            cpm = m
            m.registerCallback(carCallback, VehiclePropertyIds.PERF_VEHICLE_SPEED, CarPropertyManager.SENSOR_RATE_NORMAL)
            m.registerCallback(carCallback, VehiclePropertyIds.GEAR_SELECTION, CarPropertyManager.SENSOR_RATE_ONCHANGE)
        }.onFailure { Log.w(TAG, "car property", it) }

        runCatching {
            val f = FusedLocation(ctx)
            f.initialize()
            fused = f
            f.registerFusedLocationCallback(fusedListener)
        }.onFailure { Log.w(TAG, "fused location", it) }
        startGpsFallback()

        runCatching {
            val n = NaviHelper(ctx)
            navi = n
            n.initialize({ n.addListener(naviListener) }, { Log.w(TAG, "navi init", it) })
        }.onFailure { Log.w(TAG, "navi", it) }
    }

    fun refreshHighBeamSwitch() {
        vehicle.getLight().getHighBeamLightSwitch(
            { autoHighBeam = it == LightSwitch.AUTOMATIC },
            { Log.w(TAG, "high beam switch", it) },
        )
    }

    @SuppressLint("MissingPermission")
    private fun startGpsFallback() {
        runCatching {
            val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            locationManager = lm
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1_000L, 0f, gpsListener)
        }.onFailure { Log.w(TAG, "gps fallback", it) }
    }

    /**
     * 진행 방향은 직전 위치에서 3m 넘게 움직였을 때 두 점의 방위로 계산한다.
     * 거의 안 움직였으면 이미 계산한 방향을 유지한다(같은 위치가 위치 SDK와 GPS 두 경로로 와서
     * 보고된 0°가 계산값을 덮어쓰는 일을 막음). 아직 방향이 없을 때만 보고된 값을 쓴다.
     */
    private fun onPosition(p: LatLng, reportedHeading: Double?) {
        val prev = pos
        if (prev != null && haversineM(prev, p) > 3.0) {
            heading = bearingDeg(prev, p)
            pos = p
        } else {
            if (heading == null) heading = reportedHeading
            if (prev == null) pos = p
        }
    }

    fun stop() {
        runCatching {
            vehicle.getLight().unregisterLowBeamLightState(lowBeamListener)
            vehicle.getLight().unregisterHighBeamLightState(highBeamListener)
            vehicle.getWiper().unregisterWiperState(front, wiperListener)
        }
        runCatching { cpm?.unregisterCallback(carCallback) }
        runCatching { car?.disconnect() }
        runCatching { fused?.unregisterFusedLocationCallback(fusedListener); fused?.release() }
        runCatching { locationManager?.removeUpdates(gpsListener) }
        runCatching { navi?.removeListener(naviListener); navi?.release() }
    }

    companion object {
        private const val TAG = "m29.signals"
        private val PEDESTRIAN_TYPES = setOf(
            CautionPointType.SCHOOL_ZONE,
            CautionPointType.ACCIDENT_PRONE_AREA,
            CautionPointType.NARROW_ROAD,
        )

        fun labelOf(t: CautionPointType): String = when (t) {
            CautionPointType.SCHOOL_ZONE -> "어린이보호구역"
            CautionPointType.ACCIDENT_PRONE_AREA -> "사고가 잦은 곳"
            CautionPointType.NARROW_ROAD -> "좁은 길"
            else -> "주의 지점"
        }
    }
}
