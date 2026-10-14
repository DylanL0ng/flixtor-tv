package dev.personal.flixtortv

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.google.zxing.MultiFormatWriter
import com.google.zxing.BarcodeFormat

private enum class SettingsPage { ROOT, ACCOUNT, PROFILES, PLAYBACK, QUALITY, AUTOPLAY, BUFFER, SKIP, UPDATES, DISPLAY, LIBRARY }

@Composable internal fun AccountScreen(s: TvState, vm: TvViewModel, fitFocus: FocusRequester,
    contentFocus: FocusRequester, onContentFocusChanged: (Boolean) -> Unit,
    restoringFromFit: Boolean = false, fit: () -> Unit) {
    var page by rememberSaveable {
        mutableStateOf(if (s.settingsEntry == SettingsEntry.ACCOUNT) SettingsPage.ACCOUNT else SettingsPage.ROOT)
    }
    var origin by rememberSaveable { mutableStateOf(SettingsPage.ACCOUNT) }
    var appliedNavigation by rememberSaveable { mutableIntStateOf(s.settingsNavigationId) }
    var appliedEntry by rememberSaveable { mutableStateOf(s.settingsEntry) }
    var returning by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordLogin by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    // SaveableStateHolder restoration does not validate rememberSaveable inputs.
    // A new entry wins over the saved page; screen-fit re-entry keeps the same intent.
    if (appliedNavigation != s.settingsNavigationId || appliedEntry != s.settingsEntry) {
        page = if (s.settingsEntry == SettingsEntry.ACCOUNT) SettingsPage.ACCOUNT else SettingsPage.ROOT
        origin = SettingsPage.ACCOUNT
        appliedNavigation = s.settingsNavigationId
        appliedEntry = s.settingsEntry
        returning = false
        email = ""; password = ""; passwordLogin = false; confirmClear = false
    }
    val rootList = androidx.compose.foundation.lazy.rememberLazyListState()
    val categories = listOf(SettingsPage.ACCOUNT, SettingsPage.PROFILES, SettingsPage.PLAYBACK,
        SettingsPage.SKIP, SettingsPage.UPDATES, SettingsPage.DISPLAY, SettingsPage.LIBRARY)
    val titles = mapOf(
        SettingsPage.ROOT to stringResource(R.string.settings),
        SettingsPage.ACCOUNT to stringResource(R.string.account_settings),
        SettingsPage.PROFILES to stringResource(R.string.profiles),
        SettingsPage.PLAYBACK to stringResource(R.string.playback_settings),
        SettingsPage.QUALITY to stringResource(R.string.quality),
        SettingsPage.AUTOPLAY to stringResource(R.string.autoplay_next),
        SettingsPage.BUFFER to stringResource(R.string.buffering_mode),
        SettingsPage.SKIP to stringResource(R.string.skip_segments),
        SettingsPage.UPDATES to stringResource(R.string.updates),
        SettingsPage.DISPLAY to stringResource(R.string.display),
        SettingsPage.LIBRARY to stringResource(R.string.library_settings),
    )
    val optionFocus = remember { FocusRequester() }
    var playbackOrigin by rememberSaveable { mutableStateOf(SettingsPage.QUALITY) }
    val quality = s.profile?.quality ?: 0
    val qualities = qualityChoices()
    val autoplay = s.profile?.autoplay != false
    val autoplayOptions = autoplayChoices()
    fun open(target: SettingsPage) {
        if (page == SettingsPage.ROOT) origin = target
        if (page == SettingsPage.PLAYBACK) playbackOrigin = target
        page = target
    }
    fun back() {
        when {
            confirmClear -> confirmClear = false
            passwordLogin -> { passwordLogin = false; password = "" }
            page in listOf(SettingsPage.QUALITY, SettingsPage.AUTOPLAY, SettingsPage.BUFFER) -> page = SettingsPage.PLAYBACK
            else -> {
                if (page == SettingsPage.ACCOUNT) vm.stopPairing()
                page = SettingsPage.ROOT
                returning = true
            }
        }
    }
    BackHandler(page != SettingsPage.ROOT && page != SettingsPage.PROFILES) { back() }
    LaunchedEffect(s.account.signedIn) {
        if (s.account.signedIn) { email = ""; password = ""; passwordLogin = false }
    }
    // Category navigation never changes the active service/catalog screen.
    LaunchedEffect(s.settingsNavigationId, s.settingsEntry) {
        if (page == SettingsPage.ROOT) rootList.scrollToItem(0)
    }
    LaunchedEffect(page, s.settingsNavigationId, s.settingsEntry) {
        if (page == SettingsPage.ROOT && returning) {
            rootList.scrollToItem(categories.indexOf(origin).coerceAtLeast(0))
            withFrameNanos { }
            contentFocus.requestFocus()
            returning = false
        } else if (page == SettingsPage.PLAYBACK) {
            withFrameNanos { }; optionFocus.requestFocus()
        } else if (!restoringFromFit && page !in listOf(SettingsPage.ROOT, SettingsPage.PROFILES, SettingsPage.QUALITY, SettingsPage.AUTOPLAY, SettingsPage.BUFFER)) {
            withFrameNanos { }; contentFocus.requestFocus()
        }
    }
    Column(Modifier.fillMaxSize().onFocusChanged { onContentFocusChanged(it.hasFocus) }
        .focusGroup()
        .padding(horizontal = 24.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(titles.getValue(page), fontSize = 30.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
        if (page == SettingsPage.ROOT) {
            Text(s.profile?.name.orEmpty(), color = TextSecondary)
            Text(accountLabel(s.account), color = Accent)
            LazyColumn(Modifier.weight(1f).fillMaxWidth(.88f), state = rootList, contentPadding = PaddingValues(vertical = 4.dp)) {
                items(categories, key = { it.name }) { category ->
                    val summary = when (category) {
                        SettingsPage.ACCOUNT -> accountLabel(s.account)
                        SettingsPage.PROFILES -> s.profile?.name
                        else -> null
                    }
                    MenuRow(titles.getValue(category), value = summary,
                        modifier = if (category == origin) Modifier.focusRequester(contentFocus) else Modifier) { open(category) }
                }
            }
        } else if (page == SettingsPage.PROFILES) {
            Box(Modifier.weight(1f)) {
                ProfilesScreen(s, vm, initialManage = true, parentBack = ::back, entryFocus = contentFocus)
            }
        } else {
            MenuRow(stringResource(R.string.back), modifier = Modifier.focusRequester(contentFocus),
                chevron = false, onClick = ::back)
            when (page) {
                SettingsPage.QUALITY -> {
                    Text(stringResource(R.string.quality_help), color = TextSecondary)
                    ChoiceList(qualities, quality, Modifier.weight(1f).fillMaxWidth(.88f)) {
                        vm.setQuality(it); page = SettingsPage.PLAYBACK
                    }
                }
                SettingsPage.AUTOPLAY -> {
                    Text(stringResource(R.string.autoplay_help), color = TextSecondary)
                    ChoiceList(autoplayOptions, autoplay, Modifier.weight(1f).fillMaxWidth(.88f)) {
                        vm.setAutoplay(it); page = SettingsPage.PLAYBACK
                    }
                }
                SettingsPage.BUFFER -> {
                    Text(stringResource(R.string.buffer_help), color = TextSecondary)
                    ChoiceList(bufferChoices(), s.profile?.largerBuffer == true, Modifier.weight(1f).fillMaxWidth(.88f)) {
                        vm.setBuffering(it); page = SettingsPage.PLAYBACK
                    }
                }
                SettingsPage.PLAYBACK -> {
                    MenuRow(stringResource(R.string.quality), value = qualities.firstOrNull { it.value == quality }?.label,
                        modifier = if (playbackOrigin == SettingsPage.QUALITY) Modifier.focusRequester(optionFocus) else Modifier) { open(SettingsPage.QUALITY) }
                    MenuRow(stringResource(R.string.autoplay_next), value = autoplayOptions.first { it.value == autoplay }.label,
                        modifier = if (playbackOrigin == SettingsPage.AUTOPLAY) Modifier.focusRequester(optionFocus) else Modifier) { open(SettingsPage.AUTOPLAY) }
                    MenuRow(stringResource(R.string.buffering_mode), value = bufferChoices().first { it.value == (s.profile?.largerBuffer == true) }.label,
                        modifier = if (playbackOrigin == SettingsPage.BUFFER) Modifier.focusRequester(optionFocus) else Modifier) { open(SettingsPage.BUFFER) }
                }
                else -> LazyColumn(Modifier.weight(1f).fillMaxWidth(.88f), contentPadding = PaddingValues(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    when (page) {
                        SettingsPage.ACCOUNT -> {
                            item {
                                Text(stringResource(R.string.vip_title), fontSize = 24.sp, color = TextPrimary)
                                Text(stringResource(R.string.vip_help), color = TextSecondary, modifier = Modifier.padding(top = 8.dp))
                            }
                            item {
                                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    if (s.loading) Loading()
                                    s.failure?.let { FailureMessage(it, if (s.failure == Failure.EXPIRED) vm::startPairing else vm::refresh) }
                                    Text(accountLabel(s.account), color = Accent)
                                    if (s.account.status == AccountStatus.SIGNED_IN) Text(stringResource(R.string.account_entitlement_unknown), color = Muted)
                                    Action(stringResource(R.string.refresh_account), enabled = !s.loading && !s.pairing) { vm.refresh() }
                                    if (s.account.signedIn) Action(stringResource(R.string.clear_session), enabled = !s.loading) { vm.clearSession() }
                                    else {
                                        if (s.pairing) Action(stringResource(R.string.cancel_pair)) { vm.stopPairing() }
                                        else Action(stringResource(R.string.pair_phone), enabled = !s.loading) { vm.startPairing() }
                                        if (!s.pairing && !passwordLogin) Action(stringResource(R.string.sign_in_password), enabled = !s.loading) { passwordLogin = true }
                                        val pair = s.pair
                                        if (pair != null) PairingView(pair) else if (s.pairing) Loading()
                                    }
                                }
                            }
                            if (!s.account.signedIn && !s.pairing && passwordLogin) item {
                                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Field(stringResource(R.string.email), email, { email = it.take(254) }, keyboard = KeyboardType.Email)
                                    Field(stringResource(R.string.password), password, { password = it.take(64) }, password = true)
                                    Action(stringResource(R.string.sign_in), enabled = !s.loading && android.util.Patterns.EMAIL_ADDRESS.matcher(email.trim()).matches() && password.length in 8..64) {
                                        vm.login(email, password); password = ""
                                    }
                                    Action(stringResource(R.string.cancel)) { passwordLogin = false; password = "" }
                                }
                            }
                        }
                        SettingsPage.SKIP -> item {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                SkipPreferences(vm, false, s.skipRevision)
                                Text(stringResource(R.string.introdb_key_help), color = TextSecondary)
                                Text(stringResource(if (introDbConfigured) R.string.introdb_key_stored else R.string.introdb_key_unavailable),
                                    color = if (introDbConfigured) Accent else Muted)
                            }
                        }
                        SettingsPage.UPDATES -> item { UpdateSettings(vm) }
                        SettingsPage.DISPLAY -> item {
                            MenuRow(stringResource(R.string.screen_fit), modifier = Modifier.focusRequester(fitFocus), onClick = fit)
                        }
                        SettingsPage.LIBRARY -> {
                            item {
                                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text(stringResource(R.string.local_library), color = Muted)
                                    Action(stringResource(R.string.clear_history)) { confirmClear = true }
                                    Text(stringResource(R.string.personal_client), color = Muted, fontSize = 12.sp)
                                }
                            }
                            if (confirmClear) item {
                                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                    Text(stringResource(R.string.clear_history_title), fontSize = 20.sp)
                                    Text(stringResource(R.string.clear_history_help), color = Muted)
                                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                        Action(stringResource(R.string.cancel)) { confirmClear = false }
                                        Action(stringResource(R.string.clear)) { vm.clearLibrary(); confirmClear = false }
                                    }
                                }
                            }
                        }
                        else -> Unit
                    }
                }
            }
        }
    }
}

@Composable private fun PairingView(pair: PairCode) {
    val bitmap = remember(pair.url) {
        val matrix = MultiFormatWriter().encode(pair.url, BarcodeFormat.QR_CODE, 300, 300)
        Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888).apply {
            val pixels = IntArray(90000) { i -> if (matrix[i % 300, i / 300]) android.graphics.Color.BLACK else android.graphics.Color.WHITE }
            setPixels(pixels, 0, 300, 0, 0, 300, 300)
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.foundation.Image(bitmap.asImageBitmap(), stringResource(R.string.qr_description), Modifier.size(190.dp))
        Column(Modifier.widthIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.pair_help), fontSize = 18.sp)
            Text(pair.code, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Text(pair.url, color = Accent, fontSize = 14.sp)
            Text(stringResource(R.string.pair_expiry), color = Muted)
        }
    }
}
