package com.example.pushup

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Small, lifecycle-safe state holder for battle UI. Network/media code can feed events into it. */
class BattleViewModel : ViewModel() {
    private val _state = MutableStateFlow(BattleUiState())
    val state: StateFlow<BattleUiState> = _state.asStateFlow()

    fun connected() = update { copy(connection = ConnectionState.CONNECTED) }
    fun connecting() = update { copy(connection = ConnectionState.CONNECTING) }
    fun disconnected() = update { copy(connection = ConnectionState.DISCONNECTED) }

    fun setReps(my: Int, opponent: Int) {
        val old = _state.value
        val oldLead = old.lead
        val newLead = my - opponent
        val event = when {
            oldLead <= 0 && newLead > 0 -> BattleEvent.OVERTAKE
            oldLead >= 0 && newLead < 0 -> BattleEvent.OVERTAKEN
            else -> old.event
        }
        _state.value = old.copy(myReps = my, opponentReps = opponent, event = event)
    }

    fun setTimer(seconds: Int, startCountdown: Int? = null) {
        val phase = when {
            seconds <= 0 -> BattlePhase.FINISHED
            startCountdown != null -> BattlePhase.COUNTDOWN
            seconds <= 10 -> BattlePhase.FINAL_COUNTDOWN
            else -> BattlePhase.LIVE
        }
        update { copy(secondsRemaining = seconds.coerceAtLeast(0), startCountdown = startCountdown, phase = phase) }
    }

    fun setCombo(combo: Int, now: Long) = update { copy(combo = combo, lastRepAtMs = now) }
    fun setRepFeedback(feedback: RepFeedback?) = update { copy(repFeedback = feedback) }
    fun setEvent(event: BattleEvent?) = update { copy(event = event) }

    private inline fun update(transform: BattleUiState.() -> BattleUiState) {
        _state.value = _state.value.transform()
    }
}
