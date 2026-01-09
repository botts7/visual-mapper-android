package com.visualmapper.companion.ui

import android.content.Intent
import android.os.Bundle
import android.os.CountDownTimer
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.visualmapper.companion.R
import com.visualmapper.companion.security.AppLockManager
import com.visualmapper.companion.security.SecurePreferences

/**
 * Lock Screen Activity
 *
 * Shows when app lock is enabled. Supports:
 * - PIN entry
 * - Biometric authentication
 * - Lockout countdown
 */
class LockScreenActivity : AppCompatActivity() {

    private lateinit var securePrefs: SecurePreferences
    private lateinit var appLockManager: AppLockManager

    private lateinit var txtTitle: TextView
    private lateinit var txtError: TextView
    private lateinit var txtLockout: TextView
    private lateinit var layoutPinPad: View
    private lateinit var layoutLockout: View
    private lateinit var btnBiometric: ImageButton
    private lateinit var txtPinDisplay: TextView
    private lateinit var btnDelete: ImageButton

    private var enteredPin = StringBuilder()
    private var lockoutTimer: CountDownTimer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_lock_screen)

        securePrefs = SecurePreferences(this)
        appLockManager = AppLockManager(this, securePrefs)

        setupUI()
        checkLockoutState()
        attemptBiometricIfAvailable()
    }

    private fun setupUI() {
        txtTitle = findViewById(R.id.txtTitle)
        txtError = findViewById(R.id.txtError)
        txtLockout = findViewById(R.id.txtLockout)
        layoutPinPad = findViewById(R.id.layoutPinPad)
        layoutLockout = findViewById(R.id.layoutLockout)
        btnBiometric = findViewById(R.id.btnBiometric)
        txtPinDisplay = findViewById(R.id.txtPinDisplay)
        btnDelete = findViewById(R.id.btnDelete)

        // Setup PIN pad buttons
        val pinButtons = listOf(
            R.id.btn0, R.id.btn1, R.id.btn2, R.id.btn3, R.id.btn4,
            R.id.btn5, R.id.btn6, R.id.btn7, R.id.btn8, R.id.btn9
        )

        pinButtons.forEachIndexed { index, id ->
            val digit = if (id == R.id.btn0) 0 else index
            findViewById<Button>(id).setOnClickListener { onDigitPressed(digit) }
        }

        btnDelete.setOnClickListener { onDeletePressed() }

        // Biometric button
        if (appLockManager.isBiometricEnabled() && appLockManager.isBiometricAvailable()) {
            btnBiometric.visibility = View.VISIBLE
            btnBiometric.setOnClickListener { attemptBiometricIfAvailable() }
        } else {
            btnBiometric.visibility = View.GONE
        }

        updatePinDisplay()
    }

    private fun onDigitPressed(digit: Int) {
        if (enteredPin.length >= 8) return // Max 8 digits

        enteredPin.append(digit)
        updatePinDisplay()

        // Auto-verify when PIN is at least 4 digits
        if (enteredPin.length >= 4) {
            verifyPin()
        }
    }

    private fun onDeletePressed() {
        if (enteredPin.isNotEmpty()) {
            enteredPin.deleteCharAt(enteredPin.length - 1)
            updatePinDisplay()
            txtError.visibility = View.GONE
        }
    }

    private fun updatePinDisplay() {
        txtPinDisplay.text = "•".repeat(enteredPin.length)
    }

    private fun verifyPin() {
        val pin = enteredPin.toString()
        if (appLockManager.verifyPin(pin)) {
            onUnlocked()
        } else {
            txtError.text = "Incorrect PIN"
            txtError.visibility = View.VISIBLE
            enteredPin.clear()
            updatePinDisplay()

            // Check if now in lockout
            if (appLockManager.isInLockout()) {
                showLockout()
            }
        }
    }

    private fun checkLockoutState() {
        if (appLockManager.isInLockout()) {
            showLockout()
        } else {
            hideLockout()
        }
    }

    private fun showLockout() {
        layoutPinPad.visibility = View.GONE
        layoutLockout.visibility = View.VISIBLE
        txtError.visibility = View.GONE

        lockoutTimer?.cancel()
        lockoutTimer = object : CountDownTimer(
            appLockManager.getRemainingLockoutSeconds() * 1000L,
            1000
        ) {
            override fun onTick(millisUntilFinished: Long) {
                val seconds = (millisUntilFinished / 1000).toInt()
                txtLockout.text = "Too many attempts\nTry again in $seconds seconds"
            }

            override fun onFinish() {
                hideLockout()
            }
        }.start()
    }

    private fun hideLockout() {
        layoutPinPad.visibility = View.VISIBLE
        layoutLockout.visibility = View.GONE
        lockoutTimer?.cancel()
    }

    private fun attemptBiometricIfAvailable() {
        if (!appLockManager.isBiometricEnabled() || !appLockManager.isBiometricAvailable()) {
            return
        }

        appLockManager.authenticateWithBiometric(
            activity = this,
            onSuccess = { onUnlocked() },
            onError = { error ->
                // Show PIN pad on biometric error
                txtError.text = error
                txtError.visibility = View.VISIBLE
            }
        )
    }

    private fun onUnlocked() {
        // Navigate to main activity
        val intent = Intent(this, MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        lockoutTimer?.cancel()
    }

    // Prevent back button from bypassing lock
    override fun onBackPressed() {
        super.onBackPressed()
        // Do nothing - user must authenticate
    }
}
