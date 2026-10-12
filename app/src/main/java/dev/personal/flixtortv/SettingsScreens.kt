package dev.personal.flixtortv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
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

@Composable
private fun SettingsDialog(
    dismiss: () -> Unit,
    modifier: Modifier = Modifier,
    initialFocus: FocusRequester? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        GlassSurface(
            modifier = modifier.heightIn(max = screenHeight - 64.dp).padding(LocalSafePadding.current)
                .widthIn(max = 520.dp).fillMaxWidth(),
            surfaceColor = Color(0xF20B1426),
        ) {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                content = content,
            )
        }
        LaunchedEffect(initialFocus) {
            withFrameNanos { }
            initialFocus?.requestFocus()
        }
    }
}

@Composable
private fun SettingsAction(
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    variant: GlassButtonVariant = GlassButtonVariant.SECONDARY,
    onClick: () -> Unit,
) {
    GlassButton(label, modifier, variant, enabled, onClick = onClick)
}

@Composable internal fun ExitConfirmationDialog(stay: () -> Unit, exit: () -> Unit) {
    val stayFocus = remember { FocusRequester() }
    SettingsDialog(stay, initialFocus = stayFocus) {
        Text(stringResource(R.string.exit_title), color = TextPrimary, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Text(stringResource(R.string.exit_help), color = TextSecondary, fontSize = 17.sp, lineHeight = 24.sp)
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SettingsAction(stringResource(R.string.stay_in_app), Modifier.focusRequester(stayFocus), onClick = stay)
            SettingsAction(stringResource(R.string.exit_app), variant = GlassButtonVariant.DESTRUCTIVE, onClick = exit)
        }
    }
}

@Composable internal fun UpdateReadyDialog(version: String, dismiss: () -> Unit, install: () -> Unit) {
    val installFocus = remember { FocusRequester() }
    val laterFocus = remember { FocusRequester() }
    SettingsDialog(dismiss, initialFocus = laterFocus) {
        Text(stringResource(R.string.update_available_title, version), color = TextPrimary, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Text(stringResource(R.string.update_ready), color = TextSecondary, fontSize = 17.sp, lineHeight = 24.sp)
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SettingsAction(stringResource(R.string.install_update),
                Modifier.focusRequester(installFocus).focusProperties { right = laterFocus },
                variant = GlassButtonVariant.PRIMARY, onClick = install)
            SettingsAction(stringResource(R.string.update_later), Modifier.focusRequester(laterFocus).focusProperties { left = installFocus }, onClick = dismiss)
        }
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

/**
 * Remote keypad for four digit profile PINs. `confirm` receives the entered digits and
 * returns whether the profile may open; a wrong PIN clears the entry and explains itself.
 */
@Composable internal fun PinEntry(profile: Profile, confirm: (String) -> Boolean, dismiss: () -> Unit) {
    var entry by remember(profile.id) { mutableStateOf("") }
    var wrong by remember(profile.id) { mutableStateOf(false) }
    val first = remember { FocusRequester() }
    val submit = {
        if (confirm(entry)) dismiss() else { wrong = true; entry = "" }
    }
    fun digit(value: Int) {
        wrong = false
        if (entry.length >= ProfilePin.LENGTH) return
        entry += value.toString()
        if (entry.length == ProfilePin.LENGTH) submit()
    }
    BackHandler { dismiss() }
    SettingsDialog(dismiss, Modifier.widthIn(max = 380.dp), initialFocus = first) {
        // Avatar + name header
        Column(
            Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ProfileAvatar(profile.avatar, Modifier.size(64.dp))
            Text(
                profile.name,
                color = TextPrimary,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                stringResource(R.string.pin_required, profile.name).substringAfter(".").trim()
                    .takeIf { it.isNotBlank() } ?: stringResource(R.string.pin_locked),
                color = TextSecondary,
                fontSize = 14.sp,
            )
        }
        // PIN dots — 4 circles that fill as digits are entered
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                repeat(ProfilePin.LENGTH) { index ->
                    val filled = index < entry.length
                    Box(
                        Modifier
                            .size(22.dp)
                            .background(
                                if (filled) Accent else Color.Transparent,
                                RoundedCornerShape(11.dp),
                            )
                            .border(
                                2.dp,
                                if (filled) Accent else GlassBorderStrong,
                                RoundedCornerShape(11.dp),
                            )
                    )
                }
            }
        }
        // Error / hint text
        Text(
            if (wrong) stringResource(R.string.pin_wrong) else stringResource(R.string.pin_help),
            color = if (wrong) Color(0xFFFFB4AB) else TextSecondary,
            fontSize = 13.sp,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { liveRegion = LiveRegionMode.Polite },
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        // 3×3 numpad grid
        Column(
            Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            listOf(listOf(1, 2, 3), listOf(4, 5, 6), listOf(7, 8, 9)).forEach { row ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    row.forEach { value ->
                        GlassButton(
                            value.toString(),
                            modifier = Modifier
                                .weight(1f)
                                .then(if (value == 1) Modifier.focusRequester(first) else Modifier),
                        ) { digit(value) }
                    }
                }
            }
            // Bottom row: Clear · 0 · Delete
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                GlassButton(
                    stringResource(R.string.pin_clear),
                    modifier = Modifier.weight(1f),
                ) { wrong = false; entry = "" }
                GlassButton(
                    "0",
                    modifier = Modifier.weight(1f),
                ) { digit(0) }
                GlassButton(
                    "⌫",
                    modifier = Modifier.weight(1f),
                ) { wrong = false; entry = entry.dropLast(1) }
            }
            // Cancel
            GlassButton(
                stringResource(R.string.cancel),
                modifier = Modifier.fillMaxWidth(),
                variant = GlassButtonVariant.SECONDARY,
            ) { dismiss() }
        }
    }
}

