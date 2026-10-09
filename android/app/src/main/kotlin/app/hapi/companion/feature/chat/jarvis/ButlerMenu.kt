package app.hapi.companion.feature.chat.jarvis

import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.hapi.companion.R

/**
 * Butler-mode chrome for the chat screen (Jarvis fork). When a chat is shown
 * as the butler, the screen drops its back arrow and settings gear; session
 * list, new session and the advanced settings sheet move into the ⋮ menu
 * instead. Harness hand-overs are the server's job, so there is no entry.
 */
data class ButlerMenu(
    val onOpenSessions: () -> Unit,
    val onNewSession: () -> Unit,
)

/** Leading ⋮ entries for butler mode; [onAdvanced] opens the model/permission sheet. */
@Composable
internal fun ButlerMenuItems(menu: ButlerMenu, onAdvanced: () -> Unit, close: () -> Unit) {
    DropdownMenuItem(
        text = { Text(stringResource(R.string.jarvis_menu_sessions)) },
        onClick = { close(); menu.onOpenSessions() },
        modifier = Modifier.testTag("butler-menu-sessions"),
    )
    DropdownMenuItem(
        text = { Text(stringResource(R.string.jarvis_menu_new_session)) },
        onClick = { close(); menu.onNewSession() },
        modifier = Modifier.testTag("butler-menu-new-session"),
    )
    DropdownMenuItem(
        text = { Text(stringResource(R.string.jarvis_menu_advanced)) },
        onClick = { close(); onAdvanced() },
        modifier = Modifier.testTag("butler-menu-advanced"),
    )
    HorizontalDivider()
}
