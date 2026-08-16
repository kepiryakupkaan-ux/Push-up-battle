package com.example.pushup

import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions

/**
 * v2: gerçek istek/onay akışı. Eskiden A, B'yi "eklediğinde" onay beklenmeden anında
 * ikisinin listesine de yazılıyordu ("Eklendi ✅" mesajı yanıltıcıydı - karşı taraf hiç
 * haberdar olmadan arkadaş oluyordu). Artık sendFriendRequest() sadece bir istek
 * oluşturur ("İstek gönderildi"), karşı taraf respondToFriendRequest() ile kabul/red
 * etmeden gerçek arkadaşlık kurulmuyor.
 */
class FriendsClient {
    private val db = FirebaseFirestore.getInstance()
    private val friendsRef = db.collection("friends")
    private val requestsRef = db.collection("friendRequests")

    enum class RequestOutcome { SENT, AUTO_ACCEPTED, ALREADY_FRIENDS, ALREADY_PENDING, USER_NOT_FOUND, SELF, ERROR }

    fun sendFriendRequest(
        myUsername: String,
        myDisplayName: String,
        targetUsername: String,
        onDone: (RequestOutcome) -> Unit
    ) {
        val me = myUsername.trim().lowercase()
        val target = targetUsername.trim().lowercase()
        if (target.isBlank() || me == target) {
            onDone(RequestOutcome.SELF)
            return
        }

        db.collection("users").document(target).get()
            .addOnSuccessListener { userSnap ->
                if (!userSnap.exists()) {
                    onDone(RequestOutcome.USER_NOT_FOUND)
                    return@addOnSuccessListener
                }

                friendsRef.document(me).get().addOnSuccessListener { myFriendsSnap ->
                    @Suppress("UNCHECKED_CAST")
                    val already = (myFriendsSnap.get("usernames") as? List<String>)?.contains(target) == true
                    if (already) {
                        onDone(RequestOutcome.ALREADY_FRIENDS)
                        return@addOnSuccessListener
                    }

                    // Karşı taraf bana zaten istek göndermişse (çapraz istek), tekrar bir
                    // istek daha oluşturmak yerine doğrudan kabul edip arkadaş yapıyoruz -
                    // ikisi de aynı anda "ekle" demiş gibi düşünülebilir.
                    requestsRef.whereEqualTo("fromUsername", target)
                        .whereEqualTo("toUsername", me)
                        .whereEqualTo("status", "pending")
                        .get()
                        .addOnSuccessListener { reverseSnap ->
                            val reverseDoc = reverseSnap.documents.firstOrNull()
                            if (reverseDoc != null) {
                                commitFriendship(target, me) { ok ->
                                    reverseDoc.reference.delete()
                                    onDone(if (ok) RequestOutcome.AUTO_ACCEPTED else RequestOutcome.ERROR)
                                }
                                return@addOnSuccessListener
                            }

                            requestsRef.whereEqualTo("fromUsername", me)
                                .whereEqualTo("toUsername", target)
                                .whereEqualTo("status", "pending")
                                .get()
                                .addOnSuccessListener { existingSnap ->
                                    if (existingSnap.documents.isNotEmpty()) {
                                        onDone(RequestOutcome.ALREADY_PENDING)
                                        return@addOnSuccessListener
                                    }
                                    requestsRef.add(
                                        mapOf(
                                            "fromUsername" to me,
                                            "fromDisplayName" to myDisplayName,
                                            "toUsername" to target,
                                            "status" to "pending",
                                            "createdAt" to System.currentTimeMillis()
                                        )
                                    ).addOnSuccessListener { onDone(RequestOutcome.SENT) }
                                        .addOnFailureListener { onDone(RequestOutcome.ERROR) }
                                }
                                .addOnFailureListener { onDone(RequestOutcome.ERROR) }
                        }
                        .addOnFailureListener { onDone(RequestOutcome.ERROR) }
                }.addOnFailureListener { onDone(RequestOutcome.ERROR) }
            }
            .addOnFailureListener { onDone(RequestOutcome.ERROR) }
    }