/** Sets, changes or removes the four digit PIN while managing a profile. */
@Composable private fun PinEditor(profile: Profile, save: (String?) -> Unit) {
    var entry by remember(profile.id) { mutableStateOf("") }
    var open by remember(profile.id) { mutableStateOf(false) }
    val first = remember { FocusRequester() }
    if (open) SettingsDialog({ open = false }, Modifier.widthIn(max = 460.dp), initialFocus = first) {
        Text(stringResource(R.string.pin), color = TextPrimary, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text(stringResource(R.string.pin_help), color = TextSecondary, fontSize = 16.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            repeat(ProfilePin.LENGTH) { index ->
                val filled = index < entry.length
                Box(Modifier.size(16.dp).background(if (filled) Accent else Color.Transparent, RoundedCornerShape(8.dp))
                    .border(1.dp, if (filled) Accent else GlassBorderStrong, RoundedCornerShape(8.dp)))
            }
        }
        listOf(listOf(1, 2, 3), listOf(4, 5, 6), listOf(7, 8, 9)).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { value ->
                    SettingsAction(stringResource(R.string.pin_digit, value),
                        if (value == 1) Modifier.focusRequester(first) else Modifier) {
                        if (entry.length < ProfilePin.LENGTH) entry += value.toString()
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SettingsAction(stringResource(R.string.pin_clear)) { entry = "" }
            SettingsAction(stringResource(R.string.pin_digit, 0)) { if (entry.length < ProfilePin.LENGTH) entry += "0" }
            SettingsAction(stringResource(R.string.pin_delete_digit)) { entry = entry.dropLast(1) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SettingsAction(stringResource(R.string.save), enabled = entry.length == ProfilePin.LENGTH, variant = GlassButtonVariant.PRIMARY) {
                save(entry); entry = ""; open = false
            }
            SettingsAction(stringResource(R.string.cancel)) { entry = ""; open = false }
        }
    }
    if (profile.locked) SettingsAction(stringResource(R.string.pin_change), Modifier.focusRequester(first)) { open = true }
    else SettingsAction(stringResource(R.string.pin_set)) { open = true }
    if (profile.locked) SettingsAction(stringResource(R.string.pin_remove), variant = GlassButtonVariant.DESTRUCTIVE) { save(null) }
}

@Composable internal fun ProfilesScreen(s: TvState, vm: TvViewModel) {
    var editing by remember { mutableStateOf(false) }
    var editId by remember { mutableStateOf<String?>(null) }
    var name by remember { mutableStateOf("") }
    var avatar by remember { mutableIntStateOf(0) }
    var manage by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Profile?>(null) }
    var unlocking by remember { mutableStateOf<Profile?>(null) }
    val first = remember { FocusRequester() }
    LaunchedEffect(editing, manage, s.profiles.size) { if (!editing) first.requestFocus() }
    BackHandler(editing || manage || unlocking != null) { editing = false; manage = false; unlocking = null }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 36.dp, vertical = 28.dp), verticalArrangement = Arrangement.spacedBy(28.dp)) {
        item {
            Text(stringResource(if (editing && editId == null) R.string.add_profile else if (editing) R.string.edit_profile else if (manage) R.string.manage_profiles else R.string.who_watching),
                fontSize = 36.sp, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.profile_help), color = Muted, modifier = Modifier.padding(top = 8.dp))
        }
        if (!editing) {
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(20.dp), contentPadding = PaddingValues(horizontal = 4.dp, vertical = 12.dp)) {
                    items(s.profiles, key = Profile::id) { profile ->
                        val accessibleName = if (profile.locked) "${profile.name}, ${stringResource(R.string.pin_locked)}" else profile.name
                        FocusFrame(
                            modifier = Modifier.width(176.dp).heightIn(min = 184.dp)
                                .then(if (profile.id == s.profiles.first().id) Modifier.focusRequester(first) else Modifier),
                            radius = RadiusMedium.dp,
                            selected = s.profile?.id == profile.id,
                            contentDescription = accessibleName,
                            onClick = {
                                if (manage) { editing = true; editId = profile.id; name = profile.name; avatar = profile.avatar }
                                else if (profile.locked) unlocking = profile
                                else vm.selectProfile(profile.id)
                            },
                        ) {
                            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 18.dp),
                                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                ProfileAvatar(profile.avatar, Modifier.size(88.dp))
                                Text(profile.name, color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (profile.locked) Text(stringResource(R.string.pin_locked), color = TextSecondary, fontSize = 14.sp)
                            }
                        }
                    }
                }
            }
            item { Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                if (s.profiles.size < 7) SettingsAction(stringResource(R.string.add_profile), variant = GlassButtonVariant.PRIMARY) { editing = true; editId = null; name = ""; avatar = 0 }
                SettingsAction(stringResource(if (manage) R.string.done else R.string.manage_profiles)) { manage = !manage }
                if (s.profile != null) SettingsAction(stringResource(R.string.back)) { vm.navigate(Screen.HOME) }
            } }
        } else {
            item { Column(Modifier.widthIn(max = 600.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                ProfileAvatar(avatar)
                Field(stringResource(R.string.profile_name), name, { name = it.take(30) })
                if (name.trim().isEmpty()) Text(stringResource(R.string.profile_name_required), color = TextSecondary)
                Text(stringResource(R.string.choose_avatar), color = TextSecondary, fontSize = 17.sp)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items((0..6).toList(), key = { it }) { value ->
                        FocusFrame(
                            modifier = Modifier.size(72.dp), radius = RadiusMedium.dp,
                            selected = avatar == value,
                            contentDescription = stringResource(R.string.avatar_number, value + 1),
                            onClick = { avatar = value },
                        ) {
                            ProfileAvatar(value, Modifier.size(48.dp).align(Alignment.Center))
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SettingsAction(stringResource(R.string.save), enabled = name.trim().isNotEmpty(), variant = GlassButtonVariant.PRIMARY) {
                        vm.saveProfile(editId, name, avatar); editing = false
                    }
                    SettingsAction(stringResource(R.string.cancel)) { editing = false }
                }
                if (editId != null && s.profiles.size > 1) SettingsAction(stringResource(R.string.delete_profile), variant = GlassButtonVariant.DESTRUCTIVE) {
                    deleting = s.profiles.firstOrNull { it.id == editId }
                }
                editId?.let { id -> s.profiles.firstOrNull { it.id == id }?.let { PinEditor(it) { pin -> vm.setProfilePin(id, pin) } } }
            } }
        }
    }
    deleting?.let { profile ->
        val cancelFocus = remember { FocusRequester() }
        SettingsDialog({ deleting = null }, initialFocus = cancelFocus) {
            Text(stringResource(R.string.delete_profile_title, profile.name), color = TextPrimary, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.delete_profile_help), color = TextSecondary, fontSize = 17.sp, lineHeight = 24.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SettingsAction(stringResource(R.string.cancel), Modifier.focusRequester(cancelFocus)) { deleting = null }
                SettingsAction(stringResource(R.string.delete_profile), variant = GlassButtonVariant.DESTRUCTIVE) {
                    vm.deleteProfile(profile.id); deleting = null; editing = false
                }
            }
        }
    }
    unlocking?.let { profile ->
        PinEntry(profile, { pin -> vm.unlockProfile(profile.id, pin) }) { unlocking = null }
    }
}

