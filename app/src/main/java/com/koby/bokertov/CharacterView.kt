package com.koby.bokertov

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.view.View
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin

/** דמות סמיילי מצוירת בקוד, חדה בכל גודל ומונפשת לפי מה שהאפליקציה עושה. */
class CharacterView(ctx: Context) : View(ctx) {

    private var face = Face.OFF

    fun setFace(f: Face) {
        if (f != face) { face = f; invalidate() }
    }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }
    private val path = Path()
    private val rect = RectF()

    private var shader: Shader? = null
    private var shaderKey = ""

    private fun pick(normal: Long, gray: Long, off: Boolean): Int = (if (off) gray else normal).toInt()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val t = (SystemClock.uptimeMillis() % 100000L) / 1000f
        val w = width.toFloat()
        val h = height.toFloat()
        val r = min(w, h * 0.9f) * 0.30f
        val cx = w / 2f
        val cy = h / 2f - r * 0.1f
        val off = face == Face.OFF

        val yellowLight = pick(0xFFFFE680, 0xFFEEEEEE, off)
        val yellow = pick(0xFFFFC62E, 0xFFD6D6D6, off)
        val yellowDark = pick(0xFFE59A00, 0xFF9E9E9E, off)
        val brown = pick(0xFF5B3A1A, 0xFF616161, off)

        var bounce = 0f
        var tilt = 0f
        when (face) {
            Face.SPEAKING -> bounce = -abs(sin(t * 9f)) * r * 0.05f
            Face.THINKING -> tilt = 7f * sin(t * 1.5f)
            Face.OFF -> tilt = -6f
            Face.LISTENING -> {}
        }
        val breathe = 1f + 0.015f * sin(t * 2f)

        // צל על הרצפה
        fill.shader = null
        fill.color = 0x22000000
        rect.set(cx - r * 0.85f, cy + r * 1.25f, cx + r * 0.85f, cy + r * 1.42f)
        canvas.drawOval(rect, fill)

        canvas.save()
        canvas.translate(0f, bounce)
        canvas.rotate(tilt, cx, cy + r)
        canvas.scale(breathe, breathe, cx, cy + r)

        // טבעת הקשבה
        if (face == Face.LISTENING) {
            line.shader = null
            line.color = 0xFF43A047.toInt()
            line.alpha = (90 + 70 * sin(t * 3f)).toInt().coerceIn(0, 255)
            line.strokeWidth = r * 0.06f
            canvas.drawCircle(cx, cy, r * (1.22f + 0.05f * sin(t * 3f)), line)
            line.alpha = 255
        }

        // זרועות
        val armW = r * 0.24f
        fun arm(sx: Float, sy: Float, qx: Float, qy: Float, hx: Float, hy: Float) {
            path.reset()
            path.moveTo(sx, sy)
            path.quadTo(qx, qy, hx, hy)
            line.shader = null
            line.color = yellowDark
            line.strokeWidth = armW + r * 0.05f
            canvas.drawPath(path, line)
            line.color = yellow
            line.strokeWidth = armW
            canvas.drawPath(path, line)
        }
        fun hand(hx: Float, hy: Float) {
            fill.shader = null
            fill.color = yellowDark
            canvas.drawCircle(hx, hy, r * 0.20f, fill)
            fill.color = yellow
            canvas.drawCircle(hx, hy, r * 0.17f, fill)
        }
        // יד שמאל (בצד ימין של המסך, כמו בדמות)
        val lhx = cx - r * 1.22f
        val lhy = cy + r * 1.0f
        arm(cx - r * 0.9f, cy + r * 0.35f, cx - r * 1.25f, cy + r * 0.5f, lhx, lhy)
        // יד ימין: משתנה לפי המצב
        val rhx: Float
        val rhy: Float
        val rqx: Float
        val rqy: Float
        when (face) {
            Face.THINKING -> { rhx = cx + r * 0.30f; rhy = cy + r * 0.78f; rqx = cx + r * 1.2f; rqy = cy + r * 0.9f }
            Face.LISTENING -> { rhx = cx + r * 1.12f; rhy = cy - r * 0.12f; rqx = cx + r * 1.38f; rqy = cy + r * 0.4f }
            Face.SPEAKING -> { rhx = cx + r * 1.28f; rhy = cy + r * (0.55f + 0.12f * sin(t * 8f)); rqx = cx + r * 1.3f; rqy = cy + r * 0.5f }
            Face.OFF -> { rhx = cx + r * 1.22f; rhy = cy + r * 1.0f; rqx = cx + r * 1.25f; rqy = cy + r * 0.5f }
        }
        arm(cx + r * 0.9f, cy + r * 0.35f, rqx, rqy, rhx, rhy)
        if (face != Face.THINKING) hand(rhx, rhy)
        hand(lhx, lhy)

        // גוף
        val key = "$cx|$cy|$r|$off"
        if (key != shaderKey) {
            shader = RadialGradient(
                cx - r * 0.3f, cy - r * 0.35f, r * 1.45f,
                intArrayOf(yellowLight, yellow, yellowDark), floatArrayOf(0f, 0.62f, 1f), Shader.TileMode.CLAMP
            )
            shaderKey = key
        }
        fill.shader = shader
        canvas.drawCircle(cx, cy, r, fill)
        fill.shader = null
        line.color = yellowDark
        line.strokeWidth = r * 0.03f
        canvas.drawCircle(cx, cy, r, line)

        // לחיים
        if (!off) {
            fill.color = 0x33FF6F61
            canvas.drawCircle(cx - r * 0.66f, cy + r * 0.28f, r * 0.12f, fill)
            canvas.drawCircle(cx + r * 0.66f, cy + r * 0.28f, r * 0.12f, fill)
        }

        // גבות
        line.color = brown
        line.strokeWidth = r * 0.11f
        val liftL: Float
        val liftR: Float
        when (face) {
            Face.LISTENING -> { liftL = r * 0.08f; liftR = r * 0.08f }
            Face.SPEAKING -> { liftL = r * 0.04f * sin(t * 9f); liftR = liftL }
            Face.THINKING -> { liftL = r * 0.14f; liftR = -r * 0.04f }
            Face.OFF -> { liftL = -r * 0.06f; liftR = -r * 0.06f }
        }
        val by = cy - r * 0.52f
        path.reset()
        path.moveTo(cx - r * 0.62f, by - liftL * 0.5f)
        path.quadTo(cx - r * 0.35f, by - r * 0.17f - liftL, cx - r * 0.08f, by - liftL * 0.2f)
        canvas.drawPath(path, line)
        path.reset()
        path.moveTo(cx + r * 0.62f, by - liftR * 0.5f)
        path.quadTo(cx + r * 0.35f, by - r * 0.17f - liftR, cx + r * 0.08f, by - liftR * 0.2f)
        canvas.drawPath(path, line)

        // עיניים
        val bt = t % 3.8f
        val blink = if (bt > 3.6f) 0.12f + 0.88f * min(1f, abs(bt - 3.7f) / 0.1f) else 1f
        val ey = cy - r * 0.12f
        val rx = r * 0.25f
        val ry = r * 0.31f * blink
        for (side in intArrayOf(-1, 1)) {
            val ex = cx + side * r * 0.33f
            if (off) {
                line.color = brown
                line.strokeWidth = r * 0.07f
                path.reset()
                path.moveTo(ex - rx * 0.9f, ey)
                path.quadTo(ex, ey + r * 0.16f, ex + rx * 0.9f, ey)
                canvas.drawPath(path, line)
            } else {
                rect.set(ex - rx, ey - ry, ex + rx, ey + ry)
                fill.color = 0xFFFFFFFF.toInt()
                canvas.drawOval(rect, fill)
                line.color = 0x33000000
                line.strokeWidth = r * 0.015f
                canvas.drawOval(rect, line)
                canvas.save()
                path.reset()
                path.addOval(rect, Path.Direction.CW)
                canvas.clipPath(path)
                val ox: Float
                val oy: Float
                when (face) {
                    Face.THINKING -> { ox = -rx * 0.25f; oy = -ry * 0.4f }
                    Face.LISTENING -> { ox = sin(t * 0.9f) * rx * 0.18f; oy = 0f }
                    else -> { ox = sin(t * 2f) * rx * 0.08f; oy = 0f }
                }
                fill.color = 0xFF3D8FDB.toInt()
                canvas.drawCircle(ex + ox, ey + oy, r * 0.155f, fill)
                fill.color = 0xFF14202E.toInt()
                canvas.drawCircle(ex + ox, ey + oy, r * 0.078f, fill)
                fill.color = 0xFFFFFFFF.toInt()
                canvas.drawCircle(ex + ox - r * 0.05f, ey + oy - r * 0.06f, r * 0.04f, fill)
                canvas.restore()
            }
        }

        // פה
        val my = cy + r * 0.36f
        val hw = r * 0.36f
        if (face == Face.SPEAKING) {
            val a = 0.2f + 0.8f * abs(sin(t * 10f))
            path.reset()
            path.moveTo(cx - hw * 0.85f, my)
            path.quadTo(cx, my - r * 0.05f, cx + hw * 0.85f, my)
            path.quadTo(cx, my + r * 0.55f * a, cx - hw * 0.85f, my)
            path.close()
            fill.color = 0xFF7A2E1A.toInt()
            canvas.drawPath(path, fill)
        } else {
            line.color = brown
            line.strokeWidth = r * 0.07f
            val depth = when (face) {
                Face.LISTENING -> 0.30f
                Face.THINKING -> 0.12f
                else -> 0.07f
            }
            val shift = if (face == Face.THINKING) r * 0.12f else 0f
            path.reset()
            path.moveTo(cx - hw + shift, my)
            path.quadTo(cx + shift, my + r * depth * 1.6f, cx + hw + shift, my)
            canvas.drawPath(path, line)
        }

        // יד על הסנטר
        if (face == Face.THINKING) {
            hand(rhx, rhy)
        }

        canvas.restore()

        // Zzz כשהיא כבויה
        if (off) {
            txt.color = 0xFF607D8B.toInt()
            for (i in 0..2) {
                val phase = (t * 0.6f + i * 0.33f) % 1f
                txt.textSize = r * (0.22f + 0.1f * i)
                txt.alpha = (255 * (1f - phase)).toInt().coerceIn(0, 255)
                canvas.drawText("Z", cx + r * (0.95f + 0.2f * i), cy - r * (0.55f + 0.3f * i) - phase * r * 0.2f, txt)
            }
        }

        postInvalidateOnAnimation()
    }

    @Suppress("unused")
    private val twoPi = (2 * PI).toFloat()
}
