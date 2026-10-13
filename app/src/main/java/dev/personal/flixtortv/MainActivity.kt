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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.*
import coil.ImageLoader
import coil.compose.rememberAsyncImagePainter
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

@Composable internal fun TvApp(vm: TvViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) vm.stopPairing() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    val context = LocalContext.current
    var confirmingExit by rememberSaveable { mutableStateOf(false) }
    var fitting by remember { mutableStateOf(false) }
    BackHandler { if (!vm.back()) confirmingExit = true }
    val updateState by vm.updates.state.collectAsStateWithLifecycle()
    val lifecycleState by owner.lifecycle.currentStateFlow.collectAsState()
    var attemptedVersion by remember { mutableStateOf("") }
    var showUpdatePrompt by remember { mutableStateOf(false) }
    LaunchedEffect(updateState.phase, state.screen, lifecycleState, confirmingExit, fitting) {
        if (updateState.phase == UpdatePhase.READY && state.screen != Screen.PLAYER &&
            !confirmingExit && !fitting && lifecycleState == Lifecycle.State.RESUMED && attemptedVersion != updateState.version) {
            attemptedVersion = updateState.version
            if (context.packageManager.canRequestPackageInstalls()) (context as? MainActivity)?.installUpdate()
            else showUpdatePrompt = true
        }
    }
    val images = remember {
        ImageLoader.Builder(context).memoryCache { MemoryCache.Builder(context).maxSizeBytes(24 * 1024 * 1024).build() }
            .diskCache { DiskCache.Builder().directory(context.cacheDir.resolve("posters")).maxSizeBytes(80L * 1024 * 1024).build() }
            .build()
    }
    DisposableEffect(images) { onDispose { images.shutdown() } }
    val saveable = key(state.profile?.id) { rememberSaveableStateHolder() }
    var returnFromFit by remember { mutableStateOf(false) }
    val fitFocus = remember { FocusRequester() }
    val accountContentFocus = remember { FocusRequester() }
    var accountContentFocused by remember { mutableStateOf(false) }
    LaunchedEffect(fitting) {
        if (!fitting && returnFromFit && state.screen == Screen.ACCOUNT) {
            fitFocus.requestFocus(); returnFromFit = false
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize().background(Midnight)) {
        val configuredSafe = PaddingValues(horizontal = maxWidth * (state.screenFit.horizontal / 100f),
            vertical = maxHeight * (state.screenFit.vertical / 100f))
        val safe = if (state.screenFit == ScreenFit()) PaddingValues(horizontal = 14.dp, vertical = 12.dp) else configuredSafe

        if (!fitting) CompositionLocalProvider(LocalSafePadding provides safe) {
        Row(Modifier.fillMaxSize().then(if (state.screen == Screen.PLAYER) Modifier else Modifier.padding(safe))
            .onPreviewKeyEvent { event ->
                if (state.screen == Screen.ACCOUNT && !accountContentFocused &&
                    event.nativeKeyEvent.action == android.view.KeyEvent.ACTION_DOWN &&
                    event.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT) {
                    accountContentFocus.requestFocus()
                    true
                } else false
            }) {
        if (state.screen !in listOf(Screen.PROFILES, Screen.PLAYER, Screen.DETAIL))
            Navigation(state, state.focusedKey == null && !returnFromFit, vm::navigate, accountContentFocus)
        Column(Modifier.weight(1f).fillMaxHeight()) {
        if (state.failure == Failure.CHANGED && state.screen != Screen.PLAYER) StructuralRecovery(vm)
        if (state.failure in listOf(Failure.CAPACITY, Failure.VIP, Failure.SIGNED_OUT, Failure.EXPIRED) &&
            state.screen !in listOf(Screen.PLAYER, Screen.ACCOUNT)) {
            Action(stringResource(if (state.account.signedIn) R.string.check_account else R.string.sign_in)) { vm.navigate(Screen.ACCOUNT) }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) { saveable.SaveableStateProvider(state.screen.name) {
            when (state.screen) {
                Screen.PROFILES -> ProfilesScreen(state, vm)
                Screen.PLAYER -> {
                    val stream = state.stream
                    if (stream != null) PlaybackScreen(stream, state, vm)
                    else Column(Modifier.fillMaxSize().padding(safe).padding(12.dp), verticalArrangement = Arrangement.Center) {
                        if (state.loading) Loading()
                        state.failure?.let { if (it == Failure.VIP) AccessMessage(state.account) else FailureMessage(it, vm::refresh) }
                        Action(stringResource(R.string.back)) { vm.back() }
                        if (state.failure == Failure.CHANGED) StructuralRecovery(vm)
                        if (state.failure in listOf(Failure.VIP, Failure.EXPIRED, Failure.SIGNED_OUT, Failure.CAPACITY)) {
                            Action(stringResource(R.string.sign_in)) { vm.signInForPlayback() }
                        }
                    }
                }
                Screen.HOME -> Home(state, images, vm)
                Screen.MOVIES, Screen.SHOWS -> Browse(state, images, vm)
                Screen.SEARCH -> Search(state, images, vm)
                Screen.LIST -> Library(state, images, vm)
                Screen.ACCOUNT -> AccountScreen(state, vm, fitFocus, accountContentFocus,
                    onContentFocusChanged = { accountContentFocused = it }) { fitting = true }
                Screen.DETAIL -> DetailScreen(state, images, vm)
            }
        } }
        }
        }
        }
        if (showUpdatePrompt && !confirmingExit && !fitting && updateState.phase == UpdatePhase.READY && state.screen != Screen.PLAYER) {
            CompositionLocalProvider(LocalSafePadding provides safe) {
            UpdateReadyDialog(updateState.version, { showUpdatePrompt = false }) {
                showUpdatePrompt = false
                (context as? MainActivity)?.installUpdate()
        }
            }
        }
        if (confirmingExit) CompositionLocalProvider(LocalSafePadding provides safe) {
            ExitConfirmationDialog({ confirmingExit = false }) { (context as? android.app.Activity)?.finishAndRemoveTask() }
        }
        if (fitting) ScreenFitEditor(state.screenFit, vm::setScreenFit) { returnFromFit = true; fitting = false }
    }
}

