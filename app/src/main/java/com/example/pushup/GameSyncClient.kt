package com.example.pushup

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration

/**
 * Shares just two small things over Firestore during a live match:
 *  - the match start timestamp, so both phones count down the same 90 seconds
 *  - each player's live rep count, so you can see the opponent's number update
 *
 * Note: this is "honor system" sync, not a referee. Each phone counts its own
 * reps locally (from its own camera) and just broadcasts the number. Fine for a
 * friend-group project; if you ever wanted to stop cheating you'd need a trusted
 * server to verify video, which is a much bigger project.
 */
class GameSyncClient(roomId: String) {
    private val db = FirebaseFirestore.getInstance()
    private val gameRef = db.collection("rooms").document(roomId).collection("game").document("state")
    private var listener: ListenerRegistration? = null

    fun startMatch(matchStartMs: Long, durationSeconds: Int) {
        gameRef.set(
            mapOf(
                "matchStartMs" to matchStartMs,
                "durationSeconds" to durationSeconds
            ),
            com.google.firebase.firestore.SetOptions.merge()
        )
    }

    fun updateMyReps(isCaller: Boolean, reps: Int) {
        val field = if (isCaller) "callerReps" else "calleeReps"
        gameRef.set(mapOf(field to reps), com.google.firebase.firestore.SetOptions.merge())
    }

    fun listen(onUpdate: (matchStartMs: Long?, durationSeconds: Int, callerReps: Int, calleeReps: Int) -> Unit) {
        listener = gameRef.addSnapshotListener { snapshot, _ ->
            if (snapshot == null || !snapshot.exists()) return@addSnapshotListener
            onUpdate(
                snapshot.getLong("matchStartMs"),
                (snapshot.getLong("durationSeconds") ?: 90L).toInt(),
                (snapshot.getLong("callerReps") ?: 0L).toInt(),
                (snapshot.getLong("calleeReps") ?: 0L).toInt()
            )
        }
    }

    fun stop() {
        listener?.remove()
    }
}
