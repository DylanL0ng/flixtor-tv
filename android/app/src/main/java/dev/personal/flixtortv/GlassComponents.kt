package dev.personal.flixtortv

import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.tv.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay

internal enum class GlassButtonVariant { PRIMARY, SECONDARY, ICON, DESTRUCTIVE }

/** Quiet at rest, with independent focus and radio-selection cues. */
@Composable
internal fun MenuRow(
    label: String,
    modifier: Modifier = Modifier,
    value: String? = null,
    enabled: Boolean = true,
    selected: Boolean? = null,
    chevron: Boolean = selected == null,
    leading: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(RadiusSmall.dp)
    Row(
        modifier.heightIn(min = 56.dp).fillMaxWidth().clip(shape)
            .background(if (focused) GlassLight else Color.Transparent)
            .border(if (focused) 2.dp else 0.dp, if (focused) GlassBorderStrong else Color.Transparent, shape)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled,
                role = if (selected == null) Role.Button else Role.RadioButton, onClick = onClick)
            .semantics(mergeDescendants = true) {
                contentDescription = if (value.isNullOrBlank()) label else "$label · $value"
                if (selected != null) this.selected = selected
            }
            .graphicsLayer { alpha = if (enabled) 1f else .58f }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading?.invoke()
        Text(label, color = TextPrimary, fontSize = 17.sp, modifier = Modifier.weight(1f),
            maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (!value.isNullOrBlank()) Text(value, color = TextSecondary, fontSize = 15.sp,
            modifier = Modifier.widthIn(max = 180.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
        Canvas(Modifier.size(20.dp)) {
            val color = if (selected == true) Accent else TextSecondary
            val stroke = 2.dp.toPx()
            if (selected == true) {
                drawLine(color, Offset(size.width * .15f, size.height * .5f), Offset(size.width * .4f, size.height * .75f), stroke)
                drawLine(color, Offset(size.width * .4f, size.height * .75f), Offset(size.width * .85f, size.height * .25f), stroke)
            } else if (chevron) {
                drawLine(color, Offset(size.width * .35f, size.height * .2f), Offset(size.width * .65f, size.height * .5f), stroke)
                drawLine(color, Offset(size.width * .65f, size.height * .5f), Offset(size.width * .35f, size.height * .8f), stroke)
            }
        }
    }
}

/** Tinted glass over optional artwork. Compose blur blurs the artwork layer (not arbitrary siblings). */
@Composable
internal fun GlassSurface(
    modifier: Modifier = Modifier,
    radius: Dp = RadiusLarge.dp,
    artworkBehind: Painter? = null,
    fillHeight: Boolean = false,
    contentPadding: Dp = 24.dp,
    surfaceColor: Color? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(radius)
    Box(modifier.clip(shape)) {
        if (artworkBehind != null) {
            Image(
                painter = artworkBehind,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize().then(if (Build.VERSION.SDK_INT >= 31) Modifier.blur(24.dp) else Modifier),
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (fillHeight) Modifier.fillMaxHeight() else Modifier.wrapContentHeight())
                .background(surfaceColor ?: if (Build.VERSION.SDK_INT >= 31) GlassDark else RaisedBackground.copy(alpha = .97f))
                .border(1.dp, GlassBorder, shape)
                .padding(contentPadding),
            content = content,
        )
    }
}

/** One focus treatment for remote, keyboard and pointer focus: crisp edge, light fill, restrained scale. */
@Composable
internal fun FocusFrame(
    modifier: Modifier = Modifier,
    radius: Dp = RadiusMedium.dp,
    enabled: Boolean = true,
    selected: Boolean = false,
    restingFill: Color = Color.Transparent,
    visualHeight: Dp? = null,
    contentDescription: String? = null,
    onClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val pressed by interaction.collectIsPressedAsState()
    val fill by animateColorAsState(
        when {
            focused -> GlassLight
            selected -> AccentSoft
            else -> restingFill
        }, label = "focusFill",
    )
    val scale by animateFloatAsState(if (focused) 1.025f else 1f, label = "focusScale")
    val shape = RoundedCornerShape(radius)
    val actionModifier = if (onClick != null) {
        Modifier.clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
    } else {
        Modifier.focusable(enabled = enabled, interactionSource = interaction)
    }
    val visualModifier = if (visualHeight == null) {
        Modifier.clip(shape).background(fill).border(
            when { focused -> 2.dp; selected -> 1.5.dp; else -> 1.dp },
            when { focused -> GlassBorderStrong; selected -> Accent.copy(alpha = .68f); else -> GlassBorder }, shape,
        )
    } else {
        Modifier.drawBehind {
            val height = visualHeight.toPx().coerceAtMost(size.height)
            val top = (size.height - height) / 2f
            val corner = androidx.compose.ui.geometry.CornerRadius(radius.toPx())
            val rectSize = androidx.compose.ui.geometry.Size(size.width, height)
            val rectTop = androidx.compose.ui.geometry.Offset(0f, top)
            drawRoundRect(fill, rectTop, rectSize, corner)
            val stroke = when { focused -> 2.dp; selected -> 1.5.dp; else -> 1.dp }.toPx()
            drawRoundRect(when { focused -> GlassBorderStrong; selected -> Accent.copy(alpha = .68f); else -> GlassBorder },
                rectTop, rectSize, corner, style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
        }
    }
    Box(
        modifier = modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .then(visualModifier)
            .then(actionModifier)
            .then(if (contentDescription != null) Modifier.semantics { this.contentDescription = contentDescription } else Modifier)
            .graphicsLayer { alpha = if (!enabled) .58f else if (pressed) .90f else 1f },
        content = content,
    )
}

@Composable
internal fun GlassButton(
    label: String,
    modifier: Modifier = Modifier,
    variant: GlassButtonVariant = GlassButtonVariant.SECONDARY,
    enabled: Boolean = true,
    contentDescription: String = label,
    icon: (@Composable () -> Unit)? = null,
    compact: Boolean = false,
    onClick: () -> Unit,
) {
    val fill = when (variant) {
        GlassButtonVariant.PRIMARY -> GlassLight
        GlassButtonVariant.SECONDARY, GlassButtonVariant.ICON -> GlassMedium
        GlassButtonVariant.DESTRUCTIVE -> Danger.copy(alpha = .12f)
    }
    val labelColor = if (variant == GlassButtonVariant.DESTRUCTIVE) Danger else TextPrimary
    FocusFrame(
        modifier = modifier.heightIn(min = if (compact) 38.dp else 56.dp),
        radius = RadiusMedium.dp,
        enabled = enabled,
        restingFill = fill,
        contentDescription = contentDescription,
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = if (compact) 16.dp else if (variant == GlassButtonVariant.ICON) 16.dp else 24.dp,
                    vertical = if (compact) 6.dp else 14.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Box(Modifier.size(if (compact) 18.dp else 24.dp), contentAlignment = Alignment.Center, content = { icon() })
                if (label.isNotBlank()) Spacer(Modifier.width(if (compact) 8.dp else 12.dp))
            }
            if (label.isNotBlank()) Text(label, color = labelColor, fontSize = if (compact) 14.sp else 18.sp,
                fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
internal fun MediaPoster(
    title: String,
    painter: Painter?,
    modifier: Modifier = Modifier,
    contentDescription: String = title,
    aspectRatio: Float = 2f / 3f,
    selected: Boolean = false,
    // When true a bold title is drawn over the lower portion of the poster artwork,
    // matching the reference image's text-on-card style for the featured shelf.
    titleOverlay: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    FocusFrame(
        modifier = modifier.aspectRatio(aspectRatio),
        radius = RadiusSmall.dp,
        selected = selected,
        contentDescription = contentDescription,
        onClick = onClick,
    ) {
        if (painter != null) {
            Image(painter, contentDescription, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Box(Modifier.fillMaxSize().background(RaisedBackground), contentAlignment = Alignment.Center) {
                if (!titleOverlay) Text(title, color = TextSecondary, fontSize = 16.sp, maxLines = 3,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(12.dp))
            }
        }
        // Bold lower-third title overlay (selected poster in the reference shows this)
        if (titleOverlay) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomStart)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(.82f))))
                    .padding(horizontal = 10.dp, vertical = 10.dp),
            ) {
                Text(
                    title,
                    color = TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    lineHeight = 18.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
internal fun <T> ContentRail(
    title: String,
    items: List<T>,
    itemKey: (T) -> Any,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
    itemContent: @Composable LazyItemScope.(T) -> Unit,
) {
    Column(modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(bottom = 10.dp),
        ) {
            Text(title, color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text("›", color = TextPrimary, fontSize = 20.sp, lineHeight = 20.sp,
                fontWeight = FontWeight.Bold)
        }
        LazyRow(contentPadding = contentPadding, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(items = items, key = itemKey, itemContent = itemContent)
        }
    }
}

@Composable
internal fun HeroBanner(
    title: String,
    background: Painter?,
    metadata: List<String>,
    description: String,
    modifier: Modifier = Modifier,
    contentOffsetY: Dp = 0.dp,
    isNew: Boolean = false,
    ageRating: String = "",
    actions: @Composable RowScope.() -> Unit,
) {
    androidx.compose.foundation.layout.BoxWithConstraints(modifier) {
        val compact = maxWidth < 700.dp
        // Render the background image if explicitly provided (e.g. detail screen with backdrop URL)
        if (background != null) {
            Image(background, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        // Left scrim — text readable, right half of frame shows through
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(
            listOf(ScrimDark.copy(alpha = .97f), ScrimDark.copy(alpha = .80f),
                ScrimDark.copy(alpha = .40f), Color.Transparent))))
        // Bottom bleed — image colours dissolve into AppBackground so shelves sit naturally below
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
            0f to Color.Transparent,
            0.55f to Color.Transparent,
            0.82f to AppBackground.copy(alpha = .72f),
            1f to AppBackground,
        )))
        Column(
            Modifier.align(Alignment.CenterStart)
                .offset(y = if (compact) 0.dp else contentOffsetY)
                .widthIn(max = maxWidth * if (compact) .60f else .50f)
                .padding(top = if (compact) 8.dp else 20.dp, bottom = if (compact) 8.dp else 24.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            // "NEW" badge pill
            if (isNew) {
                Box(
                    Modifier
                        .padding(bottom = if (compact) 6.dp else 12.dp)
                        .background(BadgePurple, RoundedCornerShape(20.dp))
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text("NEW", color = BadgePurpleText, fontSize = 11.sp,
                        fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
                }
            }
            // Large display title
            Text(
                title,
                color = TextPrimary,
                fontSize = if (compact) 34.sp else 72.sp,
                lineHeight = if (compact) 40.sp else 78.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            // Metadata: year · genre · Rating X.X · [18+] pill
            MetadataRow(
                items = metadata,
                ageRating = ageRating.takeIf { it.isNotBlank() },
                modifier = Modifier.padding(top = if (compact) 4.dp else 8.dp),
            )
            // Synopsis
            if (description.isNotBlank()) {
                Text(
                    description,
                    color = TextSecondary,
                    fontSize = if (compact) 12.sp else 14.sp,
                    lineHeight = if (compact) 17.sp else 20.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = if (compact) 8.dp else 12.dp),
                )
            }
            // Action buttons
            Row(
                horizontalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 10.dp),
                modifier = Modifier.padding(top = if (compact) 10.dp else 16.dp),
                content = actions,
            )
        }
    }
}

@Composable
internal fun MetadataRow(modifier: Modifier = Modifier, items: List<String>, ageRating: String? = null) {
    val values = items.filter(String::isNotBlank)
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        values.forEachIndexed { index, value ->
            if (index > 0) Text("·", color = TextMuted, fontSize = 16.sp, lineHeight = 16.sp)
            Text(value, color = TextSecondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        // Age-rating pill: a small outlined box e.g. [18+]
        if (!ageRating.isNullOrBlank()) {
            Box(
                Modifier
                    .border(1.dp, TextMuted.copy(alpha = .7f), RoundedCornerShape(3.dp))
                    .padding(horizontal = 5.dp, vertical = 1.dp)
            ) {
                Text(ageRating, color = TextSecondary, fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold, lineHeight = 14.sp)
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun GlassDrawer(
    onDismiss: () -> Unit,
    width: Dp,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = .48f))
            .focusProperties { canFocus = false }.clickable(onClick = onDismiss))
        GlassSurface(modifier = modifier.align(Alignment.CenterEnd).fillMaxHeight().width(width)
            .focusProperties { exit = { FocusRequester.Cancel } }.focusGroup()
            .pointerInput(Unit) { detectTapGestures(onTap = {}) },
            fillHeight = true, contentPadding = 16.dp) {
            content()
        }
    }
}

@Composable
internal fun GlassDialog(
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    properties: DialogProperties = DialogProperties(usePlatformDefaultWidth = false),
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = properties) {
        GlassSurface(modifier = modifier.widthIn(max = 640.dp).fillMaxWidth()) {
            Text(title, color = TextPrimary, fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold)
            Column(Modifier.weight(1f, fill = false).padding(top = 20.dp), content = content)
            Row(Modifier.fillMaxWidth().padding(top = 24.dp), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically, content = actions)
        }
    }
}

@Composable
internal fun LoadingSkeleton(modifier: Modifier = Modifier, radius: Dp = RadiusSmall.dp) {
    Box(modifier.clip(RoundedCornerShape(radius)).background(GlassMedium))
}

@Composable
internal fun Toast(message: String?, onDismiss: () -> Unit, durationMillis: Long = 2600L) {
    if (message.isNullOrBlank()) return
    LaunchedEffect(message) { delay(durationMillis); onDismiss() }
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.TopCenter) {
        GlassSurface(modifier = Modifier.widthIn(max = 720.dp).semantics { liveRegion = LiveRegionMode.Polite }) {
            Text(message, color = TextPrimary, fontSize = 18.sp)
        }
    }
}
