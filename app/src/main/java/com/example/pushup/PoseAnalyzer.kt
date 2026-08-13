package com.example.pushup

import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.PoseLandmark
import com.google.mlkit.vision.pose.accurate.AccuratePoseDetectorOptions
import org.webrtc.VideoFrame
import org.webrtc.VideoSink

/**
 * Sits on the LOCAL video track (your own camera), not the remote one - we count
 * *your* push-ups from *your* camera. Runs ML Kit pose detection at a throttled
 * rate (analysis is expensive; we don't need 30fps for counting reps).
 */
class PoseAnalyzer(
    private val onRepCounted: (totalReps: Int) -> Unit,
    private val onNoBodyDetected: () -> Unit = {}
) : VideoSink {

    private val detector = PoseDetection.getClient(
        AccuratePoseDetectorOptions.Builder()
            .setDetectorMode(AccuratePoseDetectorOptions.STREAM_MODE)
            .build()
    )

    private val repCounter = RepCounter()
    private var busy = false
    private var lastAnalysisMs = 0L
    private val minIntervalMs = 120L // ~8 analyses/sec cap

    fun reset() = repCounter.reset()
    fun currentReps() = repCounter.reps

    override fun onFrame(frame: VideoFrame) {
        val now = System.currentTimeMillis()
        if (busy || now - lastAnalysisMs < minIntervalMs) return
        lastAnalysisMs = now
        busy = true

        frame.retain()
        try {
            val bitmap = videoFrameToNv21InputImage(frame)
            if (bitmap == null) {
                busy = false
                frame.release()
                return
            }
            detector.process(bitmap)
                .addOnSuccessListener { pose -> handlePose(pose) }
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

    private fun handlePose(pose: Pose) {
        // Prefer the right arm; fall back to left if right isn't confidently visible.
        val shoulder = pose.getPoseLandmark(PoseLandmark.RIGHT_SHOULDER)
            ?: pose.getPoseLandmark(PoseLandmark.LEFT_SHOULDER)
        val elbow = pose.getPoseLandmark(PoseLandmark.RIGHT_ELBOW)
            ?: pose.getPoseLandmark(PoseLandmark.LEFT_ELBOW)
        val wrist = pose.getPoseLandmark(PoseLandmark.RIGHT_WRIST)
            ?: pose.getPoseLandmark(PoseLandmark.LEFT_WRIST)

        if (shoulder == null || elbow == null || wrist == null ||
            shoulder.inFrameLikelihood < 0.5f || elbow.inFrameLikelihood < 0.5f || wrist.inFrameLikelihood < 0.5f
        ) {
            onNoBodyDetected()
            return
        }

        val angle = RepCounter.angleDegrees(
            shoulder.position.x, shoulder.position.y,
            elbow.position.x, elbow.position.y,
            wrist.position.x, wrist.position.y
        )

        val repJustCompleted = repCounter.onAngle(angle)
        if (repJustCompleted) {
            onRepCounted(repCounter.reps)
        }
    }

    /** Converts a WebRTC I420 video frame into an ML Kit InputImage via NV21. */
    private fun videoFrameToNv21InputImage(frame: VideoFrame): InputImage? {
        val buffer = frame.buffer.toI420() ?: return null
        try {
            val width = buffer.width
            val height = buffer.height

            val nv21 = i420ToNv21(buffer)

            return InputImage.fromByteArray(
                nv21, width, height,
                frame.rotation,
                InputImage.IMAGE_FORMAT_NV21
            )
        } finally {
            buffer.release()
        }
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
    }
}
