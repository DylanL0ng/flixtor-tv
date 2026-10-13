package dev.personal.flixtortv

import android.app.Activity
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.*
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.tv.material3.Text
import kotlinx.coroutines.delay

private enum class PlayerDrawer { SETTINGS, EPISODES }
private enum class PlayerSettingsPage { ROOT, QUALITY, AUDIO, SUBTITLES, AUTOPLAY, SKIP }

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable internal fun PlaybackScreen(stream: Stream, state: TvState, vm: TvViewModel) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val identity = remember(stream) { state.playbackIdentity }
    var failure by remember(stream) { mutableStateOf<Failure?>(null) }
    var buffering by remember(stream) { mutableStateOf(true) }
    var controls by remember(stream) { mutableStateOf(true) }
    var drawer by remember(stream) { mutableStateOf<PlayerDrawer?>(null) }
    var settingsPage by remember(stream) { mutableStateOf(PlayerSettingsPage.ROOT) }
    var settingsOrigin by remember(stream) { mutableStateOf(PlayerSettingsPage.QUALITY) }
    val settingsRows = remember(stream) { PlayerSettingsPage.entries.associateWith { FocusRequester() } }
    val settingsList = androidx.compose.foundation.lazy.rememberLazyListState()
    val settings = drawer == PlayerDrawer.SETTINGS
    val episodesOpen = drawer == PlayerDrawer.EPISODES
    var ended by remember(stream) { mutableStateOf(false) }
    var countdown by remember(stream) { mutableIntStateOf(0) }
    var marking by remember(stream) { mutableStateOf<SegmentType?>(null) }
    var markStart by remember(stream) { mutableLongStateOf(0) }
    // Segment types already used this playback, so a skip never re-offers itself.
    val skipped = remember(stream) { mutableStateListOf<String>() }
    var playing by remember(stream) { mutableStateOf(false) }
    var playRequested by remember(stream) { mutableStateOf(false) }
    var tracks by remember(stream) { mutableStateOf(Tracks.EMPTY) }
    var cues by remember(stream) { mutableStateOf<List<Cue>>(emptyList()) }
    var position by remember(stream) { mutableLongStateOf(state.resumePosition) }
    var duration by remember(stream) { mutableLongStateOf(0) }
    var interaction by remember(stream) { mutableLongStateOf(0) }
    val rootFocus = remember(stream) { FocusRequester() }
    val playFocus = remember(stream) { FocusRequester() }
    val settingsFocus = remember(stream) { FocusRequester() }
    val episodesFocus = remember(stream) { FocusRequester() }
    val episodesButtonFocus = remember(stream) { FocusRequester() }
    val nextFocus = remember(stream) { FocusRequester() }
    val skipFocus = remember(stream) { FocusRequester() }
    val timelineFocus = remember(stream) { FocusRequester() }
    var skipFocused by remember(stream) { mutableStateOf(false) }
    val episodes = state.detail?.takeIf { it.title.isSeries }?.ordered ?: emptyList()
    val segments = state.segments?.items.orEmpty()
    val active = segments.firstOrNull { it.contains(position) }
    // The skip affordance only appears while inside a segment that has not been used.
    val skippable = active?.takeIf { it.type.read !in skipped }
    var preview by remember(stream) { mutableStateOf<Long?>(null) }
    var timelineFocused by remember(stream) { mutableStateOf(false) }
    val safe = LocalSafePadding.current
    val layoutDirection = LocalLayoutDirection.current
    val density = LocalDensity.current
    var bottomAreaHeight by remember(stream) { mutableStateOf(0.dp) }
    val showTransport = controls && !ended
    val showSkip = skippable != null && failure == null && !settings && !episodesOpen && !ended
    val skipVisible by rememberUpdatedState(showSkip)
    val player = remember(stream) {
        val http = OkHttpDataSource.Factory(vm.client.mediaClient).setUserAgent(FlixtorClient.USER_AGENT)
            .setDefaultRequestProperties(mapOf("Referer" to SiteParser.BASE + "/"))
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(context, http)))
            .setSeekBackIncrementMs(10000).setSeekForwardIncrementMs(10000).build().apply {
                setAudioAttributes(AudioAttributes.DEFAULT, true); setHandleAudioBecomingNoisy(true)
                val subtitles = stream.captions.map { caption -> MediaItem.SubtitleConfiguration.Builder(android.net.Uri.parse(caption.uri))
                    .setMimeType(caption.mime).setLabel(caption.label)
                    .setSelectionFlags(if (caption.default) C.SELECTION_FLAG_DEFAULT else 0).build() }
                val item = MediaItem.Builder().setUri(stream.uri).setMimeType(MimeTypes.APPLICATION_M3U8)
                    .setSubtitleConfigurations(subtitles).setMediaMetadata(MediaMetadata.Builder().setTitle(state.detail?.title?.name).build()).build()
                setMediaItem(item, state.resumePosition); prepare()
                playWhenReady = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            }
    }
    LaunchedEffect(player, state.profile?.quality) {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setMaxVideoSize(Int.MAX_VALUE, state.profile?.quality?.takeIf { it > 0 } ?: Int.MAX_VALUE)
            .setForceHighestSupportedBitrate(false).build()
    }
    DisposableEffect(player) {
        playRequested = player.playWhenReady
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                val http = generateSequence(error.cause) { it.cause }.filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()
                failure = when {
                    http?.responseCode == 401 -> Failure.SIGNED_OUT
                    http?.responseCode == 403 -> Failure.ACCESS
                    http?.responseCode == 429 || (http?.responseCode ?: 0) >= 500 -> Failure.UNAVAILABLE
                    error.errorCode in listOf(PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
                        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED, PlaybackException.ERROR_CODE_DECODING_FAILED) -> Failure.UNSUPPORTED
                    else -> Failure.MEDIA
                }
                buffering = false; controls = true; vm.playerFailure(failure!!)
            }
            override fun onPlaybackStateChanged(value: Int) {
                buffering = value == Player.STATE_BUFFERING
                if (value == Player.STATE_ENDED) {
                    identity?.let { vm.saveProgress(it, player.duration.coerceAtLeast(0), player.duration.coerceAtLeast(0)) }
                    drawer = null; controls = true; ended = true
                }
            }
            override fun onIsPlayingChanged(value: Boolean) { playing = value }
            override fun onPlayWhenReadyChanged(value: Boolean, reason: Int) { playRequested = value }
            override fun onTracksChanged(value: Tracks) { tracks = value }
            override fun onCues(value: CueGroup) { cues = value.cues }
        }
        player.addListener(listener)
        val session = MediaSession.Builder(context, player).build()
        val window = (context as? Activity)?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                identity?.let { vm.saveProgress(it, player.currentPosition, player.duration) }; player.pause()
            }
            if (event == Lifecycle.Event.ON_RESUME) controls = true
        }
        lifecycle.addObserver(observer)
        onDispose {
            identity?.let { vm.saveProgress(it, player.currentPosition, player.duration) }
            lifecycle.removeObserver(observer); player.removeListener(listener)
            session.release(); player.release(); window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
    LaunchedEffect(player) {
        var tick = 0
        while (true) {
            delay(500); position = player.currentPosition.coerceAtLeast(0); duration = player.duration.coerceAtLeast(0)
            if (++tick % 10 == 0 && player.isPlaying) identity?.let { vm.saveProgress(it, position, duration) }
        }
    }
    LaunchedEffect(controls, settings, episodesOpen, interaction, failure, playing, preview, ended, skipFocused) {
        if (controls && !settings && !episodesOpen && !ended && preview == null && failure == null && playing && !skipFocused) {
            delay(6000); controls = false
        }
    }
    val autoplay = state.profile?.autoplay != false
    val next = state.detail?.next(state.episode)
    // Autoplay drives only the next episode; skip segments are always manual, like Netflix.
    fun skip(segment: SkipSegment) {
        skipped.add(segment.type.read)
        player.seekTo(segment.endMs)
    }
    LaunchedEffect(ended, next?.key, autoplay) {
        if (!ended || next == null || !autoplay) return@LaunchedEffect
        countdown = NEXT_EPISODE_SECONDS
        while (countdown > 0) { delay(1000); countdown-- }
        vm.play(next)
    }
    var returnToDrawer by remember(stream) { mutableStateOf<PlayerDrawer?>(null) }
    fun openDrawer(value: PlayerDrawer) { preview = null; controls = true; settingsPage = PlayerSettingsPage.ROOT; settingsOrigin = PlayerSettingsPage.QUALITY; drawer = value }
    fun closeDrawer() { returnToDrawer = drawer; drawer = null }
    fun closeOverlay() {
        when {
            settings && settingsPage != PlayerSettingsPage.ROOT -> settingsPage = PlayerSettingsPage.ROOT
            drawer != null -> closeDrawer()
            ended -> { ended = false; countdown = 0 }
            preview != null -> preview = null
            else -> controls = false
        }
    }
    LaunchedEffect(controls, settings, episodesOpen, ended, failure) {
        when {
            ended && next == null && drawer == null -> rootFocus.requestFocus()
            !controls && !ended -> rootFocus.requestFocus()
        }
    }
    LaunchedEffect(showSkip) {
        if (!showSkip && skipFocused) {
            skipFocused = false
            if (!settings && !episodesOpen && !ended && failure == null) {
                if (showTransport) {
                    if (player.isCurrentMediaItemSeekable) timelineFocus.requestFocus() else playFocus.requestFocus()
                } else rootFocus.requestFocus()
            }
        }
    }
    BackHandler(ended || preview != null || settings || episodesOpen || controls) { closeOverlay() }
    val view = remember(player) { PlayerView(context).apply {
        this.player = player; useController = false; isFocusable = false
        descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
        subtitleView?.visibility = android.view.View.GONE
    } }
    DisposableEffect(view) { onDispose { view.player = null } }
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black).focusRequester(rootFocus).onPreviewKeyEvent { event ->
        val key = event.nativeKeyEvent
        if (key.action != KeyEvent.ACTION_DOWN) return@onPreviewKeyEvent false
        // Drawer controls own navigation; the focus group prevents escape to transport.
        if (drawer != null) {
            when (key.keyCode) {
                KeyEvent.KEYCODE_BACK -> { closeOverlay(); true }
                else -> false
            }
        } else {
            interaction++
            when (key.keyCode) {
                KeyEvent.KEYCODE_BACK -> {
                    if (ended || preview != null || settings || controls) closeOverlay() else vm.back()
                    true
                }
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { if (player.playWhenReady) player.pause() else player.play(); true }
                KeyEvent.KEYCODE_MEDIA_PLAY -> { player.play(); true }
                KeyEvent.KEYCODE_MEDIA_PAUSE -> { player.pause(); true }
                KeyEvent.KEYCODE_MEDIA_REWIND -> { player.seekBack(); true }
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { player.seekForward(); true }
                KeyEvent.KEYCODE_DPAD_UP -> {
                    if (!controls && showSkip) { skipFocus.requestFocus(); true }
                    else if (!controls && !ended) { controls = true; true } else false
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    if (!controls && !skipFocused && !ended) { controls = true; true } else false
                }
                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (!controls && !ended) { controls = true; true } else false
                }
                else -> false
            }
        }
    }.focusable()) {
        val safeWidth = maxWidth - safe.calculateLeftPadding(layoutDirection) - safe.calculateRightPadding(layoutDirection)
        val drawerWidth = maxOf(320.dp, minOf(620.dp, safeWidth * .36f)).coerceAtMost(safeWidth)
        AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())
        val showNext = ended && next != null && !settings && !episodesOpen
        val showBottomArea = showTransport || showSkip || showNext
        if (showBottomArea) {
            BoxWithConstraints(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(safe)) {
                // These targets attach during this subcomposition, before its effects run.
                LaunchedEffect(controls, drawer, ended, failure) {
                    when {
                        showNext -> nextFocus.requestFocus()
                        drawer != null -> Unit
                        controls && returnToDrawer != null -> {
                            if (returnToDrawer == PlayerDrawer.SETTINGS) settingsFocus.requestFocus()
                            else episodesButtonFocus.requestFocus()
                            returnToDrawer = null
                        }
                        showTransport -> playFocus.requestFocus()
                    }
                }
                val controlsWidth = if (drawer != null) (maxWidth - drawerWidth - 16.dp).coerceAtLeast(0.dp) else maxWidth
                // One measured stack keeps actions, transport and captions clear of each other.
                Column(Modifier.width(controlsWidth)
                    .onSizeChanged { bottomAreaHeight = with(density) { it.height.toDp() } }
                    .then(if (showTransport) Modifier.background(Brush.verticalGradient(0f to Color.Transparent,
                        0.28f to Color.Black.copy(alpha = 0.40f), 1f to Color.Black.copy(alpha = 0.84f))) else Modifier)
                    .padding(horizontal = 32.dp, vertical = 16.dp),
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (showNext && next != null) {
                        GlassSurface(Modifier.widthIn(max = 520.dp)) {
                            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                Text(stringResource(R.string.next_episode), color = TextPrimary, fontSize = 28.sp,
                                    fontWeight = FontWeight.Bold)
                                Text(stringResource(R.string.episode, next.number, next.name), color = TextSecondary,
                                    fontSize = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                if (autoplay) Text(stringResource(R.string.next_episode_in, countdown), color = Accent,
                                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    GlassButton(stringResource(R.string.play_next), Modifier.focusRequester(nextFocus),
                                        variant = GlassButtonVariant.PRIMARY) { vm.play(next) }
                                    GlassButton(stringResource(R.string.cancel)) { ended = false; countdown = 0 }
                                    GlassButton(stringResource(R.string.back)) { ended = false; countdown = 0; vm.back() }
                                }
                            }
                        }
                    } else if (showSkip && skippable != null) {
                        GlassButton(segmentLabel(skippable.type), variant = GlassButtonVariant.PRIMARY,
                            modifier = Modifier.heightIn(min = 56.dp).focusRequester(skipFocus)
                                .focusProperties { if (showTransport) down = if (player.isCurrentMediaItemSeekable) timelineFocus else playFocus }
                                .onFocusChanged {
                                    // Keep focus identity through removal so its replacement can receive focus.
                                    if (it.isFocused || skipVisible) skipFocused = it.isFocused
                                }) { skip(skippable) }
                    }
                    if (showTransport) {
                        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(state.detail?.title?.name.orEmpty(), color = TextPrimary, fontSize = 24.sp, lineHeight = 30.sp,
                                fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            state.episode?.let {
                                MetadataRow(modifier = Modifier.padding(top = 1.dp), items = listOf(stringResource(R.string.episode, it.number, it.name)))
                            }
                            val shownPosition = preview ?: position
                            val fraction = if (duration > 0) (shownPosition.toFloat() / duration).coerceIn(0f, 1f) else 0f
                            val timelineLabel = stringResource(R.string.timeline)
                            Canvas(Modifier.fillMaxWidth().height(24.dp).focusRequester(timelineFocus)
                                .focusProperties { if (showSkip) up = skipFocus }.onFocusChanged { timelineFocused = it.isFocused; if (!it.isFocused) preview = null }
                                .onPreviewKeyEvent { event ->
                                    if (event.nativeKeyEvent.action != KeyEvent.ACTION_DOWN || !player.isCurrentMediaItemSeekable || duration <= 0) false
                                    else when (event.nativeKeyEvent.keyCode) {
                                        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                                            val delta = if (event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -10000L else 10000L
                                            preview = ((preview ?: position) + delta).coerceIn(0, duration); true
                                        }
                                        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                                            preview?.let(player::seekTo); preview = null; true
                                        }
                                        else -> false
                                    }
                                }.focusable(player.isCurrentMediaItemSeekable).semantics {
                                    contentDescription = timelineLabel; progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
                                }) {
                                val y = size.height / 2
                                drawLine(Color.Black.copy(alpha = 0.7f), Offset(0f, y), Offset(size.width, y), 6.dp.toPx())
                                drawLine(Color.White.copy(alpha = 0.38f), Offset(0f, y), Offset(size.width, y), 4.dp.toPx())
                                drawLine(Accent, Offset(0f, y), Offset(size.width * fraction, y), 4.dp.toPx())
                                // Segment markers show where a skip is available without leaving the video.
                                if (duration > 0) state.segments?.markers(duration)?.forEach { (_, bounds) ->
                                    val (from, to) = bounds
                                    drawRect(Color.White.copy(alpha = 0.55f), Offset(size.width * from, y - 4.dp.toPx()),
                                        androidx.compose.ui.geometry.Size(size.width * (to - from), 8.dp.toPx()))
                                }
                                if (timelineFocused) drawCircle(Color.Black.copy(alpha = .65f), 9.dp.toPx(), Offset(size.width * fraction, y))
                                drawCircle(if (timelineFocused) Accent else Color.White, if (timelineFocused) 6.dp.toPx() else 4.dp.toPx(), Offset(size.width * fraction, y))
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(clock(shownPosition), fontSize = 12.sp, color = TextSecondary)
                                Text(if (preview != null) stringResource(R.string.seek_preview, clock(shownPosition))
                                    else stringResource(R.string.time_remaining, clock((duration - position).coerceAtLeast(0))), fontSize = 12.sp, color = TextSecondary)
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                PlayerControl(stringResource(if (playRequested) R.string.pause else R.string.play), if (playRequested) TransportIcon.PAUSE else TransportIcon.PLAY,
                                    if (failure == null) Modifier.focusRequester(playFocus) else Modifier, failure == null) {
                                    if (player.playWhenReady) player.pause() else player.play()
                                }
                                PlayerControl(stringResource(R.string.seek_back), TransportIcon.BACKWARD, enabled = player.isCurrentMediaItemSeekable) { player.seekBack() }
                                PlayerControl(stringResource(R.string.seek_forward), TransportIcon.FORWARD, enabled = player.isCurrentMediaItemSeekable) { player.seekForward() }
                                Spacer(Modifier.weight(1f))
                                if (episodes.isNotEmpty() && drawer == null) PlayerControl(stringResource(R.string.episodes), TransportIcon.EPISODES,
                                    Modifier.focusRequester(episodesButtonFocus)) { openDrawer(PlayerDrawer.EPISODES) }
                                if (drawer == null) {
                                    PlayerControl(stringResource(R.string.settings), TransportIcon.SETTINGS, Modifier.focusRequester(settingsFocus)) { openDrawer(PlayerDrawer.SETTINGS) }
                                    PlayerControl(stringResource(R.string.back), TransportIcon.EXIT) { vm.back() }
                                }
                            }
                            failure?.let {
                                if (it == Failure.VIP) AccessMessage(state.account) else FailureMessage(it, vm::refresh, Modifier.focusRequester(playFocus))
                                if (it in listOf(Failure.SIGNED_OUT, Failure.EXPIRED, Failure.VIP, Failure.CAPACITY)) Action(stringResource(R.string.sign_in), if (it == Failure.VIP) Modifier.focusRequester(playFocus) else Modifier) { vm.signInForPlayback() }
                            }
                        }
                    }
                }
            }
        }
        if (settings) {
            val audioChoices = trackChoices(tracks, C.TRACK_TYPE_AUDIO)
            val subtitleChoices = listOf(MenuChoice<TrackChoice?>(null, stringResource(R.string.subtitles_off), "off")) +
                trackChoices(tracks, C.TRACK_TYPE_TEXT)
            val audioSelected = audioChoices.firstOrNull { it.value?.isSelected == true }?.value
            val subtitleSelected = subtitleChoices.firstOrNull { it.value?.isSelected == true }?.value
            val quality = state.profile?.quality ?: 0
            val qualities = qualityChoices()
            val autoplayOptions = autoplayChoices()
            val titles = mapOf(
                PlayerSettingsPage.ROOT to stringResource(R.string.settings),
                PlayerSettingsPage.QUALITY to stringResource(R.string.quality),
                PlayerSettingsPage.AUDIO to stringResource(R.string.audio),
                PlayerSettingsPage.SUBTITLES to stringResource(R.string.subtitles),
                PlayerSettingsPage.AUTOPLAY to stringResource(R.string.autoplay_next),
                PlayerSettingsPage.SKIP to stringResource(R.string.skip_segments),
            )
            fun openPage(page: PlayerSettingsPage) { settingsOrigin = page; settingsPage = page }
            GlassDrawer(
                onDismiss = ::closeDrawer,
                width = drawerWidth,
                modifier = Modifier.padding(safe).padding(vertical = 14.dp),
            ) {
                val headerBack = remember { FocusRequester() }
                Text(titles.getValue(settingsPage), color = TextPrimary, fontSize = 24.sp)
                MenuRow(stringResource(R.string.back), modifier = Modifier.focusRequester(headerBack), chevron = false, onClick = ::closeOverlay)
                when (settingsPage) {
                    PlayerSettingsPage.ROOT -> {
                        LaunchedEffect(settingsPage) {
                            val origin = if (settingsOrigin == PlayerSettingsPage.AUDIO && audioChoices.isEmpty())
                                PlayerSettingsPage.QUALITY else settingsOrigin
                            settingsList.scrollToItem(origin.ordinal - 1)
                            withFrameNanos { }
                            settingsRows.getValue(origin).requestFocus()
                        }
                        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = settingsList,
                            contentPadding = PaddingValues(vertical = 4.dp)) {
                            item(key = "quality") {
                                MenuRow(titles.getValue(PlayerSettingsPage.QUALITY),
                                    value = qualities.firstOrNull { it.value == quality }?.label,
                                    modifier = Modifier.focusRequester(settingsRows.getValue(PlayerSettingsPage.QUALITY))) { openPage(PlayerSettingsPage.QUALITY) }
                            }
                            item(key = "audio") {
                                MenuRow(titles.getValue(PlayerSettingsPage.AUDIO),
                                    value = audioChoices.firstOrNull { it.value == audioSelected }?.label ?: stringResource(R.string.no_tracks),
                                    modifier = Modifier.focusRequester(settingsRows.getValue(PlayerSettingsPage.AUDIO)),
                                    enabled = audioChoices.isNotEmpty()) { openPage(PlayerSettingsPage.AUDIO) }
                            }
                            item(key = "subtitles") {
                                MenuRow(titles.getValue(PlayerSettingsPage.SUBTITLES),
                                    value = subtitleChoices.first { it.value == subtitleSelected }.label,
                                    modifier = Modifier.focusRequester(settingsRows.getValue(PlayerSettingsPage.SUBTITLES))) { openPage(PlayerSettingsPage.SUBTITLES) }
                            }
                            item(key = "autoplay") {
                                MenuRow(titles.getValue(PlayerSettingsPage.AUTOPLAY),
                                    value = autoplayOptions.first { it.value == autoplay }.label,
                                    modifier = Modifier.focusRequester(settingsRows.getValue(PlayerSettingsPage.AUTOPLAY))) { openPage(PlayerSettingsPage.AUTOPLAY) }
                            }
                            item(key = "skip") {
                                MenuRow(titles.getValue(PlayerSettingsPage.SKIP),
                                    modifier = Modifier.focusRequester(settingsRows.getValue(PlayerSettingsPage.SKIP))) { openPage(PlayerSettingsPage.SKIP) }
                            }
                        }
                    }
                    PlayerSettingsPage.QUALITY -> {
                        Text(stringResource(R.string.quality_help), color = TextSecondary, fontSize = 13.sp)
                        ChoiceList(qualities, quality, Modifier.weight(1f)) { vm.setQuality(it); settingsPage = PlayerSettingsPage.ROOT }
                    }
                    PlayerSettingsPage.AUDIO -> {
                        // Tracks can disappear while this page is open. Back remains reachable.
                        if (audioChoices.isEmpty()) {
                            Text(stringResource(R.string.no_tracks), color = TextSecondary)
                            LaunchedEffect(Unit) { withFrameNanos { }; headerBack.requestFocus() }
                        } else ChoiceList(audioChoices, audioSelected, Modifier.weight(1f)) {
                            selectTrack(player, C.TRACK_TYPE_AUDIO, it); settingsPage = PlayerSettingsPage.ROOT
                        }
                    }
                    PlayerSettingsPage.SUBTITLES -> ChoiceList(subtitleChoices, subtitleSelected, Modifier.weight(1f)) {
                        selectTrack(player, C.TRACK_TYPE_TEXT, it); settingsPage = PlayerSettingsPage.ROOT
                    }
                    PlayerSettingsPage.AUTOPLAY -> {
                        Text(stringResource(R.string.autoplay_help), color = TextSecondary, fontSize = 13.sp)
                        ChoiceList(autoplayOptions, autoplay, Modifier.weight(1f)) { vm.setAutoplay(it); settingsPage = PlayerSettingsPage.ROOT }
                    }
                    PlayerSettingsPage.SKIP -> {
                        LaunchedEffect(Unit) { withFrameNanos { }; headerBack.requestFocus() }
                        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(vertical = 4.dp)) {
                            item {
                                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.skip_segments_help), color = Muted, fontSize = 13.sp)
                    val imdbId = vm.imdbId()
                    Field(stringResource(R.string.imdb_id), imdbId.orEmpty(), { vm.setImdbId(it.take(12)) })
                    if (state.segmentsLoading) Text(stringResource(R.string.loading), color = Muted, fontSize = 13.sp)
                    if (state.segments?.none == true && !state.segmentsLoading) {
                        Text(stringResource(R.string.no_segments), color = Muted, fontSize = 13.sp)
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SegmentType.entries.forEach { type ->
                            val existing = segments.firstOrNull { it.type == type }
                            Text(segmentLabel(type), color = TextPrimary, fontSize = 16.sp)
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(if (existing != null) "${clock(existing.startMs)} – ${clock(existing.endMs)}"
                                    else stringResource(R.string.segment_unmarked), color = Muted, fontSize = 13.sp,
                                    modifier = Modifier.weight(1f))
                                Action(stringResource(if (marking == type) R.string.segment_mark_end else R.string.segment_mark)) {
                                    if (marking == type) {
                                        vm.markSegment(type, markStart, player.currentPosition); marking = null
                                    } else { marking = type; markStart = player.currentPosition }
                                }
                            }
                        }
                    }
                    Text(stringResource(if (marking != null) R.string.segment_marking else R.string.segment_mark_help),
                        color = if (marking != null) Accent else Muted, fontSize = 13.sp,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    Text(stringResource(if (vm.canSubmit) R.string.introdb_key_stored else R.string.introdb_key_unavailable),
                        color = if (vm.canSubmit) Accent else Muted, fontSize = 13.sp)
                    Action(stringResource(R.string.segment_clear), enabled = vm.markedSegments().isNotEmpty()) {
                        SegmentType.entries.forEach(vm::clearMarked)
                    }
                    Action(stringResource(R.string.segment_submit), enabled = vm.markedSegments().isNotEmpty() && vm.canSubmit) { vm.submitSegments() }
                                }
                            }
                        }
                    }
                }
            }
        }
        if (episodesOpen) {
            val title = state.detail?.title
            val seasons = remember(episodes) { episodes.map { it.season }.distinct().sorted() }
            var picked by remember(stream) { mutableIntStateOf(0) }
            val activeSeason = if (seasons.contains(picked)) picked else seasons.firstOrNull() ?: 0
            GlassDrawer(
                onDismiss = ::closeDrawer,
                width = drawerWidth,
                modifier = Modifier.padding(safe).padding(vertical = 14.dp),
            ) {
                LaunchedEffect(Unit) { withFrameNanos { }; episodesFocus.requestFocus() }
                GlassButton(stringResource(R.string.back), Modifier.focusRequester(episodesFocus).widthIn(min = 82.dp),
                    variant = GlassButtonVariant.SECONDARY, compact = true, onClick = ::closeDrawer)
                SeasonPicker(seasons, activeSeason, modifier = Modifier.padding(top = 14.dp)) { picked = it }
                LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(episodes.filter { it.season == activeSeason }.sortedBy { it.number }, key = Episode::key) { episode ->
                        val viewing = state.viewing.firstOrNull { it.title.key == title?.key && it.episode?.key == episode.key }
                        EpisodeRow(episode, viewing, state.episode?.key == episode.key) {
                            drawer = null
                            vm.play(episode)
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    GlassButton(stringResource(R.string.settings), Modifier.weight(1f),
                        variant = GlassButtonVariant.ICON) {
                        openDrawer(PlayerDrawer.SETTINGS)
                    }
                    GlassButton(stringResource(R.string.back), Modifier.weight(1f), variant = GlassButtonVariant.ICON) {
                        closeDrawer()
                    }
                }
            }
        }
        // A separate viewport also contains explicitly positioned cues above transport.
        if (failure == null) Box(Modifier.fillMaxSize().padding(safe)) {
            AndroidView(factory = { SubtitleView(it).apply { setUserDefaultStyle(); setUserDefaultTextSize(); setBottomPaddingFraction(0.04f) } },
                modifier = Modifier.fillMaxSize().padding(end = if (drawer != null) drawerWidth + 16.dp else 0.dp,
                bottom = if (showBottomArea) bottomAreaHeight + 8.dp else 0.dp),
                update = { subtitles -> subtitles.setCues(cues) })
        }
        if (buffering && failure == null) GlassSurface(Modifier.align(Alignment.Center)) { Loading() }
    }
}

