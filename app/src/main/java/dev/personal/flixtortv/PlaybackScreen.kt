package dev.personal.flixtortv

import android.app.Activity
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
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
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import kotlinx.coroutines.delay

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable internal fun PlaybackScreen(stream: Stream, state: TvState, vm: TvViewModel) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val identity = remember(stream) { state.playbackIdentity }
    var failure by remember(stream) { mutableStateOf<Failure?>(null) }
    var buffering by remember(stream) { mutableStateOf(true) }
    var controls by remember(stream) { mutableStateOf(true) }
    var settings by remember(stream) { mutableStateOf(false) }
    var playing by remember(stream) { mutableStateOf(false) }
    var tracks by remember(stream) { mutableStateOf(Tracks.EMPTY) }
    var cues by remember(stream) { mutableStateOf<List<Cue>>(emptyList()) }
    var position by remember(stream) { mutableLongStateOf(state.resumePosition) }
    var duration by remember(stream) { mutableLongStateOf(0) }
    var interaction by remember(stream) { mutableLongStateOf(0) }
    val rootFocus = remember(stream) { FocusRequester() }
    val playFocus = remember(stream) { FocusRequester() }
    val settingsFocus = remember(stream) { FocusRequester() }
    val drawerFocus = remember(stream) { FocusRequester() }
    var preview by remember(stream) { mutableStateOf<Long?>(null) }
    var timelineFocused by remember(stream) { mutableStateOf(false) }
    val safe = LocalSafePadding.current
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
            override fun onPlaybackStateChanged(value: Int) { buffering = value == Player.STATE_BUFFERING }
            override fun onIsPlayingChanged(value: Boolean) { playing = value }
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
    LaunchedEffect(controls, settings, interaction, failure, playing, preview) {
        if (controls && !settings && preview == null && failure == null && playing) { delay(6000); controls = false }
    }
    LaunchedEffect(controls, settings, failure) {
        when { settings -> drawerFocus.requestFocus(); controls -> playFocus.requestFocus(); else -> rootFocus.requestFocus() }
    }
    var returnToSettings by remember { mutableStateOf(false) }
    LaunchedEffect(settings) {
        if (!settings && returnToSettings && controls) { settingsFocus.requestFocus(); returnToSettings = false }
    }
    BackHandler(preview != null || settings || controls) {
        when { preview != null -> preview = null; settings -> { settings = false; returnToSettings = true }; else -> controls = false }
    }
    val view = remember(player) { PlayerView(context).apply {
        this.player = player; useController = false; isFocusable = false
        subtitleView?.visibility = android.view.View.GONE
    } }
    DisposableEffect(view) { onDispose { view.player = null } }
    Box(Modifier.fillMaxSize().background(Color.Black).focusRequester(rootFocus).onPreviewKeyEvent { event ->
        val key = event.nativeKeyEvent
        if (key.action != KeyEvent.ACTION_DOWN) return@onPreviewKeyEvent false
        interaction++
        when (key.keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { if (player.playWhenReady) player.pause() else player.play(); true }
            KeyEvent.KEYCODE_MEDIA_PLAY -> { player.play(); true }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> { player.pause(); true }
            KeyEvent.KEYCODE_MEDIA_REWIND -> { player.seekBack(); true }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { player.seekForward(); true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> { if (!controls) { controls = true; true } else false }
            else -> false
        }
    }.focusable()) {
        AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())
        if (controls && !settings) {
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.88f))))
                .padding(safe).padding(top = 32.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(state.detail?.title?.name.orEmpty(), fontSize = 21.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                state.episode?.let { Text(stringResource(R.string.episode, it.number, it.name), fontSize = 14.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                val shownPosition = preview ?: position
                val fraction = if (duration > 0) (shownPosition.toFloat() / duration).coerceIn(0f, 1f) else 0f
                val timelineLabel = stringResource(R.string.timeline)
                Canvas(Modifier.fillMaxWidth().height(24.dp).onFocusChanged { timelineFocused = it.isFocused; if (!it.isFocused) preview = null }
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
                    drawLine(Color.White.copy(alpha = 0.35f), Offset(0f, y), Offset(size.width, y), if (timelineFocused) 6.dp.toPx() else 3.dp.toPx())
                    drawLine(if (timelineFocused) Accent else Color.White, Offset(0f, y), Offset(size.width * fraction, y), if (timelineFocused) 6.dp.toPx() else 3.dp.toPx())
                    drawCircle(if (timelineFocused) Accent else Color.White, if (timelineFocused) 7.dp.toPx() else 4.dp.toPx(), Offset(size.width * fraction, y))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(clock(shownPosition), fontSize = 13.sp, color = Muted)
                    Text(if (preview != null) stringResource(R.string.seek_preview, clock(shownPosition))
                        else stringResource(R.string.time_remaining, clock((duration - position).coerceAtLeast(0))), fontSize = 13.sp, color = Muted)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    PlayerControl(stringResource(if (playing) R.string.pause else R.string.play), if (playing) TransportIcon.PAUSE else TransportIcon.PLAY,
                        if (failure == null) Modifier.focusRequester(playFocus) else Modifier, failure == null) {
                        if (player.playWhenReady) player.pause() else player.play()
                    }
                    PlayerControl(stringResource(R.string.seek_back), TransportIcon.BACKWARD, enabled = player.isCurrentMediaItemSeekable) { player.seekBack() }
                    PlayerControl(stringResource(R.string.seek_forward), TransportIcon.FORWARD, enabled = player.isCurrentMediaItemSeekable) { player.seekForward() }
                    Spacer(Modifier.weight(1f))
                    PlayerControl(stringResource(R.string.settings), TransportIcon.SETTINGS, Modifier.focusRequester(settingsFocus)) { settings = true }
                    PlayerControl(stringResource(R.string.back), TransportIcon.EXIT) { vm.back() }
                }
                failure?.let {
                    if (it == Failure.VIP) AccessMessage(state.account) else FailureMessage(it, vm::refresh, Modifier.focusRequester(playFocus))
                    if (it in listOf(Failure.SIGNED_OUT, Failure.EXPIRED, Failure.VIP)) Action(stringResource(R.string.sign_in), if (it == Failure.VIP) Modifier.focusRequester(playFocus) else Modifier) { vm.signInForPlayback() }
                }
            }
        }
        if (settings) Box(Modifier.fillMaxSize().padding(safe)) {
            LazyColumn(Modifier.align(Alignment.CenterEnd).fillMaxHeight().fillMaxWidth(1f / 3f)
                .background(Color(0xF2111823), RoundedCornerShape(12.dp)).padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)) {
                item { Action(stringResource(R.string.back), Modifier.focusRequester(drawerFocus)) { settings = false; returnToSettings = true } }
                item { Text(stringResource(R.string.quality), fontSize = 20.sp); QualityOptions(state.profile?.quality ?: 0, vm::setQuality, vertical = true) }
                item { Text(stringResource(R.string.audio), fontSize = 20.sp) }
                item { TrackOptions(player, tracks, C.TRACK_TYPE_AUDIO) }
                item { Text(stringResource(R.string.subtitles), fontSize = 20.sp) }
                item {
                    val off = !tracks.groups.any { it.type == C.TRACK_TYPE_TEXT && it.isSelected }
                    Action(if (off) stringResource(R.string.selected_option, stringResource(R.string.subtitles_off)) else stringResource(R.string.subtitles_off)) {
                        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
                    }
                    TrackOptions(player, tracks, C.TRACK_TYPE_TEXT)
                }
            }
        }
        // Explicit cue line positions otherwise bypass SubtitleView's bottom padding.
        // Keep text captions inside the same safe rectangle as controls, above transport.
        BoxWithConstraints(Modifier.fillMaxSize().padding(safe)) {
            AndroidView(factory = { SubtitleView(it).apply { setUserDefaultStyle(); setUserDefaultTextSize(); setBottomPaddingFraction(0.04f) } },
                modifier = Modifier.fillMaxSize().padding(end = if (settings) maxWidth / 3 else 0.dp,
                    bottom = if (controls && !settings) 150.dp else 0.dp),
                update = { subtitles -> subtitles.setCues(cues.map { cue ->
                    if (cue.text != null) cue.buildUpon().setLine(Cue.DIMEN_UNSET, Cue.TYPE_UNSET)
                        .setLineAnchor(Cue.TYPE_UNSET).build() else cue
                }) })
        }
        if (buffering && failure == null) Box(Modifier.align(Alignment.Center).background(Panel, RoundedCornerShape(16.dp)).padding(24.dp)) { Loading() }
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable private fun TrackOptions(player: Player, tracks: Tracks, type: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val groups = tracks.groups.filter { it.type == type }
        var number = 0
        groups.forEach { group ->
            (0 until group.length).filter { group.isTrackSupported(it) }.forEach { index ->
                val format = group.getTrackFormat(index)
                val label = format.label ?: format.language ?: stringResource(R.string.track_number, ++number)
                Action(if (group.isTrackSelected(index)) stringResource(R.string.selected_option, label) else label) {
                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(type, false)
                        .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, index)).build()
                }
            }
        }
        if (groups.isEmpty()) Text(stringResource(R.string.no_tracks), color = Muted)
    }
}

internal fun clock(milliseconds: Long): String {
    val seconds = milliseconds.coerceAtLeast(0) / 1000
    return java.lang.String.format(java.util.Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
}

private enum class TransportIcon { PLAY, PAUSE, BACKWARD, FORWARD, SETTINGS, EXIT }

@Composable private fun PlayerControl(label: String, icon: TransportIcon, modifier: Modifier = Modifier,
    enabled: Boolean = true, click: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Button(onClick = click, enabled = enabled,
        modifier = modifier.size(48.dp).onFocusChanged { focused = it.isFocused }.semantics { contentDescription = label },
        contentPadding = PaddingValues(12.dp),
        colors = ButtonDefaults.colors(containerColor = Color.Transparent, contentColor = Color.White,
            focusedContainerColor = Color.White, focusedContentColor = Midnight)) {
        Canvas(Modifier.size(24.dp)) {
            val ink = if (focused) Midnight else Color.White
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
                        drawCircle(if (focused) Color.White else Color.Black, 3.dp.toPx(), Offset(x, y))
                        drawCircle(ink, 3.dp.toPx(), Offset(x, y), style = Stroke(2.dp.toPx()))
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
