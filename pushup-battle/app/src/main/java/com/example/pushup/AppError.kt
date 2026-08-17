package com.example.pushup

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/**
 * Uygulama genelinde kullanılan, kısa hata kodları.
 * "RTC-1xx" -> WebRTC bağlantı sorunları, "NET-xxx" -> internet sorunları,
 * "SIG-2xx" -> sinyalleşme (Firestore offer/answer/ICE) sorunları.
 * Ekranda gösterilen her hata Logcat'e de [KOD] etiketiyle yazılır, "adb logcat -s PushUpError"
 * ile filtrelenebilir.
 */
object AppError {
    const val NET_OFFLINE = "NET-001"

    const val RTC_ICE_FAILED = "RTC-101"
    const val RTC_ICE_DISCONNECTED = "RTC-102"
    const val RTC_PEER_FAILED = "RTC-103"
    const val RTC_PEER_CLOSED = "RTC-104"
    const val RTC_PEER_DISCONNECTED = "RTC-105"

    const val SIG_ICE_GATHER_FAILED = "SIG-201"

    const val RTC_SDP_FAILED = "RTC-106"
    const val RTC_CAMERA_ERROR = "RTC-107"

    fun message(code: String): String = when (code) {
        NET_OFFLINE -> "İnternet bağlantısı yok"
        RTC_ICE_FAILED -> "Bağlantı kurulamadı (ICE başarısız)"
        RTC_ICE_DISCONNECTED -> "Bağlantı zayıf (ICE koptu)"
        RTC_PEER_FAILED -> "Karşı tarafla bağlantı başarısız oldu"
        RTC_PEER_CLOSED -> "Bağlantı kapandı"
        RTC_PEER_DISCONNECTED -> "Bağlantı koptu, toparlanmaya çalışılıyor"
        SIG_ICE_GATHER_FAILED -> "Ağ yolu bulunamadı"
        RTC_SDP_FAILED -> "Bağlantı teklifi oluşturulamadı"
        RTC_CAMERA_ERROR -> "Kameraya erişilemedi"
        else -> "Bilinmeyen hata"
    }

    /** Logcat'te "adb logcat -s PushUpError" ile filtrelenebilir tek log noktası. */
    fun log(code: String, detail: String = "") {
        Log.e("PushUpError", "[$code] ${message(code)}${if (detail.isNotBlank()) " — $detail" else ""}")
    }
}

/** Anlık internet var mı yok mu (gerçek internet erişimi doğrulanmış - sadece Wi-Fi'ye bağlı olmak yetmez). */
fun isOnline(context: Context): Boolean {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
    val network = cm.activeNetwork ?: return false
    val capabilities = cm.getNetworkCapabilities(network) ?: return false
    return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}

/** Composable içinde internet durumunu canlı takip etmek için. Bağlantı değişince otomatik günceller. */
@Composable
fun rememberIsOnline(): Boolean {
    val context = LocalContext.current
    var online by remember { mutableStateOf(isOnline(context)) }

    DisposableEffect(Unit) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                online = isOnline(context)
            }
            override fun onLost(network: Network) {
                online = isOnline(context)
                if (!online) AppError.log(AppError.NET_OFFLINE, "network lost")
            }
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                online = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            }
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        cm.registerNetworkCallback(request, callback)
        onDispose { cm.unregisterNetworkCallback(callback) }
    }

    return online
}
