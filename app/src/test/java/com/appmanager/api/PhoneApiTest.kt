package com.appmanager.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneApiTest {

    private val owner = "o".repeat(48)
    private val phoneKey = "p".repeat(48)

    private fun router(see: Boolean, change: Boolean) =
        ApiRouter(FakeBackend(), { owner }, { PhoneApiGrant(phoneKey, see, change) })

    private fun call(r: ApiRouter, method: String, path: String, key: String) =
        r.handle(ApiRequest(method, path, headers = mapOf("x-api-key" to key))).status

    @Test
    fun `proof is base64url HMAC-SHA256 of the challenge`() {
        val key = ByteArray(32) { it.toByte() }
        assertEquals("ydm-OFuPqcA55O_1PNtRCLfJaD2hseII0m5oeMXbkCg", PhoneApi.proof(key, "abc123"))
        assertEquals(
            mapOf(PhoneApi.PROOF_HEADER to "ydm-OFuPqcA55O_1PNtRCLfJaD2hseII0m5oeMXbkCg"),
            PhoneApi.proofHeader(mapOf("x-phoneapi-challenge" to "abc123"), key)
        )
    }

    @Test
    fun `no proof without a key or a usable challenge`() {
        val key = ByteArray(32)
        assertTrue(PhoneApi.proofHeader(mapOf("x-phoneapi-challenge" to "abc"), null).isEmpty())
        assertTrue(PhoneApi.proofHeader(emptyMap(), key).isEmpty())
        assertTrue(PhoneApi.proofHeader(null, key).isEmpty())
        assertNull(PhoneApi.usableChallenge("a\u0001b"))
        assertNull(PhoneApi.usableChallenge("x".repeat(257)))
    }

    @Test
    fun `see allows GETs only`() {
        val r = router(see = true, change = false)
        assertEquals(200, call(r, "GET", "/api/apps", phoneKey))
        assertEquals(403, call(r, "POST", "/api/apps/reload", phoneKey))
    }

    @Test
    fun `change allows everything but GETs`() {
        val r = router(see = false, change = true)
        assertEquals(403, call(r, "GET", "/api/apps", phoneKey))
        assertEquals(200, call(r, "POST", "/api/apps/reload", phoneKey))
    }

    @Test
    fun `the owner key is never limited and a wrong key is a 401`() {
        val r = router(see = false, change = false)
        assertEquals(200, call(r, "GET", "/api/apps", owner))
        assertEquals(200, call(r, "POST", "/api/apps/reload", owner))
        assertEquals(401, call(r, "GET", "/api/apps", "q".repeat(48)))
        assertEquals(200, r.handle(ApiRequest("GET", "/api/health")).status)
    }

    @Test
    fun `no PhoneAPI grant opens nothing`() {
        val r = ApiRouter(FakeBackend(), { owner }, { null })
        assertEquals(401, call(r, "GET", "/api/apps", phoneKey))
    }
}
