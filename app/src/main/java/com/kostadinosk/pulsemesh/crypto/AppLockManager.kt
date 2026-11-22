// Copyright (c) 2026 Konstantinos Kourtis
// SPDX-License-Identifier: GPL-3.0-only

package com.kostadinosk.pulsemesh.crypto

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.kostadinosk.pulsemesh.util.DevicePreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

enum class AppLockTimeout(val label: String, val timeoutMs: Long) {
    OFF("Off", -1L),
    IMMEDIATE("Immediate", 0L),
    ONE_MIN("1 Minute", 60_000L),
    FIVE_MIN("5 Minutes", 5 * 60_000L);

    companion object {
        fun fromName(raw: String?): AppLockTimeout =
            entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) } ?: OFF
    }
}

/**
 * Manages BiometricPrompt + PBKDF2-backed PIN App Lock for PulseMesh v1.5.0.
 */
class AppLockManager(private val prefs: DevicePreferences) {

    private val _isLocked = MutableStateFlow(shouldInitiallyLock())
    val isLocked: StateFlow<Boolean> = _isLocked.asStateFlow()

    @Volatile
    private var lastBackgroundedAtMs: Long = 0L

    private fun shouldInitiallyLock(): Boolean {
        val timeout = prefs.appLockTimeout
        return timeout != AppLockTimeout.OFF && hasConfiguredCredential()
    }

    fun hasConfiguredCredential(): Boolean {
        return prefs.appLockPinHash.isNotBlank() || prefs.isBiometricUnlockEnabled
    }

    fun isAppLockActive(): Boolean {
        return prefs.appLockTimeout != AppLockTimeout.OFF && hasConfiguredCredential()
    }

    fun onAppBackgrounded(nowMs: Long = System.currentTimeMillis()) {
        if (lastBackgroundedAtMs == 0L) {
            lastBackgroundedAtMs = nowMs
        }
        if (prefs.appLockTimeout == AppLockTimeout.IMMEDIATE && hasConfiguredCredential()) {
            _isLocked.value = true
        }
    }

    fun onAppForegrounded(nowMs: Long = System.currentTimeMillis()) {
        val timeout = prefs.appLockTimeout
        if (timeout == AppLockTimeout.OFF || !hasConfiguredCredential()) {
            _isLocked.value = false
            lastBackgroundedAtMs = 0L
            return
        }
        val bgAt = lastBackgroundedAtMs
        lastBackgroundedAtMs = 0L
        if (bgAt > 0L && (nowMs - bgAt) >= timeout.timeoutMs) {
            _isLocked.value = true
        }
    }

    fun lockNow() {
        if (hasConfiguredCredential() && prefs.appLockTimeout != AppLockTimeout.OFF) {
            _isLocked.value = true
        }
    }

    fun unlockWithPin(pin: String): Boolean {
        val hash = prefs.appLockPinHash
        val saltHex = prefs.appLockPinSaltHex
        if (hash.isBlank() || saltHex.isBlank()) return false
        val candidate = hashAppLockPin(pin.trim(), saltHex)
        val ok = constantTimeEquals(candidate, hash)
        if (ok) {
            _isLocked.value = false
            lastBackgroundedAtMs = 0L
        }
        return ok
    }

    fun setAppLockPin(pin: String) {
        val clean = pin.trim()
        require(clean.length >= 4) { "App Lock PIN must be at least 4 characters" }
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val saltHex = salt.joinToString("") { "%02x".format(it) }
        val hash = hashAppLockPin(clean, saltHex)
        prefs.appLockPinSaltHex = saltHex
        prefs.appLockPinHash = hash
        if (prefs.appLockTimeout == AppLockTimeout.OFF) {
            prefs.appLockTimeout = AppLockTimeout.IMMEDIATE
        }
    }

    fun clearAppLockCredentials() {
        prefs.appLockPinHash = ""
        prefs.appLockPinSaltHex = ""
        prefs.isBiometricUnlockEnabled = false
        prefs.appLockTimeout = AppLockTimeout.OFF
        _isLocked.value = false
    }

    fun canUseBiometrics(context: Context): Boolean {
        val bm = BiometricManager.from(context)
        val res = bm.canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
        )
        return res == BiometricManager.BIOMETRIC_SUCCESS
    }

    fun launchBiometricPrompt(
        activity: FragmentActivity,
        title: String = "Unlock PulseMesh",
        subtitle: String = "Authenticate to access encrypted chats, keys & map",
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        val executor = ContextCompat.getMainExecutor(activity)
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                super.onAuthenticationSucceeded(result)
                _isLocked.value = false
                lastBackgroundedAtMs = 0L
                onSuccess()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                super.onAuthenticationError(errorCode, errString)
                onError(errString.toString())
            }

            override fun onAuthenticationFailed() {
                super.onAuthenticationFailed()
                onError("Authentication did not match")
            }
        }
        val prompt = BiometricPrompt(activity, executor, callback)
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
            .build()
        prompt.authenticate(promptInfo)
    }

    companion object {
        fun hashAppLockPin(pin: String, saltHex: String): String {
            val saltBytes = "PM_APPLOCK_V15:$saltHex".toByteArray(Charsets.UTF_8)
            val keyBytes = try {
                val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                factory.generateSecret(PBEKeySpec(pin.toCharArray(), saltBytes, 100_000, 256)).encoded
            } catch (_: Exception) {
                val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1")
                factory.generateSecret(PBEKeySpec(pin.toCharArray(), saltBytes, 100_000, 256)).encoded
            }
            val digest = MessageDigest.getInstance("SHA-256").digest(keyBytes)
            return digest.joinToString("") { "%02x".format(it) }
        }

        private fun constantTimeEquals(a: String, b: String): Boolean {
            if (a.length != b.length) return false
            var diff = 0
            for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
            return diff == 0
        }
    }
}
