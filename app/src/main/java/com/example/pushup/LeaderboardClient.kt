package com.example.pushup

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import java.util.Calendar

enum class LeaderboardMode { TOTAL, ELO, WEEKLY }

data class LeaderboardEntry(
    val name: String = "",
    val totalPoints: Long = 0,
    val weeklyPoints: Long = 0,
    val matchesPlayed: Long = 0,
    val bestReps: Long = 0,
    val photoBase64: String = "",
    val elo: Long = EloUtils.STARTING_ELO,
    val wins: Long = 0,
    val losses: Long = 0,
    val draws: Long = 0,
    val currentStreak: Long = 0,
    val bestStreak: Long = 0,
    val badges: Map<String, Long> = emptyMap()
)

data class MatchResultOutcome(
    val pointsEarned: Long,
    val eloDelta: Long,
    val newElo: Long,
    val oldLeague: EloUtils.League,
    val newLeague: EloUtils.League,
    val newlyEarnedBadges: List<BadgeDefinitions.Badge>
)

/**
 * Puan kuralı: galibiyet +3, berabere +2, mağlubiyet +0 (toplam puan tablosu).
 * Elo kuralı: EloUtils'e bakınız (galibiyet +30, mağlubiyet lige göre değişken ceza, berabere 0).
 * Ayrıca haftalık puan (her Pazartesi 00:00'da "sıfırlanmış" sayılır - bkz. weekStartTimestamp),
 * seri (currentStreak/bestStreak) ve rozetler de aynı transaction içinde güncellenir.
 */
class LeaderboardClient {

    private val db = FirebaseFirestore.getInstance()
    private val playersRef = db.collection("players")

    fun recordMatchResult(
        username: String,
        displayName: String,
        myReps: Int,
        wonMatch: Boolean,
        isDraw: Boolean,
        photoBase64: String? = null,
        onResult: (MatchResultOutcome) -> Unit = {}
    ) {
        val pointsEarned = when {
            isDraw -> 2L
            wonMatch -> 3L
            else -> 0L
        }
        val docRef = playersRef.document(username.trim().lowercase())
        val weekStart = currentWeekStartMillis()

        db.runTransaction { txn ->
            val snap = txn.get(docRef)
            val currentPoints = snap.getLong("totalPoints") ?: 0L
            val currentMatches = snap.getLong("matchesPlayed") ?: 0L
            val currentBest = snap.getLong("bestReps") ?: 0L
            val currentPhoto = snap.getString("photoBase64") ?: ""
            val currentElo = snap.getLong("elo") ?: EloUtils.STARTING_ELO
            val currentWins = snap.getLong("wins") ?: 0L
            val currentLosses = snap.getLong("losses") ?: 0L
            val currentDraws = snap.getLong("draws") ?: 0L
            val currentStreak = snap.getLong("currentStreak") ?: 0L
            val currentBestStreak = snap.getLong("bestStreak") ?: 0L
            val storedWeekStart = snap.getLong("weekStartTimestamp") ?: 0L
            val storedWeeklyPoints = snap.getLong("weeklyPoints") ?: 0L
            val storedWeeklyMatches = snap.getLong("weeklyMatches") ?: 0L
            val rawBadges = snap.get("badges") as? Map<*, *> ?: emptyMap<Any?, Any?>()
            val currentBadges: Map<String, Long> = rawBadges.entries.associate {
                (it.key as String) to ((it.value as? Number)?.toLong() ?: 0L)
            }

            val sameWeek = storedWeekStart == weekStart
            val weeklyPointsBase = if (sameWeek) storedWeeklyPoints else 0L
            val weeklyMatchesBase = if (sameWeek) storedWeeklyMatches else 0L

            val oldLeague = EloUtils.leagueFor(currentElo)
            val eloDelta = EloUtils.eloDelta(currentElo, wonMatch, isDraw)
            val newElo = (currentElo + eloDelta).coerceAtLeast(0L)
            val newLeague = EloUtils.leagueFor(newElo)

            val newStreak = if (wonMatch) currentStreak + 1 else 0L
            val newBestStreak = maxOf(currentBestStreak, newStreak)
            val newMatches = currentMatches + 1
            val newWeeklyMatches = weeklyMatchesBase + 1

            val earnedNow = mutableListOf<String>()
            fun award(id: String) {
                if (!currentBadges.containsKey(id)) earnedNow += id
            }
            if (currentMatches == 0L) award("first_match")
            if (newStreak >= 3) award("streak_3")
            if (myReps >= 30) award("power_30")
            if (newMatches >= 10) award("matches_10")
            if (newMatches >= 50) award("matches_50")
            if (newMatches >= 100) award("matches_100")
            if (newWeeklyMatches >= 5) award("week_5")
            if (newLeague.ordinal > oldLeague.ordinal) {
                when (newLeague) {
                    EloUtils.League.SILVER -> award("league_silver")
                    EloUtils.League.GOLD -> award("league_gold")
                    EloUtils.League.DIAMOND -> award("league_diamond")
                    else -> {}
                }
            }

            val updatedBadges = currentBadges.toMutableMap()
            val now = System.currentTimeMillis()
            earnedNow.forEach { updatedBadges[it] = now }

            val data = hashMapOf<String, Any>(
                "name" to displayName,
                "totalPoints" to currentPoints + pointsEarned,
                "weeklyPoints" to weeklyPointsBase + pointsEarned,
                "weeklyMatches" to newWeeklyMatches,
                "weekStartTimestamp" to weekStart,
                "matchesPlayed" to newMatches,
                "bestReps" to maxOf(currentBest, myReps.toLong()),
                "photoBase64" to (photoBase64 ?: currentPhoto),
                "elo" to newElo,
                "wins" to currentWins + (if (wonMatch) 1L else 0L),
                "losses" to currentLosses + (if (!wonMatch && !isDraw) 1L else 0L),
                "draws" to currentDraws + (if (isDraw) 1L else 0L),
                "currentStreak" to newStreak,
                "bestStreak" to newBestStreak,
                "badges" to updatedBadges
            )
            txn.set(docRef, data, SetOptions.merge())

            MatchResultOutcome(
                pointsEarned = pointsEarned,
                eloDelta = eloDelta,
                newElo = newElo,
                oldLeague = oldLeague,
                newLeague = newLeague,
                newlyEarnedBadges = earnedNow.mapNotNull { BadgeDefinitions.byId(it) }
            )
        }.addOnSuccessListener { outcome -> onResult(outcome) }
    }