@Composable private fun Navigation(state: TvState, restore: Boolean, navigate: (Screen) -> Unit,
    accountContentFocus: FocusRequester) {
    val active = if (state.screen == Screen.DETAIL) Screen.HOME else state.screen
    val focus = remember { FocusRequester() }
    LaunchedEffect(state.screen, restore) { if (restore && state.screen != Screen.DETAIL) focus.requestFocus() }
    // Sidebar: narrow glass column matching the reference's deep navy-purple rail
    Box(
        Modifier
            .width(70.dp)
            .fillMaxHeight()
            .padding(end = 14.dp)
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(NavSurface, RoundedCornerShape(RadiusLarge.dp))
                .border(1.dp, GlassBorder, RoundedCornerShape(RadiusLarge.dp))
        ) {
            Column(
                Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Brand wordmark
                Text(
                    stringResource(R.string.brand),
                    color = Accent,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.2.sp,
                    maxLines = 1,
                    modifier = Modifier.padding(top = 16.dp, bottom = 32.dp),
                )
                val destinations = listOf(
                    Triple(Screen.HOME, R.string.home, 0),
                    Triple(Screen.SEARCH, R.string.search, 1),
                    Triple(Screen.MOVIES, R.string.movies, 2),
                    Triple(Screen.SHOWS, R.string.shows, 3),
                    Triple(Screen.LIST, R.string.my_list, 4),
                    Triple(Screen.PROFILES, R.string.profiles, 5),
                )
                LazyColumn(
                    Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    items(destinations, key = { it.first.name }) { (screen, label, icon) ->
                        RailDestination(
                            screen == active, stringResource(label), icon,
                            Modifier.then(if (screen == active) Modifier.focusRequester(focus) else Modifier),
                        ) { navigate(screen) }
                    }
                }
                RailDestination(
                    active == Screen.ACCOUNT, stringResource(R.string.settings), 6,
                    Modifier.then(if (active == Screen.ACCOUNT) Modifier.focusRequester(focus)
                        .focusProperties { right = accountContentFocus }
                        .onPreviewKeyEvent { event ->
                            if (event.nativeKeyEvent.action == android.view.KeyEvent.ACTION_DOWN &&
                                event.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT) {
                                accountContentFocus.requestFocus(); true
                            } else false
                        } else Modifier),
                ) { navigate(Screen.ACCOUNT) }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable private fun RailDestination(selected: Boolean, label: String, icon: Int, modifier: Modifier = Modifier,
    onFocusChanged: ((Boolean) -> Unit)? = null, onClick: () -> Unit) {
    FocusFrame(
        modifier.size(44.dp).onFocusChanged { onFocusChanged?.invoke(it.isFocused) },
        radius = RadiusMedium.dp,
        restingFill = if (selected) GlassLight else Color.Transparent,
        contentDescription = label,
        onClick = onClick,
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            // Left edge accent bar for the selected state
            if (selected) Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .width(2.5.dp)
                    .height(22.dp)
                    .background(Accent, RoundedCornerShape(2.dp)),
            )
            RailIcon(icon, selected)
        }
    }
}

@Composable private fun RailIcon(kind: Int, selected: Boolean) {
    val ink = if (selected) TextPrimary else TextSecondary
    Canvas(Modifier.size(18.dp)) {
        val x = size.width; val y = size.height; val stroke = 1.8.dp.toPx()
        when (kind) {
            0 -> {
                val p = androidx.compose.ui.graphics.Path().apply {
                    moveTo(x * .12f, y * .48f); lineTo(x * .5f, y * .12f); lineTo(x * .88f, y * .48f)
                    lineTo(x * .8f, y * .48f); lineTo(x * .8f, y * .88f); lineTo(x * .58f, y * .88f)
                    lineTo(x * .58f, y * .62f); lineTo(x * .42f, y * .62f); lineTo(x * .42f, y * .88f)
                    lineTo(x * .2f, y * .88f); lineTo(x * .2f, y * .48f); close()
                }; drawPath(p, ink)
            }
            1 -> { drawCircle(ink, x * .29f, Offset(x * .42f, y * .42f), style = androidx.compose.ui.graphics.drawscope.Stroke(stroke)); drawLine(ink, Offset(x * .63f, y * .63f), Offset(x * .92f, y * .92f), stroke) }
            2 -> { drawRect(ink, Offset(x * .16f, y * .12f), androidx.compose.ui.geometry.Size(x * .68f, y * .76f), style = androidx.compose.ui.graphics.drawscope.Stroke(stroke)); for (i in 0..2) { drawLine(ink, Offset(x * (.3f + i * .2f), y * .14f), Offset(x * (.3f + i * .2f), y * .86f), stroke); drawCircle(ink, stroke * .65f, Offset(x * .22f, y * (.28f + i * .22f))); drawCircle(ink, stroke * .65f, Offset(x * .78f, y * (.28f + i * .22f))) } }
            3 -> { drawRoundRect(ink, Offset(x * .1f, y * .25f), androidx.compose.ui.geometry.Size(x * .8f, y * .56f), androidx.compose.ui.geometry.CornerRadius(stroke * 2), style = androidx.compose.ui.graphics.drawscope.Stroke(stroke)); drawLine(ink, Offset(x * .35f, y * .13f), Offset(x * .5f, y * .25f), stroke); drawLine(ink, Offset(x * .65f, y * .13f), Offset(x * .5f, y * .25f), stroke); drawLine(ink, Offset(x * .35f, y * .83f), Offset(x * .35f, y * .94f), stroke); drawLine(ink, Offset(x * .65f, y * .83f), Offset(x * .65f, y * .94f), stroke) }
            4 -> { drawRect(ink, Offset(x * .15f, y * .14f), androidx.compose.ui.geometry.Size(x * .7f, y * .72f), style = androidx.compose.ui.graphics.drawscope.Stroke(stroke)); drawLine(ink, Offset(x * .15f, y * .38f), Offset(x * .85f, y * .38f), stroke); drawLine(ink, Offset(x * .15f, y * .62f), Offset(x * .85f, y * .62f), stroke) }
            5 -> { drawCircle(ink, x * .18f, Offset(x * .5f, y * .32f), style = androidx.compose.ui.graphics.drawscope.Stroke(stroke)); drawArc(ink, 180f, 180f, false, Offset(x * .12f, y * .46f), androidx.compose.ui.geometry.Size(x * .76f, y * .48f), style = androidx.compose.ui.graphics.drawscope.Stroke(stroke)) }
            else -> { drawCircle(ink, x * .28f, Offset(x * .5f, y * .5f), style = androidx.compose.ui.graphics.drawscope.Stroke(stroke)); drawCircle(ink, stroke, Offset(x * .5f, y * .5f)); for (a in 0..7) { val angle = Math.PI * a / 4; drawLine(ink, Offset(x * (.5f + .31f * kotlin.math.cos(angle).toFloat()), y * (.5f + .31f * kotlin.math.sin(angle).toFloat())), Offset(x * (.5f + .46f * kotlin.math.cos(angle).toFloat()), y * (.5f + .46f * kotlin.math.sin(angle).toFloat())), stroke) } }
        }
    }
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable private fun Home(s: TvState, images: ImageLoader, vm: TvViewModel) {
    if (s.shelves.isEmpty()) {
        GlassSurface(Modifier.fillMaxWidth().padding(24.dp)) {
            if (s.loading) Loading()
            s.failure?.let { FailureMessage(it, vm::refresh) }
        }
        return
    }
    val listState = rememberLazyListState()
    var heroReady by remember { mutableStateOf(false) }
    LaunchedEffect(s.focusedKey) {
        heroReady = false
        if (s.focusedKey == "hero") {
            listState.scrollToItem(0)
            heroReady = true
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
    val compact = maxHeight < 420.dp
    // Hero fills ~62% of screen height — dominant, like the reference
    val heroHeight = if (compact) maxHeight * .52f else maxHeight * .62f
    LazyColumn(Modifier.fillMaxSize().testTag("home_viewport").semantics { testTagsAsResourceId = true },
        state = listState, verticalArrangement = Arrangement.spacedBy(if (compact) 12.dp else 20.dp),
        contentPadding = PaddingValues(bottom = 28.dp)) {
        item(key = "hero") {
            Box {
                Hero(s.shelves.first().titles.first(), images = images, restore = s.focusedKey == "hero" && heroReady,
                    heroHeight = heroHeight, play = vm::playTitle) { vm.openFrom(it, "hero") }
                // Pagination dots — bottom-right of hero
                if (!compact) {
                    val dotCount = s.shelves.firstOrNull()?.titles?.size?.coerceAtMost(5) ?: 1
                    Row(
                        Modifier.align(Alignment.BottomEnd).padding(end = 24.dp, bottom = 18.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        repeat(dotCount) { index ->
                            val isActive = index == 0
                            Box(Modifier
                                .then(if (isActive) Modifier.width(22.dp).height(6.dp) else Modifier.size(6.dp))
                                .background(if (isActive) TextPrimary else TextPrimary.copy(alpha = .38f), RoundedCornerShape(3.dp)))
                        }
                    }
                }
            }
        }
        if (s.failure != null) item { Box(Modifier.padding(horizontal = 8.dp)) { FailureMessage(s.failure, vm::refresh) } }
        val continuing = s.viewing.filter { it.position > 1000 && (it.duration <= 0 || it.position < it.duration - 30000) }
            .distinctBy { it.title.key }
        if (continuing.isNotEmpty()) item(key = "continue") {
            ShelfView(stringResource(R.string.continue_watching), "continue", continuing.map { it.title },
                images, s.focusedKey, landscape = false,
                posterWidth = if (compact) 80.dp else 120.dp) { vm.openFrom(it, "continue:${it.key}") }
        }
        items(s.shelves, key = { it.label }) { shelf ->
            val label = when (shelf.label) { "movies" -> R.string.new_movies; "shows" -> R.string.new_shows;
                "episodes" -> R.string.new_episodes; else -> R.string.popular }
            // First shelf uses square cards; subsequent shelves use wider landscape/cinematic cards
            val isFirstShelf = shelf == s.shelves.firstOrNull()
            ShelfView(stringResource(label), shelf.label, shelf.titles, images, s.focusedKey,
                landscape = !isFirstShelf,
                selectedKey = if (s.focusedKey == null && isFirstShelf) shelf.titles.firstOrNull()?.key else null,
                posterWidth = if (compact) 80.dp else if (isFirstShelf) 148.dp else 220.dp) {
                vm.openFrom(it, "${shelf.label}:${it.key}")
            }
        }
    }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable private fun Hero(title: Title, images: ImageLoader, showAction: Boolean = true,
    showSynopsis: Boolean = true, restore: Boolean = false, heroHeight: Dp? = null,
    play: ((Title) -> Unit)? = null, open: (Title) -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(restore) { if (restore && showAction) focus.requestFocus() }
    val bringIntoView = remember { BringIntoViewRequester() }
    var hasFocus by remember { mutableStateOf(false) }
    LaunchedEffect(hasFocus) {
        if (hasFocus) {
            withFrameNanos { }
            bringIntoView.bringIntoView()
        }
    }
    val metadata = listOf(title.year, title.genres)
    // Use backdrop if available (detail page), fall back to poster for shelf items
    val imageUrl = title.backdrop.ifBlank { title.poster }
    val painter = rememberAsyncImagePainter(imageUrl.takeIf { it.isNotBlank() }, imageLoader = images)
    HeroBanner(
        title.name,
        background = if (imageUrl.isNotBlank()) painter else null,
        metadata,
        if (showSynopsis) title.synopsis else "",
        Modifier.fillMaxWidth().height(heroHeight ?: if (showAction) 300.dp else 240.dp)
            .testTag("home_hero")
            .bringIntoViewRequester(bringIntoView).onFocusChanged { hasFocus = it.hasFocus }.focusGroup(),
        contentOffsetY = if (showAction) 24.dp else 0.dp,
        isNew = showAction,
        ageRating = if (title.rating.isNotBlank()) title.rating else "",
    ) {
        if (showAction) {
            GlassButton(stringResource(R.string.play), modifier = Modifier.focusRequester(focus),
                variant = GlassButtonVariant.PRIMARY, icon = { HeroActionIcon(play = true) }) { play?.invoke(title) ?: open(title) }
            GlassButton(stringResource(R.string.details), icon = { HeroActionIcon(play = false) }) { open(title) }
        }
    }
}

@Composable private fun HeroActionIcon(play: Boolean) {
    Canvas(Modifier.size(18.dp)) {
        if (play) {
            val path = androidx.compose.ui.graphics.Path().apply {
                moveTo(size.width * .28f, size.height * .16f)
                lineTo(size.width * .82f, size.height * .5f)
                lineTo(size.width * .28f, size.height * .84f)
                close()
            }
            drawPath(path, TextPrimary)
        } else {
            drawCircle(TextPrimary, radius = size.minDimension * .43f,
                style = androidx.compose.ui.graphics.drawscope.Stroke(1.8.dp.toPx()))
            drawCircle(TextPrimary, radius = 1.2.dp.toPx(), center = Offset(size.width * .5f, size.height * .32f))
            drawLine(TextPrimary, Offset(size.width * .5f, size.height * .48f),
                Offset(size.width * .5f, size.height * .72f), 1.8.dp.toPx())
        }
    }
}

@Composable private fun ShelfView(label: String, location: String, titles: List<Title>, images: ImageLoader,
    focused: String?, selectedKey: String? = null, landscape: Boolean = false, posterWidth: Dp = 148.dp,
    open: (Title) -> Unit) {
    // Square cards for "Popular now", widescreen cards for "Trending" / secondary shelves
    val aspectRatio = if (landscape) 16f / 9f else 1f
    ContentRail(label, titles, itemKey = Title::key, contentPadding = PaddingValues(horizontal = 2.dp, vertical = 0.dp)) { title ->
        val isSelected = focused == "$location:${title.key}" || selectedKey == title.key
        Column(Modifier.width(posterWidth)) {
            Poster(title, images, isSelected, open,
                showLabels = false, aspectRatio = aspectRatio, titleOverlay = true)
        }
    }
}

@Composable private fun Poster(title: Title, images: ImageLoader, restore: Boolean, open: (Title) -> Unit,
    showLabels: Boolean = true, aspectRatio: Float = 2f / 3f, titleOverlay: Boolean = false) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(restore) { if (restore) focus.requestFocus() }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val description = stringResource(R.string.poster_description, title.name)
        MediaPoster(title.name, rememberAsyncImagePainter(title.poster, imageLoader = images),
            modifier = Modifier.fillMaxWidth().focusRequester(focus), contentDescription = description,
            aspectRatio = aspectRatio, selected = restore, titleOverlay = titleOverlay,
            onClick = { open(title) })
        if (showLabels) {
            Text(title.name, modifier = Modifier.padding(horizontal = 3.dp), maxLines = 1,
                overflow = TextOverflow.Ellipsis, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
            if (title.year.isNotBlank()) Text(title.year, modifier = Modifier.padding(horizontal = 3.dp), fontSize = 11.sp, color = TextMuted)
        }
    }
}

@Composable private fun Browse(s: TvState, images: ImageLoader, vm: TvViewModel) {
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(if (s.screen == Screen.SHOWS) R.string.shows else R.string.movies), fontSize = 32.sp,
                fontWeight = FontWeight.Bold, color = TextPrimary, modifier = Modifier.weight(1f))
            GlassButton(stringResource(R.string.latest), variant = GlassButtonVariant.SECONDARY) { vm.sort("latest") }
            GlassButton(stringResource(R.string.top_rated), variant = GlassButtonVariant.SECONDARY) { vm.sort("best") }
        }
        Results(s, images, vm, Modifier.weight(1f)) { vm.browse(true) }
    }
}

@Composable private fun Search(s: TvState, images: ImageLoader, vm: TvViewModel) {
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Text(stringResource(R.string.search), fontSize = 32.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
        GlassSurface(Modifier.fillMaxWidth(0.76f), radius = RadiusMedium.dp) {
            Field(stringResource(R.string.search_hint), s.query, { vm.search(it) }, Modifier.fillMaxWidth())
        }
        if (s.query.isBlank()) {
            GlassSurface(Modifier.fillMaxWidth(0.76f)) {
                Text(stringResource(R.string.search_intro), fontSize = 26.sp, color = TextPrimary)
                Text(stringResource(R.string.search_help), color = TextSecondary, modifier = Modifier.padding(top = 10.dp))
            }
        } else Results(s, images, vm, Modifier.weight(1f)) { vm.search(s.query, true) }
    }
}

@Composable private fun Results(s: TvState, images: ImageLoader, vm: TvViewModel, modifier: Modifier, more: () -> Unit) {
    val grid = rememberLazyGridState()
    var gridKey by rememberSaveable { mutableStateOf(s.catalogKey) }
    val requestMore by rememberUpdatedState(more)
    val latest by rememberUpdatedState(s)
    LaunchedEffect(grid, s.catalogKey) {
        if (gridKey != s.catalogKey) { gridKey = s.catalogKey; grid.scrollToItem(0) }
        snapshotFlow { grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }.distinctUntilChanged().collect { last ->
            vm.warmCatalog(grid.firstVisibleItemIndex)
            val current = latest
            val columns = grid.layoutInfo.visibleItemsInfo.count { it.row == grid.layoutInfo.visibleItemsInfo.firstOrNull()?.row }.coerceAtLeast(1)
            if (last >= 0 && current.titles.isNotEmpty() && last >= current.titles.size - columns * 2 &&
                !current.loading && !current.appending && current.appendFailure == null && current.hasMore) requestMore()
        }
    }
    // Re-evaluate after an appended page when the viewport has not moved.
    LaunchedEffect(s.titles.size, s.appending) {
        val last = grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
        if (last >= s.titles.size - 2 && last >= 0 && !s.loading && !s.appending && s.appendFailure == null && s.hasMore) requestMore()
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (s.loading) Loading()
        s.failure?.let { FailureMessage(it, vm::refresh) }
        if (!s.loading && s.failure == null && s.titles.isEmpty()) GlassSurface(Modifier.fillMaxWidth(0.8f)) {
            Text(stringResource(R.string.empty_results), color = TextSecondary, fontSize = 20.sp)
        }
        LazyVerticalGrid(columns = GridCells.Adaptive(120.dp), state = grid, modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(20.dp), verticalArrangement = Arrangement.spacedBy(24.dp),
            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 12.dp)) {
            items(s.titles.size, key = { index -> (s.titles as? CatalogPages.Snapshot)?.key(index) ?: s.titles[index].key }) { index ->
                val title = s.titles[index]
                Poster(title, images, s.focusedKey == title.key, vm::open)
            }
        }
        if (s.appending) Loading()
        if (s.appendFailure != null) {
            if (s.appendFailure in listOf(Failure.CAPACITY, Failure.VIP, Failure.SIGNED_OUT, Failure.EXPIRED, Failure.ACCESS)) {
                FailureMessage(s.appendFailure, more)
                if (s.appendFailure != Failure.ACCESS) Action(stringResource(R.string.sign_in)) { vm.navigate(Screen.ACCOUNT) }
            } else Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.pagination_failed), color = Muted, modifier = Modifier.weight(1f))
                Action(stringResource(R.string.retry), click = more)
            }
        }
    }
}

