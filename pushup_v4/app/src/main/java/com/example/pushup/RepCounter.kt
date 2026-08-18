package com.example.pushup

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Push-up state machine.
 *
 * The counter intentionally separates:
 *  - smoothed pose readings
 *  - movement phase (UP -> DOWN -> UP)
 *  - minimum hold time for each phase
 *  - depth/form validation
 *
 * This prevents pose jitter from creating phantom reps while still allowing
 * reasonably fast push-ups.
 */
data class RepFeedback(val qualityPercent: Int, val depthPercent: Int, val message: String)

class RepCounter(
    private val downThresholdDeg: Double = 95.0,
    private val upThresholdDeg: Double = 150.0,
    private val minShoulderDrop: Float = 0.035f,
    private val maxTorsoTiltDeg: Double = 55.0,
    private val emaFactor: Double = 0.32,
    private val phaseStableReadings: Int = 2,
    private val repCooldownMs: Long = 280L,
    private val minPhaseMs: Long = 90L,
    private val maxRepCycleMs: Long = 3500L
) {
    enum class Position { UP, DOWN, UNKNOWN }

    var position: Position = Position.UNKNOWN
        private set
    var reps: Int = 0
        private set

    var lastFeedback: RepFeedback? = null
        private set

    private var filteredAngle: Double? = null
    private var filteredShoulderY: Float? = null
    private var filteredTilt: Double? = null

    private var shoulderYAtUp = 0f
    private var maxShoulderYDuringDown = 0f
    private var candidateReadings = 0
    private var lastRepMs = 0L
    private var phaseStartedMs = 0L
    private var downStartedMs = 0L

    fun reset() {
        position = Position.UNKNOWN
        reps = 0
        lastFeedback = null
        filteredAngle = null
        filteredShoulderY = null
        filteredTilt = null
        shoulderYAtUp = 0f
        maxShoulderYDuringDown = 0f
        candidateReadings = 0
        lastRepMs = 0L
        phaseStartedMs = 0L
        downStartedMs = 0L
    }

    fun onReading(angleDeg: Double, shoulderY: Float, torsoTiltDeg: Double, poseConfidence: Float = 1f): Boolean {
        val angle = smooth(filteredAngle, angleDeg).also { filteredAngle = it }
        val shoulder = smooth(filteredShoulderY, shoulderY).toFloat().also { filteredShoulderY = it }
        val tilt = smooth(filteredTilt, torsoTiltDeg).also { filteredTilt = it }

        // Bad posture pauses the phase machine instead of resetting it. This avoids
        // accidental reps when the person briefly leaves the plank position.
        if (tilt > maxTorsoTiltDeg) {
            candidateReadings = 0
            return false
        }

        val now = System.currentTimeMillis()
        if (poseConfidence < 0.55f) {
            candidateReadings = 0
            return false
        }

        when (position) {
            Position.UNKNOWN -> {
                if (angle > upThresholdDeg) {
                    position = Position.UP
                    shoulderYAtUp = shoulder
                    phaseStartedMs = now
                    candidateReadings = 0
                }
            }

            Position.UP -> {
                if (angle < downThresholdDeg) {
                    candidateReadings++
                    if (candidateReadings >= phaseStableReadings) {
                        position = Position.DOWN
                        maxShoulderYDuringDown = shoulder
                        downStartedMs = now
                        phaseStartedMs = now
                        candidateReadings = 0
                    }
                } else {
                    candidateReadings = 0
                    // Track slow camera/body drift while the athlete is clearly UP.
                    shoulderYAtUp = shoulder
                }
            }

            Position.DOWN -> {
                if (shoulder > maxShoulderYDuringDown) {
                    maxShoulderYDuringDown = shoulder
                }

                if (angle > upThresholdDeg) {
                    candidateReadings++
                    if (candidateReadings >= phaseStableReadings && now - downStartedMs >= minPhaseMs) {
                        val drop = maxShoulderYDuringDown - shoulderYAtUp
                        val cycleMs = (now - phaseStartedMs).coerceAtLeast(1L)
                        position = Position.UP
                        shoulderYAtUp = shoulder
                        phaseStartedMs = now
                        candidateReadings = 0

                        if (drop >= minShoulderDrop && now - lastRepMs >= repCooldownMs && cycleMs <= maxRepCycleMs) {
                            lastRepMs = now
                            reps++

                            val depth = ((drop / (minShoulderDrop * 2.2f)) * 100f)
                                .coerceIn(0f, 100f).toInt()
                            val angleQuality = (((angle - downThresholdDeg) /
                                (upThresholdDeg - downThresholdDeg)) * 100.0)
                                .coerceIn(0.0, 100.0).toInt()
                            val postureQuality =
                                (100.0 - ((tilt / maxTorsoTiltDeg) * 35.0))
                                    .coerceIn(0.0, 100.0).toInt()
                            val tempoQuality = when {
                                cycleMs in 700L..2200L -> 100
                                cycleMs < 700L -> (100 - ((700L - cycleMs) * 0.18)).toInt()
                                else -> (100 - ((cycleMs - 2200L) * 0.06)).toInt()
                            }.coerceIn(45, 100)

                            val quality = (
                                depth * 0.35f +
                                angleQuality * 0.25f +
                                postureQuality * 0.25f +
                                tempoQuality * 0.15f
                            ).toInt().coerceIn(0, 100)

                            lastFeedback = RepFeedback(
                                qualityPercent = quality,
                                depthPercent = depth,
                                message = when {
                                    quality >= 90 -> "PERFECT REP"
                                    quality >= 75 -> "GOOD REP"
                                    else -> "REP TAMAM"
                                }
                            )
                            return true
                        }
                    }
                } else {
                    candidateReadings = 0
                }
            }
        }
        return false
    }

    private fun smooth(previous: Double?, next: Double): Double =
        previous?.let { it + (next - it) * emaFactor } ?: next

    private fun smooth(previous: Float?, next: Float): Double =
        previous?.toDouble()?.let { it + (next - it) * emaFactor } ?: next.toDouble()

    companion object {
        fun angleDegrees(ax: Float, ay: Float, bx: Float, by: Float, cx: Float, cy: Float): Double {
            val abx = ax - bx; val aby = ay - by
            val cbx = cx - bx; val cby = cy - by
            val dot = (abx * cbx + aby * cby).toDouble()
            val magAB = sqrt((abx * abx + aby * aby).toDouble())
            val magCB = sqrt((cbx * cbx + cby * cby).toDouble())
            if (magAB == 0.0 || magCB == 0.0) return 180.0
            val cosAngle = (dot / (magAB * magCB)).coerceIn(-1.0, 1.0)
            return Math.toDegrees(acos(cosAngle))
        }

        fun tiltFromHorizontalDegrees(x1: Float, y1: Float, x2: Float, y2: Float): Double {
            val dx = (x2 - x1).toDouble()
            val dy = (y2 - y1).toDouble()
            if (dx == 0.0 && dy == 0.0) return 90.0
            return Math.toDegrees(atan2(abs(dy), abs(dx)))
        }
    }
}
