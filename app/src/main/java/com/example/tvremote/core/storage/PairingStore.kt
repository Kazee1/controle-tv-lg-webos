package com.example.tvremote.core.storage

interface PairingStore {
    fun getClientKey(host: String): String?
    fun saveClientKey(host: String, key: String)
    fun getCertificatePin(host: String): String?
    fun saveCertificatePin(host: String, pin: String)
}
