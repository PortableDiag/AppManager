package com.appmanager.api

import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The Android-free half of the PhoneAPI link (provider contract §2 and §3,
 * PhoneAPI/docs/provider-contract.md): the proof header and what PhoneAPI's key may do.
 * Kept free of Android types so the router and the proof are covered by JVM unit tests.
 */
object PhoneApi {

    const val CHALLENGE_HEADER = "x-phoneapi-challenge"
    const val PROOF_HEADER = "X-PhoneAPI-Proof"

    fun b64url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    /** Null when [s] is not base64url (padded or not). */
    fun fromB64url(s: String?): ByteArray? =
        if (s.isNullOrBlank()) null else runCatching { Base64.getUrlDecoder().decode(s.trim()) }.getOrNull()

    /** `base64url_nopad(HMAC-SHA256(proof_key, challenge_ascii))`. */
    fun proof(proofKey: ByteArray, challenge: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(proofKey, "HmacSHA256"))
        return b64url(mac.doFinal(challenge.toByteArray(Charsets.US_ASCII)))
    }

    /** A challenge worth answering: present, short, printable ASCII. It is hashed, never echoed. */
    fun usableChallenge(raw: String?): String? {
        val c = raw?.trim() ?: return null
        if (c.isEmpty() || c.length > 256) return null
        return if (c.all { it in ' '..'~' }) c else null
    }

    /** The proof header for a request's [headers] (lower-cased keys), or nothing. */
    fun proofHeader(headers: Map<String, String>?, proofKey: ByteArray?): Map<String, String> {
        if (proofKey == null || proofKey.isEmpty()) return emptyMap()
        val challenge = usableChallenge(headers?.get(CHALLENGE_HEADER)) ?: return emptyMap()
        return mapOf(PROOF_HEADER to proof(proofKey, challenge))
    }

    /** Constant-time: the timing of a string compare leaks how much of a key is right. */
    fun sameKey(offered: String, expected: String): Boolean =
        expected.isNotEmpty() && MessageDigest.isEqual(offered.toByteArray(), expected.toByteArray())
}

/**
 * PhoneAPI's own key and what the owner allowed it on the handshake screen: [see] is every GET,
 * [change] everything else. Anything not allowed is a 403. The owner's key is never limited.
 */
data class PhoneApiGrant(val key: String, val see: Boolean, val change: Boolean) {
    fun allows(method: String): Boolean = if (method == "GET" || method == "HEAD") see else change
}
