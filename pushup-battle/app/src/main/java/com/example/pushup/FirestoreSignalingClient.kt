package com.example.pushup

import android.util.Log
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription

/**
 * Handles ONLY the small "signaling" handshake needed to set up a WebRTC call:
 * the SDP offer/answer and ICE candidates. This is a few KB of text, not video.
 * Actual camera video/audio travels phone-to-phone once the connection is up.
 *
 * v3: ICE candidate'lar artık her biri ayrı bir alt-koleksiyon dokümanı olarak değil,
 * tek bir array alanında (`callerCandidates` / `calleeCandidates`) tutuluyor -
 * bir bağlantıda genelde 5-15 candidate üretildiği için bu, oluşan doküman sayısını
 * ve dinleyicinin tetiklenme sıklığını (okuma amplifikasyonunu) azaltıyor.
 *
 * Firestore layout:
 *   rooms/{roomId}
 *     offer: { sdp, type }
 *     answer: { sdp, type }
 *     callerCandidates: [ { sdpMid, sdpMLineIndex, candidate }, ... ]
 *     calleeCandidates: [ { sdpMid, sdpMLineIndex, candidate }, ... ]
 */
class FirestoreSignalingClient(private val roomId: String) {

    private val db = FirebaseFirestore.getInstance()
    private val roomRef = db.collection("rooms").document(roomId)
    private val registrations = mutableListOf<ListenerRegistration>()
    private val seenCandidateCount = mutableMapOf<String, Int>()

    // BUG DÜZELTMESİ: roomRef tek bir doküman ve offer/answer/callerCandidates/
    // calleeCandidates hepsi bu dokümanın alanları. addSnapshotListener() doküman
    // SEVİYESİNDE tetiklenir - yani sadece "offer" alanı değişince değil, örneğin
    // ICE candidate arrayUnion'ları (bir maçta 5-15 kez!) her yazıldığında da
    // listenForOffer/listenForAnswer'daki listener yeniden tetikleniyordu ve AYNI
    // SDP tekrar tekrar onOffer/onAnswer'a veriliyordu. Bu da callee tarafında
    // setRemoteDescription(offer)+createAnswer()'ın, caller tarafında da
    // setRemoteDescription(answer)'ın bağlantı zaten "stable" durumdayken defalarca
    // çağrılmasına yol açıyordu - WebRTC bunu "wrong state" hatasıyla reddediyor,
    // sonuç: maç boyunca gereksiz RTC_SDP_FAILED hataları ve durum metninin
    // flood olması, hatta bağlantı kurulma anında yarış durumu riski. Artık aynı
    // SDP metni bir daha aşağı akışa verilmiyor.
    private var lastDeliveredOfferSdp: String? = null
    private var lastDeliveredAnswerSdp: String? = null

    fun sendOffer(sdp: SessionDescription) {
        roomRef.set(
            mapOf("offer" to mapOf("sdp" to sdp.description, "type" to sdp.type.canonicalForm())),
            SetOptions.merge()
        )
    }

    fun sendAnswer(sdp: SessionDescription) {
        roomRef.set(
            mapOf("answer" to mapOf("sdp" to sdp.description, "type" to sdp.type.canonicalForm())),
            SetOptions.merge()
        )
    }

    fun listenForAnswer(onAnswer: (SessionDescription) -> Unit) {
        val reg = roomRef.addSnapshotListener { snapshot, _ ->
            val answerMap = snapshot?.get("answer") as? Map<*, *> ?: return@addSnapshotListener
            val sdp = answerMap["sdp"] as? String ?: return@addSnapshotListener
            // Aynı SDP daha önce teslim edildiyse (doküman başka bir alan - örn. ICE
            // candidate listesi - yüzünden değiştiği için tetiklendiyse) yok say.
            if (sdp == lastDeliveredAnswerSdp) return@addSnapshotListener
            lastDeliveredAnswerSdp = sdp
            onAnswer(SessionDescription(SessionDescription.Type.ANSWER, sdp))
        }
        registrations.add(reg)
    }

    fun listenForOffer(onOffer: (SessionDescription) -> Unit) {
        val reg = roomRef.addSnapshotListener { snapshot, _ ->
            val offerMap = snapshot?.get("offer") as? Map<*, *> ?: return@addSnapshotListener
            val sdp = offerMap["sdp"] as? String ?: return@addSnapshotListener
            if (sdp == lastDeliveredOfferSdp) return@addSnapshotListener
            lastDeliveredOfferSdp = sdp
            onOffer(SessionDescription(SessionDescription.Type.OFFER, sdp))
        }
        registrations.add(reg)
    }

    fun sendIceCandidate(candidate: IceCandidate, isCaller: Boolean) {
        val field = if (isCaller) "callerCandidates" else "calleeCandidates"
        val payload = mapOf(
            "sdpMid" to candidate.sdpMid,
            "sdpMLineIndex" to candidate.sdpMLineIndex,
            "candidate" to candidate.sdp
        )
        roomRef.set(mapOf(field to FieldValue.arrayUnion(payload)), SetOptions.merge())
    }

    fun listenForIceCandidates(fromCaller: Boolean, onCandidate: (IceCandidate) -> Unit) {
        val field = if (fromCaller) "callerCandidates" else "calleeCandidates"
        seenCandidateCount[field] = 0
        val reg = roomRef.addSnapshotListener { snapshot, _ ->
            val list = snapshot?.get(field) as? List<*> ?: return@addSnapshotListener
            val seen = seenCandidateCount[field] ?: 0
            if (list.size <= seen) return@addSnapshotListener
            for (i in seen until list.size) {
                val map = list[i] as? Map<*, *> ?: continue
                // BUG DÜZELTMESİ: "candidate" alanı (SDP fragmanı) null/eksikse eskiden
                // yine de IceCandidate(...) native yapıcısına null geçiliyordu - bu native
                // tarafta çökmeye yol açabilirdi. Artık böyle bozuk bir kayıt sessizce atlanıyor.
                val sdp = map["candidate"] as? String
                if (sdp == null) {
                    Log.w("PushUpSignaling", "candidate alanı eksik/bozuk, atlanıyor: $map")
                    continue
                }
                val candidate = IceCandidate(
                    map["sdpMid"] as? String,
                    ((map["sdpMLineIndex"] as? Long) ?: 0L).toInt(),
                    sdp
                )
                onCandidate(candidate)
            }
            seenCandidateCount[field] = list.size
        }
        registrations.add(reg)
    }

    fun stopListening() {
        registrations.forEach { it.remove() }
        registrations.clear()
    }
}
