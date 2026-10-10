package app.hapi.companion.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineBreak

/**
 * Jarvis fork (docs/jarvis/CHANGES.md): keep Korean words (어절) whole at
 * line ends. [LineBreak.WordBreak.Phrase] maps to Android's
 * `LineBreakConfig.LINE_BREAK_WORD_STYLE_PHRASE` on API 33+; older devices
 * ignore it. The cheap Simple strategy is kept so long transcripts measure
 * as fast as before. Latin text is unaffected (it already breaks at spaces).
 */
internal val ReadableLineBreak = LineBreak(
    strategy = LineBreak.Strategy.Simple,
    strictness = LineBreak.Strictness.Normal,
    wordBreak = LineBreak.WordBreak.Phrase,
)

private fun TextStyle.readable(): TextStyle = copy(lineBreak = ReadableLineBreak)

/** Material defaults with [ReadableLineBreak] on every style. */
internal val ReadableTypography: Typography = Typography().run {
    Typography(
        displayLarge = displayLarge.readable(),
        displayMedium = displayMedium.readable(),
        displaySmall = displaySmall.readable(),
        headlineLarge = headlineLarge.readable(),
        headlineMedium = headlineMedium.readable(),
        headlineSmall = headlineSmall.readable(),
        titleLarge = titleLarge.readable(),
        titleMedium = titleMedium.readable(),
        titleSmall = titleSmall.readable(),
        bodyLarge = bodyLarge.readable(),
        bodyMedium = bodyMedium.readable(),
        bodySmall = bodySmall.readable(),
        labelLarge = labelLarge.readable(),
        labelMedium = labelMedium.readable(),
        labelSmall = labelSmall.readable(),
    )
}
