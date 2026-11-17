package com.acite.swdw.ui

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.acite.swdw.client.SwdClientEngine
import com.acite.swdw.model.ConnectionState
import com.acite.swdw.theme.*
import org.jetbrains.compose.resources.painterResource
import swdw.shared.generated.resources.Res
import swdw.shared.generated.resources.ic_swdw_logo

@Composable
fun SwdMainScreen(
    engine: SwdClientEngine,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()

    var hostInput by remember { mutableStateOf(engine.serverHost) }
    var portInput by remember { mutableStateOf(engine.serverPort.toString()) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(HanaBg)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // ── Top Connection & Endpoint Header ─────────────────────────
            HeaderSection(
                engine = engine,
                hostInput = hostInput,
                portInput = portInput,
                onHostChange = { hostInput = it },
                onPortChange = { portInput = it },
                onConnectClick = {
                    val port = portInput.toIntOrNull() ?: 9890
                    if (engine.connectionState == ConnectionState.CONNECTED) {
                        engine.disconnect()
                    } else {
                        engine.connect(hostInput, port)
                    }
                }
            )

            // ── Downlink: Audio Output / Playback ────────────────────────
            DownlinkCard(engine = engine)

            // ── Uplink: Mobile Mic / Virtual Input ───────────────────────
            UplinkCard(engine = engine)

            // ── Host Telemetry & PipeWire Stack ─────────────────────────
            HostTelemetryCard(engine = engine)

            // ── Minimal Utilitarian Footer ───────────────────────────────
            FooterSection(engine = engine)
        }
    }
}

@Composable
private fun HeaderSection(
    engine: SwdClientEngine,
    hostInput: String,
    portInput: String,
    onHostChange: (String) -> Unit,
    onPortChange: (String) -> Unit,
    onConnectClick: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // App title and connection badges row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.weight(1f)
            ) {
                Image(
                    painter = painterResource(Res.drawable.ic_swdw_logo),
                    contentDescription = "SWD Logo",
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                )

                Column {
                    Text(
                        text = "SWD AUDIO",
                        color = HanaText,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp
                    )
                    Text(
                        text = "UDP :${engine.serverPort} · PipeWire",
                        color = HanaTextMuted,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            // Connection state & Heartbeat status badges
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                HeartbeatBadge(
                    state = engine.connectionState,
                    elapsedText = engine.heartbeatElapsedText
                )
                ConnectionStatusBadge(
                    state = engine.connectionState,
                    latencyMs = engine.latencyMs
                )
            }
        }

        // Offline alert banner (Data & state timeout notification)
        AnimatedVisibility(visible = engine.connectionState == ConnectionState.OFFLINE) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(HanaRed.copy(alpha = 0.12f))
                    .border(1.dp, HanaRed.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Warning,
                        contentDescription = null,
                        tint = HanaRed,
                        modifier = Modifier.size(16.dp)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "TIMEOUT (>4s) · 服务端未响应",
                            color = HanaRed,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = "上次心跳: ${engine.heartbeatElapsedText}",
                            color = HanaTextDim,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    Button(
                        onClick = onConnectClick,
                        colors = ButtonDefaults.buttonColors(containerColor = HanaRed),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier.height(30.dp)
                    ) {
                        Text(text = "RETRY", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }

        // Host IP & Port input bar
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(HanaPanel)
                .border(1.dp, HanaStroke, RoundedCornerShape(14.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                BasicTextFieldWithHint(
                    value = hostInput,
                    onValueChange = onHostChange,
                    hint = "192.168.50.1",
                    modifier = Modifier.weight(1f)
                )

                Text(text = ":", color = HanaTextMuted, fontSize = 13.sp, fontFamily = FontFamily.Monospace)

                BasicTextFieldWithHint(
                    value = portInput,
                    onValueChange = onPortChange,
                    hint = "9890",
                    modifier = Modifier.width(60.dp)
                )

                Button(
                    onClick = onConnectClick,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = when (engine.connectionState) {
                            ConnectionState.CONNECTED -> HanaStroke
                            ConnectionState.OFFLINE -> HanaRed
                            else -> HanaPink
                        }
                    ),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    modifier = Modifier.height(36.dp)
                ) {
                    Text(
                        text = when (engine.connectionState) {
                            ConnectionState.CONNECTED -> "断开"
                            ConnectionState.OFFLINE -> "重连"
                            ConnectionState.CONNECTING -> "连接中"
                            else -> "连接"
                        },
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            // Quick target IP shortcuts when disconnected
            if (engine.connectionState != ConnectionState.CONNECTED && engine.connectionState != ConnectionState.OFFLINE) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "目标:",
                        color = HanaTextMuted,
                        fontSize = 10.sp
                    )
                    QuickIpChip(ip = "192.168.50.1", onClick = { onHostChange("192.168.50.1") })
                    QuickIpChip(ip = "192.168.1.5", onClick = { onHostChange("192.168.1.5") })
                    QuickIpChip(ip = "127.0.0.1", onClick = { onHostChange("127.0.0.1") })
                }
            }
        }
    }
}

