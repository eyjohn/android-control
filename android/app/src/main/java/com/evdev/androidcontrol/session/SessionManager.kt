package com.evdev.androidcontrol.session

import android.content.Context
import android.content.Intent
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.evdev.androidcontrol.model.ControlMode
import com.evdev.androidcontrol.model.SessionPayload
import com.evdev.androidcontrol.rtc.WebRtcClient
import com.evdev.androidcontrol.service.ControlAccessibilityService
import com.evdev.androidcontrol.service.KeepAliveService
import com.evdev.androidcontrol.signaling.FirebaseSignalingClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

class SessionManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "SessionManager"
        private const val IDLE_BACKSTOP_MS = 600_000L // 10 minutes backstop (F4: >= 2x host 300s)

        @Volatile
        private var INSTANCE: SessionManager? = null

        fun getInstance(context: Context): SessionManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SessionManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val firebaseSignaling = FirebaseSignalingClient(context)

    // StateFlows for UI
    private val _currentUserEmail = MutableStateFlow<String?>(null)
    val currentUserEmail: StateFlow<String?> = _currentUserEmail.asStateFlow()

    private val _sessionStatus = MutableStateFlow("Signed out")
    val sessionStatus: StateFlow<String> = _sessionStatus.asStateFlow()

    private val _controlMode = MutableStateFlow(ControlMode.ACCESSIBILITY)
    val controlMode: StateFlow<ControlMode> = _controlMode.asStateFlow()

    private val _isUserModeOverride = MutableStateFlow(false)
    val isUserModeOverride: StateFlow<Boolean> = _isUserModeOverride.asStateFlow()

    private val _isConnectionsEnabled = MutableStateFlow(true)
    val isConnectionsEnabled: StateFlow<Boolean> = _isConnectionsEnabled.asStateFlow()

    private val _isAdbReachable = MutableStateFlow<Boolean?>(null)
    val isAdbReachable: StateFlow<Boolean?> = _isAdbReachable.asStateFlow()

    private val _isDevOptionsEnabled = MutableStateFlow(false)
    val isDevOptionsEnabled: StateFlow<Boolean> = _isDevOptionsEnabled.asStateFlow()

    private val _rxBytes = MutableStateFlow(0L)
    val rxBytes: StateFlow<Long> = _rxBytes.asStateFlow()

    private val _txBytes = MutableStateFlow(0L)
    val txBytes: StateFlow<Long> = _txBytes.asStateFlow()

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _activeSessionId = MutableStateFlow<String?>(null)
    val activeSessionId: StateFlow<String?> = _activeSessionId.asStateFlow()

    // Internal state
    private var rtcClient: WebRtcClient? = null
    private var adbTlsPort: Int = -1
    private var isNsdAdbWifiActive: Boolean = false
    private var nsdDiscoveryListener: NsdManager.DiscoveryListener? = null
    private var nsdServiceCallback: Any? = null

    // Deduplication tracking (F2)
    private var lastAppliedSessionId: String? = null
    private var lastAppliedSdpHash: Int = 0
    private var lastAppliedTimestampMs: Long = 0L

    // Concurrency control (R2)
    private val approveMutex = Mutex()
    private var inFlightApproveJob: Job? = null

    init {
        val prefsControl = context.getSharedPreferences("prefs_control", Context.MODE_PRIVATE)
        _isConnectionsEnabled.value = prefsControl.getBoolean("connections_enabled", true)

        val prefsAdb = context.getSharedPreferences("adb_state", Context.MODE_PRIVATE)
        adbTlsPort = prefsAdb.getInt("tls_port", -1)

        val prefsAuth = context.getSharedPreferences("auth", Context.MODE_PRIVATE)
        val savedEmail = prefsAuth.getString("user_email", null)
        val savedRefreshToken = prefsAuth.getString("refresh_token", null)

        // R6: Invalidate stale auth from previous installs without refresh token
        if (!savedEmail.isNullOrBlank() && savedRefreshToken.isNullOrBlank()) {
            firebaseSignaling.clearAuth()
            _currentUserEmail.value = null
            _sessionStatus.value = "Signed out — re-login required"
        } else if (!savedEmail.isNullOrBlank()) {
            _currentUserEmail.value = savedEmail
            _sessionStatus.value = if (_isConnectionsEnabled.value) "Listening" else "Connections disabled"
            scope.launch {
                val modeName = if (_controlMode.value == ControlMode.ACCESSIBILITY) "accessibility" else "adb"
                firebaseSignaling.registerDevice(savedEmail, modeName)
            }
        }

        checkAdbStatus()
        startAdbNsdMonitor()
        updateKeepAliveService()
        startBackgroundLoops()
    }

    private fun updateKeepAliveService() {
        if (_isConnectionsEnabled.value) {
            KeepAliveService.startIfNeeded(context)
            KeepAliveService.updateNotification(context, isConnected = _isConnected.value, mode = _controlMode.value.name)
        } else {
            KeepAliveService.stopIfRunning(context)
        }
    }

    private fun startBackgroundLoops() {
        // 1. Session metrics & idle backstop
        scope.launch {
            while (isActive) {
                rtcClient?.let { client ->
                    _rxBytes.value = client.bytesReceived.get()
                    _txBytes.value = client.bytesTransferred.get()

                    val idleMs = System.currentTimeMillis() - client.lastTrafficTime.get()
                    if (_isConnected.value && idleMs >= IDLE_BACKSTOP_MS) {
                        Log.i(TAG, "Idle backstop exceeded ($idleMs ms), tearing down session locally")
                        // F4 & R3: Send teardown notice over control channel first
                        client.sendTeardownNotice()
                        disconnectSession(clearApproval = false)
                    }
                }
                delay(1000)
            }
        }

        // 2. Firebase RTDB polling loop
        scope.launch {
            var lastRegisterMs = 0L
            while (isActive) {
                val email = _currentUserEmail.value
                val connected = _isConnected.value

                if (_isConnectionsEnabled.value && !email.isNullOrBlank()) {
                    if (System.currentTimeMillis() - lastRegisterMs > 60_000L) {
                        lastRegisterMs = System.currentTimeMillis()
                        val modeName = if (_controlMode.value == ControlMode.ACCESSIBILITY) "accessibility" else "adb"
                        firebaseSignaling.registerDevice(email, modeName)
                    }

                    // F2 & R1: Poll less frequently when actively connected (5s vs 1.5s)
                    val pollInterval = if (connected) 5000L else 1500L
                    try {
                        val pollRes = firebaseSignaling.pollPendingOffer()
                        pollRes.onSuccess { offer ->
                            if (offer != null) {
                                handleOffer(offer)
                            }
                        }.onFailure { err ->
                            if (!_isConnected.value) {
                                _sessionStatus.value = err.message ?: "Signaling poll error"
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "RTDB poll exception: ${e.message}")
                    }
                    delay(pollInterval)
                } else {
                    delay(2000)
                }
            }
        }
    }

    // R7: Instant poll trigger for wake deep links
    fun pollNow() {
        scope.launch {
            checkAdbStatus()
            val email = _currentUserEmail.value
            if (_isConnectionsEnabled.value && !email.isNullOrBlank()) {
                val pollRes = firebaseSignaling.pollPendingOffer()
                pollRes.onSuccess { offer ->
                    if (offer != null) handleOffer(offer)
                }
            }
        }
    }

    fun setConnectionsEnabled(enabled: Boolean) {
        _isConnectionsEnabled.value = enabled
        context.getSharedPreferences("prefs_control", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("connections_enabled", enabled)
            .apply()

        updateKeepAliveService()

        if (!enabled) {
            disconnectSession(clearApproval = true)
            _sessionStatus.value = "Connections disabled"
        } else {
            _sessionStatus.value = if (_currentUserEmail.value != null) "Listening" else "Signed out"
        }
    }

    fun setControlMode(mode: ControlMode, isManual: Boolean = true) {
        _isUserModeOverride.value = isManual
        _controlMode.value = mode
        val email = _currentUserEmail.value
        if (!email.isNullOrBlank()) {
            scope.launch {
                val modeName = if (mode == ControlMode.ACCESSIBILITY) "accessibility" else "adb"
                firebaseSignaling.registerDevice(email, modeName)
            }
        }
    }

    fun resetAutoMode() {
        _isUserModeOverride.value = false
        autoSelectBestMode()
    }

    fun autoSelectBestMode() {
        // F7 & R1: Don't change control mode while session is active or user manually overrode
        if (_isUserModeOverride.value || _isConnected.value) return

        val a11yReady = ControlAccessibilityService.isRunning
        val adbReady = _isAdbReachable.value == true

        if (adbReady && !a11yReady) {
            _controlMode.value = ControlMode.ADB
        } else if (a11yReady && !adbReady) {
            _controlMode.value = ControlMode.ACCESSIBILITY
        } else if (adbReady && a11yReady) {
            _controlMode.value = ControlMode.ADB
        }
        updateKeepAliveService()
    }

    fun checkDevOptions(): Boolean {
        val devSetting = try {
            Settings.Global.getInt(context.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1
        } catch (_: Exception) { false }
        val wifiSetting = try {
            Settings.Global.getInt(context.contentResolver, "adb_wifi_enabled", 0) == 1
        } catch (_: Exception) { false }
        val adbSetting = try {
            Settings.Global.getInt(context.contentResolver, Settings.Global.ADB_ENABLED, 0) == 1
        } catch (_: Exception) { false }
        val enabled = devSetting || wifiSetting || adbSetting || isNsdAdbWifiActive || (adbTlsPort > 0)
        _isDevOptionsEnabled.value = enabled
        return enabled
    }

    suspend fun checkWirelessDebugging(): Boolean = withContext(Dispatchers.IO) {
        val wifiSetting = try {
            Settings.Global.getInt(context.contentResolver, "adb_wifi_enabled", 0) == 1
        } catch (_: Exception) { false }
        var port = adbTlsPort
        if (port <= 0) {
            port = context.getSharedPreferences("adb_state", Context.MODE_PRIVATE).getInt("tls_port", -1)
            if (port > 0) adbTlsPort = port
        }
        val port5555Open = WebRtcClient.checkAdbPort("127.0.0.1", 5555, 300)
        var tlsPortOpen = false
        if (port > 0) {
            tlsPortOpen = WebRtcClient.checkAdbPort("127.0.0.1", port, 400)
            if (tlsPortOpen) {
                isNsdAdbWifiActive = true
            } else if (!isNsdAdbWifiActive) {
                saveTlsPort(-1)
            }
        }
        val reachable = wifiSetting || isNsdAdbWifiActive || port5555Open || tlsPortOpen
        _isAdbReachable.value = reachable
        reachable
    }

    fun checkAdbStatus() {
        scope.launch {
            checkDevOptions()
            checkWirelessDebugging()
            autoSelectBestMode()
        }
    }

    private fun saveTlsPort(port: Int) {
        adbTlsPort = port
        context.getSharedPreferences("adb_state", Context.MODE_PRIVATE)
            .edit()
            .putInt("tls_port", port)
            .apply()
    }

    fun startAdbNsdMonitor() {
        if (nsdDiscoveryListener != null) return
        try {
            val nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
            val listener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(regType: String) {}
                override fun onServiceFound(service: NsdServiceInfo) {
                    isNsdAdbWifiActive = true
                    _isDevOptionsEnabled.value = true
                    _isAdbReachable.value = true
                    autoSelectBestMode()

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        try {
                            val cb = object : NsdManager.ServiceInfoCallback {
                                override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {}
                                override fun onServiceUpdated(info: NsdServiceInfo) {
                                    val p = info.port
                                    if (p > 0) saveTlsPort(p)
                                    isNsdAdbWifiActive = true
                                    _isDevOptionsEnabled.value = true
                                    _isAdbReachable.value = true
                                    autoSelectBestMode()
                                }
                                override fun onServiceLost() {
                                    isNsdAdbWifiActive = false
                                    saveTlsPort(-1)
                                    checkAdbStatus()
                                }
                                override fun onServiceInfoCallbackUnregistered() {}
                            }
                            nsdServiceCallback = cb
                            nsdManager.registerServiceInfoCallback(service, context.mainExecutor, cb)
                        } catch (e: Exception) {
                            Log.w(TAG, "registerServiceInfoCallback failed: ${e.message}")
                        }
                    }
                }
                override fun onServiceLost(service: NsdServiceInfo) {
                    isNsdAdbWifiActive = false
                    saveTlsPort(-1)
                    checkAdbStatus()
                }
                override fun onDiscoveryStopped(serviceType: String) {}
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { nsdDiscoveryListener = null }
                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) { nsdDiscoveryListener = null }
            }
            nsdDiscoveryListener = listener
            nsdManager.discoverServices("_adb-tls-connect._tcp", NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            Log.w(TAG, "startAdbNsdMonitor exception: ${e.message}")
        }
    }

    // S5, F2, T4: Offer validation, deduplication, and approval via OfferGate
    fun handleOffer(offer: SessionPayload) {
        val decision = OfferGate.evaluate(
            offer = offer,
            isConnectionsEnabled = _isConnectionsEnabled.value,
            currentUserEmail = _currentUserEmail.value,
            lastAppliedSessionId = lastAppliedSessionId,
            lastAppliedSdpHash = lastAppliedSdpHash,
            lastAppliedTimestampMs = lastAppliedTimestampMs
        )

        when (decision) {
            is OfferGate.Decision.Reject -> {
                Log.w(TAG, "Offer ${offer.sessionId} rejected: ${decision.reason}")
                _sessionStatus.value = decision.reason
                return
            }
            is OfferGate.Decision.IgnoreDuplicate -> {
                Log.i(TAG, "Ignoring duplicate or stale offer for session ${offer.sessionId}")
                return
            }
            is OfferGate.Decision.Accept -> {
                lastAppliedSessionId = offer.sessionId
                lastAppliedSdpHash = offer.sdp.hashCode()
                lastAppliedTimestampMs = offer.timestampMs

                // Honor requested mode if not overridden
                if (!_isUserModeOverride.value && offer.mode.isNotBlank()) {
                    if (offer.mode == "adb" && _isAdbReachable.value == true) {
                        _controlMode.value = ControlMode.ADB
                    } else if (offer.mode == "accessibility" && ControlAccessibilityService.isRunning) {
                        _controlMode.value = ControlMode.ACCESSIBILITY
                    }
                }

                approveSession(offer)
            }
        }
    }

    // R2: Serialized offer approval (prevents concurrent offer collisions)
    private fun approveSession(offer: SessionPayload) {
        if (offer.sdp.isBlank()) {
            _sessionStatus.value = "Error: Missing WebRTC Offer SDP"
            return
        }

        _sessionStatus.value = "Connecting WebRTC (${_controlMode.value} mode)..."

        inFlightApproveJob?.cancel()
        inFlightApproveJob = scope.launch {
            approveMutex.withLock {
                try {
                    var client = rtcClient
                    if (client == null) {
                        client = WebRtcClient(
                            context = context,
                            scope = scope,
                            adbPortResolver = {
                                if (WebRtcClient.checkAdbPort("127.0.0.1", 5555, 150)) {
                                    5555
                                } else if (adbTlsPort > 0) {
                                    adbTlsPort
                                } else {
                                    5555
                                }
                            },
                            onStateChange = {
                                Log.i(TAG, "onStateChange: $it")
                                _sessionStatus.value = it
                            },
                            onConnectionReady = {
                                Log.i(TAG, "onConnectionReady: session connected")
                                _isConnected.value = true
                                _sessionStatus.value = "Connected! WebRTC DataChannels active."
                                KeepAliveService.updateNotification(context, isConnected = true, mode = _controlMode.value.name)
                            },
                            onClosed = {
                                Log.i(TAG, "onClosed: session closed")
                                // R1 & T1: Reset connection state when WebRTC closes (identity check)
                                handleSessionClosed(client)
                                KeepAliveService.updateNotification(context, isConnected = false)
                            }
                        )
                        rtcClient = client
                    }

                    val answerSdp = client.answerOffer(offer.sessionId, offer.sdp, offer.iceServers)
                    val answer = SessionPayload(
                        sessionId = offer.sessionId,
                        sdp = answerSdp,
                        type = "answer",
                        reason = offer.reason,
                        ttlSeconds = offer.ttlSeconds,
                        approved = true,
                        deviceName = firebaseSignaling.deviceName,
                        mode = if (_controlMode.value == ControlMode.ACCESSIBILITY) "accessibility" else "adb",
                        account = _currentUserEmail.value
                    )
                    val b64 = answer.toBase64Gzip()
                    _activeSessionId.value = offer.sessionId

                    // 1. Publish Answer to Firebase RTDB
                    firebaseSignaling.publishAnswer(answer)
                } catch (e: CancellationException) {
                    // Replaced by newer offer
                    throw e
                } catch (e: Exception) {
                    _sessionStatus.value = "Connection error: ${e.message}"
                }
            }
        }
    }

    // R1 & T1: Connection teardown handler with active client identity check
    private fun handleSessionClosed(client: WebRtcClient? = null) {
        if (client != null && rtcClient !== client) return
        Log.i(TAG, "handleSessionClosed executing (previous client=$client)")
        _isConnected.value = false
        _activeSessionId.value = null
        rtcClient = null
        _sessionStatus.value = if (!_isConnectionsEnabled.value) {
            "Connections disabled"
        } else if (_currentUserEmail.value != null) {
            "Ready — always on"
        } else {
            "Signed out"
        }
        autoSelectBestMode()
    }

    fun onAuthSuccess(email: String, firebaseToken: String) {
        _currentUserEmail.value = email
        context.getSharedPreferences("auth", Context.MODE_PRIVATE)
            .edit()
            .putString("user_email", email)
            .apply()

        scope.launch {
            val modeName = if (_controlMode.value == ControlMode.ACCESSIBILITY) "accessibility" else "adb"
            val registered = firebaseSignaling.registerDevice(email, modeName, explicitToken = firebaseToken)
            _sessionStatus.value = if (registered) "Logged in as $email" else "Logged in as $email (local)"
        }
    }

    fun logout() {
        _currentUserEmail.value = null
        disconnectSession(clearApproval = true)
        _sessionStatus.value = "Signed out"
        scope.launch {
            firebaseSignaling.unregisterDevice()
            firebaseSignaling.clearAuth()
        }
    }

    fun disconnectSession(clearApproval: Boolean = false) {
        inFlightApproveJob?.cancel()
        rtcClient?.close()
        handleSessionClosed()
        if (clearApproval) {
            lastAppliedSessionId = null
            lastAppliedSdpHash = 0
            lastAppliedTimestampMs = 0L
        }
    }
}
