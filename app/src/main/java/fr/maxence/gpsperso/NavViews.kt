package fr.maxence.gpsperso

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.sin

/**
 * Schéma de l'échangeur à l'approche d'une sortie, d'une bifurcation ou d'un rond-point,
 * à la manière de TomTom : la route se sépare, la voie à prendre est surlignée en bleu
 * et le panneau à suivre est mis en avant. Dessiné d'après les données de la route.
 */
class JunctionView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {

    var maneuver: Maneuver? = null
        set(value) { if (field !== value) { field = value; invalidate() } }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER }
    private val highlight = Color.parseColor("#3B8CFF")
    private val road = Color.parseColor("#4A5361")

    override fun onDraw(canvas: Canvas) {
        val m = maneuver ?: return
        if (m.kind == Maneuver.Kind.ROUNDABOUT) drawRoundabout(canvas, m) else drawJunction(canvas, m)
    }

    // ---------- Sortie et bifurcation, en perspective ----------

    private fun drawJunction(c: Canvas, m: Maneuver) {
        val w = width.toFloat()
        val h = height.toFloat()
        val sx = w / 390f
        val sy = h / 330f
        fun x(v: Float) = v * sx
        fun y(v: Float) = v * sy
        val horizon = y(130f)
        fill.color = Color.parseColor("#22344A"); c.drawRect(0f, 0f, w, horizon, fill)
        fill.color = Color.parseColor("#1F2D27"); c.drawRect(0f, horizon, w, h, fill)

        val left = m.kind == Maneuver.Kind.EXIT_LEFT || m.kind == Maneuver.Kind.FORK_LEFT
        val fork = m.kind == Maneuver.Kind.FORK_LEFT || m.kind == Maneuver.Kind.FORK_RIGHT || m.kind == Maneuver.Kind.FORK_STRAIGHT

        // Routes (dessinées pour une sortie à droite, puis retournées en miroir si besoin)
        c.save()
        if (left) c.scale(-1f, 1f, w / 2f, 0f)
        fill.color = road
        if (fork) {
            c.drawPath(poly(x(30f), h, x(360f), h, x(230f), y(220f), x(160f), y(220f)), fill)
            c.drawPath(poly(x(160f), y(220f), x(200f), y(220f), x(120f), horizon, x(95f), horizon), fill)
            c.drawPath(poly(x(190f), y(220f), x(230f), y(220f), x(300f), horizon, x(275f), horizon), fill)
            fill.color = Color.parseColor("#E7C94A")
            c.drawPath(poly(x(195f), y(222f), x(205f), y(222f), x(196f), y(240f)), fill)
            dashes(c, x(145f), h, x(183f), y(220f))
            dashes(c, x(245f), h, x(207f), y(220f))
            // Branche choisie : celle de droite (miroir pour la gauche)
            val path = Path().apply { moveTo(x(270f), h); quadTo(x(230f), y(240f), x(282f), y(150f)) }
            stroke.color = highlight; stroke.strokeWidth = x(30f)
            c.drawPath(path, stroke)
            fill.color = highlight
            c.drawPath(poly(x(262f), y(160f), x(298f), y(160f), x(286f), y(124f)), fill)
        } else {
            c.drawPath(poly(x(40f), h, x(300f), h, x(205f), horizon, x(180f), horizon), fill)
            c.drawPath(poly(x(250f), h, x(330f), h, x(385f), y(150f), x(368f), y(146f)), fill)
            dashes(c, x(125f), h, x(188f), horizon)
            dashes(c, x(210f), h, x(196f), horizon)
            stroke.color = Color.parseColor("#F5F6F7"); stroke.strokeWidth = x(3f)
            c.drawLine(x(40f), h, x(180f), horizon, stroke)
            c.drawLine(x(300f), h, x(205f), horizon, stroke)
            val path = Path().apply { moveTo(x(268f), h); quadTo(x(300f), y(250f), x(360f), y(168f)) }
            stroke.color = highlight; stroke.strokeWidth = x(30f)
            c.drawPath(path, stroke)
            fill.color = highlight
            c.drawPath(poly(x(345f), y(150f), x(385f), y(150f), x(368f), y(190f)), fill)
        }
        c.restore()

        // Portique et panneaux (jamais en miroir, pour garder le texte lisible)
        fill.color = Color.parseColor("#8A929C")
        c.drawRect(x(20f), y(16f), x(26f), horizon, fill)
        c.drawRect(x(364f), y(16f), x(370f), horizon, fill)
        c.drawRect(x(20f), y(20f), x(370f), y(28f), fill)
        val signColor = Color.parseColor(m.signColor ?: Maneuver.COLOR_MOTORWAY)
        val main = RectF(x(30f), y(34f), x(190f), y(110f))
        val other = RectF(x(200f), y(34f), x(360f), y(110f))
        val chosen = if (left) main else other
        val dimmed = if (left) other else main
        val otherTitle = if (fork) null else (m.currentRef ?: "")
        drawPanel(c, dimmed, signColor, otherTitle, if (fork) "" else "tout droit", null, false)
        drawPanel(c, chosen, signColor, m.targetRef, m.towardText ?: m.toStreets.firstOrNull { !Maneuver.ROAD_REF.matches(it) } ?: "", m.exitNumber, true)
    }

    private fun drawPanel(c: Canvas, r: RectF, color: Int, ref: String?, line: String, exit: String?, active: Boolean) {
        val alpha = if (active) 255 else 110
        fill.color = color; fill.alpha = alpha
        val radius = r.height() * 0.06f
        c.drawRoundRect(r, radius, radius, fill)
        stroke.color = Color.WHITE; stroke.alpha = alpha; stroke.strokeWidth = if (active) r.height() * 0.045f else r.height() * 0.03f
        c.drawRoundRect(r, radius, radius, stroke)
        stroke.alpha = 255
        fill.alpha = 255
        var top = r.top + r.height() * 0.1f
        val small = r.height() * 0.24f
        // Cartouche : numéro de sortie (blanc) puis référence de route (rouge, comme en France)
        var left = r.left + r.width() * 0.06f
        if (exit != null) {
            text.textSize = small * 0.8f
            val tw = text.measureText(exit) + small
            val box = RectF(left, top, left + tw, top + small * 1.1f)
            fill.color = Color.WHITE; fill.alpha = alpha
            c.drawRoundRect(box, small * 0.15f, small * 0.15f, fill)
            text.color = color; text.alpha = alpha
            c.drawText(exit, box.centerX(), box.bottom - small * 0.3f, text)
            left = box.right + small * 0.3f
        }
        if (!ref.isNullOrBlank()) {
            text.textSize = small * 0.8f
            val tw = text.measureText(ref) + small
            val box = RectF(left, top, left + tw, top + small * 1.1f)
            fill.color = Color.parseColor(if (ref.startsWith("D")) "#F2C318" else "#D93025"); fill.alpha = alpha
            c.drawRoundRect(box, small * 0.15f, small * 0.15f, fill)
            text.color = if (ref.startsWith("D")) Color.BLACK else Color.WHITE; text.alpha = alpha
            c.drawText(ref, box.centerX(), box.bottom - small * 0.3f, text)
        }
        fill.alpha = 255
        if (line.isNotBlank()) {
            text.color = Color.WHITE; text.alpha = alpha
            text.textSize = small * 0.95f
            val fitted = ellipsize(line, r.width() * 0.9f)
            c.drawText(fitted, r.centerX(), r.bottom - r.height() * 0.16f, text)
        }
        text.alpha = 255
    }

    // ---------- Rond-point, vu de dessus ----------

    private fun drawRoundabout(c: Canvas, m: Maneuver) {
        val w = width.toFloat()
        val h = height.toFloat()
        fill.color = Color.parseColor("#1F2D27"); c.drawRect(0f, 0f, w, h, fill)
        val cx = w / 2f
        val cy = h * 0.47f
        val r = h * 0.26f
        val roadW = h * 0.11f
        val exitNo = m.roundaboutExit.coerceAtLeast(1)
        val slots = maxOf(exitNo + 1, 4)
        fun angleOf(k: Int) = Math.toRadians(-90.0 + k * 360.0 / slots)
        fun at(a: Double, d: Float) = floatArrayOf(cx + d * cos(a).toFloat(), cy - d * sin(a).toFloat())

        // Branches : entrée en bas, sorties dans le sens inverse des aiguilles d'une montre
        stroke.color = road; stroke.strokeWidth = roadW
        for (k in 0 until slots) {
            val p = at(angleOf(k), r)
            val q = at(angleOf(k), maxOf(w, h))
            c.drawLine(p[0], p[1], q[0], q[1], stroke)
        }
        fill.color = road; c.drawCircle(cx, cy, r + roadW / 2f, fill)
        fill.color = Color.parseColor("#2F4A3A"); c.drawCircle(cx, cy, r - roadW / 2f, fill)

        // Trajet surligné
        val path = Path()
        val start = at(angleOf(0), maxOf(w, h))
        path.moveTo(start[0], start[1])
        val steps = 40
        val a0 = angleOf(0)
        val a1 = angleOf(exitNo)
        for (i in 0..steps) {
            val a = a0 + (a1 - a0) * i / steps
            val p = at(a, r)
            path.lineTo(p[0], p[1])
        }
        val end = at(a1, maxOf(w, h) * 0.45f)
        path.lineTo(end[0], end[1])
        stroke.color = highlight; stroke.strokeWidth = roadW * 0.55f
        c.drawPath(path, stroke)

        // Numéros des sorties
        for (k in 1 until slots) {
            val p = at(angleOf(k), r + roadW * 1.6f)
            val chosen = k == exitNo
            fill.color = if (chosen) Color.WHITE else Color.parseColor("#10161F")
            c.drawCircle(p[0], p[1], roadW * (if (chosen) 0.48f else 0.4f), fill)
            text.color = if (chosen) Color.parseColor("#10161F") else Color.WHITE
            text.textSize = roadW * 0.5f
            c.drawText(k.toString(), p[0], p[1] + text.textSize * 0.36f, text)
        }

        // Panneau de direction
        val label = m.towardText ?: m.toStreets.firstOrNull()
        if (label != null) {
            val ref = m.targetRef
            text.textSize = h * 0.075f
            val fitted = ellipsize(label, w * 0.42f)
            val tw = maxOf(text.measureText(fitted), if (ref != null) text.measureText(ref) else 0f) + h * 0.08f
            val box = RectF(w - tw - h * 0.05f, h * 0.05f, w - h * 0.05f, h * 0.05f + h * (if (ref != null) 0.27f else 0.16f))
            fill.color = Color.parseColor(m.signColor ?: "#FFFFFF")
            c.drawRoundRect(box, h * 0.02f, h * 0.02f, fill)
            stroke.color = if (m.signColor != null) Color.WHITE else Color.parseColor("#10161F"); stroke.strokeWidth = h * 0.008f
            c.drawRoundRect(box, h * 0.02f, h * 0.02f, stroke)
            var base = box.top + h * 0.1f
            if (ref != null) {
                text.color = Color.WHITE
                val rw = text.measureText(ref) + h * 0.04f
                val rb = RectF(box.left + h * 0.04f, box.top + h * 0.025f, box.left + h * 0.04f + rw, box.top + h * 0.115f)
                fill.color = Color.parseColor(if (ref.startsWith("D")) "#F2C318" else "#D93025")
                c.drawRoundRect(rb, h * 0.01f, h * 0.01f, fill)
                text.color = if (ref.startsWith("D")) Color.BLACK else Color.WHITE
                c.drawText(ref, rb.centerX(), rb.bottom - h * 0.022f, text)
                base = rb.bottom + h * 0.1f
            }
            text.color = if (m.signColor != null) Color.WHITE else Color.parseColor("#10161F")
            c.drawText(fitted, box.centerX(), base, text)
        }
    }

    // ---------- Outils ----------

    private fun poly(vararg pts: Float): Path = Path().apply {
        moveTo(pts[0], pts[1])
        var i = 2
        while (i < pts.size) { lineTo(pts[i], pts[i + 1]); i += 2 }
        close()
    }

    private fun dashes(c: Canvas, x0: Float, y0: Float, x1: Float, y1: Float) {
        stroke.color = Color.parseColor("#D9DDE3"); stroke.strokeWidth = width / 130f
        val n = 7
        for (i in 0 until n) {
            val a = i / n.toFloat()
            val b = (i + 0.55f) / n
            c.drawLine(x0 + (x1 - x0) * a, y0 + (y1 - y0) * a, x0 + (x1 - x0) * b, y0 + (y1 - y0) * b, stroke)
        }
    }

    private fun ellipsize(s: String, maxW: Float): String {
        if (text.measureText(s) <= maxW) return s
        var t = s
        while (t.length > 1 && text.measureText("$t…") > maxW) t = t.dropLast(1)
        return "$t…"
    }
}

