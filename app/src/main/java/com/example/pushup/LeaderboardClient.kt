package com.example.pushup

import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import java.util.Calendar
import kotlin.math.pow
import kotlin.math.roundToLong

// DÜZELTME (puan sistemi tamamen kaldırıldı, sadece Elo kaldı): Skor tablosu artık SADECE
// Elo puanına göre sıralanıyor ve gösteriliyor. Elo her ayın 1'inde otomatik olarak sıfırlanır
// (bkz. eloMonthStart) ve yeni açılan her hesap 0 Elo'dan başlar (bkz. AuthClient.register).
data class LeaderboardEntry(
    val name: String = "",
    val matchesPlayed: Long = 0,
    val bestReps: Long = 0,
    val photoBase64: String = "",
    val wins: Long = 0,
    val losses: Long = 0,
    val draws: Long = 0,
    val currentStreak: Long = 0,
    val bestStreak: Long = 0,
    val weeklyMatches: Long = 0,
    val badges: Map<String, Long> = emptyMap(),
    val elo: Long = 0,
    val eloMonthStart: Long = 0
)

data class MatchResultOutcome(
    val won: Boolean,
    val newlyEarnedBadges: List<BadgeDefinitions.Badge>
)

/**
 * Skor tablosu artık tek bir şeye göre çalışıyor: ELO.
 *
 * - `recordMatchResult`: her iki oyuncu da KENDİ dokümanını günceller (maç sayısı, en iyi
 *   tekrar, galibiyet/mağlubiyet/beraberlik sayısı, seri, rozetler). Bunlar profil/rozet
 *   ekranlarında hâlâ kullanılıyor ama artık skor tablosunda GÖSTERİLMİYOR.
 * - `recordEloForMatch`: maçı başlatan taraf (isCaller) TEK bir transaction içinde HER İKİ
 *   oyuncunun da Elo'sunu günceller (standart Elo formülü, K=32). Tek taraf yazdığı için
 *   çifte güncelleme / yarış durumu (race condition) olmuyor.
 * - Aylık sıfırlama sunucusuz (Cloud Functions yok) olduğu için TEMBEL (lazy) yapılıyor:
 *   her dokümanda "bu Elo hangi ay için geçerli" bilgisi (eloMonthStart) tutuluyor. Ay
 *   değiştiğinde bir sonraki maçta / skor tablosu okumasında Elo otomatik 0 kabul ediliyor.
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
        val docRef = playersRef.document(username.trim().lowercase())
        val weekStart = currentWeekStartMillis()

        db.runTransaction { txn ->
            val snap = txn.get(docRef)
            val currentMatches = snap.getLong("matchesPlayed") ?: 0L
            val currentBest = snap.getLong("bestReps") ?: 0L
            val currentPhoto = snap.getString("photoBase64") ?: ""
            val currentWins = snap.getLong("wins") ?: 0L
            val currentLosses = snap.getLong("losses") ?: 0L
            val currentDraws = snap.getLong("draws") ?: 0L
            val currentStreak = snap.getLong("currentStreak") ?: 0L
            val currentBestStreak = snap.getLong("bestStreak") ?: 0L
            val storedWeekStart = snap.getLong("weekStartTimestamp") ?: 0L
            val storedWeeklyMatches = snap.getLong("weeklyMatches") ?: 0L
            val rawBadges = snap.get("badges") as? Map<*, *> ?: emptyMap<Any?, Any?>()
            val currentBadges: Map<String, Long> = rawBadges.entries.associate {
                (it.key as String) to ((it.value as? Number)?.toLong() ?: 0L)
            }

            val sameWeek = storedWeekStart == weekStart
            val weeklyMatchesBase = if (sameWeek) storedWeeklyMatches else 0L

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

            val updatedBadges = currentBadges.toMutableMap()
            val now = System.currentTimeMillis()
            earnedNow.forEach { updatedBadges[it] = now }

            val data = hashMapOf<String, Any>(
                "name" to displayName,
                "weeklyMatches" to newWeeklyMatches,
                "weekStartTimestamp" to weekStart,
                "matchesPlayed" to newMatches,
                "bestReps" to maxOf(currentBest, myReps.toLong()),
                "photoBase64" to (photoBase64 ?: currentPhoto),
                "wins" to currentWins + (if (wonMatch) 1L else 0L),
                "losses" to currentLosses + (if (!wonMatch && !isDraw) 1L else 0L),
                "draws" to currentDraws + (if (isDraw) 1L else 0L),
                "currentStreak" to newStreak,
                "bestStreak" to newBestStreak,
                "badges" to updatedBadges
            )
            txn.set(docRef, data, SetOptions.merge())

            MatchResultOutcome(
                won = wonMatch,
                newlyEarnedBadges = earnedNow.mapNotNull { BadgeDefinitions.byId(it) }
            )
        }.addOnSuccessListener { outcome -> onResult(outcome) }
    }

    /**
     * İki oyuncunun Elo'sunu TEK transaction'da günceller (standart Elo formülü, K=32).
     * Sadece maçı başlatan taraf (isCaller) çağırmalı - böylece iki taraf da aynı maç için
     * ayrı ayrı Elo hesaplayıp çifte/çakışan güncelleme yapmaz.
     */
    fun recordEloForMatch(
        usernameA: String,
        displayNameA: String,
        usernameB: String,
        displayNameB: String,
        aWon: Boolean,
        isDraw: Boolean,
        onResult: () -> Unit = {}
    ) {
        val idA = usernameA.trim().lowercase()
        val idB = usernameB.trim().lowercase()
        if (idA.isBlank() || idB.isBlank() || idA == idB) {
            onResult()
            return
        }
        val docA = playersRef.document(idA)
        val docB = playersRef.document(idB)
        val monthStart = currentMonthStartMillis()

        db.runTransaction { txn ->
            val snapA = txn.get(docA)
            val snapB = txn.get(docB)
            val eloA = effectiveElo(snapA.getLong("elo"), snapA.getLong("eloMonthStart"), monthStart)
            val eloB = effectiveElo(snapB.getLong("elo"), snapB.getLong("eloMonthStart"), monthStart)

            val expectedA = 1.0 / (1.0 + 10.0.pow((eloB - eloA) / 400.0))
            val expectedB = 1.0 - expectedA
            val actualA = if (isDraw) 0.5 else if (aWon) 1.0 else 0.0
            val actualB = 1.0 - actualA
            val k = 32.0

            val newEloA = (eloA + k * (actualA - expectedA)).roundToLong()
            val newEloB = (eloB + k * (actualB - expectedB)).roundToLong()

            txn.set(docA, hashMapOf<String, Any>("name" to displayNameA, "elo" to newEloA, "eloMonthStart" to monthStart), SetOptions.merge())
            txn.set(docB, hashMapOf<String, Any>("name" to displayNameB, "elo" to newEloB, "eloMonthStart" to monthStart), SetOptions.merge())
            null
        }.addOnSuccessListener { onResult() }
            .addOnFailureListener { e ->
                Log.e("PushUpLeaderboard", "recordEloForMatch failed", e)
                onResult()
            }
    }

    /** Yeni hesap açılırken Elo'yu açıkça 0'a sabitler (bkz. AuthClient.register). */
    fun initializePlayerDoc(username: String, displayName: String, photoBase64: String?) {
        val docRef = playersRef.document(username.trim().lowercase())
        val data = hashMapOf<String, Any>(
            "name" to displayName,
            "elo" to 0L,
            "eloMonthStart" to currentMonthStartMillis(),
            "photoBase64" to (photoBase64 ?: "")
        )
        docRef.set(data, SetOptions.merge())
    }

    /** Skor tablosu: SADECE Elo'ya göre sıralı, en iyi `limit` oyuncu. */
    fun fetchTopPlayers(limit: Long = 50, onResult: (List<LeaderboardEntry>) -> Unit) {
        val monthStart = currentMonthStartMillis()
        // Ay değişince eski liderlerin Elo'su dokümanlarına dokunulmadan (yazmadan) "sıfır"
        // gösterilebilsin diye normalden fazla çekip, ay içi geçerli Elo'ya göre client
        // tarafında yeniden sıralıyoruz.
        playersRef.orderBy("elo", Query.Direction.DESCENDING).limit(limit * 4)
            .get()
            .addOnSuccessListener { snapshot ->
                val entries = snapshot.documents.mapNotNull { it.toObject(LeaderboardEntry::class.java) }
                    .map { entry ->
                        if (entry.eloMonthStart == monthStart) entry else entry.copy(elo = 0)
                    }
                    .sortedByDescending { it.elo }
                    .take(limit.toInt())
                onResult(entries)
            }
            .addOnFailureListener { e ->
                Log.e("PushUpLeaderboard", "fetchTopPlayers failed", e)
                onResult(emptyList())
            }
    }

    fun fetchPlayerStats(username: String, onResult: (LeaderboardEntry?) -> Unit) {
        playersRef.document(username.trim().lowercase()).get()
            .addOnSuccessListener { snap ->
                val entry = snap.toObject(LeaderboardEntry::class.java)
                if (entry == null) {
                    onResult(null)
                } else {
                    val monthStart = currentMonthStartMillis()
                    onResult(if (entry.eloMonthStart == monthStart) entry else entry.copy(elo = 0))
                }
            }
            .addOnFailureListener { onResult(null) }
    }

    private fun effectiveElo(storedElo: Long?, storedMonthStart: Long?, currentMonthStart: Long): Long {
        return if ((storedMonthStart ?: 0L) == currentMonthStart) (storedElo ?: 0L) else 0L
    }

    /** Ayın 1'i 00:00 - aylık Elo sıfırlaması bu zaman damgasına göre tetiklenir. */
    private fun currentMonthStartMillis(): Long {
        val cal = Calendar.getInstance()
        cal.set(Calendar.DAY_OF_MONTH, 1)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    /** Haftanın Pazartesi 00:00'ına denk gelen zaman damgası - "Haftanın Yıldızı" rozeti için. */
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
