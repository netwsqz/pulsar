package com.example.pulsar

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.example.pulsar.data.companion.CompanionLibraryError
import com.example.pulsar.library.CompanionLibraryTrial
import com.example.pulsar.ui.components.LibraryScreenScaffold
import com.example.pulsar.ui.components.LoadState
import com.example.pulsar.ui.theme.Dimens
import kotlinx.coroutines.launch

/** 试用只保留独立配对表单；浏览、搜索与播放控件都使用原曲库界面。 */
@Composable
internal fun CompanionPairingScreen(
    trial: CompanionLibraryTrial,
    initialError: CompanionLibraryError?,
    onPaired: () -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var address by rememberSaveable { mutableStateOf("") }
    // 密钥不写入 Activity 状态，也不回填已保存的密钥。
    var accessKey by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(initialError) }
    BackHandler(onBack = onBack)
    Surface(Modifier.fillMaxSize()) {
        LibraryScreenScaffold(
            title = "电脑曲库（试用）",
            loadState = LoadState.Content,
            modifier = Modifier.navigationBarsPadding().imePadding(),
            actions = { TextButton(onClick = onBack) { Text("返回") } },
            loading = {},
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Dimens.SpaceMedium),
            ) {
                Text("手机和电脑连同一个网络。在电脑上启动 PC 服务，再填写地址和访问密钥。")
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !loading,
                    label = { Text("电脑地址") },
                    placeholder = { Text("例如 192.168.1.8:8900") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = accessKey,
                    onValueChange = { accessKey = it },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !loading,
                    label = { Text("访问密钥") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    singleLine = true,
                )
                error?.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Button(
                    onClick = {
                        val enteredKey = accessKey
                        accessKey = ""
                        loading = true
                        error = null
                        scope.launch {
                            val result = trial.pair(address, enteredKey)
                            loading = false
                            result.fold(
                                onSuccess = { onPaired() },
                                onFailure = { error = it as? CompanionLibraryError ?: CompanionLibraryError.Unavailable },
                            )
                        }
                    },
                    enabled = !loading && address.isNotBlank() && accessKey.isNotBlank(),
                ) { Text(if (loading) "连接中…" else "配对并打开曲库") }
            }
        }
    }
}
