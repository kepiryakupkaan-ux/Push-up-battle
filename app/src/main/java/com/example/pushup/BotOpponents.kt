package com.example.pushup

import android.content.Context

/**
 * Bot 1v1 modu: sırayla yenilmesi gereken 5 sahte rakip.
 * Her rakip bir öncekinden daha hızlı/daha çok şınav çeker (zorluk artışı).
 *
 *  - repIntervalMs: botun ortalama bir şınav atma süresi (ms). Küçüldükçe bot hızlanır.
 *  - jitter: her tekrar için rastgele sapma oranı (bot robotik değil, insan gibi
 *    biraz düzensiz görünsün diye).
 *  - startDelayMs: maç başında botun "toparlanıp" ilk şınava başlamasına kadar geçen süre.
 */
data class BotOpponent(
    val id: String,
    val displayName: String,
    val avatarRes: Int,
    val repIntervalMs: Long,
    val jitter: Float,
    val startDelayMs: Long
)

object BotRoster {
    val bots = listOf(
        BotOpponent(
            id = "sinek",
            displayName = "Sinek",
            avatarRes = R.drawable.ic_bot_sinek,
            repIntervalMs = 2200L,
            jitter = 0.35f,
            startDelayMs = 1800L
        ),
        BotOpponent(
            id = "sivri",
            displayName = "Sivri",
            avatarRes = R.drawable.ic_bot_sivri,
            repIntervalMs = 1850L,
            jitter = 0.30f,
            startDelayMs = 1500L
        ),
        BotOpponent(
            id = "huysuz",
            displayName = "Huysuz",
            avatarRes = R.drawable.ic_bot_huysuz,
            repIntervalMs = 1500L,
            jitter = 0.25f,
            startDelayMs = 1200L
        ),
        BotOpponent(
            id = "keloglan",
            displayName = "Keloğlan",
            avatarRes = R.drawable.ic_bot_keloglan,
            repIntervalMs = 1150L,
            jitter = 0.20f,
            startDelayMs = 900L
        ),
        BotOpponent(
            id = "uzun",
            displayName = "Uzun",
            avatarRes = R.drawable.ic_bot_uzun,
            repIntervalMs = 850L,
            jitter = 0.15f,
            startDelayMs = 600L
        )
    )
}

/**
 * Bot modunda hangi rakibe kadar geldiğini cihazda saklar (sırayla ilerleme).
 * unlockedIndex: oynanabilir en yüksek bot index'i (0 = sadece Sinek açık).
 */
class BotProgressManager(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("pushup_bot_progress", Context.MODE_PRIVATE)

    fun unlockedIndex(): Int = prefs.getInt(KEY_UNLOCKED, 0).coerceIn(0, BotRoster.bots.lastIndex)

    fun markBeaten(index: Int) {
        val next = (index + 1).coerceAtMost(BotRoster.bots.lastIndex)
        if (next > unlockedIndex()) {
            prefs.edit().putInt(KEY_UNLOCKED, next).apply()
        }
    }

    fun reset() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val KEY_UNLOCKED = "unlocked_index"
    }
}