/** Voies de circulation dessinées : les voies à prendre sur fond blanc, les autres estompées. */
class LanesView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {

    private var lanes: List<Lane> = emptyList()
    private var preferredArrow: String? = null
    var accent: Int = Color.parseColor(Maneuver.COLOR_MOTORWAY)
        set(value) { field = value; invalidate() }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }

    fun setLanes(list: List<Lane>, maneuverArrow: String?) {
        lanes = list
        preferredArrow = maneuverArrow
        requestLayout()
        invalidate()
    }

    private val tile get() = resources.displayMetrics.density * 46f
    private val gap get() = resources.displayMetrics.density * 6f

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val n = lanes.size.coerceAtLeast(1)
        val wanted = (n * tile + (n - 1) * gap + 2 * gap).toInt()
        val w = resolveSize(wanted, widthMeasureSpec)
        setMeasuredDimension(w, (tile * 0.9f + 2 * gap).toInt())
    }

    override fun onDraw(c: Canvas) {
        if (lanes.isEmpty()) return
        val d = resources.displayMetrics.density
        val n = lanes.size
        val t = minOf(tile, (width - (n + 1) * gap) / n)
        val total = n * t + (n - 1) * gap
        var x = (width - total) / 2f
        val top = gap
        val bottom = height - gap
        fill.color = Color.argb(70, 0, 0, 0)
        c.drawRoundRect(RectF(x - gap, 0f, x + total + gap, height.toFloat()), 10 * d, 10 * d, fill)
        for (lane in lanes) {
            val box = RectF(x, top, x + t, bottom)
            if (lane.valid) {
                fill.color = Color.WHITE
                c.drawRoundRect(box, 6 * d, 6 * d, fill)
            }
            val ind = if (lane.valid) lane.indications.firstOrNull { Lane.laneArrow(it) == preferredArrow } ?: lane.indications.firstOrNull() else lane.indications.firstOrNull()
            drawArrow(c, box, ind ?: "straight", if (lane.valid) accent else Color.argb(150, 255, 255, 255))
            x += t + gap
        }
    }

    private fun drawArrow(c: Canvas, box: RectF, indication: String, color: Int) {
        val angle = when (indication) {
            "slight right" -> 45.0
            "right" -> 0.0
            "sharp right" -> -40.0
            "slight left" -> 135.0
            "left" -> 180.0
            "sharp left" -> 220.0
            "uturn" -> 270.0
            else -> 90.0
        }
        val s = box.height()
        val cx = box.centerX()
        val baseY = box.bottom - s * 0.12f
        val midY = box.top + s * 0.5f
        val len = s * 0.3f
        stroke.color = color; stroke.strokeWidth = s * 0.12f
        fill.color = color
        val a = Math.toRadians(angle)
        val ex: Float
        val ey: Float
        val path = Path()
        if (indication == "uturn") {
            path.moveTo(cx + s * 0.1f, baseY); path.lineTo(cx + s * 0.1f, midY - s * 0.05f)
            path.quadTo(cx + s * 0.1f, box.top + s * 0.18f, cx - s * 0.06f, box.top + s * 0.18f)
            path.quadTo(cx - s * 0.2f, box.top + s * 0.18f, cx - s * 0.2f, midY)
            ex = cx - s * 0.2f; ey = midY + s * 0.08f
            c.drawPath(path, stroke)
            arrowHead(c, ex, ey, -90.0, s)
            return
        }
        if (angle == 90.0) {
            path.moveTo(cx, baseY); path.lineTo(cx, box.top + s * 0.25f)
            c.drawPath(path, stroke)
            arrowHead(c, cx, box.top + s * 0.14f, 90.0, s)
            return
        }
        ex = cx + (len * cos(a)).toFloat()
        ey = midY - (len * sin(a)).toFloat()
        path.moveTo(cx, baseY); path.lineTo(cx, midY); path.lineTo(ex, ey)
        c.drawPath(path, stroke)
        arrowHead(c, cx + ((len + s * 0.1f) * cos(a)).toFloat(), midY - ((len + s * 0.1f) * sin(a)).toFloat(), angle, s)
    }

    private fun arrowHead(c: Canvas, tipX: Float, tipY: Float, angle: Double, s: Float) {
        val a = Math.toRadians(angle)
        val back = s * 0.2f
        val half = s * 0.14f
        val bx = tipX - (back * cos(a)).toFloat()
        val by = tipY + (back * sin(a)).toFloat()
        val px = (half * sin(a)).toFloat()
        val py = (half * cos(a)).toFloat()
        val p = Path().apply {
            moveTo(tipX, tipY); lineTo(bx + px, by + py); lineTo(bx - px, by - py); close()
        }
        c.drawPath(p, fill)
    }
}

