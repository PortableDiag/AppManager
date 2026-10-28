package com.appmanager.api

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * App Manager's side of the PhoneAPI link (provider contract §3): the key PhoneAPI calls us with,
 * what that key may do, the proof key we answer its challenges with, and where to reach PhoneAPI.
 *
 * PhoneAPI's key is separate from the owner's own key, so linking or unlinking PhoneAPI never
 * breaks a script, and a second handshake replaces only PhoneAPI's key. SharedPreferences, like
 * [ApiPrefs], because the socket threads read it synchronously. Not part of the settings export.
 */
object PhoneApiLink {

    private const val TAG = "AppManagerApi"

    const val PACKAGE = "com.phoneapi.app"
    const val ACTION_GRANT_KEY = "com.phoneapi.action.GRANT_KEY"
    const val EXTRA_EVENT_PORT = "phoneapi_port"
    const val EXTRA_EVENT_KEY = "phoneapi_event_key"

    /**
     * PhoneAPI's release certificate (keystore-manager record "PhoneAPI"). A debug build of
     * PhoneAPI is signed by the debug key and is refused, as PhoneAPI refuses debug builds of
     * providers.
     */
    private val CERT_SHA256: ByteArray =
        "AD:86:A7:04:86:39:86:F3:A4:ED:10:66:00:F1:EC:C2:19:EE:31:82:68:D4:84:BB:54:E9:07:9C:E3:B8:E0:02"
            .split(':').map { it.toInt(16).toByte() }.toByteArray()

    private const val FILE = "appmanager_phoneapi"
    private const val KEY_API = "api_key"
    private const val KEY_PROOF = "proof_key"
    private const val KEY_SEE = "see"
    private const val KEY_CHANGE = "change"
    private const val KEY_PORT = "phoneapi_port"
    private const val KEY_EVENT = "phoneapi_event_key"

    private fun sp(c: Context) = c.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun linked(c: Context): Boolean = sp(c).getString(KEY_API, "").orEmpty().isNotEmpty()

    /** PhoneAPI's key and what it may do, or null when PhoneAPI holds no key. */
    fun grant(c: Context): PhoneApiGrant? {
        val p = sp(c)
        val key = p.getString(KEY_API, "").orEmpty()
        if (key.isEmpty()) return null
        return PhoneApiGrant(key, p.getBoolean(KEY_SEE, false), p.getBoolean(KEY_CHANGE, false))
    }

    fun proofKey(c: Context): ByteArray? = PhoneApi.fromB64url(sp(c).getString(KEY_PROOF, null))

    /**
     * Mints a fresh key and 32-byte proof key for PhoneAPI, replacing any earlier pair, and keeps
     * the two values PhoneAPI sent us. Returns (API key, proof key as base64url).
     */
    fun create(c: Context, see: Boolean, change: Boolean, eventPort: Int, eventKey: String?): Pair<String, String> {
        val api = ApiPrefs.newKey()
        val proof = PhoneApi.b64url(ByteArray(32).also { SecureRandom().nextBytes(it) })
        sp(c).edit()
            .putString(KEY_API, api)
            .putString(KEY_PROOF, proof)
            .putBoolean(KEY_SEE, see)
            .putBoolean(KEY_CHANGE, change)
            .putInt(KEY_PORT, eventPort)
            .putString(KEY_EVENT, eventKey)
            .commit()
        return api to proof
    }

    /** Forget PhoneAPI: its key and proof key stop working at once. The owner's key is untouched. */
    fun unlink(c: Context) {
        sp(c).edit().clear().commit()
        Log.i(TAG, "phoneapi: key dropped")
    }

    /**
     * Android runs no code on uninstall, so whoever next owns the package name would inherit the
     * key. Drop it when PhoneAPI is gone or no longer signed by the pin.
     */
    fun dropIfGone(c: Context) {
        if (linked(c) && !signedByPin(c, PACKAGE)) unlink(c)
    }

    /** True when [pkg] is PhoneAPI's release build, signed by the pinned certificate. */
    fun isTrustedCaller(c: Context, pkg: String?): Boolean = pkg == PACKAGE && signedByPin(c, pkg)

    private fun signedByPin(c: Context, pkg: String): Boolean {
        val pm = c.packageManager
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pm.hasSigningCertificate(pkg, CERT_SHA256, PackageManager.CERT_INPUT_SHA256)
            } else {
                @Suppress("DEPRECATION")
                val sigs = pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES).signatures
                // One signer, and it is the pinned one. More than one signer is not PhoneAPI.
                sigs != null && sigs.size == 1 &&
                    MessageDigest.isEqual(MessageDigest.getInstance("SHA-256").digest(sigs[0].toByteArray()), CERT_SHA256)
            }
        }.getOrDefault(false)
    }
}

/** PhoneAPI uninstalled: its key goes with it. */
class PhoneApiGoneReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.data?.schemeSpecificPart != PhoneApiLink.PACKAGE) return
        PhoneApiLink.dropIfGone(context)
    }
}
