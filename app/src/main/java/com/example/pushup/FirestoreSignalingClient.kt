package com.example.pushup

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
            onAnswer(SessionDescription(SessionDescription.Type.ANSWER, sdp))
        }
        registrations.add(reg)
    }

    fun listenForOffer(onOffer: (SessionDescription) -> Unit) {
        val reg = roomRef.addSnapshotListener { snapshot, _ ->
            val offerMap = snapshot?.get("offer") as? Map<*, *> ?: return@addSnapshotListener
            val sdp = offerMap["sdp"] as? String ?: return@addSnapshotListener
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
                val candidate = IceCandidate(
                    map["sdpMid"] as? String,
                    ((map["sdpMLineIndex"] as? Long) ?: 0L).toInt(),
                    map["candidate"] as? String
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
