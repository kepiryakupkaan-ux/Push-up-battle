package com.example.pushup

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions

data class LeaderboardEntry(
    val name: String = "",
    val totalPoints: Long = 0,
    val matchesPlayed: Long = 0,
    val bestReps: Long = 0,
    val photoBase64: String = ""
)

/**
 * Puan kuralı:
 *  - Maç galibi: +3 puan
 *  - Berabere: +2 puan (her iki oyuncuya da)
 *  - Maç kaybedeni: +0 puan (kaybetme puan kazandırmaz - sadece katıldın diye puan verilmiyor)
 * Reps'in kendisi skor tablosu değil - maçlar boyunca toplanan puan skor tablosu, yani
 * sadece tek seferlik şansı değil, düzenli oynamayı ödüllendiriyor.
 */
class LeaderboardClient {

    private val db = FirebaseFirestore.getInstance()
    private val playersRef = db.collection("players")

    fun recordMatchResult(
        username: String,
        myReps: Int,
        wonMatch: Boolean,
        isDraw: Boolean,
        photoBase64: String? = null
    ) {
        val pointsEarned = when {
            isDraw -> 2L
            wonMatch -> 3L
            else -> 0L
        }
        val docRef = playersRef.document(username.trim().lowercase())

        db.runTransaction { txn ->
            val snapshot = txn.get(docRef)
            val currentPoints = snapshot.getLong("totalPoints") ?: 0L
            val currentMatches = snapshot.getLong("matchesPlayed") ?: 0L
            val currentBest = snapshot.getLong("bestReps") ?: 0L
            val currentPhoto = snapshot.getString("photoBase64") ?: ""

            val data = hashMapOf<String, Any>(
                "name" to username,
                "totalPoints" to currentPoints + pointsEarned,
                "matchesPlayed" to currentMatches + 1,
                "bestReps" to maxOf(currentBest, myReps.toLong()),
                "photoBase64" to (photoBase64 ?: currentPhoto)
            )
            txn.set(docRef, data, SetOptions.merge())
        }
    }

    fun fetchTopPlayers(limit: Long = 50, onResult: (List<LeaderboardEntry>) -> Unit) {
        playersRef.orderBy("totalPoints", com.google.firebase.firestore.Query.Direction.DESCENDING)
            .limit(limit)
            .get()
            .addOnSuccessListener { snapshot ->
                val entries = snapshot.documents.mapNotNull { it.toObject(LeaderboardEntry::class.java) }
                onResult(entries)
            }
            .addOnFailureListener { onResult(emptyList()) }
    }
}
