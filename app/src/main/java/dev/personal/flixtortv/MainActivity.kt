package dev.personal.flixtortv

import android.os.Bundle
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.*
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter

class MainActivity : ComponentActivity() {
    private val model by viewModels<TvViewModel>()
    private val installer = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (packageManager.canRequestPackageInstalls()) model.installUpdate()
    }
    fun installUpdate() {
        try {
            if (!packageManager.canRequestPackageInstalls()) {
                installer.launch(android.content.Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    android.net.Uri.parse("package:$packageName")))
            } else model.installUpdate()
        } catch (_: Exception) { model.checkUpdates() }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        androidx.core.view.WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Accent, onPrimary = Midnight,
                background = Midnight, onBackground = Color(0xFFF4F6FC), surface = Panel, onSurface = Color(0xFFF4F6FC))) {
                CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground) { TvApp(model) }
            }
        }
    }
}

@Composable private fun TvApp(vm: TvViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) vm.stopPairing() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    BackHandler(state.screen !in listOf(Screen.HOME, Screen.PROFILES)) { vm.back() }
    val context = LocalContext.current
    val updateState by vm.updates.state.collectAsStateWithLifecycle()
    val lifecycleState by owner.lifecycle.currentStateFlow.collectAsState()
    var attemptedVersion by remember { mutableStateOf("") }
    LaunchedEffect(updateState.phase, state.screen, lifecycleState) {
        if (updateState.phase == UpdatePhase.READY && state.screen != Screen.PLAYER &&
            lifecycleState == Lifecycle.State.RESUMED && attemptedVersion != updateState.version &&
            context.packageManager.canRequestPackageInstalls()) {
            attemptedVersion = updateState.version
            (context as? MainActivity)?.installUpdate()
        }
    }
    val images = remember {
        ImageLoader.Builder(context).memoryCache { MemoryCache.Builder(context).maxSizeBytes(24 * 1024 * 1024).build() }
            .diskCache { DiskCache.Builder().directory(context.cacheDir.resolve("posters")).maxSizeBytes(80L * 1024 * 1024).build() }
            .build()
    }
    DisposableEffect(images) { onDispose { images.shutdown() } }
    val saveable = key(state.profile?.id) { rememberSaveableStateHolder() }
    Column(Modifier.fillMaxSize().background(Midnight)) {
        if (state.screen !in listOf(Screen.PROFILES, Screen.PLAYER, Screen.DETAIL)) Navigation(state.screen, state.focusedKey == null, vm::navigate)
        if (state.failure == Failure.CHANGED && state.screen != Screen.PLAYER) StructuralRecovery(vm)
        saveable.SaveableStateProvider(state.screen.name) {
            when (state.screen) {
                Screen.PROFILES -> ProfilesScreen(state, vm)
                Screen.PLAYER -> {
                    val stream = state.stream
                    if (stream != null) PlaybackScreen(stream, state, vm)
                    else Column(Modifier.fillMaxSize().padding(48.dp), verticalArrangement = Arrangement.Center) {
                        if (state.loading) Loading()
                        state.failure?.let { FailureMessage(it, vm::refresh) }
                        Action(stringResource(R.string.back)) { vm.back() }
                        if (state.failure == Failure.CHANGED) StructuralRecovery(vm)
                        if (state.failure in listOf(Failure.VIP, Failure.EXPIRED, Failure.SIGNED_OUT)) {
                            Action(stringResource(R.string.sign_in)) { vm.signInForPlayback() }
                        }
                    }
                }
                Screen.HOME -> Home(state, images, vm)
                Screen.MOVIES, Screen.SHOWS -> Browse(state, images, vm)
                Screen.SEARCH -> Search(state, images, vm)
                Screen.LIST -> Library(state, images, vm)
                Screen.ACCOUNT -> AccountScreen(state, vm)
                Screen.DETAIL -> DetailScreen(state, images, vm)
            }
        }
    }
}

