package com.example.pushup

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID

/** Giriş yapmış kullanıcının o an elindeki oturum bilgisi. */
data class AuthSession(
    val username: String,
    val displayName: String,
    val sessionToken: String,
    val photoBase64: String?
)

/**
 * Basit, backend'siz (sadece Firestore) kullanıcı sistemi.
 *
 * - `users/{username}` dokümanında: passwordHash, salt, sessionToken, photoBase64 tutulur.
 * - Kayıt olurken transaction ile "bu kullanıcı adı zaten var mı" kontrolü yapılır ->
 *   aynı adla iki hesap açılamaz.
 * - Giriş yapılınca sessionToken YENİDEN üretilip dokümana yazılır. Eski cihazda açık
 *   olan oturum, kendi doküman dinleyicisinde token'ın değiştiğini görüp otomatik olarak
 *   dışarı atılır (bkz. listenForKick) -> "aynı hesapla 2 kişi aynı anda giremez" kuralı.
 *
 * DÜRÜST NOT: Bu proje Cloud Functions / gerçek bir auth sunucusu kullanmıyor (README'deki
 * felsefeyle aynı - küçük arkadaş grubu projesi). Şifre hash'i client tarafında hesaplanıp
 * Firestore'a yazılıyor; firestore.rules açık olduğu için teorik olarak herkes
 * passwordHash+salt'ı okuyabilir. Küçük ölçek için kabul edilebilir bir risk, halka açık/
 * büyük bir kullanıcı kitlesine açmadan önce gerçek bir Auth sistemine (Firebase Auth,
 * Cloud Functions + App Check vb.) geçmek gerekir.
 */
class AuthClient {

    private val db = FirebaseFirestore.getInstance()
    private val usersRef = db.collection("users")

    fun register(
        username: String,
        password: String,
        photoBase64: String?,
        onResult: (Result<AuthSession>) -> Unit
    ) {
        val id = normalize(username)
        if (id.isBlank()) {
            onResult(Result.failure(Exception("invalid_username")))
            return
        }
        val docRef = usersRef.document(id)
        val salt = generateSalt()
        val hash = hashPassword(password, salt)
        val token = UUID.randomUUID().toString()

        db.runTransaction { txn ->
            val snap = txn.get(docRef)
            if (snap.exists()) throw IllegalStateException("username_taken")
            val data = hashMapOf(
                "username" to id,
                "displayName" to username.trim(),
                "passwordHash" to hash,
                "salt" to salt,
                "sessionToken" to token,
                "photoBase64" to (photoBase64 ?: ""),
                "createdAt" to System.currentTimeMillis()
            )
            txn.set(docRef, data)
        }.addOnSuccessListener {
            onResult(Result.success(AuthSession(id, username.trim(), token, photoBase64)))
        }.addOnFailureListener { e ->
            val code = if (e.message?.contains("username_taken") == true) "username_taken" else "register_failed"
            onResult(Result.failure(Exception(code)))
        }
    }

    fun login(username: String, password: String, onResult: (Result<AuthSession>) -> Unit) {
        val id = normalize(username)
        val docRef = usersRef.document(id)
        docRef.get()
            .addOnSuccessListener { snap ->
                if (!snap.exists()) {
                    onResult(Result.failure(Exception("not_found")))
                    return@addOnSuccessListener
                }
                val salt = snap.getString("salt") ?: ""
                val storedHash = snap.getString("passwordHash") ?: ""
                val computed = hashPassword(password, salt)
                if (computed != storedHash) {
                    onResult(Result.failure(Exception("wrong_password")))
                    return@addOnSuccessListener
                }
                val newToken = UUID.randomUUID().toString()
                val displayName = snap.getString("displayName") ?: id
                val photo = snap.getString("photoBase64")

                // Yeni token yazmak = başka bir cihazda açık olan eski oturumu geçersiz kılmak.
                docRef.update("sessionToken", newToken)
                    .addOnSuccessListener {
                        onResult(Result.success(AuthSession(id, displayName, newToken, photo?.ifBlank { null })))
                    }
                    .addOnFailureListener { onResult(Result.failure(it)) }
            }
            .addOnFailureListener { onResult(Result.failure(it)) }
    }

    /** Uygulama açılışında kaydedilmiş oturumun hâlâ geçerli olup olmadığını kontrol eder. */
    fun validateSession(
        username: String,
        sessionToken: String,
        onResult: (valid: Boolean, displayName: String?, photoBase64: String?) -> Unit
    ) {
        usersRef.document(normalize(username)).get()
            .addOnSuccessListener { snap ->
                val ok = snap.exists() && snap.getString("sessionToken") == sessionToken
                onResult(ok, snap.getString("displayName"), snap.getString("photoBase64")?.ifBlank { null })
            }
            .addOnFailureListener { onResult(false, null, null) }
    }

    /** Kullanıcının doküman değişimini dinler; token değişirse (başka cihaz login oldu) tetiklenir. */
    fun listenForKick(username: String, myToken: String, onKicked: () -> Unit): ListenerRegistration {
        return usersRef.document(normalize(username)).addSnapshotListener { snap, _ ->
            val remoteToken = snap?.getString("sessionToken")
            if (remoteToken != null && remoteToken != myToken) {
                onKicked()
            }
        }
    }

    fun updatePhoto(username: String, photoBase64: String, onDone: (Boolean) -> Unit = {}) {
        usersRef.document(normalize(username))
            .set(mapOf("photoBase64" to photoBase64), SetOptions.merge())
            .addOnSuccessListener { onDone(true) }
            .addOnFailureListener { onDone(false) }
    }

    /** Çıkış yapınca sunucudaki token'ı da temizler, böylece eski token'ın hiçbir işe yaramaz. */
    fun logout(username: String, onDone: () -> Unit = {}) {
        usersRef.document(normalize(username))
            .set(mapOf("sessionToken" to ""), SetOptions.merge())
            .addOnCompleteListener { onDone() }
    }

    private fun normalize(username: String) = username.trim().lowercase()

    companion object {
        fun hashPassword(password: String, salt: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val bytes = digest.digest((salt + password).toByteArray(Charsets.UTF_8))
            return bytes.joinToString("") { "%02x".format(it) }
        }

        fun generateSalt(): String {
            val bytes = ByteArray(16)
            SecureRandom().nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
}
