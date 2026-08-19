package com.example.pushup

import android.graphics.PointF
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.PoseLandmark
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
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
    private val onRepFeedback: (RepFeedback) -> Unit = {},
    private val onLandmarks: (points: Map<Int, OverlayPoint>, postureOk: Boolean) -> Unit = { _, _ -> },
    private val onNoBodyDetected: () -> Unit = {}
) : VideoSink {

    /** Sistem sadece ön (selfie) kamerayla çalışır, önizleme her zaman aynalanır. */
    private val mirrored: Boolean = true

    private val detector = PoseDetection.getClient(
        PoseDetectorOptions.Builder()
            .setDetectorMode(PoseDetectorOptions.STREAM_MODE)
            .build()
    )

    private val repCounter = RepCounter()
    private var busy = false
    private var lastAnalysisMs = 0L
    private val minIntervalMs = 55L // ~18 analiz/sn tavanı (hızlı modelle artık rahat kaldırıyor)

    // Noktaların titremesini azaltmak için basit üstel yumuşatma (EMA).
    private val smoothed = HashMap<Int, PointF>()
    private val smoothingFactor = 0.6f // 0 = hep eski değer (donuk), 1 = hep yeni değer (titrek)

    private val trackedLandmarks = intArrayOf(
        PoseLandmark.NOSE,
        PoseLandmark.LEFT_SHOULDER, PoseLandmark.RIGHT_SHOULDER,
        PoseLandmark.LEFT_ELBOW, PoseLandmark.RIGHT_ELBOW,
        PoseLandmark.LEFT_WRIST, PoseLandmark.RIGHT_WRIST,
        PoseLandmark.LEFT_HIP, PoseLandmark.RIGHT_HIP,
        PoseLandmark.LEFT_KNEE, PoseLandmark.RIGHT_KNEE,
        PoseLandmark.LEFT_ANKLE, PoseLandmark.RIGHT_ANKLE
    )

    // ML Kit'e InputImage rotation verildiği için detector sonucu zaten döndürülmüş
    // görüntü koordinat sistemindedir. WebRTC renderer ise aynı rotation metadata'sını
    // kendi içinde uygular. Bu nedenle landmark'ı tekrar 90/180/270 derece döndürmek
    // overlay'i videodan kaydırır. Burada yalnızca ön kamera aynalamasını uyguluyoruz.
    private val outputMirror = true

    private data class Calibration(val rotationDeg: Int, val mirror: Boolean)

    // Debug ekranlarında geriye dönük uyumluluk için tutuluyor; artık manuel rotasyon
    // kilitlemiyoruz. ML Kit rotation'ı zaten uyguladığı için rotation=0 kabul edilir.
    var lastCalibrationDebug: String = "ML Kit rotation + selfie mirror"
        private set

    fun reset() {
        repCounter.reset()
        smoothed.clear()
    }
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
        // InputImage'e rotation verildi. Pose landmark koordinatları artık döndürülmüş
        // output space'tedir; sadece renderer'ın selfie mirror'ını eşleştiriyoruz.
        val effRotation = rotation
        val effMirror = outputMirror
        fun ok(l: PoseLandmark?) = l != null && l.inFrameLikelihood > 0.5f

        val leftShoulder = pose.getPoseLandmark(PoseLandmark.LEFT_SHOULDER)
        val rightShoulder = pose.getPoseLandmark(PoseLandmark.RIGHT_SHOULDER)
        val leftElbow = pose.getPoseLandmark(PoseLandmark.LEFT_ELBOW)
        val rightElbow = pose.getPoseLandmark(PoseLandmark.RIGHT_ELBOW)
        val leftWrist = pose.getPoseLandmark(PoseLandmark.LEFT_WRIST)
        val rightWrist = pose.getPoseLandmark(PoseLandmark.RIGHT_WRIST)
        val leftHip = pose.getPoseLandmark(PoseLandmark.LEFT_HIP)
        val rightHip = pose.getPoseLandmark(PoseLandmark.RIGHT_HIP)
        val leftKnee = pose.getPoseLandmark(PoseLandmark.LEFT_KNEE)
        val rightKnee = pose.getPoseLandmark(PoseLandmark.RIGHT_KNEE)

        // Form kontrolü: kalça VE omuz EKLEMLERİNDEKİ açılar - "gövdenin ekrana göre yatay
        // olması" (tiltFromHorizontal) DEĞİL. Yaygın açık kaynak MediaPipe şınav sayıcılarının
        // kullandığı yöntemle birebir aynı: "elbow>160 UP, elbow<90 DOWN, hip>160, shoulder>40".
        // DÜZELTME (asıl "push-up algılanmıyor" sebebi): eski yöntem kameranın kullanıcıyı
        // YANDAN çektiği klasik plank videoları için doğruydu. Bizim kamera kullanıcıyı ÖNDEN
        // görüyor - bu açıda vücut ekranda hiçbir zaman gerçekten "yatay" durmuyor, bu yüzden
        // doğru yapılan şınavlar bile reddediliyordu. Kalça/omuz eklem açıları ise elbow açısı
        // gibi RİJİT açılar (3 nokta arası) - kameranın nereden baktığından etkilenmez, ham
        // (döndürülmemiş) koordinatlarla hesaplanması yeterli.
        val hipForAngle = if (ok(leftHip)) leftHip else if (ok(rightHip)) rightHip else null
        val shoulderForHipAngle = if (ok(leftShoulder)) leftShoulder else if (ok(rightShoulder)) rightShoulder else null
        val elbowForShoulderAngle = if (ok(leftElbow)) leftElbow else if (ok(rightElbow)) rightElbow else null
        val kneeForHipAngle = if (ok(leftKnee)) leftKnee else if (ok(rightKnee)) rightKnee else null
        val hipAngle = if (shoulderForHipAngle != null && hipForAngle != null && kneeForHipAngle != null) {
            RepCounter.angleDegrees(
                shoulderForHipAngle.position.x, shoulderForHipAngle.position.y,
                hipForAngle.position.x, hipForAngle.position.y,
                kneeForHipAngle.position.x, kneeForHipAngle.position.y
            )
        } else 180.0 // Diz görünmüyorsa (ör. kadraj dışı), form kontrolünü engelleme.
        // Omuz eklem açısı: dirsek-omuz-kalça arası (referans koddaki "shoulder" açısı).
        val shoulderAngle = if (elbowForShoulderAngle != null && shoulderForHipAngle != null && hipForAngle != null) {
            RepCounter.angleDegrees(
                elbowForShoulderAngle.position.x, elbowForShoulderAngle.position.y,
                shoulderForHipAngle.position.x, shoulderForHipAngle.position.y,
                hipForAngle.position.x, hipForAngle.position.y
            )
        } else 180.0
        val postureOk = hipAngle >= 160.0 && shoulderAngle >= 40.0
        val overlay = HashMap<Int, OverlayPoint>()
        for (type in trackedLandmarks) {
            val lm = pose.getPoseLandmark(type) ?: continue
            val normalized = rawTransform(lm.position.x, lm.position.y, imgW, imgH, effRotation, effMirror)
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

        // Dirsek açısı üç nokta arasındaki açı olduğu için döndürme/aynalamadan etkilenmiyor
        // (rijit dönüşümler açıyı korur) - burada ham koordinat kullanmak sorun değil.
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

        // Omuzun dikey konumu (normalize, iki omuz varsa ortalama) - gerçek "inme" kontrolü
        // için. DÜZELTME: aynı sebeple (yukarıdaki tilt yorumuna bkz.) artık ham buffer y'si
        // değil, ekranda gerçekten göründüğü dikey konum kullanılıyor.
        val shoulderYs = mutableListOf<Float>()
        if (ok(leftShoulder)) shoulderYs += rawTransform(leftShoulder!!.position.x, leftShoulder.position.y, imgW, imgH, effRotation, effMirror).y
        if (ok(rightShoulder)) shoulderYs += rawTransform(rightShoulder!!.position.x, rightShoulder.position.y, imgW, imgH, effRotation, effMirror).y
        val shoulderY = if (shoulderYs.isNotEmpty()) shoulderYs.average().toFloat() else 0f

        val confidenceSamples = listOfNotNull(
            leftShoulder?.inFrameLikelihood, rightShoulder?.inFrameLikelihood,
            leftElbow?.inFrameLikelihood, rightElbow?.inFrameLikelihood,
            leftWrist?.inFrameLikelihood, rightWrist?.inFrameLikelihood,
            leftHip?.inFrameLikelihood, rightHip?.inFrameLikelihood
        )
        val poseConfidence = if (confidenceSamples.isEmpty()) 0f else confidenceSamples.average().toFloat()
        val repJustCompleted = repCounter.onReading(
            elbowAngle, shoulderY, hipAngle, shoulderAngle, poseConfidence
        )
        if (repJustCompleted) {
            onRepCounterCompleted(repCounter.reps)
        }
    }

    private fun onRepCounterCompleted(total: Int) {
        onRepCounted(total)
        repCounter.lastFeedback?.let(onRepFeedback)
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
     * Tek bir ham (x,y) noktasını verilen (rotationDeg, mirror) kombinasyonuna göre 0..1
     * normalize ekran koordinatına çevirir. Kalibrasyon bu fonksiyonu 8 farklı kombinasyonla
     * deneyip anatomik olarak doğru sonucu verenle kilitlendiği için, burada "doğru sıra/yön
     * budur" diye ayrıca bir varsayımda bulunmuyoruz.
     */
    private fun rawTransform(x: Float, y: Float, imgW: Int, imgH: Int, rotationDeg: Int, mirror: Boolean): PointF {
        // ML Kit InputImage.fromByteArray(..., rotation, ...) rotation'ı detector'a
        // verdiğimiz için PoseLandmark koordinatları rotated image space'tedir.
        // Rotation'ı burada tekrar uygulamak yanlış bir ikinci dönüşüm oluşturuyordu.
        // 90/270 derece inputlarda width/height yer değiştirir.
        val rotated = rotationDeg == 90 || rotationDeg == 270
        val outW = if (rotated) imgH.toFloat() else imgW.toFloat()
        val outH = if (rotated) imgW.toFloat() else imgH.toFloat()
        var u = (x / outW).coerceIn(0f, 1f)
        val v = (y / outH).coerceIn(0f, 1f)
        if (mirror) u = 1f - u
        return PointF(u, v)
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
