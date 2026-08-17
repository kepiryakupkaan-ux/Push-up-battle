package com.example.pushup

import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query

data class MatchHistoryEntry(
    val participants: List<String> = emptyList(),
    val timestamp: Long = 0,
    val playerA: String = "",
    val playerB: String = "",
    val repsA: Long = 0,
    val repsB: Long = 0
)

/** username'e göre "kiminle, ne zaman, kaç-kaç oynadım" geçmişini tutar. */
class MatchHistoryClient {
    private val db = FirebaseFirestore.getInstance()
    private val historyRef = db.collection("matchHistory")

    /**
     * DÜZELTME: eskiden buraya display name (session.displayName) yazılıyordu, ama
     * fetchHistory() sorgusu username (küçük harf) ile arıyordu - displayName büyük/küçük
     * harf içerebildiği için ("JohnDoe" gibi) bu ikisi neredeyse hiç eşleşmiyordu ve
     * "Geçmiş" sekmesi çoğu kullanıcı için sessizce bomboş görünüyordu. Artık participants
     * dizisine gerçekten username (küçük harf) yazılıyor, playerA/playerB alanları ise
     * ekranda güzel görünsün diye displayName olarak kalıyor.
     */
    fun recordMatch(
        myUsername: String,
        myDisplayName: String,
        opponentUsername: String,
        opponentDisplayName: String,
        repsA: Int,
        repsB: Int
    ) {
        val data = hashMapOf(
            "participants" to listOf(myUsername.trim().lowercase(), opponentUsername.trim().lowercase()),
            "timestamp" to System.currentTimeMillis(),
            "playerA" to myDisplayName,
            "playerB" to opponentDisplayName,
            "repsA" to repsA,
            "repsB" to repsB
        )
        historyRef.add(data)
    }

    fun fetchHistory(username: String, limit: Long = 50, onResult: (List<MatchHistoryEntry>) -> Unit) {
        historyRef.whereArrayContains("participants", username.trim().lowercase())
            .orderBy("timestamp", Query.Direction.DESCENDING)
            .limit(limit)
            .get()
            .addOnSuccessListener { snap ->
                onResult(snap.documents.mapNotNull { it.toObject(MatchHistoryEntry::class.java) })
            }
            .addOnFailureListener { e ->
                // whereArrayContains + orderBy(timestamp) composite index gerektirir - eksikse
                // maç geçmişi sessizce boş görünürdü.
                Log.e("PushUpHistory", "fetchHistory failed - index eksik olabilir", e)
                onResult(emptyList())
            }
    }
}
