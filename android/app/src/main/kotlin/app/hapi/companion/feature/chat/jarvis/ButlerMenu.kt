package app.hapi.companion.feature.chat.jarvis

import androidx.compose.material3.DropdownMenuItem
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
 * hub only holds the butler. (A second 「잡담」 room was tried the same day and
 * folded back: 정본 9장 [새-38].) The advanced sheet (model/permission/effort)
 * and scratchlist were dropped from the butler too — the server picks those.
 */
data class ButlerMenu(
    /** Header title — 「집사」. */
    val title: String,
    /** Step 7: opens the 「폰 활동」 settings dialog. */
    val onPhoneActivity: () -> Unit = {},
)

/** Leading ⋮ entry for butler mode: phone activity. Files follow as 「드라이브」 (upstream row, relabelled). */
@Composable
internal fun ButlerMenuItems(menu: ButlerMenu, close: () -> Unit) {
    DropdownMenuItem(
        text = { Text(stringResource(R.string.jarvis_menu_phone_activity)) },
        onClick = { close(); menu.onPhoneActivity() },
        modifier = Modifier.testTag("butler-menu-phone-activity"),
    )
}
