package dev.personal.flixtortv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text

@Composable internal fun UpdateReadyDialog(version: String, dismiss: () -> Unit, install: () -> Unit) {
    val installFocus = remember { FocusRequester() }
    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        LazyColumn(Modifier.padding(LocalSafePadding.current).widthIn(max = 520.dp)
            .background(Panel, RoundedCornerShape(16.dp)).padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)) {
            item { Text(stringResource(R.string.update_available_title, version), fontSize = 24.sp) }
            item { Text(stringResource(R.string.update_ready), color = Muted) }
            item { Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Action(stringResource(R.string.install_update), Modifier.focusRequester(installFocus), click = install)
                Action(stringResource(R.string.update_later), click = dismiss)
            } }
        }
        LaunchedEffect(Unit) { installFocus.requestFocus() }
    }
}

@Composable internal fun ProfileAvatar(avatar: Int, modifier: Modifier = Modifier) {
    val colors = listOf(Color(0xFF6DCAED), Color(0xFFA898F4), Color(0xFFFFAD98), Color(0xFF76D8B9),
        Color(0xFFF3CE78), Color(0xFFF19ACC), Color(0xFF87AFF2))
    Canvas(modifier.size(88.dp)) {
        drawCircle(colors[avatar.coerceIn(0, 6)], radius = size.minDimension / 2)
        drawCircle(Midnight, radius = size.width * 0.14f, center = Offset(size.width / 2, size.height * 0.35f))
        drawOval(Midnight, topLeft = Offset(size.width * 0.24f, size.height * 0.55f), size = Size(size.width * 0.52f, size.height * 0.28f))
    }
}

@Composable internal fun ProfilesScreen(s: TvState, vm: TvViewModel) {
    var editing by remember { mutableStateOf(false) }
    var editId by remember { mutableStateOf<String?>(null) }
    var name by remember { mutableStateOf("") }
    var avatar by remember { mutableIntStateOf(0) }
    var manage by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Profile?>(null) }
    val first = remember { FocusRequester() }
    LaunchedEffect(editing, manage, s.profiles.size) { if (!editing) first.requestFocus() }
    BackHandler(editing || manage) { editing = false; manage = false }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        item {
            Text(stringResource(if (editing && editId == null) R.string.add_profile else if (editing) R.string.edit_profile else if (manage) R.string.manage_profiles else R.string.who_watching),
                fontSize = 36.sp, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.profile_help), color = Muted, modifier = Modifier.padding(top = 8.dp))
        }
        if (!editing) {
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(24.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
                    items(s.profiles, key = Profile::id) { profile ->
                        Column(Modifier.width(142.dp), horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            ProfileAvatar(profile.avatar)
                            Action(profile.name, if (profile.id == s.profiles.first().id) Modifier.focusRequester(first) else Modifier) {
                                if (manage) { editing = true; editId = profile.id; name = profile.name; avatar = profile.avatar }
                                else vm.selectProfile(profile.id)
                            }
                        }
                    }
                }
            }
            item { Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                if (s.profiles.size < 7) Action(stringResource(R.string.add_profile)) { editing = true; editId = null; name = ""; avatar = 0 }
                Action(stringResource(if (manage) R.string.done else R.string.manage_profiles)) { manage = !manage }
                if (s.profile != null) Action(stringResource(R.string.back)) { vm.navigate(Screen.HOME) }
            } }
        } else {
            item { Column(Modifier.widthIn(max = 520.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                ProfileAvatar(avatar)
                Field(stringResource(R.string.profile_name), name, { name = it.take(30) })
                if (name.trim().isEmpty()) Text(stringResource(R.string.profile_name_required), color = Muted)
                Text(stringResource(R.string.choose_avatar), color = Muted)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items((0..6).toList(), key = { it }) { value ->
                        Action(stringResource(R.string.avatar_number, value + 1)) { avatar = value }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Action(stringResource(R.string.save), enabled = name.trim().isNotEmpty()) {
                        vm.saveProfile(editId, name, avatar); editing = false
                    }
                    Action(stringResource(R.string.cancel)) { editing = false }
                }
                if (editId != null && s.profiles.size > 1) Action(stringResource(R.string.delete_profile)) {
                    deleting = s.profiles.firstOrNull { it.id == editId }
                }
            } }
        }
    }
    deleting?.let { profile ->
        val cancelFocus = remember { FocusRequester() }
        Dialog(onDismissRequest = { deleting = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Column(Modifier.padding(LocalSafePadding.current).widthIn(max = 520.dp).background(Panel, RoundedCornerShape(16.dp)).padding(28.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Text(stringResource(R.string.delete_profile_title, profile.name), fontSize = 24.sp)
                Text(stringResource(R.string.delete_profile_help), color = Muted)
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Action(stringResource(R.string.cancel), Modifier.focusRequester(cancelFocus)) { deleting = null }
                    Action(stringResource(R.string.delete_profile)) { vm.deleteProfile(profile.id); deleting = null; editing = false }
                }
            }
            LaunchedEffect(Unit) { cancelFocus.requestFocus() }
        }
    }
}

