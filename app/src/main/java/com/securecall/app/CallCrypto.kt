package com.securecall.app

import android.util.Base64
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Arrays
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Protects the call-setup codes (the WebRTC offer/answer) with a shared random code.
 *  - code: 26 random chars from a 32-letter alphabet = 130 bits (generated here, never typed by a human)
 *  - key  = HKDF-SHA256(code) -> AES-256-GCM; fresh random 12-byte IV for every code
 *  - AAD binds the message kind ("offer"/"answer"); the answer is also bound to the SHA-256 of the offer
 *  - a timestamp inside the encrypted payload limits replay (30 minutes)
 * Because only the holder of the shared code can produce a valid offer/answer, nobody can swap the
 * DTLS fingerprint inside it, which is what makes the media channel man-in-the-middle proof.
 */
object CallCrypto {
    private val rnd = SecureRandom()
    private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    private const val PREFIX = "SKC1:"
    private const val MAX_AGE_MS = 30L * 60 * 1000

    fun newCode(): String {
        val c = CharArray(26) { ALPHABET[rnd.nextInt(ALPHABET.length)] }
        return String(c).chunked(5).joinToString("-")
    }

    fun normalize(s: String): String = s.uppercase().filter { it in ALPHABET }
    fun isValid(s: String): Boolean = normalize(s).length == 26
    /** A human-typed code like AAAAA... would be guessable; require a reasonably varied code. */
    fun isStrong(s: String): Boolean = isValid(s) && normalize(s).toSet().size >= 10

    /** Mailbox name derived from the shared code (independent of the encryption key). */
    fun roomId(code: String): String {
        val h = hmac("SecureCall-v1-room".toByteArray(), normalize(code).toByteArray())
        return "sc-" + h.copyOf(20).joinToString("") { "%02x".format(it) }
    }

    fun clean(s: String): String = s.filter { !it.isWhitespace() }
    fun digest(blob: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(clean(blob).toByteArray())

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val m = Mac.getInstance("HmacSHA256")
        m.init(SecretKeySpec(key, "HmacSHA256"))
        return m.doFinal(data)
    }

    private fun deriveKey(code: String): ByteArray {
        val prk = hmac("SecureCall-v1-salt".toByteArray(), normalize(code).toByteArray())
        val k = hmac(prk, "SecureCall-v1-key".toByteArray() + byteArrayOf(1))
        Arrays.fill(prk, 0)
        return k
    }

    private fun aad(kind: String, bind: ByteArray?): ByteArray =
        ("SKC1|" + kind).toByteArray() + (bind ?: ByteArray(0))

    fun seal(code: String, kind: String, bind: ByteArray?, sdp: String): String {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(sdp.toByteArray()) }
        val ts = ByteBuffer.allocate(8).putLong(System.currentTimeMillis()).array()
        val plain = ts + bos.toByteArray()
        val iv = ByteArray(12).also { rnd.nextBytes(it) }
        val key = deriveKey(code)
        try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
            c.updateAAD(aad(kind, bind))
            val ct = c.doFinal(plain)
            return PREFIX + Base64.encodeToString(iv + ct, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        } finally {
            Arrays.fill(key, 0); Arrays.fill(plain, 0)
        }
    }

    /** Throws on a wrong code, a tampered/foreign message, a wrong kind/binding, or an expired message. */
    fun open(code: String, kind: String, bind: ByteArray?, blob: String, maxAgeMs: Long = MAX_AGE_MS): String {
        val b = clean(blob)
        require(b.startsWith(PREFIX)) { "bad format" }
        val raw = Base64.decode(b.removePrefix(PREFIX), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        require(raw.size > 12 + 16 + 8) { "too short" }
        val iv = raw.copyOfRange(0, 12)
        val key = deriveKey(code)
        val plain: ByteArray
        try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
            c.updateAAD(aad(kind, bind))
            plain = c.doFinal(raw, 12, raw.size - 12)
        } finally {
            Arrays.fill(key, 0)
        }
        try {
            val ts = ByteBuffer.wrap(plain, 0, 8).long
            require(Math.abs(System.currentTimeMillis() - ts) < maxAgeMs) { "expired" }
            val gz = plain.copyOfRange(8, plain.size)
            return String(GZIPInputStream(ByteArrayInputStream(gz)).readBytes())
        } finally {
            Arrays.fill(plain, 0)
        }
    }
}
