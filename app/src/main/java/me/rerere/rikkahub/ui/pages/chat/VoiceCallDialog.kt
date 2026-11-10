package me.rerere.rikkahub.ui.pages.chat

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.activity.compose.BackHandler
import kotlinx.coroutines.delay
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Settings03
import me.rerere.hugeicons.stroke.Mic01
import me.rerere.hugeicons.stroke.Cancel01
import androidx.compose.ui.draw.rotate
import me.rerere.hugeicons.stroke.VolumeHigh
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.ui.components.ui.UIAvatar

@Composable
fun VoiceCallDialog(
    assistant: Assistant,
    state: VoiceSessionState,
    aiSubtitle: String,
    onDismiss: () -> Unit,
    onToggleMute: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    var startedAt by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    var elapsedMs by remember { mutableLongStateOf(0L) }
    var speakerEnabled by remember { mutableStateOf(false) }
    var routeMessage by remember { mutableStateOf<String?>(null) }
    var routeError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        startedAt = SystemClock.elapsedRealtime()
        while (true) {
            elapsedMs = SystemClock.elapsedRealtime() - startedAt
            delay(250)
        }
    }
    BackHandler(onBack = onDismiss)

    DisposableEffect(context, state.isActive) {
        if (!state.isActive) return@DisposableEffect onDispose { }
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val originalMode = audioManager.mode
        val originalSpeakerphoneOn = audioManager.isSpeakerphoneOn
        val originalCommunicationDevice = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.communicationDevice
        } else {
            null
        }
        var disposed = false

        fun restore() {
            if (disposed) return
            disposed = true
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    audioManager.clearCommunicationDevice()
                    originalCommunicationDevice?.let(audioManager::setCommunicationDevice)
                } else {
                    audioManager.isSpeakerphoneOn = originalSpeakerphoneOn
                }
            }
            runCatching { audioManager.mode = originalMode }
        }

        try {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val earpiece = audioManager.availableCommunicationDevices.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
                }
                if (earpiece == null) {
                    routeMessage = "此设备没有听筒，当前使用系统默认音频设备"
                } else if (!audioManager.setCommunicationDevice(earpiece)) {
                    routeError = "无法切换到听筒，当前使用系统默认音频设备"
                }
            } else {
                audioManager.isSpeakerphoneOn = false
            }
            speakerEnabled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audioManager.communicationDevice?.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
            } else audioManager.isSpeakerphoneOn
        } catch (e: Exception) {
            routeError = "音频路由初始化失败：${e.message ?: "未知错误"}"
            restore()
        }

        onDispose { restore() }
    }

    fun toggleSpeaker() {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        routeError = null
        routeMessage = null
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val targetType = if (speakerEnabled) {
                    AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
                } else {
                    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                }
                val target = audioManager.availableCommunicationDevices.firstOrNull { it.type == targetType }
                if (target == null) {
                    routeMessage = if (speakerEnabled) "此设备没有听筒，仍使用扬声器" else "此设备没有可用扬声器"
                    return
                }
                check(audioManager.setCommunicationDevice(target)) { "系统拒绝切换音频设备" }
                check(audioManager.communicationDevice?.id == target.id) { "音频设备未能切换" }
            } else {
                if (speakerEnabled && audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).none {
                        it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
                    }
                ) {
                    routeMessage = "此设备没有听筒，仍使用扬声器"
                    return
                }
                audioManager.isSpeakerphoneOn = !speakerEnabled
                check(audioManager.isSpeakerphoneOn != speakerEnabled) { "系统拒绝切换音频设备" }
            }
            speakerEnabled = !speakerEnabled
        } catch (e: Exception) {
            routeError = "音频路由切换失败：${e.message ?: "未知错误"}"
            val fallback = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    audioManager.availableCommunicationDevices.firstOrNull {
                        it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                    }?.let(audioManager::setCommunicationDevice) == true
                } else {
                    audioManager.isSpeakerphoneOn = true
                    audioManager.isSpeakerphoneOn
                }
            }.getOrDefault(false)
            speakerEnabled = fallback
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        val colors = MaterialTheme.colorScheme
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.surface)
                .padding(
                    top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding(),
                    bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
                )
                .padding(horizontal = 24.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                TextButton(onClick = onDismiss) { Text("结束") }
                Text("语音通话", style = MaterialTheme.typography.titleMediumEmphasized)
                IconButton(onClick = onOpenSettings) {
                    Icon(HugeIcons.Settings03, contentDescription = "语音设置")
                }
            }

            Spacer(Modifier.weight(0.55f))
            Box(contentAlignment = Alignment.Center) {
                val speaking = state.phase == VoicePhase.Speaking
                val pulse by rememberInfiniteTransition(label = "call-wave").animateFloat(
                    initialValue = 0f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse),
                    label = "call-wave-pulse",
                )
                Canvas(Modifier.size(224.dp)) {
                    val maxAmplitude = if (speaking) pulse else state.amplitudes.takeLast(32).maxOrNull()?.coerceIn(0f, 1f) ?: 0f
                    drawCircle(
                        color = colors.primary.copy(alpha = if (speaking) 0.12f + maxAmplitude * 0.16f else 0.06f),
                        radius = size.minDimension * (if (speaking) 0.47f + maxAmplitude * 0.04f else 0.46f),
                    )
                    // Incoming audio uses an activity animation; microphone bars use real ASR amplitudes.
                    val values = if (speaking) List(36) { index ->
                        (0.15f + kotlin.math.abs(kotlin.math.sin(index * 0.7f + pulse * 3f)) * 0.85f)
                    } else state.amplitudes.takeLast(36)
                    if (values.isNotEmpty()) {
                        val center = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height / 2f)
                        val baseRadius = size.minDimension * 0.43f
                        val maxBar = size.minDimension * 0.065f
                        values.forEachIndexed { index, value ->
                            val angle = Math.toRadians(index * (360.0 / values.size) - 90.0)
                            val amplitude = value.coerceIn(0f, 1f)
                            val inner = baseRadius
                            val outer = inner + maxBar * (0.15f + amplitude)
                            drawLine(
                                color = colors.primary.copy(alpha = 0.25f + amplitude * 0.75f),
                                start = androidx.compose.ui.geometry.Offset(
                                    center.x + kotlin.math.cos(angle).toFloat() * inner,
                                    center.y + kotlin.math.sin(angle).toFloat() * inner,
                                ),
                                end = androidx.compose.ui.geometry.Offset(
                                    center.x + kotlin.math.cos(angle).toFloat() * outer,
                                    center.y + kotlin.math.sin(angle).toFloat() * outer,
                                ),
                                strokeWidth = 3.dp.toPx(),
                                cap = StrokeCap.Round,
                            )
                        }
                    }
                }
                UIAvatar(
                    name = assistant.name,
                    value = assistant.avatar,
                    modifier = Modifier.size(152.dp),
                    loading = speaking,
                )
            }
            Spacer(Modifier.height(24.dp))
            Text(
                text = assistant.name.ifBlank { "AI 助手" },
                style = MaterialTheme.typography.headlineSmallEmphasized,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "%02d:%02d".format(elapsedMs / 60_000, elapsedMs / 1_000 % 60),
                style = MaterialTheme.typography.titleMedium,
                color = colors.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
            Text(
                text = if (state.isMuted && state.phase != VoicePhase.Speaking) "麦克风已静音" else when (state.phase) {
                    VoicePhase.Connecting -> "正在连接"
                    VoicePhase.Listening -> "正在聆听"
                    VoicePhase.Transcribing -> "正在识别"
                    VoicePhase.Waiting -> "等待回复"
                    VoicePhase.Speaking -> "正在说话"
                    VoicePhase.Error -> "通话遇到问题"
                    VoicePhase.Off -> "通话已结束"
                } + if (state.pendingReplies > 0) " · ${state.pendingReplies} 条待回复" else "",
                style = MaterialTheme.typography.labelLargeEmphasized,
                color = if (state.phase == VoicePhase.Error) colors.error else colors.primary,
            )
            Spacer(Modifier.height(16.dp))

            val subtitle = aiSubtitle.ifBlank { state.lastReply }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.7f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (state.transcript.isNotBlank()) {
                    Surface(color = colors.secondaryContainer, shape = MaterialTheme.shapes.large) {
                        Text(
                            text = state.transcript,
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            style = MaterialTheme.typography.bodyLarge,
                            color = colors.onSecondaryContainer,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (subtitle.isNotBlank()) {
                    Surface(color = colors.primaryContainer, shape = MaterialTheme.shapes.large) {
                        Text(
                            text = subtitle,
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            style = MaterialTheme.typography.bodyLarge,
                            color = colors.onPrimaryContainer,
                            // Keep long replies readable by scrolling the subtitle area.
                        )
                    }
                }
                (state.error ?: routeError ?: routeMessage)?.takeIf(String::isNotBlank)?.let { message ->
                    Text(
                        text = message,
                        modifier = Modifier.fillMaxWidth(),
                        color = if (state.error != null || routeError != null) colors.error else colors.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(36.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ControlButton(
                    label = if (state.isMuted) "取消静音" else "静音",
                    selected = state.isMuted,
                    onClick = onToggleMute,
                ) {
                    Icon(
                        if (state.isMuted) HugeIcons.Cancel01 else HugeIcons.Mic01,
                        contentDescription = if (state.isMuted) "取消静音" else "静音",
                    )
                }
                FilledIconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(68.dp),
                    shape = CircleShape,
                    colors = androidx.compose.material3.IconButtonDefaults.filledIconButtonColors(
                        containerColor = colors.error,
                        contentColor = colors.onError,
                    ),
                ) {
                    Icon(
                        VoiceCallPhoneIcon,
                        contentDescription = "挂断",
                        modifier = Modifier.rotate(135f),
                    )
                }
                ControlButton(
                    label = if (speakerEnabled) "切换到听筒" else "免提",
                    selected = speakerEnabled,
                    onClick = ::toggleSpeaker,
                ) {
                    Icon(HugeIcons.VolumeHigh, contentDescription = null)
                }
            }
        }
    }
}

@Composable
private fun ControlButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Surface(
            onClick = onClick,
            modifier = Modifier.size(56.dp).clip(CircleShape),
            shape = CircleShape,
            color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
        ) {
            Box(contentAlignment = Alignment.Center) { content() }
        }
        Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
}
