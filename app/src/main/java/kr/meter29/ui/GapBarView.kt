package kr.meter29.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/**
 * '보이는 거리'와 '멈추는 거리' 두 막대. 0~100m 눈금.
 * 평소에는 흰색·회색, 멈추는 거리가 더 길 때만 멈추는 거리 막대가 호박색. 초록색은 쓰지 않는다.
 */
class GapBarView(context: Context) : View(context) {

    var visibleM: Int? = null
        set(v) { field = v; invalidate() }
    var needM: Int? = null
        set(v) { field = v; invalidate() }
    var warning: Boolean = false
        set(v) { field = v; invalidate() }

    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(40, 44, 48) }
    private val visiblePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(230, 232, 228) }
    private val needPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(200, 204, 200); textSize = 34f
    }
    private val value = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 44f; isFakeBoldText = true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val left = 260f
        val right = width - 140f
        val rowH = height / 2f
        drawRow(canvas, "보이는 거리", visibleM, visiblePaint, 0f, rowH, left, right)
        needPaint.color = if (warning) Color.rgb(255, 160, 0) else Color.rgb(150, 156, 152)
        drawRow(canvas, "멈추는 거리", needM, needPaint, rowH, rowH, left, right)
    }

    private fun drawRow(c: Canvas, name: String, m: Int?, p: Paint, top: Float, h: Float, left: Float, right: Float) {
        val cy = top + h / 2f
        c.drawText(name, 20f, cy + 12f, label)
        val bar = RectF(left, cy - 18f, right, cy + 18f)
        c.drawRoundRect(bar, 18f, 18f, track)
        if (m != null) {
            val w = (right - left) * (m.coerceIn(0, 100) / 100f)
            c.drawRoundRect(RectF(left, cy - 18f, left + w, cy + 18f), 18f, 18f, p)
            c.drawText("${m}m", right + 16f, cy + 15f, value)
        } else {
            c.drawText("—", right + 16f, cy + 15f, value)
        }
    }
}