    fun fetchTopPlayers(mode: LeaderboardMode, limit: Long = 50, onResult: (List<LeaderboardEntry>) -> Unit) {
        val query = when (mode) {
            LeaderboardMode.TOTAL -> playersRef.orderBy("totalPoints", Query.Direction.DESCENDING).limit(limit)
            LeaderboardMode.ELO -> playersRef.orderBy("elo", Query.Direction.DESCENDING).limit(limit)
            LeaderboardMode.WEEKLY -> playersRef
                .whereEqualTo("weekStartTimestamp", currentWeekStartMillis())
                .orderBy("weeklyPoints", Query.Direction.DESCENDING)
                .limit(limit)
        }
        query.get()
            .addOnSuccessListener { snapshot ->
                onResult(snapshot.documents.mapNotNull { it.toObject(LeaderboardEntry::class.java) })
            }
            .addOnFailureListener { onResult(emptyList()) }
    }

    fun fetchPlayerStats(username: String, onResult: (LeaderboardEntry?) -> Unit) {
        playersRef.document(username.trim().lowercase()).get()
            .addOnSuccessListener { onResult(it.toObject(LeaderboardEntry::class.java)) }
            .addOnFailureListener { onResult(null) }
    }

    /** Haftanın Pazartesi 00:00'ına denk gelen zaman damgası - haftalık tablo bunun üstünden "sıfırlanır". */
    private fun currentWeekStartMillis(): Long {
        val cal = Calendar.getInstance()
        cal.firstDayOfWeek = Calendar.MONDAY
        cal.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        if (cal.timeInMillis > System.currentTimeMillis()) {
            cal.add(Calendar.WEEK_OF_YEAR, -1)
        }
        return cal.timeInMillis
    }
}
