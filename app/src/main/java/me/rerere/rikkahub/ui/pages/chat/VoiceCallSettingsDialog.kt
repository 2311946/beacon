package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.getSelectedTTSProvider
import me.rerere.rikkahub.ui.pages.setting.SettingVM
import me.rerere.rikkahub.ui.pages.setting.components.TTSProviderConfigure
import me.rerere.tts.provider.TTSProviderSetting
import org.koin.androidx.compose.koinViewModel

@Composable
fun VoiceCallSettingsDialog(
    settings: Settings,
    onDismiss: () -> Unit,
) {
    val vm: SettingVM = koinViewModel()
    val globalProvider = settings.getSelectedTTSProvider() ?: TTSProviderSetting.SystemTTS()
    var voiceCallProvider by remember(settings.displaySetting.voiceCallTtsProvider) {
        mutableStateOf(settings.displaySetting.voiceCallTtsProvider)
    }
    val editingProvider = voiceCallProvider ?: globalProvider

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.8f)
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "通话音色与语音服务",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )

            TTSProviderConfigure(
                setting = editingProvider,
                modifier = Modifier.weight(1f),
                onValueChange = { voiceCallProvider = it },
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                TextButton(
                    onClick = { voiceCallProvider = null },
                    enabled = voiceCallProvider != null,
                ) {
                    Text("使用全局语音配置")
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.cancel))
                }
                Button(
                    onClick = {
                        vm.updateSettings { latest ->
                            latest.copy(
                                displaySetting = latest.displaySetting.copy(
                                    voiceCallTtsProvider = voiceCallProvider,
                                ),
                            )
                        }
                        onDismiss()
                    },
                ) {
                    Text(stringResource(R.string.chat_page_save))
                }
            }
        }
    }
}
