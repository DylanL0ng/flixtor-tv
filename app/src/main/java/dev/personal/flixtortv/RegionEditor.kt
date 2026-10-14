package dev.personal.flixtortv

import java.util.UUID
import kotlin.math.abs
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import androidx.tv.material3.Text
import kotlinx.coroutines.delay

/** Draft times stay separate from validated ranges, so crossed handles cannot crash. */
@Composable internal fun RegionEditor(player: Player, vm: TvViewModel, regions: List<SkipSegment>,
    initial: SkipSegment?, duration: Long, modifier: Modifier = Modifier, close: () -> Unit) {
    var selected by remember { mutableStateOf(initial) }
    var editing by remember { mutableStateOf(initial != null) }
    var start by remember { mutableLongStateOf(initial?.startMs ?: player.currentPosition.coerceAtMost((duration - 1000).coerceAtLeast(0))) }
    var end by remember { mutableLongStateOf(initial?.endMs ?: (start + 30000).coerceAtMost(duration)) }
    var kind by remember { mutableStateOf(RegionKind(initial?.type ?: SegmentType.INTRO, initial?.typeId ?: SegmentType.INTRO.read)) }
    var action by remember { mutableStateOf(initial?.action) }
    var preview by remember { mutableLongStateOf(start) }
    var seekPending by remember { mutableStateOf(false) }
    var previewFailed by remember { mutableStateOf(false) }
    var zoom by remember { mutableStateOf(false) }
    var zoomCenter by remember { mutableLongStateOf(start) }
    var customLabel by remember { mutableStateOf("") }
    var showTypeForm by remember { mutableStateOf(false) }
    var renamingType by remember { mutableStateOf<String?>(null) }
    val startFocus = remember { FocusRequester() }
    val addFocus = remember { FocusRequester() }
    val scroll = rememberScrollState()
    val kinds = regionKinds(vm)
    val actions = actionChoices(true)
    val valid = start >= 0 && end > start && end <= duration
    fun select(region: SkipSegment?) {
        selected = region; editing = true
        start = region?.startMs ?: player.currentPosition.coerceIn(0, (duration - 1000).coerceAtLeast(0))
        end = region?.endMs ?: (start + 30000).coerceAtMost(duration)
        kind = RegionKind(region?.type ?: SegmentType.INTRO, region?.typeId ?: SegmentType.INTRO.read)
        action = region?.action; preview = start
    }
    LaunchedEffect(editing, selected?.id) {
        scroll.scrollTo(0); withFrameNanos { }
        if (editing) startFocus.requestFocus() else addFocus.requestFocus()
    }
    LaunchedEffect(preview, editing) {
        if (!editing) return@LaunchedEffect
        seekPending = true
        previewFailed = false
        delay(120)
        val framePosition = preview.coerceAtMost((duration - 1).coerceAtLeast(0))
        player.seekTo(framePosition)
        while (player.playerError == null && ((player.playbackState != Player.STATE_READY &&
                !(preview == duration && player.playbackState == Player.STATE_ENDED)) || abs(player.currentPosition - framePosition) > 1000)) delay(100)
        previewFailed = player.playerError != null
        seekPending = false
    }
    val windowStart = if (zoom) (zoomCenter - 30000).coerceIn(0, (duration - 60000).coerceAtLeast(0)) else 0L
    val windowEnd = if (zoom) (windowStart + 60000).coerceAtMost(duration) else duration
    LaunchedEffect(preview, zoom) { if (zoom && (preview < windowStart || preview > windowEnd)) zoomCenter = preview }
    val band = if (valid) listOf(SkipSegment(kind.type, start, end, typeId = kind.id)) else emptyList()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.edit_regions), color = TextPrimary, fontSize = 24.sp)
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (editing) {
                Text(stringResource(R.string.region_start, clock(start)), color = Accent)
                PlayerTimeline(start, duration, band, stringResource(R.string.region_start, clock(start)),
                    Modifier.fillMaxWidth().height(40.dp).focusRequester(startFocus), baseStep = 1000,
                    windowStart = windowStart, windowEnd = windowEnd, onMove = { start = it; preview = it })
                Text(stringResource(R.string.region_end, clock(end)), color = Accent)
                PlayerTimeline(end, duration, band, stringResource(R.string.region_end, clock(end)),
                    Modifier.fillMaxWidth().height(40.dp), baseStep = 1000,
                    windowStart = windowStart, windowEnd = windowEnd, onMove = { end = it; preview = it })
                Text(stringResource(if (seekPending) R.string.region_preview_loading else R.string.region_scrub_help),
                    color = Muted, fontSize = 13.sp, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                if (!valid) Text(stringResource(R.string.region_invalid), color = Accent)
                if (previewFailed) Text(stringResource(R.string.region_preview_failed), color = Accent)
                OptionPicker(stringResource(R.string.region_zoom), listOf(
                    MenuChoice(false, stringResource(R.string.region_full_timeline)), MenuChoice(true, stringResource(R.string.region_zoomed_timeline))), zoom) { zoomCenter = preview; zoom = it }
                OptionPicker(stringResource(R.string.region_type), kinds, kind) { kind = it; showTypeForm = false }
                OptionPicker(stringResource(R.string.region_action), actions, action) { action = it }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Action(stringResource(R.string.region_add_type), enabled = vm.customTypes().size < 50) { customLabel = ""; renamingType = null; showTypeForm = true }
                    if (kind.type == SegmentType.CUSTOM) Action(stringResource(R.string.region_rename_type)) {
                        customLabel = vm.customTypes().firstOrNull { it.id == kind.id }?.label.orEmpty(); renamingType = kind.id; showTypeForm = true
                    }
                }
                if (showTypeForm) {
                    Field(stringResource(R.string.region_type_name), customLabel, { customLabel = it.take(30) })
                    if (customLabel.isBlank()) Text(stringResource(R.string.region_type_required), color = Muted, fontSize = 13.sp)
                    val duplicate = renamingType != null && vm.customTypes().any { it.id != renamingType && it.label.equals(customLabel.trim(), true) }
                    if (duplicate) Text(stringResource(R.string.region_type_duplicate), color = Muted, fontSize = 13.sp)
                    Action(stringResource(R.string.region_create_type), enabled = customLabel.isNotBlank() && !duplicate) {
                        val type = vm.saveCustomType(customLabel, renamingType)
                        kind = RegionKind(SegmentType.CUSTOM, type.id); showTypeForm = false
                    }
                }
            }
            Action(stringResource(R.string.region_add), if (!editing) Modifier.focusRequester(addFocus) else Modifier) { select(null) }
            regions.forEach { region ->
                MenuRow(regionLabel(region, vm), value = "${clock(region.startMs)} – ${clock(region.endMs)}") { select(region) }
                if (region.source == SegmentSource.LOCAL) Action(stringResource(R.string.region_delete_local)) {
                    vm.deleteRegion(region.id)
                    if (selected?.id == region.id) { selected = null; editing = false }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Action(stringResource(R.string.save), enabled = editing && valid && !seekPending && !previewFailed && !showTypeForm) {
                vm.saveRegion(SkipSegment(kind.type, start, end, selected?.id ?: UUID.randomUUID().toString(), kind.id, action, SegmentSource.LOCAL), duration)
                close()
            }
            Action(stringResource(R.string.cancel), click = close)
        }
    }
}