    /** Bana gelen bekleyen istekleri canlı dinler (Arkadaşlar ekranında liste olarak gösterilir). */
    fun listenForIncomingRequests(myUsername: String, onRequests: (List<FriendRequest>) -> Unit): ListenerRegistration {
        return requestsRef
            .whereEqualTo("toUsername", myUsername.trim().lowercase())
            .whereEqualTo("status", "pending")
            .addSnapshotListener { snap, error ->
                if (error != null) {
                    android.util.Log.e("PushUpFriends", "listenForIncomingRequests failed", error)
                    onRequests(emptyList())
                    return@addSnapshotListener
                }
                onRequests(
                    snap?.documents?.mapNotNull { doc ->
                        val from = doc.getString("fromUsername") ?: return@mapNotNull null
                        val fromDisplay = doc.getString("fromDisplayName") ?: from
                        FriendRequest(id = doc.id, fromUsername = from, fromDisplayName = fromDisplay)
                    } ?: emptyList()
                )
            }
    }

    fun respondToFriendRequest(request: FriendRequest, myUsername: String, accept: Boolean, onDone: (Boolean) -> Unit = {}) {
        val requestDoc = requestsRef.document(request.id)
        if (!accept) {
            requestDoc.delete().addOnSuccessListener { onDone(true) }.addOnFailureListener { onDone(false) }
            return
        }
        commitFriendship(request.fromUsername, myUsername.trim().lowercase()) { ok ->
            requestDoc.delete()
            onDone(ok)
        }
    }

    private fun commitFriendship(userA: String, userB: String, onDone: (Boolean) -> Unit) {
        val batch = db.batch()
        batch.set(friendsRef.document(userA), mapOf("usernames" to FieldValue.arrayUnion(userB)), SetOptions.merge())
        batch.set(friendsRef.document(userB), mapOf("usernames" to FieldValue.arrayUnion(userA)), SetOptions.merge())
        batch.commit().addOnSuccessListener { onDone(true) }.addOnFailureListener { onDone(false) }
    }

    fun removeFriend(myUsername: String, friendUsername: String, onDone: (Boolean) -> Unit = {}) {
        val me = myUsername.trim().lowercase()
        val friend = friendUsername.trim().lowercase()
        val batch = db.batch()
        batch.set(friendsRef.document(me), mapOf("usernames" to FieldValue.arrayRemove(friend)), SetOptions.merge())
        batch.set(friendsRef.document(friend), mapOf("usernames" to FieldValue.arrayRemove(me)), SetOptions.merge())
        batch.commit().addOnSuccessListener { onDone(true) }.addOnFailureListener { onDone(false) }
    }

    fun fetchFriendUsernames(username: String, onResult: (List<String>) -> Unit) {
        friendsRef.document(username.trim().lowercase()).get()
            .addOnSuccessListener { snap ->
                @Suppress("UNCHECKED_CAST")
                onResult((snap.get("usernames") as? List<String>) ?: emptyList())
            }
            .addOnFailureListener { onResult(emptyList()) }
    }

    /** Arkadaşların profil özetini (avatar, elo vb.) players koleksiyonundan çeker. */
    fun fetchFriendProfiles(username: String, onResult: (List<FriendProfile>) -> Unit) {
        fetchFriendUsernames(username) { usernames ->
            if (usernames.isEmpty()) {
                onResult(emptyList())
                return@fetchFriendUsernames
            }
            // Firestore whereIn en fazla 10 değer destekler - arkadaş sayısı çoksa ilk 10'u gösteririz.
            db.collection("players").whereIn(com.google.firebase.firestore.FieldPath.documentId(), usernames.take(10))
                .get()
                .addOnSuccessListener { snap ->
                    onResult(snap.documents.mapNotNull { doc ->
                        val entry = doc.toObject(LeaderboardEntry::class.java) ?: return@mapNotNull null
                        FriendProfile(username = doc.id, entry = entry)
                    })
                }
                .addOnFailureListener { onResult(emptyList()) }
        }
    }
}

data class FriendProfile(val username: String, val entry: LeaderboardEntry)
data class FriendRequest(val id: String, val fromUsername: String, val fromDisplayName: String)
