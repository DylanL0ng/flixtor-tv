package dev.personal.flixtortv

import android.view.KeyEvent
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Text

// Dialogs have their own input window; inherit the player's attendance callback across them.
internal val LocalPlaybackInteraction = staticCompositionLocalOf<() -> Unit> { {} }

@Composable
internal fun Modifier.trackPlaybackInteraction(): Modifier {
    val record by rememberUpdatedState(LocalPlaybackInteraction.current)
    return onPreviewKeyEvent {
        if (it.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) record()
        false
    }.pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            record()
        }
    }
}

@Composable
internal fun WatchingPrompt(seconds: Int, continueWatching: () -> Unit, stopWatching: () -> Unit) {
    val focus = remember { FocusRequester() }
    val title = stringResource(R.string.still_watching_title)
    val height = LocalConfiguration.current.screenHeightDp.dp
    GlassDialog(title, stopWatching,
        modifier = Modifier.padding(LocalSafePadding.current).widthIn(max = 520.dp)
            .heightIn(max = height * .85f).trackPlaybackInteraction().semantics { paneTitle = title },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
        actions = {
            GlassButton(stringResource(R.string.stop_watching), Modifier.weight(1f), onClick = stopWatching)
            GlassButton(stringResource(R.string.continue_watching), Modifier.weight(1f).focusRequester(focus),
                variant = GlassButtonVariant.PRIMARY, onClick = continueWatching)
        }) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(stringResource(R.string.still_watching_help), color = TextSecondary,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            Text(stringResource(R.string.still_watching_timeout, seconds), color = TextSecondary,
                modifier = Modifier.padding(top = 12.dp))
        }
        LaunchedEffect(Unit) { withFrameNanos { }; focus.requestFocus() }
    }
}
