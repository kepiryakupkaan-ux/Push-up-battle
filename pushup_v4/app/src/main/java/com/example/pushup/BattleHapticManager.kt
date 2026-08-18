package com.example.pushup

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/** Centralized haptic vocabulary; screens no longer choose arbitrary durations everywhere. */
class BattleHapticManager(context: Context) {
    private val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= 31) {
        context.getSystemService(VibratorManager::class.java).defaultVibrator
    } else {
        @Suppress("DEPRECATION") context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }

    private fun pulse(duration: Long, amplitude: Int) {
        try {
            if (Build.VERSION.SDK_INT >= 26) vibrator.vibrate(VibrationEffect.createOneShot(duration, amplitude.coerceIn(1, 255)))
            else @Suppress("DEPRECATION") vibrator.vibrate(duration)
        } catch (_: Throwable) { }
    }

    fun click() = pulse(18, 45)
    fun rep(perfect: Boolean = false) = pulse(if (perfect) 34 else 20, if (perfect) 105 else 55)
    fun combo() = pulse(32, 105)
    fun overtake() = pulse(55, 150)
    fun warning() = pulse(45, 120)
    fun countdown(last: Boolean = false) = pulse(if (last) 35 else 18, if (last) 110 else 70)
    fun matchPoint() = pulse(28, 115)
    fun victory() = pulse(90, 170)
    fun defeat() = pulse(70, 120)
    fun disconnect() = pulse(45, 110)
}