@Composable internal fun QualityOptions(quality: Int, select: (Int) -> Unit, vertical: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (!vertical) Text(stringResource(R.string.quality_help), color = TextSecondary, fontSize = 16.sp)
        if (vertical) {
            listOf(0, 720, 1080, 2160).forEach { height ->
                val label = if (height == 0) stringResource(R.string.auto_quality) else stringResource(R.string.up_to_resolution, height)
                FocusFrame(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                    selected = quality == height,
                    contentDescription = if (quality == height) stringResource(R.string.selected_option, label) else label,
                    onClick = { select(height) },
                ) {
                    Text(label, color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp))
                }
            }
        } else LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 6.dp)) {
            items(listOf(0, 720, 1080, 2160), key = { it }) { height ->
                val label = if (height == 0) stringResource(R.string.auto_quality) else stringResource(R.string.up_to_resolution, height)
                FocusFrame(
                    modifier = Modifier.heightIn(min = 56.dp),
                    selected = quality == height,
                    contentDescription = if (quality == height) stringResource(R.string.selected_option, label) else label,
                    onClick = { select(height) },
                ) {
                    Text(label, color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp))
                }
            }
        }
    }
}

@Composable internal fun UpdateSettings(vm: TvViewModel) {
    val state by vm.updates.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.updates), color = TextPrimary, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
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
        Text(stringResource(message), color = TextSecondary, fontSize = 16.sp, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        if (state.installationFailed) Text(stringResource(R.string.update_install_failed), color = TextSecondary, fontSize = 15.sp)
        if (state.phase == UpdatePhase.DOWNLOADING) Text(stringResource(R.string.download_progress, state.progress), color = TextSecondary)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SettingsAction(stringResource(R.string.check_updates), enabled = state.phase !in listOf(UpdatePhase.CHECKING, UpdatePhase.DOWNLOADING, UpdatePhase.INSTALLING)) { vm.checkUpdates() }
            if (state.phase == UpdatePhase.READY) SettingsAction(stringResource(R.string.install_update), variant = GlassButtonVariant.PRIMARY) { (context as? MainActivity)?.installUpdate() }
        }
        Text(stringResource(R.string.about_version, BuildConfig.VERSION_NAME, BuildConfig.COMMIT_SHA), color = TextMuted, fontSize = 14.sp)
    }
}

