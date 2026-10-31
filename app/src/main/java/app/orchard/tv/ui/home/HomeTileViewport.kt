package app.orchard.tv.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt

/** A fixed viewport for tiles; the shelf clock and status pill remain outside it. */
@Composable
internal fun HomeTileViewport(scroll: () -> Float, content: @Composable BoxScope.() -> Unit) {
    // Padding before clipping reserves the header for both drawing and pointer hit testing.
    // Keep all tile positions in the original full-screen coordinates by undoing that padding
    // inside the viewport. Scaled faces, focus frames and shadows are clipped together.
    Box(Modifier.fillMaxSize().padding(top = RowAboveTop).clipToBounds()) {
        Box(
            Modifier.fillMaxSize().offset {
                IntOffset(0, -RowAboveTop.roundToPx() - scroll().roundToInt())
            },
            content = content,
        )
    }
}