@Composable private fun Navigation(active: Screen, restore: Boolean, navigate: (Screen) -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(active) { if (restore) focus.requestFocus() }
    Row(Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 10.dp)
        .background(Panel, RoundedCornerShape(16.dp)).border(1.dp, Accent.copy(alpha = 0.16f), RoundedCornerShape(16.dp))
        .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.brand), color = Accent, fontSize = 20.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(end = 20.dp))
        listOf(Screen.HOME to R.string.home, Screen.MOVIES to R.string.movies, Screen.SHOWS to R.string.shows,
            Screen.SEARCH to R.string.search, Screen.LIST to R.string.my_list, Screen.ACCOUNT to R.string.settings,
            Screen.PROFILES to R.string.profiles).forEach { (screen, label) ->
            Button(onClick = { navigate(screen) }, modifier = Modifier.heightIn(min = 48.dp)
                .then(if (active == screen) Modifier.focusRequester(focus) else Modifier),
                colors = ButtonDefaults.colors(containerColor = if (active == screen) Panel else Color.Transparent,
                    focusedContainerColor = Accent, focusedContentColor = Midnight)) {
                Text(stringResource(label), fontSize = 14.sp)
            }
        }
    }
}

@Composable private fun Home(s: TvState, images: ImageLoader, vm: TvViewModel) {
    if (s.shelves.isEmpty()) {
        Column(Modifier.padding(48.dp)) { if (s.loading) Loading(); s.failure?.let { FailureMessage(it, vm::refresh) } }
        return
    }
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(28.dp), contentPadding = PaddingValues(bottom = 36.dp)) {
        item(key = "hero") { Hero(s.shelves.first().titles.first(), images, restore = s.focusedKey == "hero") { vm.openFrom(it, "hero") } }
        if (s.failure != null) item { Box(Modifier.padding(horizontal = 48.dp)) { FailureMessage(s.failure, vm::refresh) } }
        val continuing = s.viewing.filter { it.position > 1000 && (it.duration <= 0 || it.position < it.duration - 30000) }
            .distinctBy { it.title.key }
        if (continuing.isNotEmpty()) item(key = "continue") {
            ShelfView(stringResource(R.string.continue_watching), "continue", continuing.map { it.title }, images, s.focusedKey) { vm.openFrom(it, "continue:${it.key}") }
        }
        items(s.shelves, key = { it.label }) { shelf ->
            val label = when (shelf.label) { "movies" -> R.string.new_movies; "shows" -> R.string.new_shows;
                "episodes" -> R.string.new_episodes; else -> R.string.popular }
            ShelfView(stringResource(label), shelf.label, shelf.titles, images, s.focusedKey) { vm.openFrom(it, "${shelf.label}:${it.key}") }
        }
    }
}

@Composable private fun Hero(title: Title, images: ImageLoader, showAction: Boolean = true,
    showSynopsis: Boolean = true, restore: Boolean = false, open: (Title) -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(restore) { if (restore && showAction) focus.requestFocus() }
    Box(Modifier.fillMaxWidth().height(248.dp)) {
        AsyncImage(title.backdrop.ifBlank { title.poster }, null, imageLoader = images,
            contentScale = ContentScale.Crop, alignment = Alignment.TopCenter,
            modifier = Modifier.fillMaxHeight().fillMaxWidth(0.62f).align(Alignment.CenterEnd))
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Midnight, Midnight.copy(alpha = 0.92f), Color.Transparent))))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Midnight))))
        Column(Modifier.padding(start = 48.dp, top = 22.dp, end = 32.dp).widthIn(max = 500.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title.name, fontSize = 34.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(listOf(title.year, title.genres, if (title.rating.isNotBlank()) stringResource(R.string.rating, title.rating) else "")
                .filter(String::isNotBlank).joinToString(" · "), color = Muted, fontSize = 15.sp)
            if (showSynopsis && title.synopsis.isNotBlank()) Text(title.synopsis, maxLines = 3, overflow = TextOverflow.Ellipsis, fontSize = 15.sp, color = Muted)
            if (showAction) Action(stringResource(R.string.details), modifier = Modifier.focusRequester(focus)) { open(title) }
        }
    }
}