@Composable
private fun QuickIpChip(ip: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(HanaCard)
            .border(1.dp, HanaStroke.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(text = ip, color = HanaLilacLight, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun HeartbeatBadge(
    state: ConnectionState,
    elapsedText: String
) {
    val (dotColor, borderCol) = when (state) {
        ConnectionState.CONNECTED -> HanaMint to HanaMint.copy(alpha = 0.35f)
        ConnectionState.OFFLINE -> HanaRed to HanaRed.copy(alpha = 0.5f)
        ConnectionState.CONNECTING -> HanaAmber to HanaAmber.copy(alpha = 0.35f)
        else -> HanaTextMuted to HanaStroke.copy(alpha = 0.5f)
    }

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(HanaCard)
            .border(1.dp, borderCol, RoundedCornerShape(12.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.Favorite,
                contentDescription = null,
                tint = dotColor,
                modifier = Modifier.size(11.dp)
            )
            Text(
                text = elapsedText,
                color = dotColor,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                softWrap = false
            )
        }
    }
}

@Composable
private fun ConnectionStatusBadge(
    state: ConnectionState,
    latencyMs: Int
) {
    val (dotColor, text) = when (state) {
        ConnectionState.CONNECTED -> HanaMint to "${latencyMs}ms"
        ConnectionState.CONNECTING -> HanaAmber to "CONNECTING"
        ConnectionState.DISCONNECTED -> HanaTextMuted to "OFFLINE"
        ConnectionState.OFFLINE -> HanaRed to "TIMEOUT"
        ConnectionState.ERROR -> HanaRed to "ERROR"
    }

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(HanaCard)
            .border(1.dp, HanaStroke, RoundedCornerShape(12.dp))
            .padding(horizontal = 9.dp, vertical = 4.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(dotColor)
            )
            Text(
                text = text,
                color = dotColor,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                softWrap = false
            )
        }
    }
}

