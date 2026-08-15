package com.example.pushup

/**
 * Elo/lig sistemi:
 *  - Galibiyet: sabit +30 Elo
 *  - Berabere: Elo değişmez (0)
 *  - Mağlubiyet: bulunduğun lige göre değişen bir ceza uygulanır - üst ligde kaybetmek
 *    daha pahalı, böylece üst ligde tutunmak gerçek bir çaba istiyor.
 */
object EloUtils {
    const val STARTING_ELO = 1000L
    const val WIN_ELO_GAIN = 30L

    enum class League(val displayName: String, val minElo: Long, val lossPenalty: Long, val colorHex: String) {
        BRONZE("Bronz", 0, 10, "#CD7F32"),
        SILVER("Gümüş", 1000, 15, "#C0C0C0"),
        GOLD("Altın", 1500, 22, "#FFD700"),
        DIAMOND("Elmas", 2000, 30, "#4FD0E7")
    }

    fun leagueFor(elo: Long): League {
        return League.entries.lastOrNull { elo >= it.minElo } ?: League.BRONZE
    }

    /** @return maçtan sonraki elo değişimi (pozitif/negatif/0) */
    fun eloDelta(currentElo: Long, won: Boolean, draw: Boolean): Long {
        if (draw) return 0L
        if (won) return WIN_ELO_GAIN
        return -leagueFor(currentElo).lossPenalty
    }
}
