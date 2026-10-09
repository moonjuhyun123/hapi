package app.hapi.companion.feature.chat.jarvis

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.hapi.companion.R
import app.hapi.companion.ui.theme.hapi
import app.hapi.protocol.chat.UserTextBlock

/**
 * A cron's "speak first" instruction (`jarvis-out-…`) is not something the
 * user said: one centered chip instead of a user bubble. Tapping it shows the
 * instruction text, tapping again folds it.
 */
@Composable
internal fun OutreachChip(block: UserTextBlock, modifier: Modifier = Modifier) {
    var expanded by rememberSaveable(block.id) { mutableStateOf(false) }
    val time = remember(block.createdAt) {
        java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(block.createdAt))
    }
    Box(modifier.fillMaxWidth().testTag("jarvis-outreach-${block.id}"), contentAlignment = Alignment.Center) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.widthIn(max = 520.dp).animateContentSize(),
        ) {
            Column(
                Modifier
                    .clickable(role = Role.Button) { expanded = !expanded }
                    .heightIn(min = 36.dp)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    stringResource(R.string.jarvis_outreach_chip, time),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.hapi.hint,
                    textAlign = TextAlign.Center,
                )
                if (expanded) {
                    SelectionContainer {
                        Text(
                            block.text,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 6.dp).testTag("jarvis-outreach-text"),
                        )
                    }
                }
            }
        }
    }
}
