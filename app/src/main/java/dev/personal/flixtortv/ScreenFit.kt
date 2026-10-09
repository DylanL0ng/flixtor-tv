package dev.personal.flixtortv

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text

data class ScreenFit(val horizontal: Int = 5, val vertical: Int = 5) {
    init { require(horizontal in 0..10 && vertical in 0..10) }
}
internal val LocalSafePadding = staticCompositionLocalOf { PaddingValues(0.dp) }
internal class ScreenFitStore(context: Context) {
    private val prefs = context.getSharedPreferences("screen_fit", Context.MODE_PRIVATE)
    fun read() = ScreenFit(prefs.getInt("horizontal", 5).coerceIn(0, 10), prefs.getInt("vertical", 5).coerceIn(0, 10))
    fun save(fit: ScreenFit) { prefs.edit().putInt("horizontal", fit.horizontal).putInt("vertical", fit.vertical).apply() }
}

@Composable internal fun ScreenFitEditor(fit: ScreenFit, save: (ScreenFit) -> Unit, close: () -> Unit) {
    var draft by remember { mutableStateOf(fit) }
    val first = remember { FocusRequester() }
    BackHandler { close() }
    LaunchedEffect(Unit) { first.requestFocus() }
    BoxWithConstraints(Modifier.fillMaxSize().background(Midnight)) {
        val inset = PaddingValues(horizontal = maxWidth * (draft.horizontal / 100f), vertical = maxHeight * (draft.vertical / 100f))
        Box(Modifier.fillMaxSize().padding(inset).border(2.dp, Accent))
        LazyColumn(Modifier.align(Alignment.Center).fillMaxWidth(0.65f).fillMaxHeight(0.76f).background(Panel).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { Text(stringResource(R.string.screen_fit), fontSize = 28.sp); Text(stringResource(R.string.screen_fit_help), color = Muted) }
            item {
                Text(stringResource(R.string.horizontal_margin, draft.horizontal))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Action(stringResource(R.string.decrease_margin), Modifier.focusRequester(first)) { draft = draft.copy(horizontal = (draft.horizontal - 1).coerceAtLeast(0)) }
                    Action(stringResource(R.string.increase_margin)) { draft = draft.copy(horizontal = (draft.horizontal + 1).coerceAtMost(10)) }
                }
            }
            item {
                Text(stringResource(R.string.vertical_margin, draft.vertical))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Action(stringResource(R.string.decrease_margin)) { draft = draft.copy(vertical = (draft.vertical - 1).coerceAtLeast(0)) }
                    Action(stringResource(R.string.increase_margin)) { draft = draft.copy(vertical = (draft.vertical + 1).coerceAtMost(10)) }
                }
            }
            item { Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Action(stringResource(R.string.save)) { save(draft); close() }
                Action(stringResource(R.string.cancel), click = close)
                Action(stringResource(R.string.reset)) { draft = ScreenFit() }
            } }
        }
    }
}

@Composable internal fun accountLabel(account: Account): String = stringResource(when (account.status) {
    AccountStatus.CHECKING -> R.string.account_checking
    AccountStatus.GUEST -> R.string.account_guest
    AccountStatus.SIGNED_IN -> R.string.account_signed_in
    AccountStatus.VIP -> R.string.account_vip
    AccountStatus.EXPIRED -> R.string.account_expired
    AccountStatus.UNAVAILABLE -> R.string.account_unavailable
})

@Composable internal fun AccessMessage(account: Account) {
    Text(stringResource(if (account.signedIn) R.string.vip_signed_in_required else R.string.vip_guest_required), color = Accent)
}
