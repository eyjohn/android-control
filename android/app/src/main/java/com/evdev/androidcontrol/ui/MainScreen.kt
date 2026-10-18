package com.evdev.androidcontrol.ui

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.evdev.androidcontrol.model.ControlMode
import com.evdev.androidcontrol.model.SessionPayload
import com.evdev.androidcontrol.service.ControlAccessibilityService

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val exp = (Math.log(bytes.toDouble()) / Math.log(1024.0)).toInt()
    val pre = "KMGTPE"[exp - 1]
    return String.format(java.util.Locale.US, "%.1f %sB", bytes / Math.pow(1024.0, exp.toDouble()), pre)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    controlMode: ControlMode,
    onModeChange: (ControlMode) -> Unit,
    sessionStatus: String,
    incomingOffer: SessionPayload?,
    generatedAnswer: String?,
    isAdbReachable: Boolean?,
    rxBytes: Long,
    txBytes: Long,
    onCheckAdb: () -> Unit,
    onLoadOffer: (String) -> Unit,
    onApprove: () -> Unit,
    onCopyAnswer: () -> Unit,
    onDisconnect: () -> Unit,
    isAutoDetected: Boolean = true,
    onResetAuto: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    val scrollState = rememberScrollState()
    var isA11yActive by remember { mutableStateOf(ControlAccessibilityService.isRunning) }
    var offerInputText by remember { mutableStateOf("") }
    var showManualInput by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (true) {
            isA11yActive = ControlAccessibilityService.isRunning
            kotlinx.coroutines.delay(1000)
        }
    }

    val isConnected = sessionStatus.contains("Connected", ignoreCase = true) ||
            sessionStatus.contains("DataChannel active", ignoreCase = true)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            painter = androidx.compose.ui.res.painterResource(
                                id = com.evdev.androidcontrol.R.drawable.ic_logo
                            ),
                            contentDescription = "Android Remote Control Logo",
                            modifier = Modifier.size(32.dp),
                            tint = Color.Unspecified
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text("Android Control", fontWeight = FontWeight.Bold)
                    }
                },
                actions = {
                    if (isConnected) {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = Color(0xFF22C55E).copy(alpha = 0.2f),
                            border = BorderStroke(1.dp, Color(0xFF22C55E)),
                            modifier = Modifier.padding(end = 12.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFF22C55E))
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "LIVE",
                                    color = if (dark) Color(0xFF4ADE80) else Color(0xFF15803D),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.sp,
                                    letterSpacing = 0.5.sp
                                )
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(3.dp),
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(scrollState)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // 1. Control Mode Selector with Auto-detect status
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Control Mode",
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                        if (isAutoDetected) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                            ) {
                                Text(
                                    text = "Auto-selected",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                            }
                        } else {
                            TextButton(
                                onClick = onResetAuto,
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text("Reset to Auto", fontSize = 11.sp)
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))

                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        SegmentedButton(
                            selected = controlMode == ControlMode.ACCESSIBILITY,
                            onClick = { onModeChange(ControlMode.ACCESSIBILITY) },
                            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
                        ) {
                            Text("Accessibility", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        }
                        SegmentedButton(
                            selected = controlMode == ControlMode.ADB,
                            onClick = { onModeChange(ControlMode.ADB) },
                            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
                        ) {
                            Text("ADB Bridge", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }

            // 2. Mode Status Card
            if (controlMode == ControlMode.ACCESSIBILITY) {
                // Accessibility Mode Details
                val a11yCardBg = if (isA11yActive) {
                    if (dark) Color(0xFF1C3822) else Color(0xFFE8F5E9)
                } else {
                    if (dark) Color(0xFF3E1D1D) else Color(0xFFFFEBEE)
                }
                val a11yStatusColor = if (isA11yActive) {
                    if (dark) Color(0xFF81C784) else Color(0xFF2E7D32)
                } else {
                    if (dark) Color(0xFFE57373) else Color(0xFFC62828)
                }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = a11yCardBg),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Accessibility Service",
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 15.sp
                                )
                                Text(
                                    text = if (isA11yActive) "Running & Ready" else "Disabled in System Settings",
                                    color = a11yStatusColor,
                                    fontWeight = FontWeight.Medium,
                                    fontSize = 13.sp
                                )
                            }
                            if (!isA11yActive) {
                                Button(
                                    onClick = {
                                        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                                ) {
                                    Text("Enable", fontSize = 12.sp)
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Inspects UI tree and simulates touch gestures directly. Zero-ADB required.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                // ADB Bridge Mode Details
                val adbCardBg = when (isAdbReachable) {
                    true -> if (dark) Color(0xFF1C3822) else Color(0xFFE8F5E9)
                    false -> if (dark) Color(0xFF3E2D1D) else Color(0xFFFFF3E0)
                    null -> MaterialTheme.colorScheme.surfaceVariant
                }
                val adbStatusColor = when (isAdbReachable) {
                    true -> if (dark) Color(0xFF81C784) else Color(0xFF2E7D32)
                    false -> if (dark) Color(0xFFFFB74D) else Color(0xFFE65100)
                    null -> MaterialTheme.colorScheme.onSurfaceVariant
                }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = adbCardBg),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "ADB Local Daemon",
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 15.sp
                                )
                                Text(
                                    text = when (isAdbReachable) {
                                        true -> "adbd listening on 127.0.0.1:5555"
                                        false -> "Port 5555 unreachable (Enable Wireless Debugging)"
                                        null -> "Checking port 5555..."
                                    },
                                    color = adbStatusColor,
                                    fontWeight = FontWeight.Medium,
                                    fontSize = 13.sp
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                OutlinedButton(
                                    onClick = onCheckAdb,
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Text("Check", fontSize = 12.sp)
                                }
                                if (isAdbReachable == false) {
                                    Button(
                                        onClick = {
                                            context.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
                                        },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                                    ) {
                                        Text("Dev Opts", fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Tunnels local adbd over encrypted WebRTC DataChannel to host workstation (127.0.0.1:55555).",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // 3. Connection & Tunnel Status Card (Pronounced live container)
            val statusCardBg = if (isConnected) {
                if (dark) Color(0xFF0C2B14) else Color(0xFFE8F8ED)
            } else if (incomingOffer != null) {
                if (dark) Color(0xFF1E2838) else Color(0xFFEBF3FE)
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }

            val statusCardBorder = if (isConnected) {
                BorderStroke(2.dp, if (dark) Color(0xFF22C55E) else Color(0xFF16A34A))
            } else if (incomingOffer != null) {
                BorderStroke(1.dp, if (dark) Color(0xFF3B82F6) else Color(0xFF2563EB))
            } else {
                null
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = statusCardBg),
                border = statusCardBorder,
                shape = RoundedCornerShape(14.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(12.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (isConnected) Color(0xFF22C55E)
                                        else if (incomingOffer != null) Color(0xFF3B82F6)
                                        else Color.Gray
                                    )
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (isConnected) "ACTIVE WEBRTC TUNNEL" else "SESSION TUNNEL STATUS",
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                letterSpacing = 0.5.sp,
                                color = if (isConnected) {
                                    if (dark) Color(0xFF4ADE80) else Color(0xFF15803D)
                                } else MaterialTheme.colorScheme.onSurface
                            )
                        }

                        if (isConnected) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (dark) Color(0xFF164E24) else Color(0xFFC7F2D5)
                            ) {
                                Text(
                                    text = if (controlMode == ControlMode.ACCESSIBILITY) "Accessibility" else "ADB Bridge",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (dark) Color(0xFF86EFAC) else Color(0xFF14532D),
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = sessionStatus,
                        fontSize = if (isConnected) 15.sp else 14.sp,
                        fontWeight = if (isConnected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (isConnected) {
                            if (dark) Color(0xFFE2FBE9) else Color(0xFF0F3918)
                        } else MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (isConnected) {
                        Spacer(modifier = Modifier.height(10.dp))
                        HorizontalDivider(
                            color = if (dark) Color(0xFF22C55E).copy(alpha = 0.3f)
                            else Color(0xFF16A34A).copy(alpha = 0.2f)
                        )
                        Spacer(modifier = Modifier.height(10.dp))

                        if (controlMode == ControlMode.ADB) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(
                                        text = "Forwarding",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = "127.0.0.1:5555",
                                        fontSize = 13.sp,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    Text(
                                        text = "DataChannel Traffic",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = "↓ ${formatBytes(rxBytes)}  ↑ ${formatBytes(txBytes)}",
                                        fontSize = 13.sp,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        } else {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Zero-daemon pure Accessibility RPC",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = "Ready",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (dark) Color(0xFF4ADE80) else Color(0xFF15803D)
                                )
                            }
                        }
                    }
                }
            }

            // 4. Approval Request Card
            if (incomingOffer != null && !isConnected) {
                val canApprove = if (controlMode == ControlMode.ACCESSIBILITY) {
                    isA11yActive
                } else {
                    isAdbReachable == true
                }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (dark) Color(0xFF1E2838) else Color(0xFFE3F2FD)
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("Session Approval Request", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Text("Session ID: ${incomingOffer.sessionId}", fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                        Text("Reason: ${incomingOffer.reason}", fontWeight = FontWeight.Medium, fontSize = 14.sp)
                        Text("Type: Persistent Session (Auto-reconnect)", fontSize = 13.sp)

                        Text(
                            text = if (controlMode == ControlMode.ACCESSIBILITY) {
                                "Mode: Accessibility"
                            } else {
                                "Mode: ADB Bridge"
                            },
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.primary
                        )

                        if (!canApprove) {
                            Text(
                                text = if (controlMode == ControlMode.ACCESSIBILITY) {
                                    "⚠️ Enable Accessibility Service above to approve"
                                } else {
                                    "⚠️ Ensure port 5555 is listening (Wireless Debugging) to approve"
                                },
                                color = MaterialTheme.colorScheme.error,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = onApprove,
                                modifier = Modifier.weight(1f),
                                enabled = canApprove,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (dark) Color(0xFF388E3C) else Color(0xFF2E7D32)
                                )
                            ) {
                                Text("Approve & Connect")
                            }
                            OutlinedButton(
                                onClick = onDisconnect,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Reject")
                            }
                        }
                    }
                }
            }

            // 5. Answer Payload Available (Copy back to host)
            if (generatedAnswer != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (dark) Color(0xFF1C2C1D) else Color(0xFFE8F5E9)
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text("WebRTC Answer Ready", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Single-roundtrip SDP answer generated with embedded ICE candidates.",
                            fontSize = 12.sp
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = onCopyAnswer,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                        ) {
                            Text("Copy Answer Payload to Clipboard")
                        }
                    }
                }
            }

            // 6. Manual Offer Input (Like webrtc-bridge)
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Manual Session Pairing", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        TextButton(onClick = { showManualInput = !showManualInput }) {
                            Text(if (showManualInput) "Hide" else "Show")
                        }
                    }

                    if (showManualInput) {
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = offerInputText,
                            onValueChange = { offerInputText = it },
                            placeholder = { Text("Paste Base64 or JSON Session Offer...") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(90.dp),
                            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    val item = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
                                    if (!item.isNullOrBlank()) {
                                        offerInputText = item
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Paste", fontSize = 12.sp)
                            }
                            Button(
                                onClick = {
                                    if (offerInputText.isNotBlank()) {
                                        onLoadOffer(offerInputText.trim())
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                enabled = offerInputText.isNotBlank()
                            ) {
                                Text("Load Offer", fontSize = 12.sp)
                            }
                        }
                    }
                }
            }

            // 7. Disconnect Action
            if (isConnected) {
                Button(
                    onClick = onDisconnect,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Disconnect Immediately")
                }
            }
        }
    }
}
