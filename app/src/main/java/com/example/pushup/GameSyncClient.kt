package com.example.pushup

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions

/**
 * v3: Artık sadece maç başlangıç zamanını senkronluyor - tekrar sayıları (reps) DataChannel
 * üzerinden P2P gidiyor (bkz. WebRtcClient.sendDataChannelMessage). Bu, maç başına Firestore
 * yazma sayısını 20-40'tan 1-2'ye indiriyor.
 */
class GameSyncClient(roomId: String) {
    private val db = FirebaseFirestore.getInstance()
    private val gameRef = db.collection("rooms").document(roomId).collection("game").document("state")
    private var listener: ListenerRegistration? = null

    fun startMatch(matchStartMs: Long, durationSeconds: Int) {
        gameRef.set(
            mapOf("matchStartMs" to matchStartMs, "durationSeconds" to durationSeconds),
            SetOptions.merge()
        )
    }

    fun listen(onUpdate: (matchStartMs: Long?, durationSeconds: Int) -> Unit) {
        listener = gameRef.addSnapshotListener { snapshot, _ ->
            if (snapshot == null || !snapshot.exists()) return@addSnapshotListener
            onUpdate(snapshot.getLong("matchStartMs"), (snapshot.getLong("durationSeconds") ?: 90L).toInt())
        }
    }

    fun stop() {
        listener?.remove()
    }
}
