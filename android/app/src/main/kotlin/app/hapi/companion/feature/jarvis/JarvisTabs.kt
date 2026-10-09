package app.hapi.companion.feature.jarvis

import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.hapi.companion.R

/**
 * Bottom tabs of the butler app (step 17). 정본 9장 「앱에 넣는 것」 names four —
 * 집사 대화 · 홈 · 캘린더 · 운동 기록; this ships the two that exist so far.
 */
internal enum class JarvisTab(val label: Int, val glyph: () -> ImageVector) {
    Butler(R.string.jarvis_tab_butler, { ChatGlyph }),
    Workout(R.string.jarvis_tab_workout, { DumbbellGlyph }),
}

@Composable
internal fun JarvisTabBar(selected: JarvisTab, onSelect: (JarvisTab) -> Unit) {
    NavigationBar {
        JarvisTab.entries.forEach { tab ->
            NavigationBarItem(
                selected = tab == selected,
                onClick = { onSelect(tab) },
                icon = { Icon(tab.glyph(), contentDescription = null) },
                label = { Text(stringResource(tab.label)) },
            )
        }
    }
}

private fun glyph(name: String, pathData: String): ImageVector =
    ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        .apply {
            addPath(
                pathData = addPathNodes(pathData),
                fill = null,
                stroke = SolidColor(Color.Black), // Icon() recolors via tint
                strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()

/** Speech bubble with a tail. */
private val ChatGlyph: ImageVector by lazy { glyph("JarvisChat", "M5 5h14a1 1 0 0 1 1 1v9a1 1 0 0 1-1 1H10l-4 3.5V16H5a1 1 0 0 1-1-1V6a1 1 0 0 1 1-1z") }

/** Dumbbell: bar, two plates each side. */
private val DumbbellGlyph: ImageVector by lazy { glyph("JarvisDumbbell", "M7 12h10 M7 7v10 M17 7v10 M4 9.5v5 M20 9.5v5") }