@Composable private fun Library(s: TvState, images: ImageLoader, vm: TvViewModel) {
    if (s.favorites.isEmpty()) Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        GlassSurface(Modifier.fillMaxWidth(0.8f)) {
            Text(stringResource(R.string.empty_list), fontSize = 28.sp, color = TextPrimary)
            Text(stringResource(R.string.empty_list_help), color = TextSecondary, modifier = Modifier.padding(top = 10.dp))
            GlassButton(stringResource(R.string.browse), modifier = Modifier.padding(top = 20.dp), variant = GlassButtonVariant.PRIMARY) { vm.navigate(Screen.HOME) }
        }
    } else Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(stringResource(R.string.my_list), fontSize = 32.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
        Results(s.copy(titles = s.favorites, hasMore = false, loading = false), images, vm, Modifier.weight(1f)) {}
    }
}

@Composable private fun DetailScreen(s: TvState, images: ImageLoader, vm: TvViewModel) {
    val detail = s.detail ?: return
    val title = detail.title
    val focus = remember(title.key) { FocusRequester() }
    var initiallyFocused by remember(title.key) { mutableStateOf(false) }
    val focusBack = s.loading || s.failure != null || (title.isSeries && detail.episodes.isEmpty())
    val seasons = remember(detail.episodes) { detail.episodes.map { it.season }.distinct().sorted() }
    var picked by remember(title.key) { mutableIntStateOf(0) }
    val season = if (seasons.contains(picked)) picked else startSeason(s.viewing, title) ?: seasons.firstOrNull() ?: 1
    val episodes = remember(detail.episodes, season) { detail.episodes.filter { it.season == season }.sortedBy { it.number } }
    val resume = s.viewing.firstOrNull { it.title.key == title.key && !it.finished }
    val imageUrl = title.backdrop.ifBlank { title.poster }
    val painter = rememberAsyncImagePainter(imageUrl.takeIf { it.isNotBlank() }, imageLoader = images)

    // Single scrollable column: hero image as first item, then details
    val listState = remember(title.key) { LazyListState() }
    LazyColumn(
        Modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Hero image — full-width, fixed aspect, scrolls with content
        item(key = "hero") {
            Box(Modifier.fillMaxWidth().then(if (imageUrl.isNotBlank()) Modifier.aspectRatio(16f / 9f)
                else Modifier.heightIn(min = 200.dp))) {
                if (imageUrl.isNotBlank()) {
                    Image(painter, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    // Right-edge fade
                    Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(
                        listOf(Color.Transparent, AppBackground.copy(alpha = .08f), AppBackground.copy(alpha = .72f), AppBackground),
                        startX = Float.POSITIVE_INFINITY, endX = 0f,
                    )))
                    // Bottom fade
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Transparent, AppBackground.copy(alpha = .60f), AppBackground),
                    )))
                }
                // Back button anchored top-left
                Box(Modifier.align(Alignment.TopStart).padding(start = 16.dp, top = 16.dp)) {
                    Action(stringResource(R.string.back), modifier = Modifier.focusRequester(focus)) { vm.back() }
                }
                // Title + metadata at bottom-left of image
                Column(
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 20.dp, bottom = 20.dp, end = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (title.rating.isNotBlank()) {
                        Box(
                            Modifier
                                .border(1.dp, TextMuted.copy(alpha = .5f), RoundedCornerShape(4.dp))
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        ) { Text(title.rating, color = TextMuted, fontSize = 12.sp, fontWeight = FontWeight.Medium) }
                    }
                    Text(title.name, color = TextPrimary, fontSize = 28.sp, fontWeight = FontWeight.Bold,
                        lineHeight = 32.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    if (title.year.isNotBlank() || title.genres.isNotBlank()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (title.year.isNotBlank()) Text(title.year, color = TextSecondary, fontSize = 14.sp)
                            if (title.year.isNotBlank() && title.genres.isNotBlank())
                                Box(Modifier.size(3.dp).background(TextMuted, RoundedCornerShape(2.dp)))
                            if (title.genres.isNotBlank()) Text(title.genres, color = TextSecondary, fontSize = 14.sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            LaunchedEffect(title.key) {
                if (!initiallyFocused) {
                    withFrameNanos { }
                    focus.requestFocus()
                    initiallyFocused = true
                }
            }
        }
        // Content — synopsis, actions, episodes
        item(key = "content") {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 24.dp, end = 24.dp, top = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // Synopsis
                if (title.synopsis.isNotBlank()) {
                    Text(title.synopsis, color = TextSecondary, fontSize = 15.sp, lineHeight = 22.sp,
                        maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(bottom = 4.dp))
                }
                // Loading / failure
                if (s.loading || s.failure != null) {
                    if (s.loading) Loading()
                    s.failure?.let { FailureMessage(it, vm::refresh) }
                }
                // Action row
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                    if (!title.isSeries && !s.loading && s.failure == null) {
                        GlassButton(stringResource(R.string.play), variant = GlassButtonVariant.PRIMARY,
                            icon = { HeroActionIcon(play = true) }) { vm.play(null) }
                        GlassButton(stringResource(R.string.restart)) { vm.play(null, false) }
                    }
                    if (title.isSeries && !focusBack) {
                        val first = detail.ordered.firstOrNull { ep ->
                            val v = s.viewing.firstOrNull { it.title.key == title.key && it.episode?.key == ep.key }
                            v == null || !v.finished
                        } ?: detail.ordered.first()
                        GlassButton(stringResource(R.string.play), variant = GlassButtonVariant.PRIMARY,
                            icon = { HeroActionIcon(play = true) }) { vm.play(first) }
                    }
                    if (resume != null && !s.loading && s.failure == null) {
                        GlassButton(stringResource(R.string.remaining, resume.position / 60000, resume.position / 1000 % 60)) { vm.play(resume.episode) }
                    }
                    GlassButton(stringResource(if (s.favorites.any { it.key == title.key }) R.string.remove_list else R.string.add_list)) { vm.toggle(title) }
                }
                // Season picker — shown only for multi-season series
                if (seasons.size > 1) {
                    SeasonPicker(seasons, season, Modifier.fillMaxWidth()) { picked = it }
                }
                // No episodes placeholder
                if (title.isSeries && !s.loading && s.failure == null && detail.episodes.isEmpty()) {
                    Text(stringResource(R.string.no_episodes), color = TextSecondary, fontSize = 15.sp,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp))
                }
                // Episode list
                episodes.forEach { episode ->
                    val viewing = s.viewing.firstOrNull { it.title.key == title.key && it.episode?.key == episode.key }
                    EpisodeRow(episode, viewing, s.episode?.key == episode.key, enabled = !s.loading) { vm.play(episode) }
                }
            }
        }
    }
}

