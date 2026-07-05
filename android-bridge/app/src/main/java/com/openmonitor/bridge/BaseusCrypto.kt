package com.openmonitor.bridge

import java.security.KeyFactory
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher

object BaseusCrypto {
    private const val LOGIN_PUBLIC_KEY = "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDACE9CZ0ZLsrlF0/QRxnhqufcbAR2Y8CJXKVgGBHL8XyPuSPcUhqJGCO9UE7FlDsq1BFyuqx9iLs786SEAg5BskkAm6BttV5uXQSIFOxFjuz6PRueq++TiP9KCuPOspvWhVuZFJrajeyTVJ65sViiwmnjOUTt/60qJr8Gk4ZqCPwIDAQAB"

    fun encryptLoginPassword(password: String): String {
        if (password.isBlank()) {
            return ""
        }
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        val publicKey = KeyFactory.getInstance("RSA").generatePublic(
            X509EncodedKeySpec(android.util.Base64.decode(LOGIN_PUBLIC_KEY, android.util.Base64.DEFAULT))
        )
        cipher.init(Cipher.ENCRYPT_MODE, publicKey)
        return android.util.Base64.encodeToString(
            cipher.doFinal(password.toByteArray(Charsets.UTF_8)),
            android.util.Base64.NO_WRAP,
        )
    }
}
