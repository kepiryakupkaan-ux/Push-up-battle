package com.example.pushup

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription

/**
 * Handles ONLY the small "signaling" handshake needed to set up a WebRTC call:
 * the SDP offer/answer and ICE candidates. This is a few KB of text, not video.
 * Actual camera video/audio travels phone-to-phone once the connection is up.
 *
 * Firestore layout:
 *   rooms/{roomId}
 *     offer: { sdp, type }
 *     answer: { sdp, type }
 *     rooms/{roomId}/callerCandidates/{auto-id}
 *     rooms/{roomId}/calleeCandidates/{auto-id}
 */
class FirestoreSignalingClient(private val roomId: String) {

    private val db = FirebaseFirestore.getInstance()
    private val roomRef = db.collection("rooms").document(roomId)
    private val registrations = mutableListOf<ListenerRegistration>()

    fun sendOffer(sdp: SessionDescription) {
        roomRef.set(mapOf("offer" to mapOf("sdp" to sdp.description, "type" to sdp.type.canonicalForm())))
    }

    fun sendAnswer(sdp: SessionDescription) {
        roomRef.update(mapOf("answer" to mapOf("sdp" to sdp.description, "type" to sdp.type.canonicalForm())))
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
        val sub = if (isCaller) "callerCandidates" else "calleeCandidates"
        roomRef.collection(sub).add(
            mapOf(
                "sdpMid" to candidate.sdpMid,
                "sdpMLineIndex" to candidate.sdpMLineIndex,
                "candidate" to candidate.sdp
            )
        )
    }

    fun listenForIceCandidates(fromCaller: Boolean, onCandidate: (IceCandidate) -> Unit) {
        val sub = if (fromCaller) "callerCandidates" else "calleeCandidates"
        val reg = roomRef.collection(sub).addSnapshotListener { snapshot, _ ->
            snapshot?.documentChanges?.forEach { change ->
                if (change.type.name == "ADDED") {
                    val doc = change.document
                    val candidate = IceCandidate(
                        doc.getString("sdpMid"),
                        (doc.getLong("sdpMLineIndex") ?: 0).toInt(),
                        doc.getString("candidate")
                    )
                    onCandidate(candidate)
                }
            }
        }
        registrations.add(reg)
    }

    fun stopListening() {
        registrations.forEach { it.remove() }
        registrations.clear()
    }
}
