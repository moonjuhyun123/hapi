package app.hapi.companion.feature.chat.jarvis

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.hapi.companion.R
import app.hapi.companion.feature.chat.ChatInteractions
import app.hapi.companion.feature.chat.PermissionAction
import app.hapi.companion.feature.chat.PermissionRowOverride
import app.hapi.companion.feature.chat.blocks.EDIT_TOOLS
import app.hapi.companion.feature.chat.blocks.HIDE_ALLOW_FOR_SESSION
import app.hapi.companion.feature.chat.blocks.PendingPermissionFooter
import app.hapi.companion.feature.chat.blocks.isCodexUx
import app.hapi.companion.feature.chat.toolSummaryPresentation
import app.hapi.companion.ui.theme.HapiTypography
import app.hapi.companion.ui.theme.hapi
import app.hapi.protocol.chat.ChatToolCall
import app.hapi.protocol.chat.isAskUserQuestionToolName
import app.hapi.protocol.chat.isRequestUserInputToolName
import kotlinx.coroutines.delay

/** Buttons ignore taps for this long after the card appears (stray-tap guard). */
private const val APPEAR_GUARD_MS = 700L

/** An armed Approve button falls back to its first state after this long. */
private const val ARM_TIMEOUT_MS = 4_000L

/**
 * Pending tool request, phone-first: the agent's question as large one-tap
 * buttons, or — for a permission — a one-line summary first, the original
 * command/path folded underneath, and an Approve that needs two taps.
 *
 * The app does not judge what is dangerous; it shows every request the same
 * way. Wire payloads are the ones [PendingPermissionFooter] sends.
 */
@Composable
internal fun JarvisPendingRequest(
    tool: ChatToolCall,
    requestId: String,
    interactions: ChatInteractions,
    modifier: Modifier = Modifier,
    /** Opens the full input in the inspector; null when already there. */
    onOpenFull: (() -> Unit)? = null,
) {
    val override = interactions.permissionOverrides[requestId]
    if (isAskUserQuestionToolName(tool.name) || isRequestUserInputToolName(tool.name)) {
        JarvisQuestion(tool, requestId, interactions, override, modifier)
    } else {
        JarvisPermission(tool, requestId, interactions, override, modifier, onOpenFull)
    }
}

@Composable
private fun rememberAppearGuard(key: String): Boolean {
    var ready by remember(key) { mutableStateOf(false) }
    LaunchedEffect(key) {
        delay(APPEAR_GUARD_MS)
        ready = true
    }
    return ready
}

// ------------------------------------------------------------ questions --

@Composable
private fun JarvisQuestion(
    tool: ChatToolCall,
    requestId: String,
    interactions: ChatInteractions,
    override: PermissionRowOverride?,
    modifier: Modifier,
) {
    val quick = remember(tool.name, tool.input) { quickQuestion(tool) }
    if (quick == null || override == PermissionRowOverride.AlreadyHandled) {
        PendingPermissionFooter(tool, requestId, interactions.flavor, override, interactions.resolvePermission, modifier)
        return
    }
    val resolving = override == PermissionRowOverride.Resolving
    val ready = rememberAppearGuard(requestId)
    var typing by rememberSaveable(requestId) { mutableStateOf(false) }

    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        quick.header?.let {
            Text(it, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.hapi.hint)
        }
        if (quick.prompt.isNotEmpty()) Text(quick.prompt, style = MaterialTheme.typography.bodyLarge)
        quick.choices.forEachIndexed { index, choice ->
            FilledTonalButton(
                onClick = { interactions.resolvePermission(requestId, choice.action) },
                enabled = ready && !resolving,
                shape = RoundedCornerShape(14.dp),
                contentPadding = ButtonDefaults.ContentPadding,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("jarvis-choice-$index"),
            ) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(choice.label, style = MaterialTheme.typography.titleMedium)
                    choice.description?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { typing = !typing }, enabled = !resolving) {
                Text(stringResource(if (typing) R.string.jarvis_question_hide_form else R.string.jarvis_question_type_answer))
            }
            if (resolving) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        }
        if (typing) {
            PendingPermissionFooter(tool, requestId, interactions.flavor, override, interactions.resolvePermission)
        }
    }
}

// ---------------------------------------------------------- permissions --

