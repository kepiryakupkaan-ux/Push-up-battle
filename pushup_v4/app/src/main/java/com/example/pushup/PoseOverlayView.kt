package com.example.pushup

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import com.google.mlkit.vision.pose.PoseLandmark

/**
 * Yerel kameranın üstüne, PoseAnalyzer'dan gelen vücut noktalarını (omuz/dirsek/bilek/
 * kalça/diz/ayak bileği) ve aralarındaki iskelet çizgilerini çizen basit bir overlay View'ı.
 *
 * Duruş uygun değilse (plank pozisyonunda değilsen) çizgiler kırmızıya döner, böylece
 * "neden saymıyor" sorusuna görsel bir ipucu vermiş oluyoruz.
 */
class PoseOverlayView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private var targetPoints: Map<Int, OverlayPoint> = emptyMap()
    private var points: MutableMap<Int, OverlayPoint> = mutableMapOf()
    private var goodPosture: Boolean = true

    private val boneOkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFB74D")
        strokeWidth = 5.5f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val boneWarnPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF5A67")
        strokeWidth = 5.5f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val jointGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#55FFB74D")
        style = Paint.Style.FILL
    }
    private val activeRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFFFD180")
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
    }
    private val jointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    private val bones = listOf(
        PoseLandmark.LEFT_SHOULDER to PoseLandmark.RIGHT_SHOULDER,
        PoseLandmark.LEFT_SHOULDER to PoseLandmark.LEFT_ELBOW,
        PoseLandmark.LEFT_ELBOW to PoseLandmark.LEFT_WRIST,
        PoseLandmark.RIGHT_SHOULDER to PoseLandmark.RIGHT_ELBOW,
        PoseLandmark.RIGHT_ELBOW to PoseLandmark.RIGHT_WRIST,
        PoseLandmark.LEFT_SHOULDER to PoseLandmark.LEFT_HIP,
        PoseLandmark.RIGHT_SHOULDER to PoseLandmark.RIGHT_HIP,
        PoseLandmark.LEFT_HIP to PoseLandmark.RIGHT_HIP,
        PoseLandmark.LEFT_HIP to PoseLandmark.LEFT_KNEE,
        PoseLandmark.LEFT_KNEE to PoseLandmark.LEFT_ANKLE,
        PoseLandmark.RIGHT_HIP to PoseLandmark.RIGHT_KNEE,
        PoseLandmark.RIGHT_KNEE to PoseLandmark.RIGHT_ANKLE
    )

    /** Ana thread'den çağrılmalı. */
    fun updatePose(newPoints: Map<Int, OverlayPoint>, postureOk: Boolean) {
        targetPoints = newPoints
        goodPosture = postureOk
        if (points.isEmpty()) points.putAll(newPoints)
        postInvalidateOnAnimation()
    }

    fun clear() {
        targetPoints = emptyMap()
        points.clear()
        invalidate()
    }

    private fun interpolatePoints() {
        val alpha = 0.28f
        val keys = (points.keys + targetPoints.keys).toSet()
        for (key in keys) {
            val target = targetPoints[key] ?: continue
            val current = points[key]
            points[key] = if (current == null) target else OverlayPoint(
                current.x + (target.x - current.x) * alpha,
                current.y + (target.y - current.y) * alpha,
                target.visible
            )
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (targetPoints.isEmpty() && points.isEmpty()) return
        interpolatePoints()
        val w = width.toFloat()
        val h = height.toFloat()
        val linePaint = if (goodPosture) boneOkPaint else boneWarnPaint
        val pulse = 1f + 0.10f * ((kotlin.math.sin(SystemClock.uptimeMillis() / 180.0) + 1.0) * 0.5).toFloat()
        val jointGlowRadius = 12f * pulse

        // Hafif gölge: skeleton debug çizgisi yerine kamera üstünde bir HUD/athlete
        // visualization hissi verir.
        val shadow = Paint(linePaint).apply {
            color = Color.argb(90, 0, 0, 0)
            strokeWidth = linePaint.strokeWidth + 5f
        }
        for ((a, b) in bones) {
            val pa = points[a]
            val pb = points[b]
            if (pa != null && pb != null && pa.visible && pb.visible) {
                canvas.drawLine(pa.x * w, pa.y * h, pb.x * w, pb.y * h, shadow)
                canvas.drawLine(pa.x * w, pa.y * h, pb.x * w, pb.y * h, linePaint)
            }
        }
        for (p in points.values) {
            if (!p.visible) continue
            val cx = p.x * w
            val cy = p.y * h
            canvas.drawCircle(cx, cy, jointGlowRadius, jointGlowPaint)
            canvas.drawCircle(cx, cy, 6.5f, jointPaint)
            canvas.drawCircle(cx, cy, 9.5f, activeRingPaint)
        }
        postInvalidateOnAnimation()
    }
}
