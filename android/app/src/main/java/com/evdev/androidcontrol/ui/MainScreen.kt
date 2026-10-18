package com.evdev.androidcontrol.ui

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.evdev.androidcontrol.model.ControlMode
import com.evdev.androidcontrol.model.SessionPayload
import com.evdev.androidcontrol.service.ControlAccessibilityService

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
            // 1. Control Mode Selector (Primary vs Alternative)
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "Control Mode",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
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

            // 3. Connection & Tunnel Status Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("Session Tunnel Status", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = sessionStatus,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (isConnected && controlMode == ControlMode.ADB) {
                        Spacer(modifier = Modifier.height(8.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f))
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Forwarding: 127.0.0.1:5555", fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                            Text("RX: $rxBytes B | TX: $txBytes B", fontSize = 12.sp, fontFamily = FontFamily.Monospace)
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
                        Text("TTL: ${incomingOffer.ttlSeconds / 60} minutes", fontSize = 13.sp)

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