@Composable
private fun DownlinkCard(engine: SwdClientEngine) {
    val glowBorderColor = if (engine.isPlaying) HanaPink.copy(alpha = 0.45f) else HanaStroke

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(HanaPanel)
            .border(1.dp, glowBorderColor, RoundedCornerShape(20.dp))
            .padding(14.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // Header
            SectionHeader(
                title = "DOWNLINK",
                tag = "${engine.sampleRate / 1000} kHz · 16-bit · 2 CH",
                accentColor = HanaPink
            )

            // 4-Column Live Metric Grid
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                MetricBox(
                    label = "STATE",
                    value = if (engine.isPlaying) "PLAY" else "IDLE",
                    valueColor = if (engine.isPlaying) HanaPink else HanaTextMuted,
                    modifier = Modifier.weight(1f)
                )
                MetricBox(
                    label = "RTT",
                    value = "${engine.latencyMs}",
                    unit = "ms",
                    valueColor = if (engine.connectionState == ConnectionState.CONNECTED) HanaMint else HanaTextMuted,
                    modifier = Modifier.weight(1f)
                )
                MetricBox(
                    label = "RATE",
                    value = "${engine.packetsPerSecond}",
                    unit = "pkt/s",
                    valueColor = if (engine.packetsPerSecond > 0) HanaMint else HanaTextMuted,
                    modifier = Modifier.weight(1f)
                )
                MetricBox(
                    label = "PING",
                    value = engine.heartbeatElapsedText,
                    valueColor = when (engine.connectionState) {
                        ConnectionState.CONNECTED -> HanaMint
                        ConnectionState.OFFLINE -> HanaRed
                        else -> HanaTextMuted
                    },
                    modifier = Modifier.weight(1f)
                )
            }

            // Real-time FFT / Spectrum Visualizer Canvas
            VisualizerCanvas(
                bars = engine.visualizerBars,
                isPlaying = engine.isPlaying,
                sampleRate = engine.sampleRate
            )

            // Play / Pause Icon Button + Volume Control Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (engine.isPlaying) HanaCard else HanaPink)
                        .border(
                            1.dp,
                            if (engine.isPlaying) HanaPink.copy(alpha = 0.5f) else Color.Transparent,
                            RoundedCornerShape(12.dp)
                        )
                        .clickable { engine.togglePlayback() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (engine.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = null,
                        tint = if (engine.isPlaying) HanaPink else Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }

                // Volume slider & mute button
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(HanaCard)
                        .border(1.dp, HanaStroke, RoundedCornerShape(12.dp))
                        .padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = if (engine.isMuted || engine.volume == 0f) Icons.Filled.VolumeOff else Icons.Filled.VolumeUp,
                        contentDescription = null,
                        tint = if (engine.isMuted) HanaRed else HanaLilac,
                        modifier = Modifier
                            .size(20.dp)
                            .clickable { engine.toggleMute() }
                    )

                    Slider(
                        value = if (engine.isMuted) 0f else engine.volume,
                        onValueChange = { engine.updateVolume(it) },
                        valueRange = 0f..1f,
                        colors = SliderDefaults.colors(
                            thumbColor = HanaPink,
                            activeTrackColor = HanaPink,
                            inactiveTrackColor = HanaStroke
                        ),
                        modifier = Modifier.weight(1f)
                    )

                    Text(
                        text = "${((if (engine.isMuted) 0f else engine.volume) * 100).toInt()}%",
                        color = HanaTextDim,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.width(34.dp)
                    )
                }
            }

            // Dual-channel Peak VU Meters
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(text = "PEAK VU", color = HanaTextMuted, fontSize = 10.sp, letterSpacing = 0.5.sp, fontFamily = FontFamily.Monospace)
                    Text(
                        text = "L: ${(engine.peakLeft * 100).toInt()}% | R: ${(engine.peakRight * 100).toInt()}%",
                        color = HanaTextDim,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = "L", color = HanaTextMuted, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                    VuMeterBar(level = engine.peakLeft, modifier = Modifier.weight(1f))
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = "R", color = HanaTextMuted, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                    VuMeterBar(level = engine.peakRight, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun UplinkCard(engine: SwdClientEngine) {
    val glowBorderColor = if (engine.isMicStreaming) HanaMint.copy(alpha = 0.45f) else HanaStroke

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(HanaPanel)
            .border(1.dp, glowBorderColor, RoundedCornerShape(20.dp))
            .padding(14.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // Header
            SectionHeader(
                title = "UPLINK",
                tag = "48 kHz · 16-bit · 1 CH",
                accentColor = HanaMint
            )

            // 4-Column Live Metric Grid
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                MetricBox(
                    label = "STATE",
                    value = if (engine.isMicStreaming) "LIVE" else "IDLE",
                    valueColor = if (engine.isMicStreaming) HanaMint else HanaTextMuted,
                    modifier = Modifier.weight(1f)
                )
                MetricBox(
                    label = "PEAK",
                    value = "${(engine.micPeak * 100).toInt()}%",
                    valueColor = if (engine.micPeak > 0.8f) HanaAmber else HanaMint,
                    modifier = Modifier.weight(1f)
                )
                MetricBox(
                    label = "GAIN",
                    value = "${(engine.micGain * 10).toInt() / 10f}",
                    unit = "×",
                    valueColor = HanaLilac,
                    modifier = Modifier.weight(1f)
                )
                MetricBox(
                    label = "DSP",
                    value = when {
                        engine.echoCancellation && engine.noiseSuppression -> "AEC+NS"
                        engine.echoCancellation -> "AEC"
                        engine.noiseSuppression -> "NS"
                        else -> "OFF"
                    },
                    valueColor = if (engine.echoCancellation || engine.noiseSuppression) HanaMint else HanaTextMuted,
                    modifier = Modifier.weight(1f)
                )
            }

            // Mic Input VU Bar
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(text = "MIC VU", color = HanaTextMuted, fontSize = 10.sp, letterSpacing = 0.5.sp, fontFamily = FontFamily.Monospace)
                    Text(
                        text = "${(engine.micPeak * 100).toInt()}%",
                        color = HanaTextDim,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
                VuMeterBar(
                    level = engine.micPeak,
                    brush = Brush.horizontalGradient(
                        if (engine.micPeak > 0.85f) listOf(HanaMint, HanaRed)
                        else listOf(HanaMint, Color(0xFF4ADE80))
                    )
                )
            }

            // Headphone warning when both are active
            AnimatedVisibility(visible = engine.isPlaying && engine.isMicStreaming) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(HanaAmber.copy(alpha = 0.1f))
                        .border(1.dp, HanaAmber.copy(alpha = 0.25f), RoundedCornerShape(10.dp))
                        .padding(horizontal = 10.dp, vertical = 7.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Headphones,
                            contentDescription = null,
                            tint = HanaAmber,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "建议佩戴耳机以防声学环路回声反馈",
                            color = HanaAmber,
                            fontSize = 11.sp
                        )
                    }
                }
            }

            // Mic Toggle Icon Button + Mic Gain Slider Row (Symmetrical to Downlink!)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (engine.isMicStreaming) HanaCard else HanaMint)
                        .border(
                            1.dp,
                            if (engine.isMicStreaming) HanaMint.copy(alpha = 0.5f) else Color.Transparent,
                            RoundedCornerShape(12.dp)
                        )
                        .clickable { engine.toggleMic() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (engine.isMicStreaming) Icons.Filled.MicOff else Icons.Filled.Mic,
                        contentDescription = null,
                        tint = if (engine.isMicStreaming) HanaMint else Color(0xFF0F172A),
                        modifier = Modifier.size(24.dp)
                    )
                }

                // Mic Gain Slider Row
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(HanaCard)
                        .border(1.dp, HanaStroke, RoundedCornerShape(12.dp))
                        .padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "GAIN",
                        color = HanaTextMuted,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Medium
                    )

                    Slider(
                        value = engine.micGain,
                        onValueChange = { engine.updateMicGain(it) },
                        valueRange = 0.1f..3.0f,
                        colors = SliderDefaults.colors(
                            thumbColor = HanaMint,
                            activeTrackColor = HanaMint,
                            inactiveTrackColor = HanaStroke
                        ),
                        modifier = Modifier.weight(1f)
                    )

                    Text(
                        text = "${(engine.micGain * 10).toInt() / 10f}×",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.width(34.dp)
                    )
                }
            }

            // DSP Filters Row (Pure Abbreviations)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ToggleChip(
                    selected = engine.echoCancellation,
                    label = "AEC",
                    onToggle = { engine.updateEchoCancellation(it) },
                    modifier = Modifier.weight(1f)
                )
                ToggleChip(
                    selected = engine.noiseSuppression,
                    label = "NS",
                    onToggle = { engine.updateNoiseSuppression(it) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun HostTelemetryCard(engine: SwdClientEngine) {
    val status = engine.systemStatus
    var showAllDevices by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(HanaPanel)
            .border(1.dp, HanaStroke, RoundedCornerShape(20.dp))
            .padding(14.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // Header
            SectionHeader(
                title = "HOST TELEMETRY",
                tag = status?.os?.osName ?: "OFFLINE",
                accentColor = HanaLilac
            )

            if (status != null) {
                // Host Hardware & OS Data
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(HanaCard)
                        .border(1.dp, HanaStroke.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    InfoRow("OS", status.os.osName)
                    InfoRow("KERNEL", status.os.kernel, isMono = true, color = HanaLilac)
                    InfoRow("HOST", status.os.hostname, isMono = true)
                    InfoRow("CPU", "${status.os.cpuModel} (${status.os.cpuCores}c)")

                    // Memory Progress
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        val memRatio = if (status.os.memTotalMb > 0) {
                            status.os.memUsedMb.toFloat() / status.os.memTotalMb.toFloat()
                        } else 0f
                        val memPercent = (memRatio * 100).toInt()

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(text = "RAM", color = HanaTextDim, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                            Text(
                                text = "${status.os.memUsedMb} / ${status.os.memTotalMb} MB ($memPercent%)",
                                color = HanaTextDim,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        VuMeterBar(
                            level = memRatio,
                            brush = Brush.horizontalGradient(listOf(HanaLilac, HanaPink))
                        )
                    }

                    InfoRow("UPTIME", formatUptime(status.os.uptimeSecs), isMono = true)
                }

                // PipeWire Audio Engine
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(HanaCard)
                        .border(1.dp, HanaStroke.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "PIPEWIRE",
                            color = HanaPink,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 0.5.sp
                        )
                        Text(
                            text = "v${status.audio.pipewireVersion}",
                            color = HanaPink,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    InfoRow("CPAL", status.audio.cpalHost, isMono = true, color = HanaLilac)
                    InfoRow("DEFAULT SINK", status.audio.defaultOutputDevice)
                    InfoRow("DEFAULT SOURCE", status.audio.defaultInputDevice)
                }

                // Discovered Audio Devices
                if (status.audio.devices.isNotEmpty()) {
                    val sortedDevices = remember(status.audio.devices) {
                        status.audio.devices.sortedByDescending { it.isDefault }
                    }
                    val visibleDevices = if (showAllDevices) sortedDevices else sortedDevices.take(4)

                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "DEVICES (${status.audio.devices.size})",
                                color = HanaTextMuted,
                                fontSize = 10.sp,
                                letterSpacing = 0.5.sp,
                                fontFamily = FontFamily.Monospace
                            )
                            if (sortedDevices.size > 4) {
                                Text(
                                    text = if (showAllDevices) "收起" else "显示全部",
                                    color = HanaLilac,
                                    fontSize = 10.sp,
                                    modifier = Modifier.clickable { showAllDevices = !showAllDevices }
                                )
                            }
                        }

                        visibleDevices.forEach { dev ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (dev.isDefault) HanaPink.copy(alpha = 0.1f) else HanaCard)
                                    .border(
                                        1.dp,
                                        if (dev.isDefault) HanaPink.copy(alpha = 0.35f) else HanaStroke.copy(alpha = 0.4f),
                                        RoundedCornerShape(8.dp)
                                    )
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = dev.name,
                                    color = if (dev.isDefault) Color.White else HanaTextDim,
                                    fontSize = 11.sp,
                                    modifier = Modifier.weight(1f)
                                )
                                if (dev.isDefault) {
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(HanaPink.copy(alpha = 0.2f))
                                            .padding(horizontal = 5.dp, vertical = 2.dp)
                                    ) {
                                        Text(
                                            text = "DEFAULT",
                                            color = HanaPink,
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }
                                } else {
                                    Text(
                                        text = "${dev.maxChannels}ch",
                                        color = HanaTextMuted,
                                        fontSize = 10.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }
                        }

                        if (sortedDevices.size > 4 && !showAllDevices) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(HanaCard)
                                    .border(1.dp, HanaStroke.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                                    .clickable { showAllDevices = true }
                                    .padding(vertical = 6.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "展开剩余 ${sortedDevices.size - 4} 个节点...",
                                    color = HanaTextMuted,
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(HanaCard)
                        .padding(12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "○ 等待主机遥测数据广播...",
                        color = HanaTextMuted,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(
    title: String,
    tag: String,
    accentColor: Color = HanaPink
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(3.dp, 12.dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(accentColor)
            )
            Text(
                text = title,
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp,
                fontFamily = FontFamily.Monospace
            )
        }
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(HanaCard)
                .border(1.dp, HanaStroke.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
                .padding(horizontal = 7.dp, vertical = 2.dp)
        ) {
            Text(
                text = tag,
                color = HanaTextDim,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

@Composable
private fun MetricBox(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    unit: String? = null,
    valueColor: Color = Color.White
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(HanaCard)
            .border(1.dp, HanaStroke.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
            .padding(horizontal = 6.dp, vertical = 7.dp)
    ) {
        Column {
            Text(
                text = label,
                color = HanaTextMuted,
                fontSize = 9.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.2.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Row(
                verticalAlignment = Alignment.Bottom,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = value,
                    color = valueColor,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis
                )
                if (unit != null) {
                    Spacer(modifier = Modifier.width(2.dp))
                    Text(
                        text = unit,
                        color = HanaTextDim,
                        fontSize = 8.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.padding(bottom = 1.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ToggleChip(
    selected: Boolean,
    label: String,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) HanaMint.copy(alpha = 0.12f) else HanaCard)
            .border(
                1.dp,
                if (selected) HanaMint.copy(alpha = 0.4f) else HanaStroke,
                RoundedCornerShape(10.dp)
            )
            .clickable { onToggle(!selected) }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(if (selected) HanaMint else HanaTextMuted)
            )
            Text(
                text = label,
                color = if (selected) HanaMint else HanaTextDim,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
            )
        }
    }
}

@Composable
private fun InfoRow(
    label: String,
    value: String,
    isMono: Boolean = false,
    color: Color = Color.White
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = HanaTextDim,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = value,
            color = color,
            fontSize = 11.sp,
            fontFamily = if (isMono) FontFamily.Monospace else FontFamily.Default,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun BasicTextFieldWithHint(
    value: String,
    onValueChange: (String) -> Unit,
    hint: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(HanaCard)
            .border(1.dp, HanaStroke, RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        if (value.isEmpty()) {
            Text(text = hint, color = HanaTextMuted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        }
        androidx.compose.foundation.text.BasicTextField(
            value = value,
            onValueChange = onValueChange,
            textStyle = androidx.compose.ui.text.TextStyle(
                color = Color.White,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace
            ),
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
    }
}

@Composable
private fun FooterSection(engine: SwdClientEngine) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "SWD AUDIO ENGINE · UDP PORT ${engine.serverPort}",
            color = HanaTextMuted,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace
        )
    }
}

private fun formatUptime(secs: Long): String {
    val days = secs / 86400
    val hours = (secs % 86400) / 3600
    val minutes = (secs % 3600) / 60
    return if (days > 0) "${days}d ${hours}h ${minutes}m"
    else "${hours}h ${minutes}m"
}