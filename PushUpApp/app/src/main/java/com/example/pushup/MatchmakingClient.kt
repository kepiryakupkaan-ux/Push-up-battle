package com.example.pushup

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration

/**
 * Very simple client-side matchmaking, no Cloud Functions needed (keeps everything
 * on Firebase's free Spark plan).
 *
 * How it works:
 *  1. When you want a match, look in the "queue" collection for someone else waiting.
 *  2. If found: claim them via a Firestore transaction (avoids two players both
 *     grabbing the same opponent), create a room, both sides move to that room.
 *  3. If nobody's waiting: add yourself to the queue and listen for someone to
 *     claim you and write a roomId onto your queue entry.
 *
 * Good enough for a friend-group / small-scale project. At real scale you'd want
 * a Cloud Function to avoid race conditions entirely, but for ~100 users this is fine.
 */
class MatchmakingClient(private val myName: String) {

    private val db = FirebaseFirestore.getInstance()
    private val queueRef = db.collection("queue")
    private var myQueueDocId: String? = null
    private var queueListener: ListenerRegistration? = null

    fun findMatch(onMatched: (roomId: String, isCaller: Boolean) -> Unit, onError: (String) -> Unit) {
        queueRef.limit(20).get()
            .addOnSuccessListener { snapshot ->
                val candidate = snapshot.documents.firstOrNull { it.getString("status") == "waiting" }

                if (candidate == null) {
                    joinQueueAndWait(onMatched, onError)
                    return@addOnSuccessListener
                }

                val roomId = "room_" + System.currentTimeMillis().toString(36) +
                        (1000..9999).random()

                db.runTransaction { txn ->
                    val fresh = txn.get(candidate.reference)
                    if (fresh.getString("status") != "waiting") {
                        throw IllegalStateException("already_claimed")
                    }
                    txn.update(candidate.reference, mapOf(
                        "status" to "matched",
                        "roomId" to roomId
                    ))
                }.addOnSuccessListener {
                    // I claimed them, I'm the caller.
                    onMatched(roomId, true)
                }.addOnFailureListener {
                    // Someone else grabbed this candidate first — try again.
                    findMatch(onMatched, onError)
                }
            }
            .addOnFailureListener { onError(it.message ?: "queue_read_failed") }
    }

    private fun joinQueueAndWait(
        onMatched: (roomId: String, isCaller: Boolean) -> Unit,
        onError: (String) -> Unit
    ) {
        val doc = queueRef.document()
        myQueueDocId = doc.id
        doc.set(mapOf("name" to myName, "status" to "waiting", "ts" to System.currentTimeMillis()))
            .addOnFailureListener { onError(it.message ?: "queue_join_failed") }

        queueListener = doc.addSnapshotListener { snapshot, _ ->
            val status = snapshot?.getString("status")
            val roomId = snapshot?.getString("roomId")
            if (status == "matched" && roomId != null) {
                queueListener?.remove()
                // Someone else claimed me, I'm the callee.
                onMatched(roomId, false)
            }
        }
    }

    fun cancel() {
        queueListener?.remove()
        myQueueDocId?.let { queueRef.document(it).delete() }
        myQueueDocId = null
    }
}
