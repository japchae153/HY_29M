package kr.meter29.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ZonesTest {
    private val pos = LatLng(37.5665, 126.9780)

    private fun north(meters: Double, name: String = "횡단보도") =
        ZonePoint(name, LatLng(pos.lat + meters / 111195.0, pos.lon), "테스트")

    @Test fun haversine_seoulCityHallToGwanghwamun() {
        assertEquals(1050.0, haversineM(pos, LatLng(37.5759, 126.9769)), 20.0)
    }

    @Test fun zoneMatcher_findsPointAhead() {
        val result = nearestAhead(pos, 0.0, listOf(north(200.0)))!!
        assertEquals("횡단보도", result.name)
        assertEquals(200.0, result.distanceM.toDouble(), 1.0)
    }

    @Test fun zoneMatcher_ignoresPointsBehind() {
        assertNull(nearestAhead(pos, 0.0, listOf(north(-200.0))))
    }

    @Test fun zoneMatcher_ignoresPointsBeyond300M() {
        assertNull(nearestAhead(pos, 0.0, listOf(north(350.0))))
    }

    @Test fun zoneMatcher_ignoresBearing_whenHeadingUnknown() {
        assertNameDistance("횡단보도", 200, nearestAhead(pos, null, listOf(north(-200.0))))
        assertNull(nearestAhead(pos, null, listOf(north(350.0))))
    }

    @Test fun zoneMatcher_wrapsBearingAt360() {
        val origin = LatLng(0.0, 0.0)
        val point = ZonePoint("횡단보도", LatLng(0.0017713, 0.00031233), "테스트")
        assertEquals(10.0, bearingDeg(origin, point.pos), 0.01)
        assertNameDistance("횡단보도", 200, nearestAhead(origin, 350.0, listOf(point)))
    }

    @Test fun zoneMatcher_selectsNearestEligiblePoint() {
        val points = listOf(north(-50.0, "뒤"), north(250.0, "먼 곳"), north(100.0, "가까운 곳"))
        assertNameDistance("가까운 곳", 100, nearestAhead(pos, 0.0, points))
        assertNull(nearestAhead(pos, 0.0, emptyList()))
    }

    @Test fun zoneMatcher_respectsCustomLimits() {
        assertNull(nearestAhead(pos, 0.0, listOf(north(200.0)), maxDistM = 100))
        val point = ZonePoint("동쪽", LatLng(0.0, 0.001), "테스트")
        assertNull(nearestAhead(LatLng(0.0, 0.0), 0.0, listOf(point)))
        assertNameDistance("동쪽", 111, nearestAhead(
            LatLng(0.0, 0.0), 0.0, listOf(point), maxBearingDiff = 90.0
        ))
    }

    @Test fun bearing_cardinalDirections() {
        val origin = LatLng(0.0, 0.0)
        assertEquals(0.0, bearingDeg(origin, LatLng(1.0, 0.0)), 0.0001)
        assertEquals(90.0, bearingDeg(origin, LatLng(0.0, 1.0)), 0.0001)
        assertEquals(180.0, bearingDeg(origin, LatLng(-1.0, 0.0)), 0.0001)
        assertEquals(270.0, bearingDeg(origin, LatLng(0.0, -1.0)), 0.0001)
        assertEquals(0.0, haversineM(origin, origin), 0.0)
    }

    @Test fun parseZones_readsInlineData() {
        val json = """[{"name":"횡단보도","lat":37.1,"lon":127.1,"source":"테스트 출처"}]"""
        assertEquals(listOf(ZonePoint("횡단보도", LatLng(37.1, 127.1), "테스트 출처")), parseZones(json))
        assertEquals(emptyList<ZonePoint>(), parseZones(" [] "))
    }

    @Test fun parseZones_handlesOrderWhitespaceEscapesAndNumbers() {
        val json = """
            [
              {"source":"테스트, {출처}","lon":1.271e2,"name":"횡단\"보도\"","lat":37.1},
              {"name":"\uD69F\n길\\끝","lat":-3.71E1,"lon":127,"source":"a\/b\t\b\f\r"}
            ]
        """.trimIndent()
        assertEquals(listOf(
            ZonePoint("횡단\"보도\"", LatLng(37.1, 127.1), "테스트, {출처}"),
            ZonePoint("횟\n길\\끝", LatLng(-37.1, 127.0), "a/b\t\b\u000c\r")
        ), parseZones(json))
    }

    @Test(expected = IllegalArgumentException::class)
    fun parseZones_rejectsMissingFields() {
        parseZones("""[{"name":"횡단보도","lat":37.1,"lon":127.1}]""")
    }

    @Test(expected = IllegalArgumentException::class)
    fun parseZones_rejectsTrailingContent() {
        parseZones("[] garbage")
    }

    @Test(expected = IllegalArgumentException::class)
    fun parseZones_rejectsTrailingComma() {
        parseZones("""[{"name":"횡단보도","lat":37.1,"lon":127.1,"source":"테스트"},]""")
    }

    @Test fun nearestAhead_setsIdFromNameAndPosition() {
        val p = ZonePoint("횡단보도", LatLng(37.5, 127.0), "test")
        val z = nearestAhead(LatLng(37.499, 127.0), 0.0, listOf(p))!!
        assertEquals("횡단보도@37.5,127.0", z.id)
    }

    private fun assertNameDistance(name: String, distanceM: Int, actual: ZoneAhead?) {
        assertEquals(name to distanceM, actual?.let { it.name to it.distanceM })
    }

    @Test fun parseZones_readsBundledPublicDataAsset() {
        // tools/make_zones.py로 만든 공공데이터 지점(도로교통공단·공공데이터포털)
        val json = java.io.File("src/main/assets/zones.json").readText()
        val counts = parseZones(json).groupingBy { it.name }.eachCount()
        assertEquals(
            mapOf(
                "보행자 사고가 잦은 곳" to 2223,
                "어르신 보행 사고가 잦은 곳" to 664,
                "어린이 보행 사고가 잦은 곳" to 348,
                "집중조명이 없는 횡단보도" to 20863,
            ),
            counts
        )
    }
}
