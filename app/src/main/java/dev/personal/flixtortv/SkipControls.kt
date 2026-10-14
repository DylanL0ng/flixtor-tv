package dev.personal.flixtortv

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text

internal data class RegionKind(val type: SegmentType, val id: String = type.read)

@Composable internal fun bufferChoices() = listOf(MenuChoice(false, stringResource(R.string.buffering_normal), "normal"),
    MenuChoice(true, stringResource(R.string.buffering_larger), "larger"))

@Composable internal fun typeLabel(type: SegmentType): String = stringResource(when (type) {
    SegmentType.INTRO -> R.string.region_intro
    SegmentType.RECAP -> R.string.region_recap
    SegmentType.CREDITS -> R.string.region_credits
    SegmentType.POST_CREDITS -> R.string.region_post_credits
    SegmentType.MIXED_INTRO -> R.string.region_mixed_intro
    SegmentType.MIXED_CREDITS -> R.string.region_mixed_credits
    SegmentType.CUSTOM -> R.string.region_custom
})
@Composable internal fun regionKinds(vm: TvViewModel): List<MenuChoice<RegionKind>> =
    SegmentType.entries.filter { it != SegmentType.CUSTOM }.map { MenuChoice(RegionKind(it), typeLabel(it), it.read) } +
        vm.customTypes().map { MenuChoice(RegionKind(SegmentType.CUSTOM, it.id), it.label, it.id) }

@Composable internal fun actionChoices(inherit: Boolean = false): List<MenuChoice<SkipAction?>> =
    (if (inherit) listOf(MenuChoice<SkipAction?>(null, stringResource(R.string.region_inherit), "inherit")) else emptyList()) +
        listOf(MenuChoice<SkipAction?>(SkipAction.ASK, stringResource(R.string.region_ask), "ask"),
            MenuChoice<SkipAction?>(SkipAction.AUTO, stringResource(R.string.region_auto), "auto"),
            MenuChoice<SkipAction?>(SkipAction.OFF, stringResource(R.string.region_off), "off"))

@Composable internal fun regionLabel(region: SkipSegment, vm: TvViewModel): String =
    if (region.type == SegmentType.CUSTOM) stringResource(R.string.skip_custom,
        vm.customTypes().firstOrNull { it.id == region.typeId }?.label ?: typeLabel(SegmentType.CUSTOM))
    else segmentLabel(region.type)

@Composable internal fun SkipPreferences(vm: TvViewModel, showAvailable: Boolean, revision: Int) {
    var show by remember { mutableStateOf(false) }
    val scopes = listOf(MenuChoice(false, stringResource(R.string.region_profile_defaults), "profile"),
        MenuChoice(true, stringResource(R.string.region_show_overrides), "show"))
    if (showAvailable) OptionPicker(stringResource(R.string.region_preferences), scopes, show) { show = it }
    else Text(stringResource(R.string.region_profile_defaults), color = TextPrimary)
    val kinds = regionKinds(vm)
    val options = actionChoices(show)
    // Revision makes persisted preference changes observable without copying server ranges.
    kinds.forEach { kind ->
        val selected = remember(revision, show, kind.value.id) { vm.actionPreference(kind.value.id, show) ?: if (show) null else SkipAction.ASK }
        OptionPicker(kind.label, options, selected) { vm.setActionPreference(kind.value.id, it, show) }
    }
}

@Composable internal fun AnimeMappingFields(vm: TvViewModel, identity: String, revision: Int) {
    val mapping = vm.animeMapping()
    var mal by remember(identity, revision, mapping?.malId) { mutableStateOf(mapping?.malId?.toString().orEmpty()) }
    var number by remember(identity, revision, mapping?.episode) { mutableStateOf(mapping?.episode?.let {
        if (it % 1.0 == 0.0) it.toLong().toString() else it.toString()
    }.orEmpty()) }
    val valid = mal.toIntOrNull()?.let { it > 0 } == true && number.toDoubleOrNull()?.let { it.isFinite() && it > 0 } == true
    Field(stringResource(R.string.anime_mal_id), mal, { mal = it.take(10) }, keyboard = KeyboardType.Number)
    Field(stringResource(R.string.anime_timestamp_episode), number, { number = it.take(12) }, keyboard = KeyboardType.Decimal)
    Text(stringResource(R.string.anime_mapping_help), color = Muted, fontSize = 13.sp)
    if (!valid) Text(stringResource(R.string.anime_mapping_invalid), color = Muted, fontSize = 13.sp)
    Action(stringResource(R.string.anime_mapping_apply), enabled = valid) { vm.setAnimeMapping(mal, number) }
    Action(stringResource(R.string.anime_mapping_reset)) { mal = ""; number = ""; vm.setAnimeMapping("", "") }
}

@Composable internal fun SkipSettings(state: TvState, vm: TvViewModel, canEdit: Boolean, edit: (SkipSegment?) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(if (vm.isAnime) R.string.anime_skip_help else R.string.skip_segments_help), color = Muted, fontSize = 13.sp)
        if (vm.isAnime) AnimeMappingFields(vm, state.playbackIdentity.toString(), state.skipRevision)
        else Field(stringResource(R.string.imdb_id), vm.imdbId().orEmpty(), { vm.setImdbId(it.take(12)) })
        if (state.segmentsLoading) Text(stringResource(R.string.loading), color = Muted)
        else if (state.segmentsUnavailable) {
            Text(stringResource(R.string.regions_lookup_failed), color = Muted)
            Action(stringResource(R.string.retry)) { vm.loadSegments() }
        } else if (state.segments?.none == true) Text(stringResource(R.string.no_segments), color = Muted)
        Action(stringResource(R.string.edit_regions), enabled = canEdit) { edit(null) }
        state.segments?.items.orEmpty().forEach { region ->
            Text(regionLabel(region, vm), color = TextPrimary)
            Text("${clock(region.startMs)} – ${clock(region.endMs)}", color = Muted, fontSize = 13.sp)
            Action(stringResource(R.string.region_edit), enabled = canEdit) { edit(region) }
            if (region.source == SegmentSource.LOCAL) Action(stringResource(R.string.region_delete_local)) { vm.deleteRegion(region.id) }
        }
        SkipPreferences(vm, true, state.skipRevision)
        if (!vm.isAnime) {
            Text(stringResource(if (vm.canSubmit) R.string.introdb_key_stored else R.string.introdb_key_unavailable), color = Muted, fontSize = 13.sp)
            Action(stringResource(R.string.segment_submit), enabled = vm.markedSegments().any { it.type.contributable } && vm.canSubmit) { vm.submitSegments() }
        }
    }
}
