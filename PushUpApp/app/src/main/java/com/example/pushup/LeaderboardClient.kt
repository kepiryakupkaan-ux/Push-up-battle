package com.example.pushup

import com.google.firebase.firestore.FirebaseFirestore

data class LeaderboardEntry(
    val name: String = "",
    val totalPoints: Long = 0,
    val matchesPlayed: Long = 0,
    val bestReps: Long = 0
)

/**
 * Points rule (simple, adjust freely):
 *  - Match winner: +3 points
 *  - Match loser: +1 point (for showing up / trying)
 *  - Draw: +2 points each
 * Reps themselves aren't the leaderboard score - total points across matches is,
 * so it rewards playing more, not just one lucky round.
 */
class LeaderboardClient {

    private val db = FirebaseFirestore.getInstance()
    private val playersRef = db.collection("players")

    fun recordMatchResult(
        myName: String,
        myReps: Int,
        wonMatch: Boolean,
        isDraw: Boolean
    ) {
        val pointsEarned = when {
            isDraw -> 2L
            wonMatch -> 3L
            else -> 1L
        }
        val docRef = playersRef.document(myName)

        db.runTransaction { txn ->
            val snapshot = txn.get(docRef)
            val currentPoints = snapshot.getLong("totalPoints") ?: 0L
            val currentMatches = snapshot.getLong("matchesPlayed") ?: 0L
            val currentBest = snapshot.getLong("bestReps") ?: 0L

            txn.set(docRef, mapOf(
                "name" to myName,
                "totalPoints" to currentPoints + pointsEarned,
                "matchesPlayed" to currentMatches + 1,
                "bestReps" to maxOf(currentBest, myReps.toLong())
            ))
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