private data class TrackChoice(val group: Tracks.Group, val index: Int) {
    val isSelected get() = group.isTrackSelected(index)
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable private fun trackChoices(tracks: Tracks, type: Int): List<MenuChoice<TrackChoice?>> {
    var number = 0
    return tracks.groups.filter { it.type == type }.flatMap { group ->
        (0 until group.length).filter { group.isTrackSupported(it) }.map { index ->
            val format = group.getTrackFormat(index)
            val label = format.label ?: format.language ?: stringResource(R.string.track_number, ++number)
            MenuChoice<TrackChoice?>(TrackChoice(group, index), label, "${group.mediaTrackGroup.id}:$index")
        }
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private fun selectTrack(player: Player, type: Int, choice: TrackChoice?) {
    val parameters = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(type, choice == null)
        .clearOverridesOfType(type)
    if (choice != null) parameters.setOverrideForType(TrackSelectionOverride(choice.group.mediaTrackGroup, choice.index))
    player.trackSelectionParameters = parameters.build()
}

internal fun clock(milliseconds: Long): String {
    val seconds = milliseconds.coerceAtLeast(0) / 1000
    return java.lang.String.format(java.util.Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
}

/** The action label for a segment, e.g. "Skip intro". */
@Composable internal fun segmentLabel(type: SegmentType): String = stringResource(when (type) {
    SegmentType.INTRO -> R.string.skip_intro
    SegmentType.RECAP -> R.string.skip_recap
    SegmentType.CREDITS -> R.string.skip_credits
    SegmentType.POST_CREDITS -> R.string.skip_post_credits
})

private enum class TransportIcon { PLAY, PAUSE, BACKWARD, FORWARD, SETTINGS, EPISODES, EXIT }
private const val NEXT_EPISODE_SECONDS = 5

@Composable private fun PlayerControl(label: String, icon: TransportIcon, modifier: Modifier = Modifier,
    enabled: Boolean = true, click: () -> Unit) {
    FocusFrame(modifier = modifier.width(56.dp).height(56.dp), radius = RadiusMedium.dp, enabled = enabled,
        restingFill = GlassMedium, visualHeight = 36.dp, contentDescription = label, onClick = click) {
        Canvas(Modifier.align(Alignment.Center).size(24.dp)) {
            val ink = TextPrimary
            val w = size.width; val h = size.height
            fun triangle(x: Float, backwards: Boolean = false) {
                val p = Path()
                p.moveTo(x + if (backwards) w * 0.30f else 0f, h * 0.18f)
                p.lineTo(x + if (backwards) 0f else w * 0.30f, h * 0.5f)
                p.lineTo(x + if (backwards) w * 0.30f else 0f, h * 0.82f); p.close()
                drawPath(p, ink)
            }
            when (icon) {
                TransportIcon.PLAY -> { val p = Path(); p.moveTo(w * 0.25f, h * 0.12f); p.lineTo(w * 0.82f, h * 0.5f); p.lineTo(w * 0.25f, h * 0.88f); p.close(); drawPath(p, ink) }
                TransportIcon.PAUSE -> { drawRect(ink, Offset(w * 0.22f, h * 0.15f), Size(w * 0.19f, h * 0.7f)); drawRect(ink, Offset(w * 0.59f, h * 0.15f), Size(w * 0.19f, h * 0.7f)) }
                TransportIcon.BACKWARD -> { triangle(w * 0.12f, true); triangle(w * 0.52f, true) }
                TransportIcon.FORWARD -> { triangle(w * 0.12f); triangle(w * 0.52f) }
                TransportIcon.SETTINGS -> {
                    for (row in 1..3) { val y = h * row / 4; val x = w * if (row == 2) 0.7f else 0.3f
                        drawLine(ink, Offset(w * 0.1f, y), Offset(w * 0.9f, y), 2.dp.toPx())
                        drawCircle(Color.Black.copy(alpha = .58f), 3.dp.toPx(), Offset(x, y))
                        drawCircle(ink, 3.dp.toPx(), Offset(x, y), style = Stroke(2.dp.toPx()))
                    }
                }
                TransportIcon.EPISODES -> {
                    // Stacked list glyph: three rows with a leading marker, drawn as shapes.
                    for (row in 0..2) {
                        val y = h * (0.24f + row * 0.26f)
                        drawCircle(ink, 2.4.dp.toPx(), Offset(w * 0.19f, y))
                        drawLine(ink, Offset(w * 0.38f, y), Offset(w * 0.88f, y), 2.dp.toPx())
                    }
                }
                TransportIcon.EXIT -> { drawLine(ink, Offset(w * 0.15f, h * 0.5f), Offset(w * 0.85f, h * 0.5f), 2.dp.toPx())
                    drawLine(ink, Offset(w * 0.15f, h * 0.5f), Offset(w * 0.45f, h * 0.2f), 2.dp.toPx())
                    drawLine(ink, Offset(w * 0.15f, h * 0.5f), Offset(w * 0.45f, h * 0.8f), 2.dp.toPx())
                }
            }
        }
    }
}
