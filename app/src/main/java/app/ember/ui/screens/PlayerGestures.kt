package app.ember.ui.screens

import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** A light tick, like a keyboard key: confirms a skip without looking. */
@Composable
fun rememberTick(): () -> Unit {
    val view = LocalView.current
    return remember(view) { { view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) } }
}

/**
 * Double-tap the left half of [content] to skip back, the right half to skip forward
 * (like YouTube). A small label flashes on the side that was tapped.
 */
@Composable
fun DoubleTapSkip(
    skipBackSec: Int,
    skipForwardSec: Int,
    onBack: () -> Unit,
    onForward: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val tick = rememberTick()
    val back by rememberUpdatedState(onBack)
    val forward by rememberUpdatedState(onForward)
    // (forward?, how many taps in a row) so repeated double-taps add up: +30s, +60s…
    var flash by remember { mutableStateOf<Pair<Boolean, Int>?>(null) }
    Box(
        modifier.pointerInput(Unit) {
            detectTapGestures(onDoubleTap = { at ->
                val fwd = at.x > size.width / 2f
                tick()
                if (fwd) forward() else back()
                val prev = flash
                flash = fwd to (if (prev != null && prev.first == fwd) prev.second + 1 else 1)
            })
        },
        contentAlignment = Alignment.Center,
    ) {
        content()
        val f = flash
        AnimatedVisibility(
            visible = f != null,
            enter = fadeIn() + scaleIn(initialScale = 0.8f),
            exit = fadeOut(),
            modifier = Modifier
                .align(if (f?.first == true) Alignment.CenterEnd else Alignment.CenterStart)
                .padding(horizontal = 14.dp),
        ) {
            val shown = f ?: (true to 1)
            val secs = (if (shown.first) skipForwardSec else skipBackSec) * shown.second
            Row(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(if (shown.first) "" else "«", color = Color.White, style = MaterialTheme.typography.titleMedium)
                if (!shown.first) Spacer(Modifier.width(6.dp))
                Text(
                    (if (shown.first) "+" else "−") + formatSkip(secs),
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                )
                if (shown.first) Spacer(Modifier.width(6.dp))
                Text(if (shown.first) "»" else "", color = Color.White, style = MaterialTheme.typography.titleMedium)
            }
        }
    }
    LaunchedEffect(flash) {
        if (flash != null) {
            delay(800)
            flash = null
        }
    }
}

internal fun formatSkip(sec: Int): String = when {
    sec < 60 -> "${sec}s"
    sec % 60 == 0 -> "${sec / 60}m"
    else -> "${sec / 60}m ${sec % 60}s"
}
