package com.example.pushup

import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query

data class MatchInvite(
    val id: String = "",
    val fromUsername: String = "",
    val fromDisplayName: String = "",
    val toUsername: String = "",
    val status: String = "pending", // pending / accepted / declined
    val roomId: String = "",
    val createdAt: Long = 0
)

/** Bir arkadaşa doğrudan maç daveti (revanş dahil) göndermek/almak için. */
class MatchInviteClient {
    private val db = FirebaseFirestore.getInstance()
    private val invitesRef = db.collection("matchInvites")

    /** @param onCreated (daveti temsil eden doküman id'si, oluşturulan oda id'si) */
    fun sendInvite(fromUsername: String, fromDisplayName: String, toUsername: String, onCreated: (String, String) -> Unit) {
        val roomId = "invite_" + System.currentTimeMillis().toString(36) + (1000..9999).random()
        val data = hashMapOf(
            "fromUsername" to fromUsername.trim().lowercase(),
            "fromDisplayName" to fromDisplayName,
            "toUsername" to toUsername.trim().lowercase(),
            "status" to "pending",
            "roomId" to roomId,
            "createdAt" to System.currentTimeMillis()
        )
        invitesRef.add(data).addOnSuccessListener { ref -> onCreated(ref.id, roomId) }
    }

    /** Bana gelen bekleyen daveti canlı dinler (HomeScreen'de banner göstermek için). */
    fun listenForIncoming(myUsername: String, onInvite: (MatchInvite?) -> Unit): ListenerRegistration {
        return invitesRef
            .whereEqualTo("toUsername", myUsername.trim().lowercase())
            .whereEqualTo("status", "pending")
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(1)
            .addSnapshotListener { snap, error ->
                if (error != null) {
                    // Composite index eksikse (toUsername + status + createdAt) sorgu burada
                    // sessizce başarısız olurdu ve davetler hiç görünmezdi. Artık en azından
                    // logcat'e düşüyor: "PushUpInvites" etiketiyle ara.
                    Log.e("PushUpInvites", "listenForIncoming failed - Firestore index eksik olabilir", error)
                    onInvite(null)
                    return@addSnapshotListener
                }
                val doc = snap?.documents?.firstOrNull()
                if (doc == null) {
                    onInvite(null)
                    return@addSnapshotListener
                }
                onInvite(
                    MatchInvite(
                        id = doc.id,
                        fromUsername = doc.getString("fromUsername") ?: "",
                        fromDisplayName = doc.getString("fromDisplayName") ?: "",
                        toUsername = doc.getString("toUsername") ?: "",
                        status = doc.getString("status") ?: "pending",
                        roomId = doc.getString("roomId") ?: "",
                        createdAt = doc.getLong("createdAt") ?: 0
                    )
                )
            }
    }

    /** Daveti gönderen taraf, karşı tarafın kabul edip etmediğini dinler. */
    fun listenForResponse(inviteId: String, onStatus: (String) -> Unit): ListenerRegistration {
        return invitesRef.document(inviteId).addSnapshotListener { snap, _ ->
            val status = snap?.getString("status") ?: return@addSnapshotListener
            onStatus(status)
        }
    }

    fun respond(inviteId: String, accept: Boolean) {
        invitesRef.document(inviteId).update("status", if (accept) "accepted" else "declined")
    }
}
