package app.hapi.companion.feature.chat.jarvis

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.hapi.companion.feature.chat.blocks.AgentTextBlockView
import app.hapi.protocol.chat.AgentTextBlock
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val HM = DateTimeFormatter.ofPattern("M/d HH:mm")

/** True for a server notice row (step 19) — drawn as its own card, not running into the butler's words. */
fun isNoticeBlock(block: AgentTextBlock): Boolean = block.id.startsWith(NOTICE_ID_PREFIX)

/**
 * Every butler message as its own card (주현님 10-10 「모든 말을 이렇게 따로 카드로」): header
 * 「집사 · 시각」, or 「🔔 알림 · 시각」 for a server notice (tinted stronger), the text below.
 */
@Composable
fun ButlerCard(block: AgentTextBlock, modifier: Modifier = Modifier) {
    val notice = isNoticeBlock(block)
    Surface(
        color = if (notice) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        shape = RoundedCornerShape(14.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(
                (if (notice) "🔔 알림 · " else "집사 · ") + HM.format(Instant.ofEpochMilli(block.createdAt).atZone(ZoneId.of("Asia/Seoul"))),
                fontSize = 12.sp,
                color = if (notice) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            AgentTextBlockView(block)
        }
    }
}
