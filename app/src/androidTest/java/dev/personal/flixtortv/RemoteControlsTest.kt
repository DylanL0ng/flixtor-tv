package dev.personal.flixtortv

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.tv.material3.MaterialTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Explicit opt-in, isolated profiles/files, offline video/audio/captions. Never run on a household TV. */
@RunWith(AndroidJUnit4::class)
class RemoteControlsTest {
    private val instrument = InstrumentationRegistry.getInstrumentation()
    private val target get() = instrument.targetContext
    private fun text(id: Int) = target.getString(id)
    private data class Node(val label: String, val bounds: Rect, val focused: Boolean, val clickable: Boolean)

    private fun nodes(): List<Node> {
        fun read(node: AccessibilityNodeInfo?): List<Node> {
            if (node == null) return emptyList()
            node.refresh()
            val own = Node(node.contentDescription?.toString() ?: node.text?.toString().orEmpty(),
                Rect().also(node::getBoundsInScreen), node.isFocused, node.isClickable)
            val children = (0 until node.childCount).flatMap { read(node.getChild(it)) }
            @Suppress("DEPRECATION")
            node.recycle()
            return listOf(own) + children
        }
        val raw = read(instrument.uiAutomation.rootInActiveWindow)
        return raw.map { node ->
            if (node.label.isBlank() && (node.clickable || node.focused))
                node.copy(label = raw.firstOrNull { node.bounds.contains(it.bounds) && it.label.isNotBlank() &&
                    it.bounds.width() >= node.bounds.width() / 2 }?.label.orEmpty())
            else node
        }
    }

