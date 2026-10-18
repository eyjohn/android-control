package com.evdev.androidcontrol.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.evdev.androidcontrol.service.ControlAccessibilityService

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    sessionStatus: String,
    incomingReason: String?,
    incomingTtl: Int?,
    onApprove: () -> Unit,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    var isA11yActive by remember { mutableStateOf(ControlAccessibilityService.isRunning) }

    LaunchedEffect(Unit) {
        while (true) {
            isA11yActive = ControlAccessibilityService.isRunning
            kotlinx.coroutines.delay(1000)
        }
    }

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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Android Control", fontWeight = FontWeight.Bold) },
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
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // A11y Service Status Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = a11yCardBg),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Accessibility Service",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 16.sp
                        )
                        Text(
                            text = if (isA11yActive) "Running & Ready" else "Disabled in System Settings",
                            color = a11yStatusColor,
                            fontSize = 14.sp
                        )
                    }
                    if (!isA11yActive) {
                        Button(
                            onClick = {
                                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                        ) {
                            Text("Enable")
                        }
                    }
                }
            }

            // Connection Status Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Connection Status", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = sessionStatus,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Approval Card
            if (incomingReason != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text("Session Approval Request", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Text("Agent: Eva (personal-agent)", fontSize = 14.sp)
                        Text("Reason: $incomingReason", fontWeight = FontWeight.Medium, fontSize = 15.sp)
                        if (incomingTtl != null) {
                            Text("TTL: ${incomingTtl / 60} minutes", fontSize = 14.sp)
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Button(
                                onClick = onApprove,
                                modifier = Modifier.weight(1f),
                                enabled = isA11yActive,
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

            // Disconnect Action
            if (sessionStatus.contains("Connected") || sessionStatus.contains("open")) {
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
