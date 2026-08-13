package com.example.pushup

import kotlin.math.acos
import kotlin.math.sqrt

/**
 * Counts push-up reps from a stream of elbow angles (in degrees).
 *
 * A rep is: arm goes from extended (UP) to bent (DOWN) and back to extended (UP).
 * We only increment the counter on the DOWN -> UP transition, so a rep only
 * counts once it's actually completed (not just started).
 */
class RepCounter(
    private val downThresholdDeg: Double = 90.0,
    private val upThresholdDeg: Double = 155.0
) {
    enum class Position { UP, DOWN, UNKNOWN }

    var position: Position = Position.UNKNOWN
        private set
    var reps: Int = 0
        private set

    fun reset() {
        position = Position.UNKNOWN
        reps = 0
    }

    /** Feed a new elbow angle reading. Returns true if this reading just completed a rep. */
    fun onAngle(angleDeg: Double): Boolean {
        when (position) {
            Position.UNKNOWN -> {
                position = if (angleDeg > upThresholdDeg) Position.UP else Position.DOWN
            }
            Position.UP -> {
                if (angleDeg < downThresholdDeg) position = Position.DOWN
            }
            Position.DOWN -> {
                if (angleDeg > upThresholdDeg) {
                    position = Position.UP
                    reps += 1
                    return true
                }
            }
        }
        return false
    }

    companion object {
        /** Angle at point B, formed by rays B->A and B->C, in degrees. */
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
    }
}