@Composable private fun AccountScreen(s: TvState, vm: TvViewModel, fitFocus: FocusRequester,
    contentFocus: FocusRequester, onContentFocusChanged: (Boolean) -> Unit, fit: () -> Unit) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordLogin by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    LaunchedEffect(s.account.signedIn) { if (s.account.signedIn) { email = ""; password = ""; passwordLogin = false } }
    LazyColumn(Modifier.fillMaxSize().onFocusChanged { onContentFocusChanged(it.hasFocus) }
        .padding(horizontal = 24.dp, vertical = 16.dp), contentPadding = PaddingValues(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item {
            GlassSurface(Modifier.fillMaxWidth(0.82f)) {
                Text(stringResource(R.string.settings), fontSize = 30.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                Text(s.profile?.name.orEmpty(), color = TextSecondary, modifier = Modifier.padding(top = 6.dp))
                GlassButton(stringResource(R.string.manage_profiles), modifier = Modifier.padding(top = 14.dp)
                    .focusRequester(contentFocus)) { vm.navigate(Screen.PROFILES) }
            }
        }
        item {
            GlassSurface(Modifier.fillMaxWidth(0.82f)) {
                Text(stringResource(R.string.vip_title), fontSize = 26.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                Text(stringResource(R.string.vip_help), color = TextSecondary, modifier = Modifier.padding(top = 8.dp))
            }
        }
        item {
            GlassSurface(Modifier.fillMaxWidth(0.82f)) {
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
                    if (pair != null) PairingView(pair)
                    else if (s.pairing) Loading()
                }
            }
            }
        }
        if (!s.account.signedIn && !s.pairing && passwordLogin) item {
            GlassSurface(Modifier.widthIn(max = 560.dp).fillMaxWidth()) {
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
        item {
            GlassSurface(Modifier.fillMaxWidth(0.82f)) {
                Text(stringResource(R.string.playback_settings), fontSize = 24.sp, color = TextPrimary)
                QualityOptions(s.profile?.quality ?: 0, vm::setQuality)
                val autoplay = s.profile?.autoplay != false
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Action(if (autoplay) stringResource(R.string.selected_option, stringResource(R.string.autoplay_next))
                        else stringResource(R.string.autoplay_next)) { vm.setAutoplay(!autoplay) }
                    Text(stringResource(R.string.autoplay_help), color = TextSecondary)
                }
            }
        }
        item {
            GlassSurface(Modifier.fillMaxWidth(0.82f)) {
                Text(stringResource(R.string.skip_segments), fontSize = 24.sp, color = TextPrimary)
                Text(stringResource(R.string.introdb_key_help), color = TextSecondary, modifier = Modifier.padding(top = 8.dp))
                Text(stringResource(if (introDbConfigured) R.string.introdb_key_stored else R.string.introdb_key_unavailable),
                    color = if (introDbConfigured) Accent else Muted, modifier = Modifier.padding(top = 8.dp))
            }
        }
        item { UpdateSettings(vm) }
        item {
            GlassSurface(Modifier.fillMaxWidth(0.82f)) {
                Text(stringResource(R.string.display), fontSize = 24.sp, color = TextPrimary)
                GlassButton(stringResource(R.string.screen_fit), Modifier.focusRequester(fitFocus), onClick = fit)
            }
        }
        item {
            GlassSurface(Modifier.fillMaxWidth(0.82f)) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.local_library), color = Muted)
                Action(stringResource(R.string.clear_history)) { confirmClear = true }
                Text(stringResource(R.string.personal_client), color = Muted, fontSize = 12.sp)
            }
            }
        }
        if (confirmClear) item {
            GlassSurface(Modifier.fillMaxWidth(0.82f)) {
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
                .background(GlassMedium, RoundedCornerShape(RadiusSmall.dp)).border(if (focused) 2.dp else 1.dp,
                    if (focused) GlassBorderStrong else GlassBorder, RoundedCornerShape(RadiusSmall.dp))
                .padding(14.dp).semantics { contentDescription = label })
    }
}

@Composable internal fun Action(label: String, modifier: Modifier = Modifier, enabled: Boolean = true, click: () -> Unit) {
    GlassButton(label, modifier, GlassButtonVariant.SECONDARY, enabled, onClick = click)
}

@Composable internal fun Loading() {
    Text(stringResource(R.string.loading), color = Accent, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
}

@Composable internal fun FailureMessage(failure: Failure, retry: () -> Unit, modifier: Modifier = Modifier) {
    val text = when (failure) { Failure.NETWORK -> R.string.error_network; Failure.ACCESS -> R.string.error_access;
        Failure.VIP -> R.string.error_vip; Failure.EXPIRED -> R.string.error_expired; Failure.BLOCKED -> R.string.error_blocked;
        Failure.MEDIA -> R.string.error_media; Failure.CHANGED -> R.string.error_changed;
        Failure.SIGNED_OUT -> R.string.error_signed_out; Failure.UNAVAILABLE -> R.string.error_unavailable;
        Failure.UNSUPPORTED -> R.string.error_unsupported; Failure.CAPACITY -> R.string.error_capacity }
    Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(text), color = Color(0xFFFFB4AB), modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite })
        Action(stringResource(R.string.retry), modifier = modifier, click = retry)
    }
}
