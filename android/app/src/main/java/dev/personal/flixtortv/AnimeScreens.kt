package dev.personal.flixtortv

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Text

@Composable internal fun animeVersionLabel(version: AnimeVersion) = stringResource(
    if (version == AnimeVersion.DUB) R.string.anime_dub else R.string.anime_sub)

@Composable internal fun episodeLabel(episode: Episode) = if (episode.providerId.isBlank())
    stringResource(R.string.episode, episode.number, episode.name)
    else stringResource(R.string.anime_episode, episode.displayNumber, episode.name)

@Composable internal fun ContentFailure(failure: Failure, vm: TvViewModel, modifier: Modifier = Modifier,
    retry: () -> Unit = vm::refresh) {
    if (vm.isAnime) Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.anime_source_failure), color = TextSecondary,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        Action(stringResource(R.string.retry), click = retry)
    } else FailureMessage(failure, retry, modifier)
}

/** Kept above the initiating screen/player so dismissal preserves its focus and state. */
@Composable internal fun AnimeVersionDialog(choice: AnimeChoice, vm: TvViewModel) {
    val focus = remember { FocusRequester() }
    val back = remember { FocusRequester() }
    val retryFocus = remember { FocusRequester() }
    val selected = AnimeVersion.entries.firstOrNull { it in choice.available }
    val height = LocalConfiguration.current.screenHeightDp.dp
    GlassDialog(stringResource(R.string.anime_choose_version), vm::cancelAnimeChoice,
        modifier = Modifier.padding(LocalSafePadding.current).widthIn(max = 520.dp).heightIn(max = height * .85f),
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
        actions = {
            GlassButton(stringResource(R.string.back), Modifier.focusRequester(back), onClick = vm::cancelAnimeChoice)
        }) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (choice.loading) Loading()
            else if (choice.failure != null || choice.available.isEmpty()) {
                Text(stringResource(R.string.anime_source_failure), color = TextSecondary)
                GlassButton(stringResource(R.string.retry), Modifier.focusRequester(retryFocus), onClick = vm::retryAnimeChoice)
            } else {
                choice.missing?.let {
                    Text(stringResource(R.string.anime_version_missing, animeVersionLabel(it)), color = TextSecondary)
                }
                AnimeVersion.entries.forEach { version ->
                    val available = version in choice.available
                    val label = animeVersionLabel(version)
                    MenuRow(if (available) label else stringResource(R.string.anime_version_unavailable, label),
                        modifier = if (version == selected) Modifier.focusRequester(focus) else Modifier,
                        enabled = available, chevron = false) { vm.chooseAnimeVersion(version) }
                }
            }
        }
        LaunchedEffect(choice.loading, choice.available, choice.failure) {
            withFrameNanos { }
            when {
                choice.loading -> back.requestFocus()
                choice.failure != null || selected == null -> retryFocus.requestFocus()
                else -> focus.requestFocus()
            }
        }
    }
}
