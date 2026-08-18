package com.example.pushup

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool

/** Single owner for short battle SFX. Prevents Audio logic leaking into screens. */
class BattleAudioManager(context: Context) {
    private val pool: SoundPool
    private val ids = mutableMapOf<Int, Int>()
    private var lastPlayed = mutableMapOf<Int, Long>()

    private object Key { const val CLICK=1; const val CONFIRM=2; const val REP=3; const val PERFECT=4; const val COUNTDOWN=5; const val FIGHT=6; const val COMBO=7; const val OVERTAKE=8; const val WARNING=9; const val VICTORY=10; const val DEFEAT=11; const val DRAW=12; const val MATCH_POINT=13; const val DISCONNECT=14; const val OPPONENT_REP=15 }

    init {
        val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        pool = SoundPool.Builder().setMaxStreams(6).setAudioAttributes(attrs).build()
        fun load(key: Int, res: Int) { ids[key] = pool.load(context, res, 1) }
        load(Key.CLICK, R.raw.click_pop); load(Key.CONFIRM, R.raw.confirm_pop)
        load(Key.REP, R.raw.rep_tick); load(Key.PERFECT, R.raw.rep_perfect)
        load(Key.COUNTDOWN, R.raw.countdown_beep); load(Key.FIGHT, R.raw.fight_start)
        load(Key.COMBO, R.raw.combo_rise); load(Key.OVERTAKE, R.raw.overtake)
        load(Key.WARNING, R.raw.warning_beep); load(Key.VICTORY, R.raw.victory_fanfare)
        load(Key.DEFEAT, R.raw.defeat_drop); load(Key.DRAW, R.raw.draw_chime)
        load(Key.MATCH_POINT, R.raw.match_point); load(Key.DISCONNECT, R.raw.disconnect_alert); load(Key.OPPONENT_REP, R.raw.opponent_rep)
    }

    private fun play(key: Int, volume: Float, cooldownMs: Long = 0L, rate: Float = 1f) {
        val id = ids[key] ?: return
        val now = System.currentTimeMillis()
        if (cooldownMs > 0 && now - (lastPlayed[key] ?: 0L) < cooldownMs) return
        lastPlayed[key] = now
        pool.play(id, volume, volume, 1, 0, rate)
    }

    fun click() = play(Key.CLICK, .65f, 35)
    fun confirm() = play(Key.CONFIRM, .9f, 80)
    fun rep(perfect: Boolean) = play(if (perfect) Key.PERFECT else Key.REP, if (perfect) .9f else .65f, 80)
    fun countdown() = play(Key.COUNTDOWN, .7f, 120)
    fun fight() = play(Key.FIGHT, .95f, 200)
    fun combo() = play(Key.COMBO, .85f, 180)
    fun overtake() = play(Key.OVERTAKE, .95f, 250)
    fun warning() = play(Key.WARNING, .75f, 180)
    fun victory() = play(Key.VICTORY, 1f)
    fun defeat() = play(Key.DEFEAT, .9f)
    fun draw() = play(Key.DRAW, .9f)
    fun matchPoint() = play(Key.MATCH_POINT, .95f, 450)
    fun disconnect() = play(Key.DISCONNECT, .8f, 250)
    fun opponentRep() = play(Key.OPPONENT_REP, .42f, 90, 0.95f)

    fun release() { pool.release(); ids.clear(); lastPlayed.clear() }
}
