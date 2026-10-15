package com.example.tvremote.core.network

import com.example.tvremote.core.storage.SecurePairingStore
import okhttp3.OkHttpClient
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/** A dedicated per-TV client using trust-on-first-use for the TV's self-signed certificate. */
object TvTls {
    fun client(host: String, store: SecurePairingStore): OkHttpClient {
        val trustManager = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                require(chain.isNotEmpty()) { "A TV não apresentou certificado" }
                chain.first().checkValidity()
                val fingerprint = MessageDigest.getInstance("SHA-256")
                    .digest(chain.first().encoded)
                    .joinToString("") { "%02x".format(it) }
                val stored = store.getCertificatePin(host)
                if (stored == null) store.saveCertificatePin(host, fingerprint)
                else require(stored == fingerprint) { "O certificado da TV mudou. Esqueça e pareie novamente." }
            }
        }
        val context = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(trustManager), SecureRandom())
        }
        return OkHttpClient.Builder()
            .sslSocketFactory(context.socketFactory, trustManager)
            .hostnameVerifier { requested, _ -> requested.equals(host, ignoreCase = true) }
            .retryOnConnectionFailure(true)
            .build()
    }
}
