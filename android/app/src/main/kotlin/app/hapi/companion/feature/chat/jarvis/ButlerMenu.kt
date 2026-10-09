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
 * instead. Harness hand-overs are the server's job, so there is no entry. The
 * session list and new-session entries were dropped too (주현님 10-09) — the
 * app only has two rooms, the butler and 「잡담」, and ⋮ switches between them.
 */
data class ButlerMenu(
    /** Header title of this room — 「집사」 or 「잡담」. */
    val title: String,
    /** The other room's name, shown as the first ⋮ entry. */
    val otherRoom: String,
    val onOtherRoom: () -> Unit,
    /** Step 7: opens the 「폰 활동」 settings dialog. */
    val onPhoneActivity: () -> Unit = {},
)

/** Leading ⋮ entries for butler mode: the other room, phone activity, and [onAdvanced] (model/permission sheet). */
@Composable
internal fun ButlerMenuItems(menu: ButlerMenu, onAdvanced: () -> Unit, close: () -> Unit) {
    DropdownMenuItem(
        text = { Text(menu.otherRoom) },
        onClick = { close(); menu.onOtherRoom() },
        modifier = Modifier.testTag("butler-menu-room"),
    )
    DropdownMenuItem(
        text = { Text(stringResource(R.string.jarvis_menu_phone_activity)) },
        onClick = { close(); menu.onPhoneActivity() },
        modifier = Modifier.testTag("butler-menu-phone-activity"),
    )
    DropdownMenuItem(
        text = { Text(stringResource(R.string.jarvis_menu_advanced)) },
        onClick = { close(); onAdvanced() },
        modifier = Modifier.testTag("butler-menu-advanced"),
    )
    HorizontalDivider()
}
