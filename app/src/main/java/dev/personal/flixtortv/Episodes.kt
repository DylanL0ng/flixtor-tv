package dev.personal.flixtortv

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text

/**
 * Episode rows are the single compact representation of an episode: number, title, a
 * progress track and the remaining or watched state. The detail screen and the player
 * drawer share it so watched progress is visible in both places.
 */
@Composable internal fun EpisodeRow(episode: Episode, viewing: Viewing?, current: Boolean, modifier: Modifier = Modifier,
    enabled: Boolean = true, click: () -> Unit) {
    val state = episodeState(viewing)
    val label = stringResource(R.string.episode, episode.number, episode.name)
    val description = listOf(label, if (current) stringResource(R.string.now_playing) else "", state.label)
        .filter(String::isNotBlank).joinToString(" · ")
    FocusFrame(
        modifier = modifier.fillMaxWidth().semantics {
            contentDescription = description
            progressBarRangeInfo = ProgressBarRangeInfo(state.fraction, 0f..1f)
        },
        radius = RadiusMedium.dp,
        enabled = enabled,
        selected = current,
        restingFill = GlassDark,
        contentDescription = description,
        onClick = click,
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 72.dp)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                // Episode number badge
                Box(
                    Modifier
                        .size(40.dp)
                        .background(
                            if (current) Accent.copy(alpha = .18f) else GlassMedium,
                            RoundedCornerShape(8.dp),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        episode.number.toString(),
                        color = if (current) Accent else TextSecondary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
                // Title + state label
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        episode.name.ifBlank { label },
                        color = if (state.finished) TextSecondary else TextPrimary,
                        fontSize = 16.sp,
                        fontWeight = if (current) FontWeight.SemiBold else FontWeight.Medium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (state.label.isNotBlank()) {
                        Text(state.label, color = if (state.finished) TextMuted else Accent.copy(alpha = .85f),
                            fontSize = 13.sp)
                    }
                }
                // Now playing indicator
                if (current) {
                    Box(
                        Modifier
                            .background(Accent.copy(alpha = .14f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(stringResource(R.string.now_playing), color = Accent, fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            // Progress track — full-width bottom edge of the card
            if (state.fraction > 0f || state.finished) {
                ProgressTrack(state.fraction, state.finished)
            }
        }
    }
}

/** The watched fraction plus the short label shown beside it. */
internal data class EpisodeProgress(val fraction: Float, val finished: Boolean, val label: String)

@Composable internal fun episodeState(viewing: Viewing?): EpisodeProgress {
    val unseen = stringResource(R.string.episode_not_watched)
    val watched = stringResource(R.string.episode_watched)
    if (viewing == null || viewing.position <= 0) return EpisodeProgress(0f, false, unseen)
    if (viewing.finished) return EpisodeProgress(1f, true, watched)
    val minutes = ((viewing.duration - viewing.position).coerceAtLeast(0) / 60000).toInt()
    return EpisodeProgress(viewing.fraction, false, stringResource(R.string.episode_remaining, minutes))
}

/** Full-width progress bar shown at the bottom of an episode card. */
@Composable internal fun ProgressTrack(fraction: Float, finished: Boolean, modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxWidth().height(3.dp)) {
        val y = size.height / 2
        drawLine(TextPrimary.copy(alpha = 0.15f), Offset(0f, y), Offset(size.width, y), size.height)
        if (fraction > 0f) drawLine(
            if (finished) TextPrimary.copy(alpha = 0.7f) else Accent,
            Offset(0f, y), Offset(size.width * fraction.coerceIn(0f, 1f), y), size.height,
        )
    }
}

/** Season switcher — horizontal chip strip with Accent highlight on active season. */
@Composable internal fun SeasonPicker(seasons: List<Int>, selected: Int, modifier: Modifier = Modifier, select: (Int) -> Unit) {
    if (seasons.size < 2) return
    LazyRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp)) {
        items(seasons, key = { it }) { season ->
            val isSelected = season == selected
            val label = stringResource(R.string.season, season)
            FocusFrame(
                modifier = Modifier.heightIn(min = 44.dp),
                selected = isSelected,
                restingFill = if (isSelected) Accent.copy(alpha = .18f) else GlassDark,
                contentDescription = if (isSelected) stringResource(R.string.selected_option, label) else label,
                onClick = { select(season) },
            ) {
                Text(
                    label,
                    color = if (isSelected) Accent else TextSecondary,
                    fontSize = 15.sp,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                )
            }
        }
    }
}

/** The season holding the first unfinished episode, so the list opens where viewing stopped. */
internal fun startSeason(viewing: List<Viewing>, title: Title): Int? =
    viewing.firstOrNull { it.title.key == title.key && it.episode != null && !it.finished }?.episode?.season
