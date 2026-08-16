package com.example.pushup

import android.graphics.PointF
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.PoseLandmark
import com.google.mlkit.vision.pose.accurate.AccuratePoseDetectorOptions
import org.webrtc.VideoFrame
import org.webrtc.VideoSink

/** Overlay çiziminde kullanılacak, ekrana göre normalize edilmiş (0..1) tek bir vücut noktası. */
data class OverlayPoint(val x: Float, val y: Float, val visible: Boolean)

/**
 * Sits on the LOCAL video track (your own camera), not the remote one - we count
 * *your* push-ups from *your* camera. Runs ML Kit pose detection at a throttled
 * rate (analysis is expensive; we don't need 30fps for counting reps).
 *
 * v2 değişiklikleri:
 *  - Artık sadece rep sayısı değil, tüm vücut noktalarını (omuz/dirsek/bilek/kalça/diz/ayak
 *    bileği) normalize koordinatlarla dışarı veriyor -> ekranda iskelet çizmek için.
 *  - Sayım artık sadece kol açısına değil, gövdenin yatay (plank) olmasına ve omzun
 *    gerçekten inip çıkmasına da bakıyor (bkz. RepCounter).
 */
class PoseAnalyzer(
    private val onRepCounted: (totalReps: Int) -> Unit,
    private val onLandmarks: (points: Map<Int, OverlayPoint>, postureOk: Boolean) -> Unit = { _, _ -> },
    private val onNoBodyDetected: () -> Unit = {}
) : VideoSink {

    /** Sistem sadece ön (selfie) kamerayla çalışır, önizleme her zaman aynalanır. */
    private val mirrored: Boolean = true

    private val detector = PoseDetection.getClient(
        AccuratePoseDetectorOptions.Builder()
            .setDetectorMode(AccuratePoseDetectorOptions.STREAM_MODE)
            .build()
    )

    private val repCounter = RepCounter()
    private var busy = false
    private var lastAnalysisMs = 0L
    private val minIntervalMs = 90L // ~11 analiz/sn tavanı

    // Noktaların titremesini azaltmak için basit üstel yumuşatma (EMA).
    private val smoothed = HashMap<Int, PointF>()
    private val smoothingFactor = 0.45f // 0 = hep eski değer (donuk), 1 = hep yeni değer (titrek)

    private val trackedLandmarks = intArrayOf(
        PoseLandmark.NOSE,
        PoseLandmark.LEFT_SHOULDER, PoseLandmark.RIGHT_SHOULDER,
        PoseLandmark.LEFT_ELBOW, PoseLandmark.RIGHT_ELBOW,
        PoseLandmark.LEFT_WRIST, PoseLandmark.RIGHT_WRIST,
        PoseLandmark.LEFT_HIP, PoseLandmark.RIGHT_HIP,
        PoseLandmark.LEFT_KNEE, PoseLandmark.RIGHT_KNEE,
        PoseLandmark.LEFT_ANKLE, PoseLandmark.RIGHT_ANKLE
    )

    fun reset() = repCounter.reset()
    fun currentReps() = repCounter.reps

    override fun onFrame(frame: VideoFrame) {
        val now = System.currentTimeMillis()
        if (busy || now - lastAnalysisMs < minIntervalMs) return
        lastAnalysisMs = now
        busy = true

        frame.retain()
        try {
            val buffer = frame.buffer.toI420()
            if (buffer == null) {
                busy = false
                frame.release()
                return
            }
            val width = buffer.width
            val height = buffer.height
            val nv21 = i420ToNv21(buffer)
            buffer.release()
            val rotation = frame.rotation

            val image = InputImage.fromByteArray(nv21, width, height, rotation, InputImage.IMAGE_FORMAT_NV21)
            detector.process(image)
                .addOnSuccessListener { pose -> handlePose(pose, width, height, rotation) }
                .addOnFailureListener { }
                .addOnCompleteListener {
                    busy = false
                    frame.release()
                }
        } catch (e: Exception) {
            busy = false
            frame.release()
        }
    }

    private fun handlePose(pose: Pose, imgW: Int, imgH: Int, rotation: Int) {
        fun ok(l: PoseLandmark?) = l != null && l.inFrameLikelihood > 0.5f

        val leftShoulder = pose.getPoseLandmark(PoseLandmark.LEFT_SHOULDER)
        val rightShoulder = pose.getPoseLandmark(PoseLandmark.RIGHT_SHOULDER)
        val leftElbow = pose.getPoseLandmark(PoseLandmark.LEFT_ELBOW)
        val rightElbow = pose.getPoseLandmark(PoseLandmark.RIGHT_ELBOW)
        val leftWrist = pose.getPoseLandmark(PoseLandmark.LEFT_WRIST)
        val rightWrist = pose.getPoseLandmark(PoseLandmark.RIGHT_WRIST)
        val leftHip = pose.getPoseLandmark(PoseLandmark.LEFT_HIP)
        val rightHip = pose.getPoseLandmark(PoseLandmark.RIGHT_HIP)

        // Gövde eğimi: omuz-kalça hattının yataydan sapması (plank kontrolü).
        val shoulderForTilt = if (ok(leftShoulder)) leftShoulder else rightShoulder
        val hipForTilt = if (ok(leftHip)) leftHip else rightHip
        val torsoTilt = if (shoulderForTilt != null && hipForTilt != null && ok(shoulderForTilt) && ok(hipForTilt)) {
            RepCounter.tiltFromHorizontalDegrees(
                shoulderForTilt.position.x, shoulderForTilt.position.y,
                hipForTilt.position.x, hipForTilt.position.y
            )
        } else null
        val postureOk = torsoTilt == null || torsoTilt <= 55.0

        // Overlay için tüm noktaları normalize edip yumuşatarak yayınla.
        val overlay = HashMap<Int, OverlayPoint>()
        for (type in trackedLandmarks) {
            val lm = pose.getPoseLandmark(type) ?: continue
            val normalized = rotatedMirroredNormalized(lm.position.x, lm.position.y, imgW, imgH, rotation)
            val smoothedPoint = smooth(type, normalized.x, normalized.y)
            overlay[type] = OverlayPoint(smoothedPoint.x, smoothedPoint.y, lm.inFrameLikelihood > 0.4f)
        }
        if (overlay.isNotEmpty()) onLandmarks(overlay, postureOk)

        val leftArmOk = ok(leftShoulder) && ok(leftElbow) && ok(leftWrist)
        val rightArmOk = ok(rightShoulder) && ok(rightElbow) && ok(rightWrist)

        if ((!leftArmOk && !rightArmOk) || (!ok(leftHip) && !ok(rightHip))) {
            onNoBodyDetected()
            return
        }

        val angles = mutableListOf<Double>()
        if (leftArmOk) angles += RepCounter.angleDegrees(
            leftShoulder!!.position.x, leftShoulder.position.y,
            leftElbow!!.position.x, leftElbow.position.y,
            leftWrist!!.position.x, leftWrist.position.y
        )
        if (rightArmOk) angles += RepCounter.angleDegrees(
            rightShoulder!!.position.x, rightShoulder.position.y,
            rightElbow!!.position.x, rightElbow.position.y,
            rightWrist!!.position.x, rightWrist.position.y
        )
        val elbowAngle = angles.average()

        // Omuzun dikey konumu (normalize, iki omuz varsa ortalama) - gerçek "inme" kontrolü için.
        val shoulderYs = mutableListOf<Float>()
        if (ok(leftShoulder)) shoulderYs += leftShoulder!!.position.y / imgH
        if (ok(rightShoulder)) shoulderYs += rightShoulder!!.position.y / imgH
        val shoulderY = if (shoulderYs.isNotEmpty()) shoulderYs.average().toFloat() else 0f

        val repJustCompleted = repCounter.onReading(elbowAngle, shoulderY, torsoTilt ?: 0.0)
        if (repJustCompleted) {
            onRepCounted(repCounter.reps)
        }
    }

    private fun smooth(type: Int, x: Float, y: Float): PointF {
        val prev = smoothed[type]
        val result = if (prev == null) {
            PointF(x, y)
        } else {
            PointF(
                prev.x + (x - prev.x) * smoothingFactor,
                prev.y + (y - prev.y) * smoothingFactor
            )
        }
        smoothed[type] = result
        return result
    }

    /**
     * ML Kit landmark koordinatlarını (ham buffer piksel) 0..1 arası, ekranda gördüğün
     * (aynalanmış + döndürülmüş) önizlemeyle hizalı hale getirir.
     *
     * DÜZELTME (2. tur): Bu analiz SADECE ön kamerada (selfie) çalışıyor - önizleme
     * aynalanmış oluyor (bkz. WebRtcClient.setMirror(true)). Döndürme ve aynalama işlemleri
     * matematiksel olarak yer değiştirilebilir DEĞİL (mirror sonra rotate ≠ rotate sonra
     * mirror). Bu fonksiyon önce döndürüp sonra aynalıyor - bu sıralama aynalanmamış (arka
     * kamera) bir görüntü için doğruydu, ama ön kameranın aynalı görüntüsünde 90/270
     * durumlarının birbirinin yerine geçmesine yol açıyordu (iskelet dikey değil yatık
     * görünüyordu, sanki kamera sola/sağa yatmış gibi). 90 ve 270 dalları burada bilerek
     * ters çevrildi - ön kamera + "rotate sonra mirror" sıralaması için doğru eşleme budur.
     */
    private fun rotatedMirroredNormalized(x: Float, y: Float, imgW: Int, imgH: Int, rotationDeg: Int): PointF {
        val nx: Float
        val ny: Float
        when (rotationDeg) {
            90 -> { nx = y / imgH; ny = 1f - x / imgW }
            180 -> { nx = 1f - x / imgW; ny = 1f - y / imgH }
            270 -> { nx = 1f - y / imgH; ny = x / imgW }
            else -> { nx = x / imgW; ny = y / imgH }
        }
        // Ön kamera önizlemesi selfie görünümü için yatayda aynalanıyor (bkz. setMirror(true)
        // in WebRtcClient), overlay'in de aynı şekilde aynalanması gerekiyor.
        return PointF(1f - nx, ny)
    }

    private fun i420ToNv21(buffer: VideoFrame.I420Buffer): ByteArray {
        val width = buffer.width
        val height = buffer.height
        val ySize = width * height
        val uvSize = width * height / 4

        val nv21 = ByteArray(ySize + uvSize * 2)

        val yPlane = buffer.dataY
        val uPlane = buffer.dataU
        val vPlane = buffer.dataV

        var pos = 0
        for (row in 0 until height) {
            yPlane.position(row * buffer.strideY)
            yPlane.get(nv21, pos, width)
            pos += width
        }

        val chromaHeight = height / 2
        val chromaWidth = width / 2
        for (row in 0 until chromaHeight) {
            for (col in 0 until chromaWidth) {
                val vIndex = row * buffer.strideV + col
                val uIndex = row * buffer.strideU + col
                nv21[pos++] = vPlane.get(vIndex)
                nv21[pos++] = uPlane.get(uIndex)
            }
        }
        return nv21
    }

    fun close() {
        detector.close()
        smoothed.clear()
    }
}