/** Barre qui se vide à l'approche de la manœuvre. */
class ShrinkBar @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    var fraction = 1f
        set(value) { val v = value.coerceIn(0f, 1f); if (v != field) { field = v; invalidate() } }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(c: Canvas) {
        val r = height / 2f
        paint.color = Color.argb(64, 255, 255, 255)
        c.drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), r, r, paint)
        paint.color = Color.WHITE
        if (fraction > 0f) c.drawRoundRect(RectF(0f, 0f, width * fraction, height.toFloat()), r, r, paint)
    }
}

/** Vue d'approche d'un radar fixe : la route, le radar sur le bord et le panneau de limitation. */
class RadarView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {

    var limit: Int? = null
        set(value) { if (field != value) { field = value; invalidate() } }
    var redLight = false
        set(value) { if (field != value) { field = value; invalidate() } }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val horizon = h * 0.4f
        fill.color = Color.parseColor("#22344A"); c.drawRect(0f, 0f, w, horizon, fill)
        fill.color = Color.parseColor("#1F2D27"); c.drawRect(0f, horizon, w, h, fill)
        val cx = w / 2f
        val road = Path().apply {
            moveTo(cx - w * 0.4f, h); lineTo(cx + w * 0.4f, h); lineTo(cx + w * 0.04f, horizon); lineTo(cx - w * 0.04f, horizon); close()
        }
        fill.color = Color.parseColor("#4A5361"); c.drawPath(road, fill)
        stroke.color = Color.parseColor("#F5F6F7"); stroke.strokeWidth = w / 130f
        c.drawLine(cx - w * 0.4f, h, cx - w * 0.04f, horizon, stroke)
        c.drawLine(cx + w * 0.4f, h, cx + w * 0.04f, horizon, stroke)
        stroke.color = Color.parseColor("#D9DDE3")
        for (i in 0 until 6) {
            val a = i / 6f
            val b = (i + 0.5f) / 6f
            c.drawLine(cx, h + (horizon - h) * a, cx, h + (horizon - h) * b, stroke)
        }
        // Radar sur son poteau, bord droit
        val px = cx + w * 0.2f
        fill.color = Color.parseColor("#8A929C")
        c.drawRect(px - w * 0.008f, horizon - h * 0.05f, px + w * 0.008f, horizon + h * 0.28f, fill)
        val box = RectF(px - w * 0.05f, horizon - h * 0.2f, px + w * 0.05f, horizon)
        fill.color = Color.parseColor("#E9ECEF"); c.drawRoundRect(box, h * 0.02f, h * 0.02f, fill)
        fill.color = Color.parseColor("#10161F")
        if (redLight) {
            fill.color = Color.parseColor("#D93025")
            c.drawCircle(box.centerX(), box.centerY(), box.height() * 0.28f, fill)
        } else {
            c.drawCircle(box.left + box.width() * 0.32f, box.centerY(), box.height() * 0.22f, fill)
            c.drawRect(box.left + box.width() * 0.6f, box.top + box.height() * 0.3f, box.left + box.width() * 0.85f, box.bottom - box.height() * 0.25f, fill)
        }
        // Panneau de limitation
        val l = limit ?: return
        val r = h * 0.2f
        val sx = w - r * 1.5f
        val sy = r * 1.4f
        fill.color = Color.WHITE; c.drawCircle(sx, sy, r, fill)
        stroke.color = Color.parseColor("#D93025"); stroke.strokeWidth = r * 0.22f
        c.drawCircle(sx, sy, r - stroke.strokeWidth / 2f, stroke)
        text.color = Color.parseColor("#10161F")
        text.textSize = r * (if (l >= 100) 0.75f else 0.9f)
        c.drawText(l.toString(), sx, sy + text.textSize * 0.36f, text)
    }
}