    private fun visible(label: String) = nodes().any { it.label == label }
    private fun focused() = nodes().firstOrNull { it.focused && it.label.isNotBlank() }
    private fun await(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 8000
        while (!condition() && SystemClock.uptimeMillis() < deadline) Thread.sleep(80)
        assertTrue("$message; focus=${focused()}; nodes=${nodes()}", condition())
    }
    private fun key(code: Int) { instrument.sendKeyDownUpSync(code); Thread.sleep(100) }
    private fun focus(label: String, direction: Int = KeyEvent.KEYCODE_DPAD_DOWN) {
        repeat(50) {
            if (focused()?.label?.substringBefore(" · ") == label) return
            key(direction)
        }
        fail("Remote could not reach $label; focus=${focused()}")
    }
    private fun select(label: String, direction: Int = KeyEvent.KEYCODE_DPAD_DOWN) {
        focus(label, direction); key(KeyEvent.KEYCODE_DPAD_CENTER)
    }
    private fun capture(name: String) {
        val size = InstrumentationRegistry.getArguments().getString("captureSize", "1080")
        val bitmap = instrument.uiAutomation.takeScreenshot()
        File(target.getExternalFilesDir(null), "$name-$size.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }

    private fun withActivity(block: (MainActivity) -> Unit) {
        assumeTrue(InstrumentationRegistry.getArguments().getString("remoteQA") == "true")
        assumeTrue(android.os.Build.FINGERPRINT.contains("generic") || android.os.Build.MODEL.contains("sdk"))
        instrument.setInTouchMode(false)
        instrument.uiAutomation // Attach before creating dialog windows.
        val activity = instrument.startActivitySync(Intent(target, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        try { block(activity) } finally { instrument.runOnMainSync { activity.finish() } }
    }

    private fun render(activity: MainActivity, content: @Composable () -> Unit) {
        instrument.runOnMainSync {
            activity.setContent {
                MaterialTheme {
                    BoxWithConstraints(Modifier.fillMaxSize().background(Midnight)) {
                        CompositionLocalProvider(LocalSafePadding provides PaddingValues(maxWidth * .05f, maxHeight * .05f)) {
                            content()
                        }
                    }
                }
            }
        }
        instrument.waitForIdleSync()
        Thread.sleep(250) // setContent schedules a frame after the main queue becomes idle.
        instrument.waitForIdleSync()
    }

    @Test fun selectorsScrollToSelectionCancelAndHandleChangedChoices() = withActivity { activity ->
        val chosen = mutableStateOf(90)
        val longLabel = "Season 91 with a deliberately long title for checking wrapped remote menu labels"
        val choices = mutableStateOf((0..99).map {
            MenuChoice(it, if (it == 90) longLabel else target.getString(R.string.season, it + 1))
        })
        render(activity) {
            Column(Modifier.padding(24.dp).width(460.dp)) {
                OptionPicker(text(R.string.season_options), choices.value, chosen.value) { chosen.value = it }
            }
        }
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        focus(text(R.string.season_options))
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        await("Long picker scrolls directly to selected option") { focused()?.label == longLabel }
        capture("remote-long-picker")
        key(KeyEvent.KEYCODE_BACK)
        await("Cancel restores opener without selection") { chosen.value == 90 && focused()?.label?.startsWith(text(R.string.season_options)) == true }
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        instrument.runOnMainSync { choices.value = choices.value.take(3) }
        await("Removed selection focuses first remaining choice") { focused()?.label == target.getString(R.string.season, 1) }
        select(target.getString(R.string.season, 2))
        await("Choice applies and popup closes") { chosen.value == 1 && focused()?.label?.startsWith(text(R.string.season_options)) == true }
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        await("Reopen focuses newly selected option") { focused()?.label == target.getString(R.string.season, 2) }
        key(KeyEvent.KEYCODE_BACK)
    }

    @Test fun updatesKeepRemoteFocusAndRecoverDismissedInstallers() = withActivity { activity ->
        val state = mutableStateOf(UpdateState(UpdatePhase.INSTALLING))
        var checks = 0
        var installs = 0
        render(activity) {
            val back = androidx.compose.runtime.remember { androidx.compose.ui.focus.FocusRequester() }
            androidx.compose.runtime.LaunchedEffect(Unit) { withFrameNanos { }; back.requestFocus() }
            Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                MenuRow(text(R.string.back), Modifier.focusRequester(back)) { }
                androidx.compose.foundation.lazy.LazyColumn(Modifier.weight(1f).fillMaxWidth(.88f)) {
                    item { UpdateSettings(state.value, { checks++ }, { installs++ }) }
                }
            }
        }
        await("Updates opens on Back") { focused()?.label == text(R.string.back) }
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        await("Check stays reachable while installer is waiting") { focused()?.label == text(R.string.check_updates) }
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(1, checks)
        for (phase in UpdatePhase.entries) {
            instrument.runOnMainSync { state.value = UpdateState(phase, progress = 50) }
            await("Phase $phase preserves remote focus") { focused()?.label == text(R.string.check_updates) }
            val before = checks
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            assertEquals("Busy checks/downloads must not queue duplicate requests", before +
                if (phase in listOf(UpdatePhase.CHECKING, UpdatePhase.DOWNLOADING)) 0 else 1, checks)
        }
        instrument.runOnMainSync { state.value = UpdateState(UpdatePhase.READY) }
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        await("Install remains reachable") { focused()?.label == text(R.string.install_update) }
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(1, installs)
        key(KeyEvent.KEYCODE_DPAD_UP)
        await("Up returns to Back") { focused()?.label == text(R.string.back) }
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        await("Down returns to an update action") { focused()?.label in listOf(text(R.string.check_updates), text(R.string.install_update)) }
        capture("remote-updates")

        val prefix = "update_test_${UUID.randomUUID()}_"
        val context = object : ContextWrapper(target) {
            override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences(prefix + name, mode)
        }
        val service = UpdateService(context)
        val prefs = context.getSharedPreferences("update_install", Context.MODE_PRIVATE)
        @Suppress("UNCHECKED_CAST")
        val flow = UpdateService::class.java.getDeclaredField("mutable").apply { isAccessible = true }
            .get(service) as MutableStateFlow<UpdateState>
        val installer = target.packageManager.packageInstaller
        var sessionId = -1
        try {
            flow.value = UpdateState(UpdatePhase.INSTALLING)
            assertTrue("Missing installer callback can be retried", kotlinx.coroutines.runBlocking { service.recoverInstallation() })
            assertEquals(UpdatePhase.READY, flow.value.phase)
            sessionId = installer.createSession(android.content.pm.PackageInstaller.SessionParams(
                android.content.pm.PackageInstaller.SessionParams.MODE_FULL_INSTALL))
            prefs.edit().putInt("session", sessionId).apply()
            flow.value = UpdateState(UpdatePhase.INSTALLING)
            installer.openSession(sessionId).use {
                assertFalse("Active staging must never be interrupted", kotlinx.coroutines.runBlocking { service.recoverInstallation() })
                assertEquals(UpdatePhase.INSTALLING, flow.value.phase)
            }
            await("Closed session becomes idle") { installer.getSessionInfo(sessionId)?.isActive == false }
            assertTrue("Idle dismissed installer can be retried", kotlinx.coroutines.runBlocking { service.recoverInstallation() })
            await("Abandoned session is removed asynchronously") { installer.getSessionInfo(sessionId) == null }
            assertEquals(UpdatePhase.READY, flow.value.phase)
            assertTrue(flow.value.installationFailed)
            UpdateReceiver().onReceive(context, Intent().putExtra(android.content.pm.PackageInstaller.EXTRA_SESSION_ID, sessionId)
                .putExtra(android.content.pm.PackageInstaller.EXTRA_STATUS, android.content.pm.PackageInstaller.STATUS_FAILURE))
            assertFalse("Abandoned callback cannot overwrite retry", prefs.contains("status"))
            prefs.edit().putInt("session", sessionId).apply()
            flow.value = UpdateState(UpdatePhase.INSTALLING)
            UpdateReceiver().onReceive(context, Intent().putExtra(android.content.pm.PackageInstaller.EXTRA_SESSION_ID, sessionId)
                .putExtra(android.content.pm.PackageInstaller.EXTRA_STATUS, android.content.pm.PackageInstaller.STATUS_FAILURE))
            await("Current installer failure restores retry") { flow.value.phase == UpdatePhase.READY }
            assertFalse("Finished attempt is cleared", prefs.contains("session"))
        } finally {
            if (sessionId >= 0) runCatching { installer.abandonSession(sessionId) }
            service.close()
            target.deleteSharedPreferences(prefix + "update_install")
        }
    }

    @Test fun remoteDigitsMixWithKeypadAndEditorRequiresSave() = withActivity { activity ->
        val profile = Profile("remote-pin", "PIN fixture", pin = ProfilePin.digest("remote-pin", "0123"))
        val submitted = mutableListOf<String>()
        var dismissed = false
        var attempt = 0
        fun unlock(expected: String) {
            submitted.clear(); dismissed = false
            render(activity) { } // A real dismissal removes the keypad composition.
            val fixture = profile.copy(id = "remote-pin-${++attempt}", name = "PIN fixture $attempt")
            render(activity) { PinEntry(fixture, { submitted.add(it); it == expected }) { dismissed = true } }
            await("PIN keypad receives initial focus") { visible(fixture.name) && focused()?.label == "1" }
        }
        fun digits(value: String, base: Int = KeyEvent.KEYCODE_0) {
            value.forEach { key(base + it.digitToInt()) }
        }
        for (base in listOf(KeyEvent.KEYCODE_0, KeyEvent.KEYCODE_NUMPAD_0)) {
            listOf("0123", "4567", "8901").forEach { expected ->
                unlock(expected); digits(expected, base)
                await("Fourth digit submits once ($expected, $base, submitted=$submitted)") { submitted == listOf(expected) && dismissed }
                assertFalse("Digits must remain masked", visible(expected))
            }
        }
        unlock("0123")
        digits("9999")
        await("Wrong PIN explains failure") { visible(text(R.string.pin_wrong)) && !dismissed }
        digits("0"); key(KeyEvent.KEYCODE_DPAD_CENTER) // Focus stays on onscreen 1.
        digits("23")
        await("Wrong PIN clears; hardware and keypad mix") { submitted == listOf("9999", "0123") && dismissed }

        unlock("0234")
        key(KeyEvent.KEYCODE_0)
        val down = SystemClock.uptimeMillis()
        repeat(3) { count ->
            instrument.sendKeySync(KeyEvent(down, SystemClock.uptimeMillis(), KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_1, count))
        }
        instrument.sendKeySync(KeyEvent(down, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, KeyEvent.KEYCODE_1, 0))
        key(KeyEvent.KEYCODE_DEL); digits("234")
        await("Held digit appends once; Delete removes it") { submitted == listOf("0234") && dismissed }

        unlock("0123"); digits("01"); key(KeyEvent.KEYCODE_FORWARD_DEL); digits("123")
        await("Forward Delete removes last digit") { submitted == listOf("0123") }
        unlock("0123"); key(KeyEvent.KEYCODE_DEL); digits("01"); key(KeyEvent.KEYCODE_BACK)
        await("Back cancels without submitting") { dismissed && submitted.isEmpty() }

        val saved = mutableListOf<String?>()
        render(activity) { Column { PinEditor(profile.copy(pin = "")) { saved.add(it) } } }
        // The editor trigger uses native focus; focus it explicitly with the remote.
        key(KeyEvent.KEYCODE_DPAD_DOWN); key(KeyEvent.KEYCODE_DPAD_CENTER)
        await("PIN editor opens") { visible(text(R.string.pin)) && focused()?.label == "1" }
        digits("01234")
        assertTrue("Editor must not auto-save or accept a fifth digit", saved.isEmpty())
        key(KeyEvent.KEYCODE_DEL); key(KeyEvent.KEYCODE_NUMPAD_3)
        select(text(R.string.save))
        await("Save commits four digits") { saved == listOf("0123") && !visible(text(R.string.pin)) }
        key(KeyEvent.KEYCODE_DPAD_UP); key(KeyEvent.KEYCODE_DPAD_CENTER)
        await("Editor reopens empty") { visible(text(R.string.pin)) }
        digits("99"); key(KeyEvent.KEYCODE_BACK)
        await("Editor Back cancels") { !visible(text(R.string.pin)) && saved.size == 1 }
    }

    @Test fun drawersOwnRemoteFocusAndSharePlaybackLayout() = withActivity { activity ->
        val prefix = "remote_test_${UUID.randomUUID()}_"
        val folder = File(target.cacheDir, prefix).apply { mkdirs() }
        val prefs = mutableSetOf<String>()
        val app = object : Application() {
            init { attachBaseContext(object : ContextWrapper(target) {
                override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                    prefs.add(prefix + name)
                    return super.getSharedPreferences(prefix + name, mode)
                }
                override fun getFilesDir() = folder
            }) }
        }
        val store = ViewModelStore()
        val catalogRequests = java.util.concurrent.CopyOnWriteArrayList<String>()
        val catalog = "<div class=contentListM><div class=cflip data-href='/watch/movie/2/fixture'>" +
            "<div class=front><div class=card-footer>Sort fixture</div></div></div></div>"
        lateinit var vm: TvViewModel
        lateinit var mutable: MutableStateFlow<TvState>
        instrument.runOnMainSync {
            vm = TvViewModel(app, FlixtorClient(SessionStore(app)) { path ->
                catalogRequests.add(path)
                when {
                    path == "/home" -> "<meta name=csrf-token content=fixture>$catalog"
                    path.startsWith("/show/") -> catalog
                    else -> throw SiteException(Failure.MEDIA)
                }
            })
            store.put("fixture", vm)
            @Suppress("UNCHECKED_CAST")
            mutable = TvViewModel::class.java.getDeclaredField("mutable").apply { isAccessible = true }
                .get(vm) as MutableStateFlow<TvState>
        }
        listOf("qa-player.m3u8", "qa-player-0.ts", "qa-audio.aac").forEach { name ->
            instrument.context.assets.open(name).use { input -> File(folder, name).outputStream().use { output -> input.copyTo(output) } }
        }
        File(folder, "audio.m3u8").writeText("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:61\n#EXTINF:60.1,\nqa-audio.aac\n#EXT-X-ENDLIST\n")
        File(folder, "master.m3u8").writeText("#EXTM3U\n" +
            "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"audio\",NAME=\"QA Audio 1\",LANGUAGE=\"en\",DEFAULT=YES,AUTOSELECT=YES,URI=\"audio.m3u8\"\n" +
            "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"audio\",NAME=\"QA Audio 2\",LANGUAGE=\"ja\",DEFAULT=NO,AUTOSELECT=YES,URI=\"audio.m3u8\"\n" +
            "#EXT-X-STREAM-INF:BANDWIDTH=100000,CODECS=\"avc1.42c00a,mp4a.40.2\",AUDIO=\"audio\"\nqa-player.m3u8\n")
        val captions = "WEBVTT\n\n00:00.000 --> 01:00.000\nDrawer captions fixture.\n"
        val caption = Caption("data:text/vtt;base64," + android.util.Base64.encodeToString(captions.toByteArray(), android.util.Base64.NO_WRAP), "QA English", "text/vtt", true)
        val stream = Stream(File(folder, "master.m3u8").toURI().toString(), listOf(caption))
        val profile = vm.state.value.profiles.first()
        val episodes = (1..2).flatMap { season -> (1..8).map { Episode(season, it, "Episode fixture $it") } }
        val title = Title("remote-show", "/watch/tv/remote-show", "Remote player fixture")
        val base = TvState(screen = Screen.PLAYER, profile = profile, profiles = listOf(profile),
            account = Account(false), accountChecked = true, stream = stream,
            detail = Detail(title, episodes), episode = episodes.first(),
            playbackIdentity = PlaybackIdentity(profile.id, title, episodes.first()))
        try {
            if (InstrumentationRegistry.getArguments().getString("settingsOnly") != "true") {
            for (fit in listOf(ScreenFit(), ScreenFit(10, 10))) {
                ProfileStore(app).quality(profile.id, 0)
                ProfileStore(app).autoplay(profile.id, true)
                // New composition/player for each margin size; household VM is never used.
                render(activity) { }
                instrument.runOnMainSync { mutable.value = base.copy(screenFit = fit) }
                render(activity) {
                    val state by vm.state.collectAsState()
                    BoxWithConstraints(Modifier.fillMaxSize()) {
                        CompositionLocalProvider(LocalSafePadding provides PaddingValues(
                            maxWidth * (fit.horizontal / 100f), maxHeight * (fit.vertical / 100f))) {
                            PlaybackScreen(stream, state, vm)
                        }
                    }
                }
                await("Offline video is playing") { visible(text(R.string.pause)) }
                select(text(R.string.settings), KeyEvent.KEYCODE_DPAD_RIGHT)
                await("Settings opens on Quality") { focused()?.label?.startsWith(text(R.string.quality)) == true }
                repeat(4) { key(KeyEvent.KEYCODE_DPAD_LEFT) }
                assertTrue("Focus cannot escape drawer", focused()?.label?.startsWith(text(R.string.quality)) == true)
                assertFalse("Quality options stay hidden until opened", visible(target.getString(R.string.up_to_resolution, 720)))
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                await("Selected quality receives focus") { focused()?.label == text(R.string.auto_quality) }
                key(KeyEvent.KEYCODE_BACK)
                await("Back returns to the quality category") { focused()?.label?.startsWith(text(R.string.quality)) == true }
                assertEquals(0, vm.state.value.profile?.quality)
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                capture("remote-quality-${fit.horizontal}")
                select(target.getString(R.string.up_to_resolution, 720))
                await("Quality changes and persists; parent focus restores") { vm.state.value.profile?.quality == 720 &&
                    ProfileStore(app).all().first().quality == 720 && focused()?.label?.startsWith(text(R.string.quality)) == true }
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                await("Reopening quality focuses selected choice") { focused()?.label == target.getString(R.string.up_to_resolution, 720) }
                key(KeyEvent.KEYCODE_BACK)
                select(text(R.string.audio))
                select("QA Audio 2")
                await("Audio track activated") { visible("${text(R.string.audio)} · QA Audio 2") }
                select(text(R.string.subtitles))
                select(text(R.string.subtitles_off), KeyEvent.KEYCODE_DPAD_UP)
                await("Captions can turn off") { visible("${text(R.string.subtitles)} · ${text(R.string.subtitles_off)}") }
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                select("QA English")
                await("Caption track activated") { visible("${text(R.string.subtitles)} · QA English") }
                select(text(R.string.autoplay_next))
                select(text(R.string.option_off))
                await("Autoplay changes and persists") { vm.state.value.profile?.autoplay == false && !ProfileStore(app).all().first().autoplay }
                select(text(R.string.skip_segments))
                select(text(R.string.segment_mark))
                await("Skip controls activate") { visible(text(R.string.segment_mark_end)) }
                capture("remote-settings-scrolled-${fit.horizontal}")
                key(KeyEvent.KEYCODE_BACK)
                await("Skip Back returns to its category") { focused()?.label == text(R.string.skip_segments) }
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                focus(text(R.string.segment_mark_end))
                await("In-progress marking survives navigation") { visible(text(R.string.segment_mark_end)) }
                key(KeyEvent.KEYCODE_BACK)
                key(KeyEvent.KEYCODE_BACK)
                await("Closing Settings restores opener") { focused()?.label == text(R.string.settings) }
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                await("Reopening Settings focuses Quality") { focused()?.label?.startsWith(text(R.string.quality)) == true }
                val before = LibraryStore(app, profile.id).viewing().firstOrNull()?.position ?: 0
                Thread.sleep(6500)
                assertTrue("Drawer remains focused beyond control auto-hide",
                    focused()?.label?.startsWith(text(R.string.quality)) == true)
                assertTrue("Playback continues beneath menu",
                    (LibraryStore(app, profile.id).viewing().firstOrNull()?.position ?: 0) > before)
                capture("remote-settings-${fit.horizontal}")
                key(KeyEvent.KEYCODE_BACK) // Root menu closes.
                await("Drawer Back restores Settings") { focused()?.label == text(R.string.settings) }
                key(KeyEvent.KEYCODE_DPAD_LEFT); key(KeyEvent.KEYCODE_DPAD_CENTER)
                await("Episodes opens on Back") { focused()?.label == text(R.string.back) }
                repeat(4) { key(KeyEvent.KEYCODE_DPAD_LEFT) }
                assertEquals(text(R.string.back), focused()?.label)
                capture("remote-episodes-${fit.horizontal}")
                select(text(R.string.season_options))
                select(target.getString(R.string.season, 2))
                await("Season selection restores season row") { focused()?.label == "${text(R.string.season_options)} · ${target.getString(R.string.season, 2)}" }
                assertEquals("Season picking keeps current episode playing", 1, vm.state.value.episode?.season)
                key(KeyEvent.KEYCODE_BACK)
                await("Episodes restores opener") { focused()?.label == text(R.string.episodes) }
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                await("Episodes reopens") { focused()?.label == text(R.string.back) }
                select(text(R.string.settings)) // Footer switch, not transport.
                await("Episodes switches to Settings without losing focus") {
                    focused()?.label?.startsWith(text(R.string.quality)) == true
                }
                key(KeyEvent.KEYCODE_BACK)
                await("Switched menu restores Settings transport") { focused()?.label == text(R.string.settings) }
            }
            // A video-only stream still allows captions Off and skips disabled Audio during D-pad traversal.
            render(activity) { }
            val silent = Stream(File(folder, "qa-player.m3u8").toURI().toString(), emptyList())
            instrument.runOnMainSync { mutable.value = base.copy(stream = silent) }
            render(activity) {
                val state by vm.state.collectAsState()
                PlaybackScreen(silent, state, vm)
            }
            await("Video-only fixture plays") { visible(text(R.string.pause)) }
            select(text(R.string.settings), KeyEvent.KEYCODE_DPAD_RIGHT)
            val unavailableAudio = "${text(R.string.audio)} · ${text(R.string.no_tracks)}"
            await("Unavailable audio is explained and disabled") { nodes().any { it.label == unavailableAudio && !it.clickable } }
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            await("Remote skips disabled Audio") { focused()?.label?.startsWith(text(R.string.subtitles)) == true }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Subtitles always offers Off") { focused()?.label == text(R.string.subtitles_off) }
            capture("remote-unavailable-tracks")
            key(KeyEvent.KEYCODE_BACK); key(KeyEvent.KEYCODE_BACK)

            }
            for (fit in listOf(ScreenFit(), ScreenFit(10, 10))) {
                render(activity) { }
                instrument.runOnMainSync {
                    ProfileStore(app).quality(profile.id, 720)
                    ProfileStore(app).autoplay(profile.id, false)
                    val current = ProfileStore(app).all().first()
                    mutable.value = base.copy(screen = Screen.ACCOUNT, stream = null, detail = null,
                        profile = current, profiles = listOf(current), screenFit = fit)
                }
                render(activity) { TvApp(vm) }
                await("Settings navigation receives focus") { focused()?.label == text(R.string.settings) }
                key(KeyEvent.KEYCODE_DPAD_RIGHT)
                await("Sidebar enters category list") { focused()?.label?.startsWith(text(R.string.account_settings)) == true }
                capture("remote-app-settings-${fit.horizontal}")
                assertFalse("Account actions stay inside category", visible(text(R.string.refresh_account)))
                select(text(R.string.playback_settings))
                await("Playback focuses Quality") { focused()?.label?.startsWith(text(R.string.quality)) == true }
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                await("App quality uses saved preference") { focused()?.label == target.getString(R.string.up_to_resolution, 720) }
                select(target.getString(R.string.up_to_resolution, 1080))
                await("App selection returns to Playback and persists") { vm.state.value.profile?.quality == 1080 &&
                    ProfileStore(app).all().first().quality == 1080 && focused()?.label?.startsWith(text(R.string.quality)) == true }
                key(KeyEvent.KEYCODE_BACK)
                await("Category Back restores originating row") { focused()?.label == text(R.string.playback_settings) }
                select(text(R.string.display))
                select(text(R.string.screen_fit))
                await("Display opens existing editor") { visible(text(R.string.screen_fit_help)) }
                key(KeyEvent.KEYCODE_BACK)
                await("Screen-fit cancel restores Display target") { focused()?.label == text(R.string.screen_fit) }
                assertEquals(fit, vm.state.value.screenFit)
                key(KeyEvent.KEYCODE_BACK)
                select(text(R.string.profiles), KeyEvent.KEYCODE_DPAD_UP)
                select(vm.state.value.profile!!.name, KeyEvent.KEYCODE_DPAD_UP)
                await("Profile editing opens") { visible(text(R.string.profile_name)) }
                select(text(R.string.avatar_settings))
                select(target.getString(R.string.avatar_number, 2))
                await("Avatar popup returns to draft editor") { focused()?.label?.startsWith(text(R.string.avatar_settings)) == true }
                assertEquals("Avatar choice stays draft until Save", 0, ProfileStore(app).all().first().avatar)
                key(KeyEvent.KEYCODE_BACK)
                await("Editing Back returns to profile management") { !visible(text(R.string.profile_name)) }
                key(KeyEvent.KEYCODE_BACK)
                await("Profile management Back restores category") { focused()?.label?.startsWith(text(R.string.profiles)) == true }
                instrument.runOnMainSync { vm.openAccountSettings() }
                await("Recovery entry opens Account directly") { visible(text(R.string.refresh_account)) }
                assertEquals(SettingsEntry.ACCOUNT, vm.state.value.settingsEntry)
                capture("remote-app-account-${fit.horizontal}")
                key(KeyEvent.KEYCODE_BACK)
                await("Account Back returns to category root") { focused()?.label?.startsWith(text(R.string.account_settings)) == true }
            }
            instrument.runOnMainSync { vm.navigate(Screen.MOVIES) }
            await("Movies navigation opens") { focused()?.label == text(R.string.movies) }
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            select(text(R.string.sort_options), KeyEvent.KEYCODE_DPAD_UP)
            select(text(R.string.top_rated))
            await("Sort applies machine value and restores row") { vm.state.value.sort == "best" &&
                focused()?.label?.startsWith(text(R.string.sort_options)) == true }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            select(text(R.string.latest), KeyEvent.KEYCODE_DPAD_UP)
            await("Second sort wins without losing focus") { vm.state.value.sort == "latest" &&
                catalogRequests.any { it.contains("sort=best") } && catalogRequests.any { it.contains("sort=latest") } }
            capture("remote-sort")
            instrument.runOnMainSync { vm.navigate(Screen.ACCOUNT) }
            await("Settings returns to category root") { focused()?.label == text(R.string.settings) }
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            select(text(R.string.profiles))
            select(vm.state.value.profile!!.name, KeyEvent.KEYCODE_DPAD_UP)
            select(text(R.string.avatar_settings))
            select(target.getString(R.string.avatar_number, 2))
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            select(text(R.string.save), KeyEvent.KEYCODE_DPAD_LEFT)
            await("Profile Save commits draft avatar") { ProfileStore(app).all().first().avatar == 1 }
            key(KeyEvent.KEYCODE_BACK)
            instrument.runOnMainSync { vm.navigate(Screen.MOVIES) }
            await("Leaving Settings focuses Movies") { focused()?.label == text(R.string.movies) }
            instrument.runOnMainSync { vm.navigate(Screen.ACCOUNT) }
            await("Navigation Settings opens category root, not saved profile manager") {
                visible(text(R.string.playback_settings)) && !visible(text(R.string.done))
            }
            instrument.runOnMainSync { vm.saveProfile(null, "Second fixture profile", 0) }
            val other = vm.state.value.profiles.last()
            instrument.runOnMainSync { vm.selectProfile(other.id); vm.setQuality(720); vm.setAutoplay(true) }
            assertEquals("First profile quality remains independent", 1080, ProfileStore(app).all().first().quality)
            assertFalse("First profile autoplay remains independent", ProfileStore(app).all().first().autoplay)
            assertEquals(720, ProfileStore(app).all().last().quality)
        } finally {
            render(activity) { }
            instrument.runOnMainSync { store.clear() }
            folder.deleteRecursively()
            prefs.forEach { target.deleteSharedPreferences(it) }
        }
    }
}
