package dev.personal.flixtortv

import android.app.Activity
import android.view.KeyEvent
import android.view.WindowManager
import android.os.SystemClock
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
import androidx.compose.ui.res.pluralStringResource
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
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionError
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.tv.material3.Text
import kotlinx.coroutines.delay

private enum class PlayerDrawer { SETTINGS, EPISODES }
private enum class PlayerSettingsPage { ROOT, QUALITY, AUDIO, SUBTITLES, AUTOPLAY, SKIP, BUFFER }

@Composable internal fun PlaybackScreen(stream: Stream, state: TvState, vm: TvViewModel) {
    // A recovered stream may be identical. Recreate its player without resetting attendance.
    key(vm.watchingSession.token, state.playerGeneration) { PlaybackContent(stream, state, vm) }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable private fun PlaybackContent(stream: Stream, state: TvState, vm: TvViewModel) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val identity = remember(stream) { state.playbackIdentity }
    val playbackToken = remember(stream) { vm.watchingSession.token }
    val playerGeneration = remember(stream) { state.playerGeneration }
    fun activePlayer() = vm.isCurrentPlayer(playbackToken, playerGeneration)
    val lifecycleState by lifecycle.currentStateFlow.collectAsState()
    val awaitingConfirmation = state.stillWatchingSeconds != null
    val choosingVersion = state.animeChoice != null
    val failure = state.failure
    var buffering by remember(stream) { mutableStateOf(!vm.watchingSession.finished) }
    var controls by remember(stream) { mutableStateOf(true) }
    var drawer by remember(stream) { mutableStateOf<PlayerDrawer?>(null) }
    var settingsPage by remember(stream) { mutableStateOf(PlayerSettingsPage.ROOT) }
    var settingsOrigin by remember(stream) { mutableStateOf(PlayerSettingsPage.QUALITY) }
    val settingsRows = remember(stream) { PlayerSettingsPage.entries.associateWith { FocusRequester() } }
    val settingsList = androidx.compose.foundation.lazy.rememberLazyListState()
    val settings = drawer == PlayerDrawer.SETTINGS
    val episodesOpen = drawer == PlayerDrawer.EPISODES
    var ended by remember(stream) { mutableStateOf(vm.watchingSession.finished) }
    var countdown by remember(stream) { mutableIntStateOf(0) }
    var editorOpen by remember(stream) { mutableStateOf(false) }
    var editingRegion by remember(stream) { mutableStateOf<SkipSegment?>(null) }
    var editorOrigin by remember(stream) { mutableLongStateOf(0) }
    var editorResume by remember(stream) { mutableStateOf(false) }
    var returnToEditor by remember(stream) { mutableStateOf(false) }
    val editFocus = remember(stream) { FocusRequester() }
    var undoRegion by remember(stream) { mutableStateOf<SkipSegment?>(null) }
    val nextClock = remember(stream) { NextEpisodeCountdown() }
    var nextCancelled by remember(stream) { mutableStateOf(false) }
    var skippedCredits by remember(stream) { mutableStateOf<SkipSegment?>(null) }
    val loadControl = remember(stream) { PlaybackBuffer() }
    var bufferAhead by remember(stream) { mutableStateOf(false) }
    var bufferResume by remember(stream) { mutableStateOf(false) }
    var bufferPreviousMode by remember(stream) { mutableStateOf(false) }
    var buffered by remember(stream) { mutableLongStateOf(0) }
    var bufferComplete by remember(stream) { mutableStateOf(false) }
    var bufferStalled by remember(stream) { mutableStateOf(false) }
    var bufferRestart by remember(stream) { mutableIntStateOf(0) }
    val bufferFocus = remember(stream) { FocusRequester() }
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
    val active = segments.filter { it.contains(position) && it.endMs <= duration }.maxByOrNull { it.startMs }
    // The skip affordance only appears while inside a segment that has not been used.
    val skippable = active?.takeIf { it.id !in skipped && vm.action(it) != SkipAction.OFF }
    var preview by remember(stream) { mutableStateOf<Long?>(null) }
    var timelineFocused by remember(stream) { mutableStateOf(false) }
    val safe = LocalSafePadding.current
    val layoutDirection = LocalLayoutDirection.current
    val density = LocalDensity.current
    var bottomAreaHeight by remember(stream) { mutableStateOf(0.dp) }
    val showTransport = controls && !ended && !awaitingConfirmation && !editorOpen && !bufferAhead
    val showSkip = skippable != null && failure == null && !settings && !episodesOpen && !ended && !awaitingConfirmation && !editorOpen && !bufferAhead
    val skipVisible by rememberUpdatedState(showSkip)
    val player = remember(stream) {
        val http = OkHttpDataSource.Factory(if (state.detail?.title?.source == ContentSource.HIANIME) vm.anime.mediaClient else vm.client.mediaClient).setUserAgent(FlixtorClient.USER_AGENT)
            .setDefaultRequestProperties(stream.headers)
        ExoPlayer.Builder(context)
            .setLoadControl(loadControl.apply { larger = state.profile?.largerBuffer == true })
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(context, http)))
            .setSeekBackIncrementMs(10000).setSeekForwardIncrementMs(10000).build().apply {
                setAudioAttributes(AudioAttributes.DEFAULT, true); setHandleAudioBecomingNoisy(true)
                val subtitles = stream.captions.map { caption -> MediaItem.SubtitleConfiguration.Builder(android.net.Uri.parse(caption.uri))
                    .setMimeType(caption.mime).setLabel(caption.label)
                    .setSelectionFlags(if (caption.default) C.SELECTION_FLAG_DEFAULT else 0).build() }
                val item = MediaItem.Builder().setUri(stream.uri).setMimeType(stream.mime)
                    .setSubtitleConfigurations(subtitles).setMediaMetadata(MediaMetadata.Builder().setTitle(state.detail?.title?.name).build()).build()
                setMediaItem(item, state.resumePosition)
                if (!vm.watchingSession.finished) prepare()
                playWhenReady = state.resumePlayWhenReady && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                    !vm.watchingSession.finished && !awaitingConfirmation
            }
    }
    LaunchedEffect(player, state.profile?.quality) {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setMaxVideoSize(Int.MAX_VALUE, state.profile?.quality?.takeIf { it > 0 } ?: Int.MAX_VALUE)
            .setForceHighestSupportedBitrate(false).build()
    }
    LaunchedEffect(state.profile?.largerBuffer) { loadControl.larger = state.profile?.largerBuffer == true }
    fun closeEditor() {
        editorOpen = false; player.seekTo(editorOrigin); controls = true; returnToEditor = true
        if (editorResume && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && !awaitingConfirmation && !choosingVersion && failure == null) player.play()
        editorResume = false
    }
    fun openEditor(region: SkipSegment?) {
        editorOrigin = player.currentPosition; editorResume = player.playWhenReady
        player.pause(); preview = null; drawer = null; controls = true
        editingRegion = region; editorOpen = true
    }
    fun closeBuffer(play: Boolean) {
        bufferAhead = false; loadControl.larger = bufferPreviousMode; controls = true
        if (play && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && !awaitingConfirmation && !choosingVersion && failure == null) player.play()
        bufferResume = false
    }
    fun startBuffer() {
        bufferResume = player.playWhenReady; bufferPreviousMode = loadControl.larger
        player.pause(); loadControl.larger = true; drawer = null; preview = null; controls = true
        bufferComplete = false; bufferStalled = false; bufferAhead = true
    }
    LaunchedEffect(bufferAhead, bufferRestart) {
        if (!bufferAhead) return@LaunchedEffect
        var lastPosition = player.bufferedPosition
        var grewAt = SystemClock.elapsedRealtime()
        while (true) {
            val ahead = (player.bufferedPosition - player.currentPosition).coerceAtLeast(0)
            val remaining = (player.duration - player.currentPosition).coerceAtLeast(0)
            if (player.bufferedPosition > lastPosition) { lastPosition = player.bufferedPosition; grewAt = SystemClock.elapsedRealtime() }
            bufferComplete = player.duration > 0 && bufferReady(ahead, remaining, loadControl.allocator.totalBytesAllocated, loadControl.byteTarget)
            bufferStalled = !bufferComplete && SystemClock.elapsedRealtime() - grewAt >= 15000
            delay(250)
        }
    }
    DisposableEffect(player) {
        playRequested = player.playWhenReady
        fun watch(foreground: Boolean = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            if (activePlayer()) vm.watchPlayback(playbackToken, player.isPlaying, foreground)
        }
        watch()
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                if (!activePlayer()) return
                val http = generateSequence(error.cause) { it.cause }.filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()
                val detectedFailure = when {
                    http?.responseCode == 401 -> if (vm.isAnime) Failure.ACCESS else Failure.SIGNED_OUT
                    http?.responseCode == 403 -> Failure.ACCESS
                    http?.responseCode == 429 || (http?.responseCode ?: 0) >= 500 -> Failure.UNAVAILABLE
                    error.errorCode in listOf(PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
                        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED, PlaybackException.ERROR_CODE_DECODING_FAILED) -> Failure.UNSUPPORTED
                    else -> Failure.MEDIA
                }
                buffering = false; controls = true
                vm.playerFailure(detectedFailure, playbackToken, playerGeneration, player.currentPosition,
                    player.duration, player.playWhenReady)
            }
            override fun onPlaybackStateChanged(value: Int) {
                if (!activePlayer()) return
                buffering = value == Player.STATE_BUFFERING
                if (value == Player.STATE_ENDED && !editorOpen && !bufferAhead) {
                    identity?.let { vm.saveProgress(it, player.duration.coerceAtLeast(0), player.duration.coerceAtLeast(0)) }
                    watch()
                    vm.playbackEnded(playbackToken)
                    if (vm.watchingSession.pending) player.pause()
                    drawer = null; controls = true; ended = true
                }
            }
            override fun onIsPlayingChanged(value: Boolean) { if (activePlayer()) { playing = value; watch() } }
            override fun onPlayWhenReadyChanged(value: Boolean, reason: Int) { if (activePlayer()) playRequested = value }
            override fun onTracksChanged(value: Tracks) { if (activePlayer()) tracks = value }
            override fun onCues(value: CueGroup) { if (activePlayer()) cues = value.cues }
        }
        player.addListener(listener)
        val session = MediaSession.Builder(context, player).setCallback(object : MediaSession.Callback {
            override fun onMediaButtonEvent(session: MediaSession, controllerInfo: MediaSession.ControllerInfo,
                intent: android.content.Intent): Boolean {
                if (!activePlayer()) return true
                val key = androidx.core.content.IntentCompat.getParcelableExtra(intent,
                    android.content.Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
                if (key?.action == KeyEvent.ACTION_DOWN) vm.recordPlaybackInteraction()
                return vm.watchingSession.pending || vm.state.value.animeChoice != null || editorOpen || bufferAhead
            }
            @Suppress("OVERRIDE_DEPRECATION") // Media3 1.4.1 command gate keeps pending confirmation authoritative.
            override fun onPlayerCommandRequest(session: MediaSession, controller: MediaSession.ControllerInfo,
                playerCommand: Int): Int {
                if (!activePlayer()) return SessionError.ERROR_INVALID_STATE
                val transport = playerCommand in setOf(Player.COMMAND_PLAY_PAUSE, Player.COMMAND_STOP, Player.COMMAND_PREPARE,
                    Player.COMMAND_SET_MEDIA_ITEM, Player.COMMAND_CHANGE_MEDIA_ITEMS,
                    Player.COMMAND_SET_REPEAT_MODE, Player.COMMAND_SET_SHUFFLE_MODE,
                    Player.COMMAND_SEEK_TO_DEFAULT_POSITION, Player.COMMAND_SEEK_TO_MEDIA_ITEM,
                    Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, Player.COMMAND_SEEK_BACK, Player.COMMAND_SEEK_FORWARD,
                    Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                    Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                    Player.COMMAND_SET_SPEED_AND_PITCH, Player.COMMAND_SET_TRACK_SELECTION_PARAMETERS)
                if (transport) {
                    vm.recordPlaybackInteraction()
                    if (playerCommand in setOf(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, Player.COMMAND_SEEK_BACK,
                        Player.COMMAND_SEEK_FORWARD, Player.COMMAND_SEEK_TO_DEFAULT_POSITION)) skippedCredits = null
                    if (vm.watchingSession.pending || vm.state.value.animeChoice != null || editorOpen || bufferAhead) return SessionError.ERROR_INVALID_STATE
                }
                return SessionResult.RESULT_SUCCESS
            }
        }).build()
        val window = (context as? Activity)?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val observer = LifecycleEventObserver { _, event ->
            if (!activePlayer()) return@LifecycleEventObserver
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) watch(false)
            if (event == Lifecycle.Event.ON_STOP) {
                if (!ended) identity?.let { vm.saveProgress(it, player.currentPosition, player.duration) }
                player.pause()
                editorResume = false; bufferResume = false
                if (bufferAhead) { bufferAhead = false; loadControl.larger = bufferPreviousMode }
            }
            if (event == Lifecycle.Event.ON_RESUME) { controls = true; watch(true) }
        }
        lifecycle.addObserver(observer)
        onDispose {
            if (activePlayer()) vm.watchPlayback(playbackToken, false, false)
            // Recovery already captured this player's position. A retired player must not overwrite it.
            if (!ended && (activePlayer() || playbackToken != vm.watchingSession.token))
                identity?.let { vm.saveProgress(it, player.currentPosition, player.duration) }
            lifecycle.removeObserver(observer); player.removeListener(listener)
            session.release(); player.release(); window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
    LaunchedEffect(player) {
        var tick = 0
        while (true) {
            delay(250)
            if (!activePlayer()) return@LaunchedEffect
            position = player.currentPosition.coerceAtLeast(0); duration = player.duration.coerceAtLeast(0)
            buffered = player.bufferedPosition.coerceAtLeast(0)
            vm.playerDuration(playbackToken, duration)
            vm.watchPlayback(playbackToken, player.isPlaying, lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
            if (++tick % 20 == 0 && player.isPlaying) identity?.let { vm.saveProgress(it, position, duration) }
        }
    }
    var resumeAfterChoice by remember(stream) { mutableStateOf(false) }
    LaunchedEffect(player, choosingVersion) {
        if (choosingVersion) {
            resumeAfterChoice = player.playWhenReady
            identity?.let { vm.saveProgress(it, player.currentPosition, player.duration) }
            player.pause()
        } else if (resumeAfterChoice) {
            resumeAfterChoice = false
            if (lifecycleState == Lifecycle.State.RESUMED && !ended && !awaitingConfirmation) player.play()
        }
    }
    LaunchedEffect(controls, settings, episodesOpen, interaction, failure, playing, preview, ended, skipFocused, editorOpen, bufferAhead) {
        if (controls && !settings && !episodesOpen && !ended && preview == null && failure == null && playing && !skipFocused && !editorOpen && !bufferAhead) {
            delay(6000); controls = false
        }
    }
    val autoplay = state.profile?.autoplay != false && !state.animeAutoplayBlocked
    val next = state.detail?.next(state.episode)
    val credits = segments.firstOrNull { it.type == SegmentType.CREDITS && it.contains(position) && it.endMs <= duration }
        ?: skippedCredits?.takeIf { skippedRegion -> position >= skippedRegion.endMs &&
            vm.action(skippedRegion) == SkipAction.AUTO && segments.any { it.id == skippedRegion.id } }
    val nextEligible = next != null && (ended || credits != null)
    val countdownEnabled = autoplay && !nextCancelled && (ended || vm.creditsAutoplayAllowed(playbackToken))
    // Autoplay drives only the next episode; skip segments are always manual, like Netflix.
    fun skip(segment: SkipSegment) {
        skipped.add(segment.id)
        player.seekTo(segment.endMs)
    }
    val canCount = countdownEnabled && !awaitingConfirmation && !choosingVersion && lifecycleState == Lifecycle.State.RESUMED &&
        failure == null && !buffering && !editorOpen && !bufferAhead && drawer == null && preview == null && (ended || playing)
    LaunchedEffect(nextEligible, next?.key, canCount, ended) {
        while (true) {
            if (!activePlayer()) return@LaunchedEffect
            val advance = nextClock.tick(SystemClock.elapsedRealtime(), nextEligible, canCount)
            countdown = ((nextClock.remainingMs + 999) / 1000).toInt()
            if (advance && next != null) {
                if (vm.autoplayNext(playbackToken, next, if (ended) null else player.currentPosition, duration, skippedCredits?.id)) ended = true
                break
            }
            delay(100)
        }
    }
    LaunchedEffect(position, skippable?.id, state.skipRevision, playing, editorOpen, bufferAhead, drawer, preview, awaitingConfirmation) {
        if (playing && !editorOpen && !bufferAhead && drawer == null && preview == null && !awaitingConfirmation && failure == null && !choosingVersion &&
            skippable != null && vm.action(skippable) == SkipAction.AUTO) {
            if (skippable.type == SegmentType.CREDITS && next != null) skippedCredits = skippable
            undoRegion = skippable; skip(skippable)
        }
    }
    LaunchedEffect(undoRegion?.id) {
        if (undoRegion != null) { delay(5000); undoRegion = null }
    }
    var returnToDrawer by remember(stream) { mutableStateOf<PlayerDrawer?>(null) }
    fun openDrawer(value: PlayerDrawer) { preview = null; controls = true; settingsPage = PlayerSettingsPage.ROOT; settingsOrigin = PlayerSettingsPage.QUALITY; drawer = value }
    fun closeDrawer() { returnToDrawer = drawer; drawer = null }
    fun closeOverlay() {
        when {
            editorOpen -> closeEditor()
            bufferAhead -> closeBuffer(bufferResume)
            settings && settingsPage != PlayerSettingsPage.ROOT -> settingsPage = PlayerSettingsPage.ROOT
            drawer != null -> closeDrawer()
            ended || nextEligible && !nextCancelled -> { nextClock.cancel(); nextCancelled = true; ended = false; countdown = 0 }
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
    BackHandler(ended || preview != null || settings || episodesOpen || controls || editorOpen || bufferAhead || nextEligible && !nextCancelled) { closeOverlay() }
    val view = remember(player) { PlayerView(context).apply {
        this.player = player; useController = false; isFocusable = false
        descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
        subtitleView?.visibility = android.view.View.GONE
    } }
    DisposableEffect(view) { onDispose { view.player = null } }
    CompositionLocalProvider(LocalPlaybackInteraction provides vm::recordPlaybackInteraction) {
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black).trackPlaybackInteraction().focusRequester(rootFocus).onPreviewKeyEvent { event ->
        val key = event.nativeKeyEvent
        if (key.action != KeyEvent.ACTION_DOWN) return@onPreviewKeyEvent false
        if (vm.watchingSession.pending) return@onPreviewKeyEvent true
        // Drawer controls own navigation; the focus group prevents escape to transport.
        if (editorOpen || bufferAhead) {
            if (key.keyCode == KeyEvent.KEYCODE_BACK) { closeOverlay(); true }
            else if (key.keyCode in listOf(KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD)) true else false
        } else if (drawer != null) {
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
                KeyEvent.KEYCODE_MEDIA_REWIND -> { skippedCredits = null; player.seekBack(); true }
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { skippedCredits = null; player.seekForward(); true }
                KeyEvent.KEYCODE_DPAD_UP -> {
                    if (!controls && nextEligible && !nextCancelled) { nextFocus.requestFocus(); true }
                    else if (!controls && showSkip) { skipFocus.requestFocus(); true }
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
        val showNext = nextEligible && (ended || !nextCancelled) && !settings && !episodesOpen && !awaitingConfirmation && !editorOpen && !bufferAhead && failure == null
        val showBottomArea = showTransport || showSkip || showNext
        // A separate viewport also contains explicitly positioned cues above transport.
        if (failure == null) Box(Modifier.fillMaxSize().padding(safe)) {
            AndroidView(factory = { SubtitleView(it).apply { setUserDefaultStyle(); setUserDefaultTextSize(); setBottomPaddingFraction(0.04f) } },
                modifier = Modifier.fillMaxSize().padding(end = if (drawer != null) drawerWidth + 16.dp else 0.dp,
                bottom = if (showBottomArea || editorOpen || bufferAhead) bottomAreaHeight + 8.dp else 0.dp),
                update = { subtitles -> subtitles.setCues(cues) })
        }
        if (showBottomArea) {
            BoxWithConstraints(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(safe)) {
                // These targets attach during this subcomposition, before its effects run.
                LaunchedEffect(controls, drawer, ended, failure) {
                    when {
                        showNext && ended -> nextFocus.requestFocus()
                        drawer != null -> Unit
                        returnToEditor -> { editFocus.requestFocus(); returnToEditor = false }
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
                                Text(episodeLabel(next), color = TextSecondary,
                                    fontSize = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                if (countdownEnabled) Text(stringResource(R.string.next_episode_in, countdown), color = Accent,
                                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    GlassButton(stringResource(R.string.play_next), Modifier.focusRequester(nextFocus),
                                        variant = GlassButtonVariant.PRIMARY) { if (vm.playNext(playbackToken, next, duration)) ended = true }
                                    GlassButton(stringResource(R.string.cancel)) { nextClock.cancel(); nextCancelled = true; ended = false; countdown = 0 }
                                    if (ended) GlassButton(stringResource(R.string.back)) { nextClock.cancel(); vm.back() }
                                }
                            }
                        }
                    } else if (showSkip && skippable != null) {
                        GlassButton(regionLabel(skippable, vm), variant = GlassButtonVariant.PRIMARY,
                            modifier = Modifier.heightIn(min = 56.dp).focusRequester(skipFocus)
                                .focusProperties { if (showTransport) down = if (player.isCurrentMediaItemSeekable) timelineFocus else playFocus }
                                .onFocusChanged {
                                    // Keep focus identity through removal so its replacement can receive focus.
                                    if (it.isFocused || skipVisible) skipFocused = it.isFocused
                                }) { skip(skippable) }
                    }
                    undoRegion?.takeIf { !editorOpen && !bufferAhead }?.let { region ->
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.region_skipped, if (region.type == SegmentType.CUSTOM)
                                vm.customTypes().firstOrNull { it.id == region.typeId }?.label.orEmpty() else typeLabel(region.type)), color = TextPrimary)
                            GlassButton(stringResource(R.string.region_undo)) { skippedCredits = null; player.seekTo(region.startMs); undoRegion = null }
                        }
                    }
                    if (showTransport) {
                        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(state.detail?.title?.name.orEmpty(), color = TextPrimary, fontSize = 24.sp, lineHeight = 30.sp,
                                fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            state.episode?.let {
                                MetadataRow(modifier = Modifier.padding(top = 1.dp), items = listOf(episodeLabel(it)))
                            }
                            state.version?.let { Text(animeVersionLabel(it), color = TextSecondary, fontSize = 13.sp) }
                            val shownPosition = preview ?: position
                            val timelineLabel = stringResource(R.string.timeline)
                            PlayerTimeline(shownPosition, duration, segments, timelineLabel,
                                Modifier.fillMaxWidth().height(32.dp).focusRequester(timelineFocus)
                                    .focusProperties { if (showNext) up = nextFocus else if (showSkip) up = skipFocus }
                                    .onFocusChanged { timelineFocused = it.isFocused; if (!it.isFocused) preview = null },
                                buffered = buffered, enabled = player.isCurrentMediaItemSeekable && duration > 0,
                                onMove = { skippedCredits = null; preview = it }, onCommit = { preview?.let(player::seekTo); preview = null })
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
                                PlayerControl(stringResource(R.string.seek_back), TransportIcon.BACKWARD, enabled = player.isCurrentMediaItemSeekable) { skippedCredits = null; player.seekBack() }
                                PlayerControl(stringResource(R.string.seek_forward), TransportIcon.FORWARD, enabled = player.isCurrentMediaItemSeekable) { skippedCredits = null; player.seekForward() }
                                Spacer(Modifier.weight(1f))
                                if (drawer == null) PlayerControl(stringResource(R.string.edit_regions), TransportIcon.REGIONS, Modifier.focusRequester(editFocus),
                                    enabled = player.isCurrentMediaItemSeekable && duration > 0 && failure == null) { openEditor(null) }
                                if (episodes.isNotEmpty() && drawer == null) PlayerControl(stringResource(R.string.episodes), TransportIcon.EPISODES,
                                    Modifier.focusRequester(episodesButtonFocus)) { openDrawer(PlayerDrawer.EPISODES) }
                                if (drawer == null) {
                                    PlayerControl(stringResource(R.string.settings), TransportIcon.SETTINGS, Modifier.focusRequester(settingsFocus)) { openDrawer(PlayerDrawer.SETTINGS) }
                                    PlayerControl(stringResource(R.string.back), TransportIcon.EXIT) { vm.back() }
                                }
                            }
                            failure?.let {
                                if (it == Failure.VIP && !vm.isAnime) AccessMessage(state.account) else ContentFailure(it, vm, Modifier.focusRequester(playFocus))
                                if (vm.isAnime) Action(stringResource(R.string.anime_change_version)) { vm.play(state.episode) }
                                if (!vm.isAnime && it in listOf(Failure.SIGNED_OUT, Failure.EXPIRED, Failure.VIP, Failure.CAPACITY)) Action(stringResource(R.string.sign_in), if (it == Failure.VIP) Modifier.focusRequester(playFocus) else Modifier) { vm.signInForPlayback() }
                            }
                            if (buffering && failure == null) Action(stringResource(R.string.buffer_ahead), enabled = duration > 0) { startBuffer() }
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
                PlayerSettingsPage.BUFFER to stringResource(R.string.buffering_mode),
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
                            item(key = "buffer") {
                                MenuRow(titles.getValue(PlayerSettingsPage.BUFFER),
                                    value = stringResource(if (state.profile?.largerBuffer == true) R.string.buffering_larger else R.string.buffering_normal),
                                    modifier = Modifier.focusRequester(settingsRows.getValue(PlayerSettingsPage.BUFFER))) { openPage(PlayerSettingsPage.BUFFER) }
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
                    PlayerSettingsPage.BUFFER -> {
                        LaunchedEffect(Unit) { withFrameNanos { }; headerBack.requestFocus() }
                        Text(stringResource(R.string.buffer_help), color = Muted, fontSize = 13.sp)
                        OptionPicker(stringResource(R.string.buffering_mode), bufferChoices(), state.profile?.largerBuffer == true) { vm.setBuffering(it) }
                        Action(stringResource(R.string.buffer_ahead), enabled = duration > 0 && failure == null) { startBuffer() }
                    }
                    PlayerSettingsPage.SKIP -> {
                        LaunchedEffect(Unit) { withFrameNanos { }; headerBack.requestFocus() }
                        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(vertical = 4.dp)) {
                            item { SkipSettings(state, vm, player.isCurrentMediaItemSeekable && duration > 0 && failure == null, ::openEditor) }
                        }
                    }
                }
            }
        }
        if (episodesOpen) {
            val title = state.detail?.title
            val seasons = remember(episodes) { episodes.map { it.season }.distinct().sorted() }
            val target = remember(episodes, state.episode?.key) { episodeDrawerTarget(episodes, state.episode) }
            var picked by remember(stream) { mutableIntStateOf(target?.first ?: 0) }
            val activeSeason = if (seasons.contains(picked)) picked else seasons.firstOrNull() ?: 0
            val seasonEpisodes = episodes.filter { it.season == activeSeason }
            val episodeList = androidx.compose.foundation.lazy.rememberLazyListState()
            val currentFocus = remember { FocusRequester() }
            var focusIndex by remember(stream) { mutableIntStateOf(target?.second ?: 0) }
            GlassDrawer(
                onDismiss = ::closeDrawer,
                width = drawerWidth,
                modifier = Modifier.padding(safe).padding(vertical = 14.dp),
            ) {
                LaunchedEffect(activeSeason) {
                    if (seasonEpisodes.isEmpty()) { withFrameNanos { }; episodesFocus.requestFocus() }
                    else {
                        episodeList.scrollToItem(focusIndex.coerceIn(seasonEpisodes.indices))
                        withFrameNanos { }; withFrameNanos { }; currentFocus.requestFocus()
                    }
                }
                GlassButton(stringResource(R.string.back), Modifier.focusRequester(episodesFocus).widthIn(min = 82.dp),
                    variant = GlassButtonVariant.SECONDARY, compact = true, onClick = ::closeDrawer)
                SeasonPicker(seasons, activeSeason, modifier = Modifier.padding(top = 14.dp)) { picked = it; focusIndex = 0 }
                LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp), state = episodeList, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(seasonEpisodes, key = Episode::key) { episode ->
                        val viewing = state.viewing.firstOrNull { it.title.key == title?.key && it.episode?.key == episode.key && it.version == state.version }
                        EpisodeRow(episode, viewing, state.episode?.key == episode.key,
                            modifier = if (seasonEpisodes.getOrNull(focusIndex)?.key == episode.key) Modifier.focusRequester(currentFocus) else Modifier) {
                            if (!vm.isAnime) drawer = null
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
        if (editorOpen) {
            val editorHeight = maxHeight * .7f
            GlassSurface(Modifier.align(Alignment.BottomCenter).padding(safe).fillMaxWidth().heightIn(max = maxHeight * .8f)
                .onSizeChanged { bottomAreaHeight = with(density) { it.height.toDp() } }) {
                RegionEditor(player, vm, segments, editingRegion, duration, Modifier.fillMaxWidth().heightIn(max = editorHeight), ::closeEditor)
            }
        }
        if (bufferAhead) {
            GlassSurface(Modifier.align(Alignment.BottomEnd).padding(safe).widthIn(max = 520.dp)
                .onSizeChanged { bottomAreaHeight = with(density) { it.height.toDp() } }) {
                val ahead = (buffered - position).coerceAtLeast(0) / 1000
                val targetSeconds = minOf(90L, ((duration - position).coerceAtLeast(0) + 999) / 1000)
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    LaunchedEffect(Unit) { withFrameNanos { }; bufferFocus.requestFocus() }
                    Text(stringResource(R.string.buffer_ahead), color = TextPrimary, fontSize = 24.sp)
                    Text(stringResource(R.string.buffer_help), color = Muted, fontSize = 13.sp)
                    Text(if (bufferComplete) pluralStringResource(R.plurals.buffer_ready, ahead.toInt(), ahead)
                        else pluralStringResource(R.plurals.buffer_progress, targetSeconds.toInt(), ahead, targetSeconds),
                        color = Accent, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    if (bufferStalled) {
                        Text(stringResource(R.string.buffer_stalled), color = Muted)
                        Action(stringResource(R.string.retry)) { if (player.playerError != null) player.prepare(); bufferRestart++ }
                    }
                    failure?.let { FailureMessage(it, { vm.refresh() }) }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Action(stringResource(R.string.buffer_play_now), Modifier.focusRequester(bufferFocus), failure == null) { closeBuffer(true) }
                        Action(stringResource(R.string.cancel)) { closeBuffer(bufferResume) }
                    }
                }
            }
        }
        if (buffering && failure == null && !bufferAhead && !editorOpen) GlassSurface(Modifier.align(Alignment.Center)) { Loading() }
        state.stillWatchingSeconds?.let { WatchingPrompt(it, vm::continueWatching) { vm.back() } }
    }
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
    SegmentType.MIXED_INTRO -> R.string.skip_mixed_intro
    SegmentType.MIXED_CREDITS -> R.string.skip_mixed_credits
    SegmentType.CUSTOM -> R.string.region_custom
})

private enum class TransportIcon { PLAY, PAUSE, BACKWARD, FORWARD, SETTINGS, EPISODES, REGIONS, EXIT }

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
                TransportIcon.REGIONS -> {
                    drawLine(ink, Offset(w * .1f, h * .5f), Offset(w * .9f, h * .5f), 2.dp.toPx())
                    drawRect(ink, Offset(w * .3f, h * .3f), Size(w * .4f, h * .4f), style = Stroke(2.dp.toPx()))
                    drawLine(ink, Offset(w * .3f, h * .18f), Offset(w * .3f, h * .82f), 2.dp.toPx())
                    drawLine(ink, Offset(w * .7f, h * .18f), Offset(w * .7f, h * .82f), 2.dp.toPx())
                }
                TransportIcon.EXIT -> { drawLine(ink, Offset(w * 0.15f, h * 0.5f), Offset(w * 0.85f, h * 0.5f), 2.dp.toPx())
                    drawLine(ink, Offset(w * 0.15f, h * 0.5f), Offset(w * 0.45f, h * 0.2f), 2.dp.toPx())
                    drawLine(ink, Offset(w * 0.15f, h * 0.5f), Offset(w * 0.45f, h * 0.8f), 2.dp.toPx())
                }
            }
        }
    }
}
