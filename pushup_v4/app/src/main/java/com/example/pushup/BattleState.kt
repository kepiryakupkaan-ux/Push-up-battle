package com.example.pushup

enum class BattlePhase { WAITING, COUNTDOWN, LIVE, FINAL_COUNTDOWN, FINISHED }
enum class ConnectionState { CONNECTING, CONNECTED, DEGRADED, DISCONNECTED }
enum class BattleEvent { FIGHT, OVERTAKE, OVERTAKEN, COMBO, MATCH_POINT, VICTORY, DEFEAT, DRAW, DISCONNECT }

data class BattleUiState(
    val phase: BattlePhase = BattlePhase.WAITING,
    val myReps: Int = 0,
    val opponentReps: Int = 0,
    val secondsRemaining: Int = 90,
    val combo: Int = 0,
    val connection: ConnectionState = ConnectionState.CONNECTING,
    val event: BattleEvent? = null,
    val repFeedback: RepFeedback? = null,
    val startCountdown: Int? = null,
    val lastRepAtMs: Long = 0L
) {
    val lead: Int get() = myReps - opponentReps
}
