package com.example.pushup

import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions

/**
 * Basit, karşılıklı arkadaşlık: A, B'yi eklediğinde ikisinin listesine de otomatik yazılır
 * (onay beklemez - küçük arkadaş grubu için en az hataya açık, en basit yol).
 */
class FriendsClient {
    private val db = FirebaseFirestore.getInstance()
    private val friendsRef = db.collection("friends")

    fun addFriend(myUsername: String, friendUsername: String, onDone: (Boolean) -> Unit = {}) {
        val me = myUsername.trim().lowercase()
        val friend = friendUsername.trim().lowercase()
        if (me == friend || friend.isBlank()) {
            onDone(false)
            return
        }
        // Önce böyle bir kullanıcı gerçekten var mı diye kontrol et - yoksa "eklendi"
        // yanıtı vermek yanıltıcı oluyordu (arkadaş listesine geçersiz kayıt eklenmesin).
        db.collection("users").document(friend).get()
            .addOnSuccessListener { snap ->
                if (!snap.exists()) {
                    onDone(false)
                    return@addOnSuccessListener
                }
                val batch = db.batch()
                batch.set(friendsRef.document(me), mapOf("usernames" to FieldValue.arrayUnion(friend)), SetOptions.merge())
                batch.set(friendsRef.document(friend), mapOf("usernames" to FieldValue.arrayUnion(me)), SetOptions.merge())
                batch.commit().addOnSuccessListener { onDone(true) }.addOnFailureListener { onDone(false) }
            }
            .addOnFailureListener { onDone(false) }
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
