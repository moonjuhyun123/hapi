package app.hapi.companion.feature.jarvis.activity

import android.content.Intent
import android.provider.Settings
import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import app.hapi.companion.R
import java.util.Date
import kotlinx.coroutines.launch

/**
 * 「폰 활동」 settings, opened from the butler ⋮ menu (step 7). On/off, the
 * collection entrance address + token, the usage-access state and the last
 * send. Kept out of the upstream settings screen to keep the fork's diff small.
 */
@Composable
internal fun PhoneActivityDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember(context) { ActivityPrefs(context) }
    val settings by prefs.settings.collectAsState(initial = null)
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf<String?>(null) }
    var token by remember { mutableStateOf<String?>(null) }
    var permitted by remember { mutableStateOf(UsageReader.hasPermission(context)) }

    // Coming back from the system 「사용 기록 접근」 screen: re-check.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) { permitted = UsageReader.hasPermission(context) }
    }

    val s = settings ?: return
    val urlText = url ?: s.url
    val tokenText = token ?: s.token

    fun save() = scope.launch {
        url?.let { prefs.setUrl(it) }
        token?.let { prefs.setToken(it) }
    }

    AlertDialog(
        onDismissRequest = { save(); onDismiss() },
        title = { Text(stringResource(R.string.jarvis_phone_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.jarvis_phone_explain), style = MaterialTheme.typography.bodyMedium)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.jarvis_phone_enable), modifier = Modifier.weight(1f))
                    Switch(checked = s.enabled, onCheckedChange = { on ->
                        scope.launch {
                            prefs.setEnabled(on)
                            if (on) PhoneActivityWorker.onAppOpened(context)
                        }
                    })
                }
                OutlinedTextField(
                    value = urlText,
                    onValueChange = { url = it },
                    label = { Text(stringResource(R.string.jarvis_phone_url)) },
                    placeholder = { Text("https://") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = tokenText,
                    onValueChange = { token = it },
                    label = { Text(stringResource(R.string.jarvis_phone_token)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    stringResource(if (permitted) R.string.jarvis_phone_permission_ok else R.string.jarvis_phone_permission_missing),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(statusLine(s), style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!permitted) {
                        OutlinedButton(onClick = {
                            context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }) { Text(stringResource(R.string.jarvis_phone_open_permission)) }
                    }
                    OutlinedButton(
                        enabled = s.enabled && permitted && urlText.isNotBlank(),
                        onClick = { save(); PhoneActivityWorker.sendNow(context) },
                    ) { Text(stringResource(R.string.jarvis_phone_send_now)) }
                }
            }
        },
        confirmButton = { TextButton(onClick = { save(); onDismiss() }) { Text(stringResource(R.string.jarvis_phone_close)) } },
    )
}

@Composable
private fun statusLine(s: ActivitySettings): String {
    val at = if (s.statusAt > 0) DateFormat.format("MM-dd HH:mm", Date(s.statusAt)).toString() else ""
    return when (s.status) {
        SendStatus.NONE -> stringResource(R.string.jarvis_phone_status_none)
        SendStatus.SENT -> stringResource(R.string.jarvis_phone_status_sent, at, s.statusDetail.toInt())
        SendStatus.NO_PERMISSION -> stringResource(R.string.jarvis_phone_status_no_permission, at)
        SendStatus.FAILED -> stringResource(R.string.jarvis_phone_status_failed, at, s.statusDetail.toString())
    }
}
