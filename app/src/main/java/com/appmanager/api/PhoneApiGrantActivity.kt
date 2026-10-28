package com.appmanager.api

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.appmanager.Prefs
import com.appmanager.R
import com.appmanager.ThemeManager
import com.appmanager.databinding.ActivityPhoneapiGrantBinding

/**
 * PhoneAPI asks for a local API key (`com.phoneapi.action.GRANT_KEY`, provider contract §3) with
 * `startActivityForResult`. The key travels only as this activity's result, never a file, a
 * broadcast or the clipboard.
 *
 * Any caller that is not PhoneAPI's release build gets RESULT_CANCELED and sees nothing. Otherwise
 * the owner picks what the key may do, every switch starting off, and on Allow App Manager mints
 * PhoneAPI's own key and proof key and switches the local API on, since that is what they agreed to.
 */
class PhoneApiGrantActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPhoneapiGrantBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        val prefs = Prefs(this)
        ThemeManager.apply(prefs.themeMode)
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)

        val caller = callingPackage
        if (intent?.action != PhoneApiLink.ACTION_GRANT_KEY || !PhoneApiLink.isTrustedCaller(this, caller)) {
            Log.w(TAG, "phoneapi: key request refused (" +
                (caller?.let { "$it is not PhoneAPI's release build" } ?: "not started for a result") + ")")
            finish()
            return
        }

        ThemeManager.applyPalette(this, prefs.palette)
        binding = ActivityPhoneapiGrantBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val body = StringBuilder(getString(R.string.phoneapi_body))
        if (!ApiPrefs.enabled(this)) body.append(' ').append(getString(R.string.phoneapi_turns_on))
        if (PhoneApiLink.linked(this)) body.append(' ').append(getString(R.string.phoneapi_replaces))
        binding.body.text = body

        // Everything starts off (or as PhoneAPI's current key has it), and a key that may do nothing
        // isn't worth minting.
        PhoneApiLink.grant(this)?.let {
            binding.see.isChecked = it.see
            binding.change.isChecked = it.change
        }
        val sync = { binding.allow.isEnabled = binding.see.isChecked || binding.change.isChecked }
        binding.see.setOnCheckedChangeListener { _, _ -> sync() }
        binding.change.setOnCheckedChangeListener { _, _ -> sync() }
        sync()

        binding.deny.setOnClickListener {
            Log.i(TAG, "phoneapi: key request declined")
            finish()
        }
        binding.allow.setOnClickListener { allow() }
    }

    private fun allow() {
        val (apiKey, proofKey) = PhoneApiLink.create(
            this,
            see = binding.see.isChecked,
            change = binding.change.isChecked,
            eventPort = intent.getIntExtra(PhoneApiLink.EXTRA_EVENT_PORT, 0),
            eventKey = intent.getStringExtra(PhoneApiLink.EXTRA_EVENT_KEY)
        )
        ApiPrefs.setEnabled(this, true)
        ApiService.startIfEnabled(this)
        Log.i(TAG, "phoneapi: key granted")
        setResult(
            RESULT_OK,
            Intent()
                .putExtra("api_key", apiKey)
                .putExtra("proof_key", proofKey)
                .putExtra("port", ApiPrefs.port(this))
        )
        finish()
    }

    private companion object {
        const val TAG = "AppManagerApi"
    }
}