@Composable private fun ShelfView(label: String, location: String, titles: List<Title>, images: ImageLoader, focused: String?, open: (Title) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(label, modifier = Modifier.padding(start = 48.dp), fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(18.dp), contentPadding = PaddingValues(horizontal = 48.dp, vertical = 8.dp)) {
            items(titles, key = Title::key) { Poster(it, images, focused == "$location:${it.key}", open) }
        }
    }
}

@Composable private fun Poster(title: Title, images: ImageLoader, restore: Boolean, open: (Title) -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(restore) { if (restore) focus.requestFocus() }
    Surface(onClick = { open(title) }, modifier = Modifier.width(120.dp).focusRequester(focus),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        colors = ClickableSurfaceDefaults.colors(containerColor = Panel, focusedContainerColor = Accent,
            contentColor = Color.White, focusedContentColor = Midnight)) {
        Column {
            val description = stringResource(R.string.poster_description, title.name)
            Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f)) {
                Text(title.name, modifier = Modifier.padding(12.dp), fontSize = 14.sp, maxLines = 4)
                AsyncImage(title.poster, description, imageLoader = images, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
            Text(title.name, modifier = Modifier.padding(start = 9.dp, end = 9.dp, top = 8.dp), maxLines = 1,
                overflow = TextOverflow.Ellipsis, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(title.year, modifier = Modifier.padding(start = 9.dp, bottom = 9.dp, top = 2.dp), fontSize = 11.sp)
        }
    }
}

@Composable private fun Browse(s: TvState, images: ImageLoader, vm: TvViewModel) {
    Column(Modifier.fillMaxSize().padding(horizontal = 48.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(if (s.screen == Screen.SHOWS) R.string.shows else R.string.movies), fontSize = 28.sp, modifier = Modifier.weight(1f))
            Action(stringResource(R.string.latest)) { vm.sort("latest") }
            Action(stringResource(R.string.top_rated)) { vm.sort("best") }
        }
        Results(s, images, vm, Modifier.weight(1f)) { vm.browse(true) }
    }
}

@Composable private fun Search(s: TvState, images: ImageLoader, vm: TvViewModel) {
    Column(Modifier.fillMaxSize().padding(horizontal = 48.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Field(stringResource(R.string.search_hint), s.query, { vm.search(it) }, Modifier.fillMaxWidth(0.75f))
        if (s.query.isBlank()) {
            Text(stringResource(R.string.search_intro), fontSize = 30.sp)
            Text(stringResource(R.string.search_help), color = Muted)
        } else Results(s, images, vm, Modifier.weight(1f)) { vm.search(s.query, true) }
    }
}

@Composable private fun Results(s: TvState, images: ImageLoader, vm: TvViewModel, modifier: Modifier, more: () -> Unit) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (s.loading) Loading()
        s.failure?.let { FailureMessage(it, vm::refresh) }
        if (!s.loading && s.failure == null && s.titles.isEmpty()) Text(stringResource(R.string.empty_results), color = Muted)
        LazyVerticalGrid(columns = GridCells.Adaptive(120.dp), modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(18.dp), verticalArrangement = Arrangement.spacedBy(20.dp),
            contentPadding = PaddingValues(vertical = 12.dp)) {
            gridItems(s.titles, key = Title::key) { Poster(it, images, s.focusedKey == it.key, vm::open) }
        }
        if (s.hasMore && s.titles.isNotEmpty()) Action(stringResource(R.string.load_more), enabled = !s.loading, click = more)
    }
}