@Composable internal fun QualityOptions(quality: Int, select: (Int) -> Unit, vertical: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (!vertical) Text(stringResource(R.string.quality_help), color = Muted)
        if (vertical) {
            listOf(0, 720, 1080, 2160).forEach { height ->
                val label = if (height == 0) stringResource(R.string.auto_quality) else stringResource(R.string.up_to_resolution, height)
                Action(if (quality == height) stringResource(R.string.selected_option, label) else label, Modifier.fillMaxWidth()) { select(height) }
            }
        } else LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 6.dp)) {
            items(listOf(0, 720, 1080, 2160), key = { it }) { height ->
                val label = if (height == 0) stringResource(R.string.auto_quality) else stringResource(R.string.up_to_resolution, height)
                Action(if (quality == height) stringResource(R.string.selected_option, label) else label) { select(height) }
            }
        }
    }
}

@Composable internal fun UpdateSettings(vm: TvViewModel) {
    val state by vm.updates.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.updates), fontSize = 24.sp)
        val message = when (state.phase) {
            UpdatePhase.IDLE -> R.string.update_not_checked
            UpdatePhase.CHECKING -> R.string.update_checking
            UpdatePhase.CURRENT -> R.string.update_current
            UpdatePhase.DOWNLOADING -> R.string.update_downloading
            UpdatePhase.READY -> R.string.update_ready
            UpdatePhase.FAILED -> R.string.update_unavailable
            UpdatePhase.INSTALLING -> R.string.update_installing
            UpdatePhase.INCOMPATIBLE -> R.string.update_incompatible
            UpdatePhase.SIGNER_MISMATCH -> R.string.update_signer_mismatch
        }
        Text(stringResource(message), color = Muted, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        if (state.installationFailed) Text(stringResource(R.string.update_install_failed), color = Muted)
        if (state.phase == UpdatePhase.DOWNLOADING) Text(stringResource(R.string.download_progress, state.progress))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Action(stringResource(R.string.check_updates), enabled = state.phase !in listOf(UpdatePhase.CHECKING, UpdatePhase.DOWNLOADING, UpdatePhase.INSTALLING)) { vm.checkUpdates() }
            if (state.phase == UpdatePhase.READY) Action(stringResource(R.string.install_update)) { (context as? MainActivity)?.installUpdate() }
        }
        Text(stringResource(R.string.about_version, BuildConfig.VERSION_NAME, BuildConfig.COMMIT_SHA), color = Muted)
    }
}

@Composable internal fun StructuralRecovery(vm: TvViewModel) {
    val state by vm.updates.state.collectAsStateWithLifecycle()
    val app by vm.state.collectAsStateWithLifecycle()
    val incidentMatches = state.incidentFingerprint == app.failureOperation.lowercase() + ".response-v1"
    val context = LocalContext.current
    Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when {
            state.phase == UpdatePhase.READY -> {
                if (app.screen == Screen.PLAYER) {
                    Text(stringResource(R.string.update_after_playback), color = Muted)
                    Action(stringResource(R.string.back)) { vm.back() }
                } else Action(stringResource(R.string.install_update)) { (context as? MainActivity)?.installUpdate() }
            }
            state.phase in listOf(UpdatePhase.CHECKING, UpdatePhase.DOWNLOADING, UpdatePhase.INSTALLING) -> Text(stringResource(R.string.update_checking), color = Muted)
            state.incidentKnown && incidentMatches -> Text(stringResource(R.string.fix_pending), color = Muted)
            else -> {
                if (!state.incidentChecked || !incidentMatches || state.phase == UpdatePhase.FAILED) Text(stringResource(R.string.update_unavailable), color = Muted)
                val label = when (state.report) {
                    ReportPhase.SENDING -> R.string.report_sending
                    ReportPhase.SENT -> R.string.report_sent
                    ReportPhase.FAILED -> R.string.report_failed
                    ReportPhase.UNCONFIGURED -> R.string.report_unconfigured
                    else -> R.string.notify_dylan
                }
                Text(stringResource(label), color = Muted, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                if (state.report !in listOf(ReportPhase.SENDING, ReportPhase.SENT, ReportPhase.UNCONFIGURED)) {
                    Action(stringResource(R.string.notify_dylan)) { vm.report() }
                }
            }
        }
    }
}
