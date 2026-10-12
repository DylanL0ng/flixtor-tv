package dev.personal.flixtortv

import androidx.compose.ui.graphics.Color

/** FLIXTOR UI chrome tokens. Artwork and video remain the primary color source. */
// Deep indigo-navy ground — matches the reference screenshot
internal val AppBackground = Color(0xFF060C1A)
internal val RaisedBackground = Color(0xFF0E1729)
// Nav sidebar tinted glass — deep purple-navy per reference
internal val NavSurface = Color(0xE8101830)
internal val GlassDark = Color(0xE6101B30)
internal val GlassMedium = Color(0x80182540)
internal val GlassLight = Color(0x3D4A6285)
internal val GlassBorder = Color(0x28C8DEFF)
internal val GlassBorderStrong = Color(0x75B8E0FF)
internal val TextPrimary = Color(0xFFF4F6FC)
internal val TextSecondary = Color(0xFFB8C8DC)
internal val TextMuted = Color(0xFF7A8CA8)
// Vivid cyan-blue accent — matches the "Play" button highlight in the reference
internal val Accent = Color(0xFF5B9CFF)
internal val AccentSoft = Color(0x305B9CFF)
// "NEW" badge purple
internal val BadgePurple = Color(0xFF7B5CF0)
internal val BadgePurpleText = Color(0xFFE8D8FF)
internal val Divider = Color(0x1ADAE8FF)
internal val ScrimDark = Color(0xCC030814)
internal val Danger = Color(0xFFF28B8B)

// Keep the existing app theme call sites source-compatible during screen migration.
internal val Midnight = AppBackground
internal val Panel = Color(0xF50E1729)
internal val Muted = TextSecondary

internal val RadiusSmall = 10
internal val RadiusMedium = 14
internal val RadiusLarge = 20