@Composable private fun Library(s: TvState, images: ImageLoader, vm: TvViewModel) {
    if (s.favorites.isEmpty()) Column(Modifier.padding(48.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Text(stringResource(R.string.empty_list), fontSize = 32.sp)
        Text(stringResource(R.string.empty_list_help), color = Muted)
        Action(stringResource(R.string.browse)) { vm.navigate(Screen.HOME) }
    } else Column(Modifier.fillMaxSize().padding(horizontal = 48.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(stringResource(R.string.my_list), fontSize = 28.sp)
        Results(s.copy(titles = s.favorites, hasMore = false, loading = false), images, vm, Modifier.weight(1f)) {}
    }
}

@Composable private fun DetailScreen(s: TvState, images: ImageLoader, vm: TvViewModel) {
    val detail = s.detail ?: return
    val title = detail.title
    val listState = remember(title.key) { LazyListState() }
    val focus = remember(title.key) { FocusRequester() }
    val focusBack = s.loading || s.failure != null || (title.isSeries && detail.episodes.isEmpty())
    LaunchedEffect(title.key, s.loading, s.failure) { focus.requestFocus() }
    LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = 40.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Box(Modifier.padding(start = 48.dp, top = 16.dp)) {
            Action(stringResource(R.string.back), modifier = if (focusBack) Modifier.focusRequester(focus) else Modifier) { vm.back() }
        } }
        item { Hero(title, images, showAction = false, showSynopsis = false) {} }
        item {
            Column(Modifier.padding(horizontal = 48.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (s.loading) Loading()
                s.failure?.let { FailureMessage(it, vm::refresh) }
                if (title.synopsis.isNotBlank()) Text(title.synopsis, color = Muted, fontSize = 16.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    if (!title.isSeries && !s.loading && s.failure == null) {
                        Action(stringResource(R.string.play), modifier = Modifier.focusRequester(focus)) { vm.play(null) }
                        Action(stringResource(R.string.restart)) { vm.play(null, false) }
                    }
                    if (title.isSeries && !focusBack) {
                        Action(stringResource(R.string.play), modifier = Modifier.focusRequester(focus)) { vm.play(detail.episodes.first()) }
                    }
                    Action(stringResource(if (s.favorites.any { it.key == title.key }) R.string.remove_list else R.string.add_list)) { vm.toggle(title) }
                }
                val previous = s.viewing.firstOrNull { it.title.key == title.key && (it.duration <= 0 || it.position < it.duration - 30000) }
                if (previous != null && !s.loading && s.failure == null) {
                    Action(stringResource(R.string.remaining, previous.position / 60000, previous.position / 1000 % 60)) { vm.play(previous.episode) }
                }
            }
        }
        if (title.isSeries && !s.loading && s.failure == null && detail.episodes.isEmpty()) item { Text(stringResource(R.string.no_episodes), modifier = Modifier.padding(horizontal = 48.dp)) }
        detail.episodes.groupBy(Episode::season).forEach { (season, episodes) ->
            item(key = "season:$season") { Text(stringResource(R.string.season, season), modifier = Modifier.padding(start = 48.dp, top = 12.dp), fontSize = 24.sp) }
            items(episodes, key = Episode::key) { episode ->
                Box(Modifier.padding(horizontal = 48.dp)) {
                    Action(stringResource(R.string.episode, episode.number, episode.name), enabled = !s.loading) { vm.play(episode) }
                }
            }
        }
    }
}

@Composable private fun AccountScreen(s: TvState, vm: TvViewModel) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmClear by remember { mutableStateOf(false) }
    LaunchedEffect(s.account.signedIn) { if (s.account.signedIn) { email = ""; password = "" } }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 48.dp), contentPadding = PaddingValues(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item {
            Text(stringResource(R.string.settings), fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
            Text(s.profile?.name.orEmpty(), color = Muted)
            Action(stringResource(R.string.manage_profiles)) { vm.navigate(Screen.PROFILES) }
        }
        item {
            Text(stringResource(R.string.playback_settings), fontSize = 24.sp)
            QualityOptions(s.profile?.quality ?: 0, vm::setQuality)
        }
        item { UpdateSettings(vm) }
        item {
            Text(stringResource(R.string.vip_title), fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.vip_help), color = Muted, modifier = Modifier.padding(top = 8.dp))
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (s.loading) Loading()
                s.failure?.let { FailureMessage(it, if (s.failure == Failure.EXPIRED) vm::startPairing else vm::refresh) }
                Text(stringResource(if (!s.accountChecked) R.string.account_unknown else if (s.account.signedIn) R.string.signed_in else R.string.signed_out))
                if (s.account.signedIn) Action(stringResource(R.string.clear_session), enabled = !s.loading) { vm.clearSession() }
                else {
                    if (s.pairing) Action(stringResource(R.string.cancel_pair)) { vm.stopPairing() }
                    else Action(stringResource(R.string.pair_phone), enabled = !s.loading) { vm.startPairing() }
                    val pair = s.pair
                    if (pair != null) PairingView(pair)
                    else if (s.pairing) Loading()
                }
            }
        }
        if (!s.account.signedIn && !s.pairing) item {
            Column(Modifier.widthIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Field(stringResource(R.string.email), email, { email = it.take(254) }, keyboard = KeyboardType.Email)
                Field(stringResource(R.string.password), password, { password = it.take(64) }, password = true)
                Action(stringResource(R.string.sign_in), enabled = !s.loading && android.util.Patterns.EMAIL_ADDRESS.matcher(email.trim()).matches() && password.length in 8..64) {
                    vm.login(email, password); password = ""
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.local_library), color = Muted)
                Action(stringResource(R.string.clear_history)) { confirmClear = true }
                Text(stringResource(R.string.personal_client), color = Muted, fontSize = 12.sp)
            }
        }
        if (confirmClear) item {
            Column(Modifier.background(Panel, RoundedCornerShape(12.dp)).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(stringResource(R.string.clear_history_title), fontSize = 20.sp)
                Text(stringResource(R.string.clear_history_help), color = Muted)
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Action(stringResource(R.string.cancel)) { confirmClear = false }
                    Action(stringResource(R.string.clear)) { vm.clearLibrary(); confirmClear = false }
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

@Composable internal fun Field(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier,
    password: Boolean = false, keyboard: KeyboardType = KeyboardType.Text) {
    var focused by remember { mutableStateOf(false) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, fontSize = 14.sp, color = Muted)
        BasicTextField(value, onChange, singleLine = true, textStyle = TextStyle(color = Color.White, fontSize = 18.sp),
            keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else keyboard),
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            cursorBrush = Brush.verticalGradient(listOf(Accent, Accent)),
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).onFocusChanged { focused = it.isFocused }
                .background(Panel, RoundedCornerShape(8.dp)).border(if (focused) 2.dp else 1.dp,
                    if (focused) Accent else Muted.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                .padding(14.dp).semantics { contentDescription = label })
    }
}

