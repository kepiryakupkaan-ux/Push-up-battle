package com.example.pushup

import android.os.Handler
import android.os.Looper
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration

/**
 * Very simple client-side matchmaking, no Cloud Functions needed (keeps everything
 * on Firebase's free Spark plan).
 *
 * v3 değişiklikleri:
 *  - Kuyruk okuma limiti 20'den 8'e düşürüldü (gereksiz okumayı azaltır, kuyruk zaten
 *    genelde küçüktür).
 *  - "Aday başkası tarafından kapıldı" durumunda hemen tekrar denemek yerine kısa bir
 *    gecikme (350ms) eklendi - aynı anda çok kişi ararsa Firestore'u boşuna yormasın.
 *  - 60 saniyede eşleşme bulunamazsa otomatik timeout - sonsuza kadar "aranıyor" kalmaz.
 */
class MatchmakingClient(private val myName: String) {

    private val db = FirebaseFirestore.getInstance()
    private val queueRef = db.collection("queue")
    private var myQueueDocId: String? = null
    private var queueListener: ListenerRegistration? = null
    private val handler = Handler(Looper.getMainLooper())
    private var timeoutRunnable: Runnable? = null
    private var cancelled = false

    companion object {
        /** Bu süreden eski "waiting" kuyruk kayıtları hayalet (terk edilmiş) sayılır. */
        private const val STALE_MS = 90_000L
    }

    fun findMatch(onMatched: (roomId: String, isCaller: Boolean) -> Unit, onError: (String) -> Unit) {
        cancelled = false
        startTimeout(onError)
        attemptMatch(onMatched, onError)
    }

    private fun attemptMatch(onMatched: (roomId: String, isCaller: Boolean) -> Unit, onError: (String) -> Unit) {
        if (cancelled) return
        // DÜZELTME: eskiden filtresiz "ilk 8 doküman" çekiliyordu (status'e göre server-side
        // filtre yoktu) ve eşleşen/terk edilmiş kayıtlar hiç silinmiyordu. Koleksiyon zamanla
        // büyüdükçe bu rastgele 8'lik örnek neredeyse hiç gerçekten "waiting" biri içermiyordu
        // - iki kişi aynı anda arasa bile birbirini bulamıyordu. Şimdi hem server-side
        // status=="waiting" filtresi var hem de eşleşme sonrası kayıtlar temizleniyor
        // (bkz. joinQueueAndWait), hem de çok eski ("hayalet") kayıtlar atlanıyor.
        queueRef.whereEqualTo("status", "waiting").limit(8).get()
            .addOnSuccessListener { snapshot ->
                if (cancelled) return@addOnSuccessListener
                val now = System.currentTimeMillis()
                val candidate = snapshot.documents.firstOrNull { doc ->
                    val ts = doc.getLong("ts") ?: 0L
                    // Uygulamasını kapatıp cancel() çağrılmadan çıkan kullanıcıların kaydı
                    // sonsuza kadar "waiting" kalabilir - bunlarla eşleşirsek karşı taraf
                    // hiçbir zaman bağlanmaz. STALE_MS'den eski kayıtları eleyip, fırsat
                    // bulmuşken siliyoruz (best-effort, hata olursa önemli değil).
                    val isStale = now - ts > STALE_MS
                    if (isStale) doc.reference.delete()
                    !isStale
                }

                if (candidate == null) {
                    joinQueueAndWait(onMatched, onError)
                    return@addOnSuccessListener
                }

                val roomId = "room_" + System.currentTimeMillis().toString(36) + (1000..9999).random()

                db.runTransaction { txn ->
                    val fresh = txn.get(candidate.reference)
                    if (fresh.getString("status") != "waiting") {
                        throw IllegalStateException("already_claimed")
                    }
                    txn.update(candidate.reference, mapOf("status" to "matched", "roomId" to roomId))
                }.addOnSuccessListener {
                    cancelTimeout()
                    onMatched(roomId, true)
                }.addOnFailureListener {
                    // Başkası bu adayı benden önce kaptı - kısa bir bekleme sonra tekrar dene.
                    handler.postDelayed({ attemptMatch(onMatched, onError) }, 350)
                }
            }
            .addOnFailureListener { onError(it.message ?: "queue_read_failed") }
    }

    private fun joinQueueAndWait(
        onMatched: (roomId: String, isCaller: Boolean) -> Unit,
        onError: (String) -> Unit
    ) {
        if (cancelled) return
        val doc = queueRef.document()
        myQueueDocId = doc.id
        doc.set(mapOf("name" to myName, "status" to "waiting", "ts" to System.currentTimeMillis()))
            .addOnFailureListener { onError(it.message ?: "queue_join_failed") }

        queueListener = doc.addSnapshotListener { snapshot, _ ->
            val status = snapshot?.getString("status")
            val roomId = snapshot?.getString("roomId")
            if (status == "matched" && roomId != null) {
                queueListener?.remove()
                cancelTimeout()
                // Eşleşme bulundu - kendi kuyruk kaydımızı temizliyoruz, yoksa koleksiyonda
                // sonsuza kadar birikip yeni aramaları bozardı (bkz. yukarıdaki not).
                doc.delete()
                onMatched(roomId, false)
            }
        }
    }

    private fun startTimeout(onError: (String) -> Unit) {
        cancelTimeout()
        val runnable = Runnable {
            if (!cancelled) {
                cancel()
                onError("timeout")
            }
        }
        timeoutRunnable = runnable
        handler.postDelayed(runnable, 60_000)
    }

    private fun cancelTimeout() {
        timeoutRunnable?.let { handler.removeCallbacks(it) }
        timeoutRunnable = null
    }

    fun cancel() {
        cancelled = true
        cancelTimeout()
        queueListener?.remove()
        myQueueDocId?.let { queueRef.document(it).delete() }
        myQueueDocId = null
    }
}
