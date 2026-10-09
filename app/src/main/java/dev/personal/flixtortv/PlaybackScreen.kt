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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
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
import androidx.tv.material3.Text
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
    var position by remember(stream) { mutableLongStateOf(state.resumePosition) }
    var duration by remember(stream) { mutableLongStateOf(0) }
    var interaction by remember(stream) { mutableLongStateOf(0) }
    val rootFocus = remember(stream) { FocusRequester() }
    val playFocus = remember(stream) { FocusRequester() }
    val settingsFocus = remember(stream) { FocusRequester() }
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
    LaunchedEffect(controls, settings, interaction, failure, playing) {
        if (controls && !settings && failure == null && playing) { delay(6000); controls = false }
    }
    LaunchedEffect(controls, settings, failure) {
        when { settings -> settingsFocus.requestFocus(); controls -> playFocus.requestFocus(); else -> rootFocus.requestFocus() }
    }
    BackHandler(settings || controls) { if (settings) settings = false else controls = false }
    val view = remember(player) { PlayerView(context).apply { this.player = player; useController = false; isFocusable = false } }
    DisposableEffect(view) { onDispose { view.player = null } }
    Box(Modifier.fillMaxSize().background(Color.Black).focusRequester(rootFocus).onPreviewKeyEvent { event ->
        val key = event.nativeKeyEvent
        if (key.action != KeyEvent.ACTION_DOWN) return@onPreviewKeyEvent false
        interaction++
        when (key.keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { if (player.isPlaying) player.pause() else player.play(); true }
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
                .background(Panel, RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)).padding(28.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(state.detail?.title?.name.orEmpty(), fontSize = 24.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                state.episode?.let { Text(stringResource(R.string.episode, it.number, it.name), color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                val fraction = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
                Canvas(Modifier.fillMaxWidth().height(4.dp).semantics { progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f) }) {
                    drawRect(Muted.copy(alpha = 0.3f)); drawRect(Accent, size = size.copy(width = size.width * fraction))
                }
                Text(stringResource(R.string.player_time, clock(position), clock(duration), clock((duration - position).coerceAtLeast(0))), color = Muted)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Action(stringResource(if (playing) R.string.pause else R.string.play),
                        if (failure == null) Modifier.focusRequester(playFocus) else Modifier, failure == null) {
                        if (player.isPlaying) player.pause() else player.play()
                    }
                    Action(stringResource(R.string.seek_back), enabled = player.isCurrentMediaItemSeekable) { player.seekBack() }
                    Action(stringResource(R.string.seek_forward), enabled = player.isCurrentMediaItemSeekable) { player.seekForward() }
                    Action(stringResource(R.string.settings)) { settings = true }
                    Action(stringResource(R.string.back)) { vm.back() }
                }
                failure?.let {
                    FailureMessage(it, vm::refresh, Modifier.focusRequester(playFocus))
                    if (it in listOf(Failure.SIGNED_OUT, Failure.EXPIRED, Failure.VIP)) Action(stringResource(R.string.sign_in)) { vm.signInForPlayback() }
                }
            }
        }
        if (settings) LazyColumn(Modifier.align(Alignment.CenterEnd).fillMaxHeight().fillMaxWidth(0.52f)
            .background(Panel).padding(28.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            item { Action(stringResource(R.string.back), Modifier.focusRequester(settingsFocus)) { settings = false } }
            item { Text(stringResource(R.string.quality), fontSize = 24.sp); QualityOptions(state.profile?.quality ?: 0, vm::setQuality) }
            item { Text(stringResource(R.string.audio), fontSize = 24.sp) }
            item { TrackOptions(player, tracks, C.TRACK_TYPE_AUDIO) }
            item { Text(stringResource(R.string.subtitles), fontSize = 24.sp) }
            item {
                Action(stringResource(R.string.subtitles_off)) {
                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
                }
                TrackOptions(player, tracks, C.TRACK_TYPE_TEXT)
            }
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
