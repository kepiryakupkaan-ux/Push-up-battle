package com.example.pushup

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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

    private var points: Map<Int, OverlayPoint> = emptyMap()
    private var goodPosture: Boolean = true

    private val boneOkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#3DDC84")
        strokeWidth = 7f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val boneWarnPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF4D5E")
        strokeWidth = 7f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val jointGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#55FF5A2E")
        style = Paint.Style.FILL
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
        points = newPoints
        goodPosture = postureOk
        invalidate()
    }

    fun clear() {
        points = emptyMap()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (points.isEmpty()) return
        val w = width.toFloat()
        val h = height.toFloat()
        val linePaint = if (goodPosture) boneOkPaint else boneWarnPaint

        for ((a, b) in bones) {
            val pa = points[a]
            val pb = points[b]
            if (pa != null && pb != null && pa.visible && pb.visible) {
                canvas.drawLine(pa.x * w, pa.y * h, pb.x * w, pb.y * h, linePaint)
            }
        }
        for (p in points.values) {
            if (!p.visible) continue
            val cx = p.x * w
            val cy = p.y * h
            canvas.drawCircle(cx, cy, 15f, jointGlowPaint)
            canvas.drawCircle(cx, cy, 6f, jointPaint)
        }
    }
}
