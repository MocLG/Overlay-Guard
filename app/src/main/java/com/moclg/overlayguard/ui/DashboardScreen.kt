package com.moclg.overlayguard.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

private val DarkBg = Color(0xFF1A1A2E)
private val CardBg = Color(0xFF16213E)
private val Accent = Color(0xFF0F3460)
private val AccentLight = Color(0xFFE94560)

@Composable
fun DashboardScreen(
    isServiceEnabled: Boolean,
    overlayHeight: Int,
    thresholdDegrees: Float,
    isRooted: Boolean,
    onToggleService: (Boolean) -> Unit,
    onHeightChanged: (Int) -> Unit,
    onThresholdChanged: (Float) -> Unit,
    onOpenAccessibilitySettings: () -> Unit
) {
    var height by remember { mutableIntStateOf(overlayHeight) }
    var threshold by remember { mutableFloatStateOf(thresholdDegrees) }
    var enabled by remember { mutableStateOf(isServiceEnabled) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBg)
            .padding(20.dp),
        verticalArrangement = Arrangement.Top,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(32.dp))

        Text(
            text = "Overlay Guard",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Privacy overlay for status bar",
            fontSize = 14.sp,
            color = Color.Gray
        )

        Spacer(modifier = Modifier.height(32.dp))

        // ── Service Toggle ──
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = CardBg)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Service", color = Color.White, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (enabled) "Active" else "Inactive",
                        color = if (enabled) AccentLight else Color.Gray,
                        fontSize = 12.sp
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = {
                        enabled = it
                        onToggleService(it)
                    },
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = AccentLight,
                        checkedThumbColor = Color.White
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // ── Height Slider ──
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = CardBg)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Overlay Height", color = Color.White, fontWeight = FontWeight.SemiBold)
                    Text("${height}px", color = AccentLight, fontSize = 14.sp)
                }
                Spacer(modifier = Modifier.height(8.dp))
                Slider(
                    value = height.toFloat(),
                    onValueChange = {
                        height = it.roundToInt()
                        onHeightChanged(height)
                    },
                    valueRange = 50f..500f,
                    steps = 44
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // ── Sensitivity Slider ──
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = CardBg)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Roll Threshold", color = Color.White, fontWeight = FontWeight.SemiBold)
                    Text("${threshold.roundToInt()}°", color = AccentLight, fontSize = 14.sp)
                }
                Spacer(modifier = Modifier.height(8.dp))
                Slider(
                    value = threshold,
                    onValueChange = {
                        threshold = it
                        onThresholdChanged(threshold)
                    },
                    valueRange = 10f..45f,
                    steps = 34
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // ── Root / Fallback ──
        if (!isRooted) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = CardBg)
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (enabled) {
                        Text(
                            "Running without root",
                            color = Color(0xFF4CAF50),
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "The service is active. Some features (DND toggle) " +
                                    "require manual permission grants without root.",
                            color = Color.LightGray,
                            fontSize = 12.sp
                        )
                    } else {
                        Text(
                            "Root not detected",
                            color = Color.Yellow,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "On Android 13+, go to App Info → ⋮ → Allow Restricted Settings " +
                                    "before enabling the accessibility service.",
                            color = Color.LightGray,
                            fontSize = 12.sp
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = onOpenAccessibilitySettings,
                        colors = ButtonDefaults.buttonColors(containerColor = Accent)
                    ) {
                        Text("Open Accessibility Settings", color = Color.White)
                    }
                }
            }
        }
    }
}