@Composable
private fun JarvisPermission(
    tool: ChatToolCall,
    requestId: String,
    interactions: ChatInteractions,
    override: PermissionRowOverride?,
    modifier: Modifier,
    onOpenFull: (() -> Unit)?,
) {
    val resources = LocalContext.current.resources
    val summary = remember(tool.name, tool.input, tool.description, resources) {
        toolSummaryPresentation(tool, null, resources)
    }
    val raw = remember(tool.name, tool.input) { permissionRawText(tool) }
    var rawOpen by rememberSaveable(requestId) { mutableStateOf(false) }

    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            stringResource(
                when (permissionKind(tool)) {
                    PermissionKind.Command -> R.string.jarvis_perm_kind_command
                    PermissionKind.FileEdit -> R.string.jarvis_perm_kind_file_edit
                    PermissionKind.Web -> R.string.jarvis_perm_kind_web
                    PermissionKind.Other -> R.string.jarvis_perm_kind_other
                },
            ),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
        )
        Row(verticalAlignment = Alignment.Top) {
            Text(summary.icon, modifier = Modifier.padding(end = 8.dp))
            Column(Modifier.weight(1f)) {
                Text(summary.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium,
                    maxLines = 3, overflow = TextOverflow.Ellipsis)
                summary.subtitle?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.hapi.hint,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Row {
            TextButton(onClick = { rawOpen = !rawOpen }, modifier = Modifier.testTag("jarvis-perm-raw")) {
                Text(stringResource(if (rawOpen) R.string.jarvis_perm_hide_raw else R.string.jarvis_perm_show_raw))
            }
            onOpenFull?.let { open ->
                TextButton(onClick = open) { Text(stringResource(R.string.chat_view_full_input)) }
            }
        }
        if (rawOpen) {
            Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.hapi.codeBackground, modifier = Modifier.fillMaxWidth()) {
                Box(Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState()).padding(10.dp)) {
                    SelectionContainer { Text(raw, style = HapiTypography.code) }
                }
            }
        }
        JarvisApprovalButtons(tool, requestId, interactions, override)
    }
}

/**
 * Deny | Approve, spaced apart. Approve arms on the first tap ("tap again to
 * approve") and only acts on the second; both ignore taps for a moment after
 * the card appears. Session-wide grants stay behind "More options".
 */
@Composable
internal fun JarvisApprovalButtons(
    tool: ChatToolCall,
    requestId: String,
    interactions: ChatInteractions,
    override: PermissionRowOverride?,
    modifier: Modifier = Modifier,
) {
    if (override == PermissionRowOverride.AlreadyHandled) {
        Text(
            stringResource(R.string.chat_perm_already_handled),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.hapi.hint,
            modifier = modifier.padding(vertical = 6.dp),
        )
        return
    }
    val flavor = interactions.flavor
    val onAction = interactions.resolvePermission
    val resolving = override == PermissionRowOverride.Resolving
    val codex = isCodexUx(flavor, tool.name)
    val canAllowForSession = codex || tool.name !in HIDE_ALLOW_FOR_SESSION
    val canAllowAllEdits = flavor == "claude" && tool.name in EDIT_TOOLS
    val ready = rememberAppearGuard(requestId)
    var armed by remember(requestId) { mutableStateOf(false) }
    var moreOpen by remember(requestId) { mutableStateOf(false) }
    LaunchedEffect(armed) {
        if (armed) {
            delay(ARM_TIMEOUT_MS)
            armed = false
        }
    }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(
                onClick = { onAction(requestId, if (codex) PermissionAction.Abort else PermissionAction.Deny) },
                // Denying/aborting is a decision too: same stray-tap guard as Approve.
                enabled = ready && !resolving,
                modifier = Modifier.weight(1f).heightIn(min = 52.dp).testTag("jarvis-perm-deny"),
            ) {
                Text(
                    stringResource(if (codex) R.string.chat_perm_abort else R.string.chat_perm_deny),
                    color = MaterialTheme.colorScheme.error,
                )
            }
            val approveModifier = Modifier.weight(1f).heightIn(min = 52.dp).testTag("jarvis-perm-allow")
            val onApprove = {
                if (armed) {
                    armed = false
                    onAction(requestId, PermissionAction.Allow)
                } else {
                    armed = true
                }
            }
            if (armed) {
                Button(onClick = onApprove, enabled = ready && !resolving, modifier = approveModifier) {
                    Text(stringResource(R.string.jarvis_perm_allow_confirm), maxLines = 2)
                }
            } else {
                FilledTonalButton(onClick = onApprove, enabled = ready && !resolving, modifier = approveModifier) {
                    Text(stringResource(R.string.jarvis_perm_allow))
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (canAllowForSession || canAllowAllEdits) {
                Box {
                    TextButton(onClick = { moreOpen = true }, enabled = !resolving) {
                        Text(stringResource(R.string.jarvis_perm_more))
                    }
                    DropdownMenu(expanded = moreOpen, onDismissRequest = { moreOpen = false }) {
                        if (canAllowForSession) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.chat_perm_allow_for_session)) },
                                onClick = {
                                    moreOpen = false
                                    onAction(requestId, PermissionAction.AllowForSession)
                                },
                            )
                        }
                        if (canAllowAllEdits) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.chat_perm_allow_all_edits)) },
                                onClick = {
                                    moreOpen = false
                                    onAction(requestId, PermissionAction.AllowAllEdits)
                                },
                            )
                        }
                    }
                }
            }
            if (resolving) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        }
    }
}
