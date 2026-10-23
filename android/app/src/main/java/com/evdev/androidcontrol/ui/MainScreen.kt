package com.evdev.androidcontrol.ui

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
import androidx.compose.ui.text.style.TextOverflow
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
    currentUserEmail: String?,
    isConnected: Boolean = false,
    onLogin: () -> Unit,
    onLogout: () -> Unit,
    isConnectionsEnabled: Boolean,
    onToggleConnections: (Boolean) -> Unit,
    controlMode: ControlMode,
    onModeChange: (ControlMode) -> Unit,
    sessionStatus: String,
    isAdbReachable: Boolean?,
    isDevOptionsEnabled: Boolean = false,
    rxBytes: Long,
    txBytes: Long,
    onDisconnect: () -> Unit,
    isAutoDetected: Boolean = true,
    onResetAuto: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    val scrollState = rememberScrollState()
    var isA11yActive by remember { mutableStateOf(ControlAccessibilityService.isRunning) }

    LaunchedEffect(Unit) {
        while (true) {
            isA11yActive = ControlAccessibilityService.isRunning
            kotlinx.coroutines.delay(1000)
        }
    }

    val isSelectedModeActive = if (controlMode == ControlMode.ACCESSIBILITY) {
        isA11yActive
    } else {
        isAdbReachable == true
    }
    val isReady = isConnectionsEnabled && currentUserEmail != null && isSelectedModeActive

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
                            modifier = Modifier.size(30.dp),
                            tint = Color.Unspecified
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text("Android Control", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    }
                },
                actions = {
                    val statusText = when {
                        isConnected -> "CONNECTED"
                        !isConnectionsEnabled -> "DISABLED"
                        isReady -> "LISTENING"
                        else -> "OFFLINE"
                    }
                    val statusDotColor = when {
                        isConnected -> Color(0xFF22C55E)
                        !isConnectionsEnabled -> Color(0xFFEF4444)
                        isReady -> Color(0xFF22C55E)
                        else -> Color.Gray
                    }

                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = when {
                            isConnected -> Color(0xFF22C55E).copy(alpha = 0.2f)
                            !isConnectionsEnabled -> if (dark) Color(0xFF3B1E1E) else Color(0xFFFFEBEE)
                            isReady -> if (dark) Color(0xFF1E3A2B) else Color(0xFFE8F5E9)
                            else -> MaterialTheme.colorScheme.surfaceVariant
                        },
                        border = BorderStroke(
                            1.dp,
                            when {
                                isConnected -> Color(0xFF22C55E)
                                !isConnectionsEnabled -> Color(0xFFEF4444)
                                isReady -> Color(0xFF81C784)
                                else -> Color.Gray
                            }
                        ),
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
                                    .background(statusDotColor)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = statusText,
                                color = if (isConnected || isReady) {
                                    if (dark) Color(0xFF4ADE80) else Color(0xFF15803D)
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp,
                                letterSpacing = 0.5.sp
                            )
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
            // 1. Logged In As (Top)
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (currentUserEmail != null) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Logged in as",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = currentUserEmail,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        TextButton(
                            onClick = onLogout,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text("Log out", fontSize = 13.sp)
                        }
                    } else {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Not signed in",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Sign in to enable connections",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Button(
                            onClick = onLogin,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                        ) {
                            Text("Sign in", fontSize = 13.sp)
                        }
                    }
                }
            }

            // 2. Global Enable/Disable Remote Control Switch
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (isConnectionsEnabled) {
                        if (dark) Color(0xFF132A1C) else Color(0xFFE8F5E9)
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    }
                ),
                border = if (isConnectionsEnabled) {
                    BorderStroke(1.dp, if (dark) Color(0xFF22C55E).copy(alpha = 0.5f) else Color(0xFF81C784))
                } else null
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "Remote Control",
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (isConnected) {
                                Spacer(modifier = Modifier.width(8.dp))
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = Color(0xFF22C55E).copy(alpha = 0.2f),
                                    border = BorderStroke(1.dp, Color(0xFF22C55E))
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(6.dp)
                                                .clip(CircleShape)
                                                .background(Color(0xFF22C55E))
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = "CONNECTED",
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (dark) Color(0xFF4ADE80) else Color(0xFF15803D),
                                            maxLines = 1
                                        )
                                    }
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = if (isConnectionsEnabled) "Enabled" else "Disabled",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Switch(
                        checked = isConnectionsEnabled,
                        onCheckedChange = onToggleConnections
                    )
                }
            }

            // 3. Control Mode with secondary status & menu actions
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
                            fontSize = 15.sp
                        )
                        if (isAutoDetected) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                            ) {
                                Text(
                                    text = "Auto-detected",
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

                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    Spacer(modifier = Modifier.height(10.dp))

                    // Secondary section with buttons/links to menus, status, and warnings
                    if (controlMode == ControlMode.ACCESSIBILITY) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Accessibility: ", fontSize = 13.sp)
                                Text(
                                    text = if (isA11yActive) "Enabled" else "Disabled",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isA11yActive) {
                                        if (dark) Color(0xFF81C784) else Color(0xFF2E7D32)
                                    } else {
                                        if (dark) Color(0xFFE57373) else Color(0xFFC62828)
                                    }
                                )
                            }
                            OutlinedButton(
                                onClick = {
                                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                                },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Text("Settings", fontSize = 12.sp)
                            }
                        }

                        if (!isA11yActive) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "⚠ Service disabled. Enable in Settings.",
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Wireless Debugging: ", fontSize = 13.sp)
                                Text(
                                    text = when (isAdbReachable) {
                                        true -> "Enabled"
                                        false -> "Disabled"
                                        null -> "Checking..."
                                    },
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = when (isAdbReachable) {
                                        true -> if (dark) Color(0xFF81C784) else Color(0xFF2E7D32)
                                        false -> if (dark) Color(0xFFFFB74D) else Color(0xFFE65100)
                                        null -> MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )
                            }
                            OutlinedButton(
                                onClick = {
                                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
                                },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Text("Dev Options", fontSize = 12.sp)
                            }
                        }

                        if (isAdbReachable == false) {
                            Spacer(modifier = Modifier.height(10.dp))
                            val warningText = if (!isDevOptionsEnabled) {
                                "⚠ Dev Options disabled. Tap Dev Options."
                            } else {
                                "⚠ Wireless Debugging inactive. Tap Dev Options."
                            }
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = warningText,
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 3. Connection Request / Status (Bottom)
            if (isConnected) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (dark) Color(0xFF0C2B14) else Color(0xFFE8F8ED)
                    ),
                    border = BorderStroke(1.dp, if (dark) Color(0xFF22C55E) else Color(0xFF16A34A)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFF22C55E))
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Connected",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                    color = if (dark) Color(0xFF4ADE80) else Color(0xFF15803D)
                                )
                            }
                            Text(
                                text = "↓ ${formatBytes(rxBytes)}  ↑ ${formatBytes(txBytes)}",
                                fontSize = 13.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Medium
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))
                        Button(
                            onClick = onDisconnect,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                        ) {
                            Text("Disconnect", fontSize = 13.sp)
                        }
                    }
                }
            } else {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(
                                    when {
                                        !isConnectionsEnabled -> Color(0xFFEF4444)
                                        isReady -> Color(0xFF22C55E)
                                        else -> Color.Gray
                                    }
                                )
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        val bottomStatusText = when {
                            !isConnectionsEnabled -> "Connections disabled"
                            currentUserEmail == null -> "Sign in to connect"
                            !isSelectedModeActive -> {
                                val modeLabel = if (controlMode == ControlMode.ACCESSIBILITY) "Accessibility" else "Wireless Debugging"
                                "Enable $modeLabel to connect"
                            }
                            else -> "Listening for incoming sessions"
                        }
                        Text(
                            text = bottomStatusText,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}
