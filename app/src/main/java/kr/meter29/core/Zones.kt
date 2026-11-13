package kr.meter29.core

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class LatLng(val lat: Double, val lon: Double)
data class ZonePoint(val name: String, val pos: LatLng, val source: String)
/** id는 같은 이름(예: 어린이보호구역)의 서로 다른 지점을 구분한다. 기본값은 이름. */
data class ZoneAhead(val name: String, val distanceM: Int, val id: String = name)

private const val EARTH_RADIUS_M = 6_371_000.0

fun haversineM(a: LatLng, b: LatLng): Double {
    val latA = Math.toRadians(a.lat)
    val latB = Math.toRadians(b.lat)
    val halfLat = sin((latB - latA) / 2.0)
    val halfLon = sin(Math.toRadians(b.lon - a.lon) / 2.0)
    val h = halfLat * halfLat + cos(latA) * cos(latB) * halfLon * halfLon
    return 2.0 * EARTH_RADIUS_M * asin(sqrt(h.coerceIn(0.0, 1.0)))
}

fun bearingDeg(a: LatLng, b: LatLng): Double {
    val latA = Math.toRadians(a.lat)
    val latB = Math.toRadians(b.lat)
    val deltaLon = Math.toRadians(b.lon - a.lon)
    val y = sin(deltaLon) * cos(latB)
    val x = cos(latA) * sin(latB) - sin(latA) * cos(latB) * cos(deltaLon)
    return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
}

fun nearestAhead(
    pos: LatLng,
    headingDeg: Double?,
    points: List<ZonePoint>,
    maxDistM: Int = 300,
    maxBearingDiff: Double = 45.0
): ZoneAhead? {
    var nearest: ZonePoint? = null
    var nearestDistance = Double.POSITIVE_INFINITY
    for (point in points) {
        val distance = haversineM(pos, point.pos)
        if (distance > maxDistM || distance >= nearestDistance) continue
        if (headingDeg != null) {
            val delta = bearingDeg(pos, point.pos) - headingDeg
            val normalized = ((delta % 360.0) + 360.0) % 360.0
            val difference = minOf(normalized, 360.0 - normalized)
            if (difference > maxBearingDiff) continue
        }
        nearest = point
        nearestDistance = distance
    }
    return nearest?.let {
        ZoneAhead(it.name, Math.round(nearestDistance).toInt(), id = "${it.name}@${it.pos.lat},${it.pos.lon}")
    }
}

fun parseZones(json: String): List<ZonePoint> = ZoneJsonParser(json).parse()

// 지정된 지점 배열 형식만 읽는다. 잘못된 입력은 부분 결과 대신 예외로 알린다.
private class ZoneJsonParser(private val json: String) {
    private var index = 0
    private val numberPattern = Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?")

    fun parse(): List<ZonePoint> {
        expect('[')
        val points = mutableListOf<ZonePoint>()
        if (!take(']')) {
            do {
                points.add(point())
            } while (take(','))
            expect(']')
        }
        whitespace()
        require(index == json.length) { "Unexpected content at $index" }
        return points
    }

    private fun point(): ZonePoint {
        expect('{')
        var name: String? = null
        var lat: Double? = null
        var lon: Double? = null
        var source: String? = null
        val keys = mutableSetOf<String>()
        if (!take('}')) {
            do {
                val key = string()
                require(keys.add(key)) { "Duplicate field: $key" }
                expect(':')
                when (key) {
                    "name" -> name = string()
                    "lat" -> lat = number()
                    "lon" -> lon = number()
                    "source" -> source = string()
                    else -> throw IllegalArgumentException("Unknown field: $key")
                }
            } while (take(','))
            expect('}')
        }
        return ZonePoint(
            requireNotNull(name) { "Missing name" },
            LatLng(requireNotNull(lat) { "Missing lat" }, requireNotNull(lon) { "Missing lon" }),
            requireNotNull(source) { "Missing source" }
        )
    }

    private fun string(): String {
        expect('"')
        val result = StringBuilder()
        while (index < json.length) {
            val c = json[index++]
            when (c) {
                '"' -> return result.toString()
                '\\' -> {
                    require(index < json.length) { "Unfinished escape" }
                    result.append(when (val escaped = json[index++]) {
                        '"', '\\', '/' -> escaped
                        'b' -> '\b'
                        'f' -> '\u000c'
                        'n' -> '\n'
                        'r' -> '\r'
                        't' -> '\t'
                        'u' -> {
                            require(index + 4 <= json.length) { "Unfinished unicode escape" }
                            val hex = json.substring(index, index + 4)
                            require(hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
                                "Invalid unicode escape"
                            }
                            index += 4
                            hex.toInt(16).toChar()
                        }
                        else -> throw IllegalArgumentException("Invalid escape: $escaped")
                    })
                }
                else -> {
                    require(c >= ' ') { "Unescaped control character" }
                    result.append(c)
                }
            }
        }
        throw IllegalArgumentException("Unterminated string")
    }

    // 숫자 후보 구간만 잘라 낸 뒤 정규식으로 검사한다. 안드로이드 정규식은 Matcher를 만들 때
    // 입력 전체를 복사하므로, 4MB 문자열에 matchAt을 반복하면 기기에서 수십 초가 걸린다.
    private fun number(): Double {
        whitespace()
        val start = index
        while (index < json.length && (json[index].isDigit() || json[index] in "+-.eE")) index++
        val text = json.substring(start, index)
        require(text.isNotEmpty() && numberPattern.matches(text)) { "Expected number at $start" }
        return text.toDouble().also { require(it.isFinite()) { "Non-finite coordinate" } }
    }

    private fun whitespace() {
        while (index < json.length && json[index] in " \t\r\n") index++
    }

    private fun take(c: Char): Boolean {
        whitespace()
        if (index >= json.length || json[index] != c) return false
        index++
        return true
    }

    private fun expect(c: Char) {
        require(take(c)) { "Expected '$c' at $index" }
    }
}
