package com.findphone.app

import android.util.Base64
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object Crypto {
    const val PREFIX = "FP1:"
    private fun key(code: String): SecretKeySpec {
        val f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val k = f.generateSecret(PBEKeySpec(code.toCharArray(), "findphone-v1".toByteArray(), 20000, 256)).encoded
        return SecretKeySpec(k, "AES")
    }

    fun enc(code: String, plain: String): String {
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key(code), GCMParameterSpec(128, iv))
        return PREFIX + Base64.encodeToString(iv + c.doFinal(plain.toByteArray()), Base64.NO_WRAP or Base64.URL_SAFE)
    }

    fun dec(code: String, body: String): String? = try {
        val raw = Base64.decode(body.removePrefix(PREFIX).trim(), Base64.NO_WRAP or Base64.URL_SAFE)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(code), GCMParameterSpec(128, raw.copyOfRange(0, 12)))
        String(c.doFinal(raw.copyOfRange(12, raw.size)))
    } catch (e: Exception) { null }
}
