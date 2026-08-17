package com.example.pushup

/** Sabit rozet tanımları. Kazanılan rozetler players/{username}.badges alanında (id -> earnedAtMillis) tutulur. */
object BadgeDefinitions {
    data class Badge(val id: String, val title: String, val emoji: String, val description: String)

    val ALL = listOf(
        Badge("first_match", "İlk Adım", "🎬", "İlk maçını oynadın"),
        Badge("streak_3", "Seri Galip", "🔥", "3 galibiyet üst üste"),
        Badge("power_30", "Güç Canavarı", "💪", "Tek maçta 30+ push-up"),
        Badge("matches_10", "Alışkanlık", "📈", "Toplam 10 maç oynadın"),
        Badge("matches_50", "Kararlı", "🏋️", "Toplam 50 maç oynadın"),
        Badge("matches_100", "Efsane", "👑", "Toplam 100 maç oynadın"),
        Badge("week_5", "Haftanın Yıldızı", "⭐", "Bir haftada 5 maç oynadın")
    )

    fun byId(id: String): Badge? = ALL.firstOrNull { it.id == id }
}
