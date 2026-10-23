package com.evdev.androidcontrol

import android.accounts.AccountManager
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.lifecycleScope
import com.evdev.androidcontrol.session.SessionManager
import com.evdev.androidcontrol.signaling.FirebaseSignalingClient
import com.evdev.androidcontrol.ui.AndroidControlTheme
import com.evdev.androidcontrol.ui.MainScreen
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var sessionManager: SessionManager
    private var pendingConsentEmail: String? = null

    private val accountPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val selectedEmail = result.data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME)
            if (!selectedEmail.isNullOrBlank()) {
                authenticateAndBind(selectedEmail)
            }
        }
    }

    private val consentLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val email = pendingConsentEmail
        pendingConsentEmail = null
        if (result.resultCode == Activity.RESULT_OK && !email.isNullOrBlank()) {
            authenticateAndBind(email)
        } else {
            Toast.makeText(this, "Google sign-in authorization denied", Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleLogin() {
        @Suppress("DEPRECATION")
        val intent = AccountManager.newChooseAccountIntent(
            null,
            null,
            arrayOf("com.google"),
            false,
            null,
            null,
            null,
            null
        )
        accountPickerLauncher.launch(intent)
    }

    private fun authenticateAndBind(email: String) {
        lifecycleScope.launch {
            when (val res = sessionManager.firebaseSignaling.authenticateAccount(email)) {
                is FirebaseSignalingClient.AuthResult.ConsentRequired -> {
                    pendingConsentEmail = res.email
                    consentLauncher.launch(res.intent)
                }
                is FirebaseSignalingClient.AuthResult.Success -> {
                    sessionManager.onAuthSuccess(res.email, res.firebaseToken)
                }
                is FirebaseSignalingClient.AuthResult.Error -> {
                    Toast.makeText(this@MainActivity, res.message, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sessionManager = SessionManager.getInstance(applicationContext)
        com.evdev.androidcontrol.service.KeepAliveService.startIfNeeded(applicationContext)

        handleIntent(intent)

        setContent {
            AndroidControlTheme {
                val currentUserEmail by sessionManager.currentUserEmail.collectAsState()
                val isConnected by sessionManager.isConnected.collectAsState()
                val sessionStatus by sessionManager.sessionStatus.collectAsState()
                val isConnectionsEnabled by sessionManager.isConnectionsEnabled.collectAsState()
                val controlMode by sessionManager.controlMode.collectAsState()
                val isUserModeOverride by sessionManager.isUserModeOverride.collectAsState()
                val isAdbReachable by sessionManager.isAdbReachable.collectAsState()
                val isDevOptionsEnabled by sessionManager.isDevOptionsEnabled.collectAsState()
                val rxBytes by sessionManager.rxBytes.collectAsState()
                val txBytes by sessionManager.txBytes.collectAsState()

                MainScreen(
                    currentUserEmail = currentUserEmail,
                    isConnected = isConnected,
                    onLogin = { handleLogin() },
                    onLogout = { sessionManager.logout() },
                    isConnectionsEnabled = isConnectionsEnabled,
                    onToggleConnections = { sessionManager.setConnectionsEnabled(it) },
                    controlMode = controlMode,
                    onModeChange = { sessionManager.setControlMode(it, isManual = true) },
                    sessionStatus = sessionStatus,
                    isAdbReachable = isAdbReachable,
                    isDevOptionsEnabled = isDevOptionsEnabled,
                    rxBytes = rxBytes,
                    txBytes = txBytes,
                    onDisconnect = { sessionManager.disconnectSession(clearApproval = true) },
                    isAutoDetected = !isUserModeOverride,
                    onResetAuto = { sessionManager.resetAutoMode() }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        sessionManager.checkAdbStatus()
        com.evdev.androidcontrol.service.KeepAliveService.startIfNeeded(applicationContext)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val uri: Uri? = intent?.data
        if (uri != null) {
            // S5 & R7: Deep link intents are wake triggers; immediately poll RTDB for pending offers
            sessionManager.pollNow()
        }
    }
}
