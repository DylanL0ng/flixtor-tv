package dev.personal.flixtortv

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Text

internal data class MenuChoice<T>(val value: T, val label: String, val key: String = value.toString())

/** Scroll before requesting focus, including when a selected track disappears. */
@Composable
internal fun <T> ChoiceList(
    choices: List<MenuChoice<T>>,
    selected: T?,
    modifier: Modifier = Modifier,
    select: (T) -> Unit,
) {
    val list = rememberLazyListState()
    val focus = remember { FocusRequester() }
    val initial = choices.indexOfFirst { it.value == selected }.coerceAtLeast(0)
    LaunchedEffect(choices.map { it.key }, initial) {
        if (choices.isNotEmpty()) {
            list.scrollToItem(initial)
            withFrameNanos { }
            focus.requestFocus()
        }
    }
    LazyColumn(modifier.fillMaxWidth(), state = list, contentPadding = PaddingValues(vertical = 4.dp)) {
        itemsIndexed(choices, key = { _, choice -> choice.key }) { index, choice ->
            MenuRow(choice.label, selected = choice.value == selected,
                modifier = if (index == initial) Modifier.focusRequester(focus) else Modifier,
                onClick = { select(choice.value) })
        }
    }
}

@Composable
internal fun <T> ChoiceDialog(
    title: String,
    choices: List<MenuChoice<T>>,
    selected: T?,
    dismiss: () -> Unit,
    select: (T) -> Unit,
) {
    val height = LocalConfiguration.current.screenHeightDp.dp
    val back = remember { FocusRequester() }
    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        GlassSurface(Modifier.trackPlaybackInteraction().padding(LocalSafePadding.current).widthIn(max = 520.dp).fillMaxWidth()
            .heightIn(max = height * .8f)) {
            Text(title, color = TextPrimary, fontSize = 24.sp)
            MenuRow(stringResource(R.string.back), chevron = false, modifier = Modifier.focusRequester(back), onClick = dismiss)
            if (choices.isEmpty()) {
                Text(stringResource(R.string.no_tracks), color = TextSecondary)
                LaunchedEffect(Unit) { withFrameNanos { }; back.requestFocus() }
            } else ChoiceList(choices, selected, Modifier.weight(1f, fill = false), select)
        }
    }
}

/** Screen selectors own their popup and restore the same remote target on every exit. */
@Composable
internal fun <T> OptionPicker(
    label: String,
    choices: List<MenuChoice<T>>,
    selected: T,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
    select: (T) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    var returning by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    fun close() { open = false; returning = true }
    LaunchedEffect(open, returning) {
        if (!open && returning) {
            withFrameNanos { }
            if (choices.isNotEmpty()) focus.requestFocus()
            else focusManager.moveFocus(androidx.compose.ui.focus.FocusDirection.Next)
            returning = false
        }
    }
    MenuRow(label, modifier.focusRequester(focus), value = choices.firstOrNull { it.value == selected }?.label,
        enabled = choices.isNotEmpty(), leading = leading, onClick = { open = true })
    if (open) ChoiceDialog(label, choices, selected, ::close) { value -> select(value); close() }
}

@Composable
internal fun qualityChoices(): List<MenuChoice<Int>> = listOf(0, 720, 1080, 2160).map { height ->
    MenuChoice(height, if (height == 0) stringResource(R.string.auto_quality) else stringResource(R.string.up_to_resolution, height))
}

@Composable
internal fun autoplayChoices(): List<MenuChoice<Boolean>> = listOf(
    MenuChoice(true, stringResource(R.string.option_on)), MenuChoice(false, stringResource(R.string.option_off)),
)
