package com.example.pushup

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Counts push-up reps from a stream of (elbow angle, shoulder height, torso tilt) readings.
 *
 * v2 - eski sürüm sadece dirsek açısına bakıyordu, bu yüzden kolunu havada kaldırıp
 * indirmek bile "tekrar" olarak sayılıyordu. Artık bir tekrarın geçerli sayılması için
 * iki ek şart var:
 *
 *  1) GÖVDE YATAY OLMALI (plank pozisyonu): omuz-kalça hattı yataydan çok sapmışsa
 *     (örn. ayaktasın ya da oturuyorsun) o okuma tamamen görmezden gelinir.
 *  2) OMUZ GERÇEKTEN İNİP ÇIKMALI: sadece dirseği kırıp açmak yetmiyor - kol
 *     yukarıdayken ile aşağıdayken arasında omzun dikey konumu da belirgin şekilde
 *     değişmiş olmalı (minShoulderDrop). Böylece "kolumu havada kaldırsam da sayıyor"
 *     sorunu ortadan kalkıyor.
 */
class RepCounter(
    private val downThresholdDeg: Double = 95.0,
    private val upThresholdDeg: Double = 150.0,
    private val minShoulderDrop: Float = 0.035f,
    private val maxTorsoTiltDeg: Double = 55.0
) {
    enum class Position { UP, DOWN, UNKNOWN }

    var position: Position = Position.UNKNOWN
        private set
    var reps: Int = 0
        private set

    private var shoulderYAtUp: Float = 0f
    private var maxShoulderYDuringDown: Float = 0f

    fun reset() {
        position = Position.UNKNOWN
        reps = 0
    }

    /**
     * @param angleDeg dirsek açısı (omuz-dirsek-bilek arasında, derece)
     * @param shoulderY omuzun kare içindeki normalize (0..1) dikey konumu (aşağı = büyük)
     * @param torsoTiltDeg gövdenin (omuz-kalça hattı) yataydan sapma açısı, derece
     * @return true ise bu okuma az önce GEÇERLİ bir push-up'ı tamamladı
     */
    fun onReading(angleDeg: Double, shoulderY: Float, torsoTiltDeg: Double): Boolean {
        val postureOk = torsoTiltDeg <= maxTorsoTiltDeg
        if (!postureOk) {
            // Duruş plank'a benzemiyor (muhtemelen ayakta / oturuyor) -> bu okumayı sayma,
            // ama state'i de bozma; doğru pozisyona dönünce kaldığı yerden devam etsin.
            return false
        }

        when (position) {
            Position.UNKNOWN -> {
                if (angleDeg > upThresholdDeg) {
                    position = Position.UP
                    shoulderYAtUp = shoulderY
                }
            }
            Position.UP -> {
                if (angleDeg < downThresholdDeg) {
                    position = Position.DOWN
                    maxShoulderYDuringDown = shoulderY
                } else {
                    // Kol hâlâ yukarıdayken referans omuz konumunu güncelle (küçük driftleri tolere et)
                    shoulderYAtUp = shoulderY
                }
            }
            Position.DOWN -> {
                if (shoulderY > maxShoulderYDuringDown) maxShoulderYDuringDown = shoulderY
                if (angleDeg > upThresholdDeg) {
                    val drop = maxShoulderYDuringDown - shoulderYAtUp
                    position = Position.UP
                    shoulderYAtUp = shoulderY
                    if (drop >= minShoulderDrop) {
                        reps += 1
                        return true
                    }
                    // Kol kırıldı ama gövde/omuz yeterince inmedi -> muhtemelen sahte hareket, sayma.
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

        /** Bir hattın yataydan sapma açısı (0 = tam yatay/plank, 90 = tam dikey/ayakta). */
        fun tiltFromHorizontalDegrees(x1: Float, y1: Float, x2: Float, y2: Float): Double {
            val dx = (x2 - x1).toDouble()
            val dy = (y2 - y1).toDouble()
            if (dx == 0.0 && dy == 0.0) return 90.0
            val angleRad = atan2(abs(dy), abs(dx))
            return Math.toDegrees(angleRad)
        }
    }
}
