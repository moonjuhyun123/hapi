package app.hapi.companion.feature.chat.jarvis

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.hapi.companion.R
import app.hapi.companion.feature.chat.LocalChatInspection
import app.hapi.companion.feature.chat.toolSummaryPresentation
import app.hapi.companion.ui.theme.HapiTypography
import app.hapi.companion.ui.theme.hapi
import kotlinx.coroutines.delay

/**
 * One-line "what is happening now" strip above the composer:
 * thinking · using a tool (which tool, on what) · step count · writing · done.
 * Built only from the hub's common block model, so every harness looks the
 * same. Tapping a tool line opens that tool's original input/output.
 */
@Composable
internal fun ActivityStatusBar(
    /** From [deriveActivity] over the UNPROJECTED blocks (see ChatViewModel). */
    snapshot: ActivitySnapshot?,
    basePath: String?,
    modifier: Modifier = Modifier,
) {
    snapshot ?: return
    val live = snapshot.phase != ActivityPhase.Done
    val inspection = LocalChatInspection.current
    val resources = LocalContext.current.resources
    val toolId = snapshot.toolId
    val toolLine = remember(snapshot.toolCall, basePath, resources) {
        snapshot.toolCall?.let { toolSummaryPresentation(it, basePath, resources) }
    }

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    if (live) {
        LaunchedEffect(snapshot.turnStartedAt) {
            while (true) {
                now = System.currentTimeMillis()
                delay(1_000)
            }
        }
    }
    val seconds = elapsedSeconds(snapshot.turnStartedAt, if (live) now else snapshot.lastActivityAt)

    val phaseText = stringResource(
        when (snapshot.phase) {
            ActivityPhase.Thinking -> R.string.jarvis_activity_thinking
            ActivityPhase.UsingTool -> R.string.jarvis_activity_using_tool
            ActivityPhase.AwaitingApproval -> R.string.jarvis_activity_awaiting_approval
            ActivityPhase.Writing -> R.string.jarvis_activity_writing
            ActivityPhase.Done -> R.string.jarvis_activity_done
        },
    )
    val stepText = when {
        snapshot.toolCalls == 0 -> null
        live -> stringResource(R.string.jarvis_activity_step, snapshot.toolCalls)
        else -> stringResource(R.string.jarvis_activity_tools_used, snapshot.toolCalls)
    }
    val timeText = seconds?.let {
        if (it < 60) stringResource(R.string.jarvis_activity_seconds, it.toInt())
        else stringResource(R.string.jarvis_activity_minutes, (it / 60).toInt(), (it % 60).toInt())
    }
    val openLabel = stringResource(R.string.jarvis_activity_open_tool)

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (snapshot.phase == ActivityPhase.AwaitingApproval) {
            MaterialTheme.colorScheme.tertiaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .testTag("jarvis-activity"),
    ) {
        Row(
            modifier = Modifier
                .then(
                    if (toolId != null && inspection != null) {
                        Modifier.clickable(onClickLabel = openLabel) { inspection.openTool(toolId) }
                    } else {
                        Modifier
                    },
                )
                .heightIn(min = 40.dp)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            when (snapshot.phase) {
                ActivityPhase.Done -> Text("✓", color = MaterialTheme.hapi.hint)
                ActivityPhase.AwaitingApproval -> Text("✋")
                else -> CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
            }
            Column(Modifier.weight(1f)) {
                Row {
                    // Phase changes are announced; the ticking timer is not.
                    Text(
                        text = listOfNotNull(phaseText, stepText).joinToString(" · "),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false).semantics { liveRegion = LiveRegionMode.Polite },
                    )
                    timeText?.let {
                        Text(
                            text = " · $it",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.hapi.hint,
                            maxLines = 1,
                        )
                    }
                }
                toolLine?.let { line ->
                    Text(
                        text = listOfNotNull(line.icon, line.title, line.subtitle).joinToString(" "),
                        style = HapiTypography.caption,
                        color = MaterialTheme.hapi.hint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (toolId != null && inspection != null) Text("›", color = MaterialTheme.hapi.hint)
        }
    }
}
