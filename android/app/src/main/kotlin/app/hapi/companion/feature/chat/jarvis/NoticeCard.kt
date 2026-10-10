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

/** A server notice as a card: 「🔔 알림 · 시각」 header, the notice text below. */
@Composable
fun NoticeCard(block: AgentTextBlock, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f),
        shape = RoundedCornerShape(14.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(
                "🔔 알림 · " + HM.format(Instant.ofEpochMilli(block.createdAt).atZone(ZoneId.of("Asia/Seoul"))),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            AgentTextBlockView(block)
        }
    }
}