@Composable internal fun StructuralRecovery(vm: TvViewModel) {
    val state by vm.updates.state.collectAsStateWithLifecycle()
    val app by vm.state.collectAsStateWithLifecycle()
    val incidentMatches = state.incidentFingerprint == app.failureOperation.lowercase() + ".response-v1"
    val context = LocalContext.current
    GlassSurface(Modifier.padding(horizontal = 24.dp, vertical = 8.dp).widthIn(max = 680.dp)) {
      Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        when {
            state.phase == UpdatePhase.READY -> {
                if (app.screen == Screen.PLAYER) {
                    Text(stringResource(R.string.update_after_playback), color = TextSecondary, fontSize = 16.sp)
                    SettingsAction(stringResource(R.string.back)) { vm.back() }
                } else SettingsAction(stringResource(R.string.install_update), variant = GlassButtonVariant.PRIMARY) { (context as? MainActivity)?.installUpdate() }
            }
            state.phase in listOf(UpdatePhase.CHECKING, UpdatePhase.DOWNLOADING, UpdatePhase.INSTALLING) -> Text(stringResource(R.string.update_checking), color = TextSecondary)
            state.incidentKnown && incidentMatches -> Text(stringResource(R.string.fix_pending), color = TextSecondary)
            else -> {
                if (!state.incidentChecked || !incidentMatches || state.phase == UpdatePhase.FAILED) Text(stringResource(R.string.update_unavailable), color = TextSecondary)
                val label = when (state.report) {
                    ReportPhase.SENDING -> R.string.report_sending
                    ReportPhase.SENT -> R.string.report_sent
                    ReportPhase.FAILED -> R.string.report_failed
                    ReportPhase.UNCONFIGURED -> R.string.report_unconfigured
                    else -> R.string.notify_dylan
                }
                Text(stringResource(label), color = TextSecondary, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                if (state.report !in listOf(ReportPhase.SENDING, ReportPhase.SENT, ReportPhase.UNCONFIGURED)) {
                    SettingsAction(stringResource(R.string.notify_dylan)) { vm.report() }
                }
            }
        }
      }
    }
}