@Composable internal fun Action(label: String, modifier: Modifier = Modifier, enabled: Boolean = true, click: () -> Unit) {
    Button(onClick = click, enabled = enabled, modifier = modifier.heightIn(min = 48.dp),
        colors = ButtonDefaults.colors(containerColor = Panel, focusedContainerColor = Accent, focusedContentColor = Midnight)) {
        Text(label, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable internal fun Loading() {
    Text(stringResource(R.string.loading), color = Accent, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
}

@Composable internal fun FailureMessage(failure: Failure, retry: () -> Unit, retryModifier: Modifier = Modifier) {
    val text = when (failure) { Failure.NETWORK -> R.string.error_network; Failure.ACCESS -> R.string.error_access;
        Failure.VIP -> R.string.error_vip; Failure.EXPIRED -> R.string.error_expired; Failure.BLOCKED -> R.string.error_blocked;
        Failure.MEDIA -> R.string.error_media; Failure.CHANGED -> R.string.error_changed;
        Failure.SIGNED_OUT -> R.string.error_signed_out; Failure.UNAVAILABLE -> R.string.error_unavailable;
        Failure.UNSUPPORTED -> R.string.error_unsupported }
    Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(text), color = Color(0xFFFFB4AB), modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite })
        Action(stringResource(R.string.retry), modifier = retryModifier, click = retry)
    }
}
