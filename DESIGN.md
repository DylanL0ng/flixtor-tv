---
name: Flixtor TV
description: Sky-inspired native Android TV browsing with cinema-led playback.
colors:
  midnight: "#06152E"
  panel: "#1B3457E6"
  accent: "#9CEBFF"
  muted: "#BDCEE7"
  foreground: "#F4F6FC"
  white: "#FFFFFF"
  black: "#000000"
typography:
  display:
    fontFamily: "Roboto, Android sans-serif"
    fontSize: "34sp"
    fontWeight: 700
  headline:
    fontFamily: "Roboto, Android sans-serif"
    fontSize: "30sp"
    fontWeight: 600
  title:
    fontFamily: "Roboto, Android sans-serif"
    fontSize: "24sp"
  shelf-title:
    fontFamily: "Roboto, Android sans-serif"
    fontSize: "22sp"
    fontWeight: 600
  body:
    fontFamily: "Roboto, Android sans-serif"
    fontSize: "16sp"
  action:
    fontFamily: "Roboto, Android sans-serif"
    fontSize: "15sp"
  label:
    fontFamily: "Roboto, Android sans-serif"
    fontSize: "14sp"
  metadata:
    fontFamily: "Roboto, Android sans-serif"
    fontSize: "13sp"
rounded:
  field: "8dp"
  poster: "10dp"
  container: "12dp"
  panel: "16dp"
spacing:
  compact: "8dp"
  control: "12dp"
  content: "14dp"
  group: "16dp"
  rail: "18dp"
  section: "20dp"
  roomy: "24dp"
  large: "28dp"
components:
  action:
    backgroundColor: "{colors.panel}"
    typography: "{typography.action}"
  action-focused:
    backgroundColor: "{colors.accent}"
    textColor: "{colors.midnight}"
    typography: "{typography.action}"
  field:
    backgroundColor: "{colors.panel}"
    textColor: "{colors.white}"
    rounded: "{rounded.field}"
    padding: "14dp"
  poster:
    backgroundColor: "{colors.panel}"
    textColor: "{colors.white}"
    rounded: "{rounded.poster}"
    width: "120dp"
  navigation:
    backgroundColor: "{colors.panel}"
    rounded: "{rounded.panel}"
    padding: "4dp 8dp"
  dialog:
    backgroundColor: "{colors.panel}"
    rounded: "{rounded.panel}"
    padding: "28dp"
  transport:
    backgroundColor: "transparent"
    textColor: "{colors.white}"
    size: "48dp"
    padding: "12dp"
  transport-focused:
    backgroundColor: "{colors.white}"
    textColor: "{colors.midnight}"
    size: "48dp"
    padding: "12dp"
---

# Design System: Flixtor TV

## Overview

**Creative North Star: "Sky shell, cinema stage"**

The native Android TV shell uses midnight blue, translucent navy panels and pale cyan focus. Artwork supplies the atmosphere; Roboto, restrained surfaces and generous remote targets keep browsing readable from a sofa. Compose for TV owns native button and clickable-surface behavior. This is a Kotlin/Compose system: frontmatter retains native `dp` and `sp` units.

Playback lets video lead. A compact Netflix/Disney-inspired bottom gradient carries title, timeline and transport; track selection moves into a separate side drawer. Household identity and service status remain visible through profiles, the navigation status label and the account block near the top of Settings.

**Key Characteristics:**
- Sky blue shell and pale cyan remote focus.
- Full-bleed artwork and video, adjustable safe interaction margins.
- Native TV typography, controls and focus restoration.
- Compact cinema controls with separate track selection and caption space.

Source of truth: `TvTheme.kt`, `MainActivity.kt`, `SettingsScreens.kt`, `PlaybackScreen.kt` and `ScreenFit.kt`. `PRODUCT.md` constrains the personal, remote-operated client; `.impeccable/player-direction.md` records the approved visual direction. The 720p and 1080p emulator review captures use fixtures and public sample media. They establish layout and interaction only, not live VIP access, physical ONN/Chromecast playback, or complete caption acceptance.

## Colors

Deep blue environment, luminous focus and cool secondary text. Frontmatter is normative; Compose ARGB panel color is expressed as CSS RGBA hex for portability.

### Primary
- **Pale Sky Cyan** (`accent`): focused shell actions, posters and timeline, identity and VIP account status; other navigation status uses muted text.

### Neutral
- **Midnight Blue** (`midnight`): shell background, artwork wash and dark ink on focused actions.
- **Translucent Navy** (`panel`): floating navigation, actions, fields, dialogs and fallback posters.
- **Cool Mist** (`muted`): synopsis, metadata, field labels and supporting account copy.
- **Soft White** (`foreground`): native theme foreground on background and surface.
- **White** (`white`): playback icons, timeline and explicitly styled field/poster content.
- **Black** (`black`): video background and playback contrast gradients.

**The Focus Has a Home Rule.** Shell focus uses pale cyan with midnight ink. Playback transport uses white with midnight ink; keep its focused timeline cyan.

## Typography

**Display Font:** Roboto through Android's native sans-serif convention.
**Body Font:** The same native family; no separate display or mono family.

**Character:** Familiar TV typography with larger headings and compact supporting labels. Native `sp` respects Android text scaling; do not translate it to fixed physical pixels in application code.

### Hierarchy
- **Display:** Bold artwork titles; the profile chooser uses a slightly larger bold heading (36sp).
- **Headline:** Settings and search entry headings; browse destinations use a smaller heading (28sp).
- **Title:** Dialog, season and settings-section headings.
- **Shelf title:** Semibold shelf labels, below display scale and above metadata.
- **Body:** Detail synopsis; hero supporting copy uses a slightly smaller size (15sp).
- **Action / Label:** Action text and navigation or input labels.
- **Metadata:** Time, account status and poster titles; poster year is smaller supporting metadata (11sp).

**The Title Once Rule.** Detail places the synopsis once below the artwork hero. Playback title and episode labels stay compact and ellipsize rather than becoming a second hero.

## Layout

**The Safe Interaction Rule.** Apply the device-wide horizontal and vertical screen-fit margins to interaction and text. Each defaults to 5% and is adjustable from 0–10%; compute against the current viewport. Artwork and video may extend to all screen edges. Keep dialogs inside the same safe padding.

Navigation floats across the available safe width. It is absent on profile selection, detail and playback. Home uses vertically scrolling shelves and horizontally scrolling poster rails. Posters use the recorded width; grids adapt to available width with the same minimum cell width and stable title keys. Browsing accumulates virtualized pages seamlessly rather than introducing a separate paginated screen.

Use the native spacing steps to separate labels, controls and sections. Shelf gaps are generous; rail gaps use the rail step, and grid rows use the section step. Forms constrain readable width, while Settings, profile lists and player track choices scroll vertically. No fixed desktop breakpoint is imposed on this TV system.

Playback controls align to the bottom inside safe padding. The settings drawer occupies one third of the safe content width and its full height; it scrolls vertically. Captions use a separate safe viewport, reserving space above visible controls or beside the drawer. Video remains full bleed behind both.

## Elevation & Depth

Depth comes from artwork, tonal layering and gradients, rather than a custom shadow scale. The shell darkens backdrop artwork with a midnight wash. Hero gradients separate text from art. Playback uses a transparent-to-black bottom gradient, with stronger opacity near transport. The track drawer is an almost opaque dark surface; these surface-specific colors are not extra global accents. Native Compose for TV components retain their library focus behavior.

**The Video Leads Rule.** Keep playback overlays localized. Continuous full-screen blur is outside the approved world; gradients and a discrete settings drawer provide contrast.

## Shapes

Fields have small soft corners; posters are slightly rounder; drawers and ordinary containers use the container radius; navigation and dialogs use the panel radius. Native TV buttons retain library shapes and focus treatment rather than a separately invented radius token. Fine borders describe navigation and field boundaries. Focus increases field border strength and changes its color to cyan.

## Components

### Buttons

Native and remote-first. Shell actions use the navy surface and a cyan-focused fill with midnight text. Actions and navigation have a minimum height (48dp). Labels allow two lines with ellipsis. Enabled state belongs to the native component. Preserve focus requesters and restore the initiating action after closing an overlay.

### Cards / Containers

Artwork-first posters use cropped 2:3 artwork, the poster radius and native clickable-surface focus scaling. Text fallback remains readable when artwork is missing. Poster titles ellipsize to one line below artwork; year is secondary. Returning from detail restores the originating shelf and title, or the independent hero target.

### Inputs / Fields

Labeled native `BasicTextField` surfaces use the field radius, navy fill, cyan cursor and internal content padding. The resting border is muted; focus uses a thicker cyan border. Text entry delegates to Android IME. Passwords remain masked. Form labels stay visible independently of field contents.

### Navigation

The translucent rounded rail combines brand and destination actions. A compact account status label sits below it, aligned to the right. Active destinations have a navy fill; D-pad focus is cyan. Entering a navigation destination focuses its active tab, except when restoring a content origin or returning from screen-fit adjustment.

### Profiles and Account

The chooser pairs distinct illustrated avatars with native name actions in a scrolling row. Up to seven profiles share one device service login; personal watchlist, progress and maximum quality remain profile-specific. Settings surfaces service status and recovery actions near the top. Do not imply separate service credentials for each avatar.

### Cinema Player

Media3 supplies playback and tracks; `PlayerView`'s controller is disabled. Compose supplies the bottom controls: compact title, optional episode label, focusable seek timeline, elapsed/remaining time and icon transport. Icons are drawn shapes, not text glyphs. Transport uses the recorded square target and switches from transparent/white to white/midnight on focus. Timeline focus thickens the track and exposes a cyan thumb; a seek preview must be committed or cancelled.

Vertical drawer choices cover quality, audio and subtitles, with a Back action at entry. Closing the drawer returns focus to Settings. Back cancels an active seek preview, otherwise closes settings, hides controls, then exits playback. Captions use Android subtitle styling in their dedicated viewport. Control visibility and caption positioning must remain coordinated.

### Dialogs

Downloaded updates present Install and Later. The update dialog disables Android's platform default width, constrains content to a maximum width (520dp), applies safe padding and the dialog surface, and scrolls its content. Install receives initial focus. Profile deletion uses the same surface language and initially focuses Cancel. Preserve legible action labels; label wrapping defects are not a new component style.

## Do's and Don'ts

### Do:
- **Do** use native dp/sp values and Compose for TV focus semantics.
- **Do** keep interactive content within the current device screen-fit margins.
- **Do** retain full-bleed video and artwork with local contrast layers.
- **Do** restore the initiating content or control focus after navigation and overlays.
- **Do** give captions a safe viewport separate from player controls.
- **Do** distinguish service restrictions from structural client failures with visible recovery actions.

### Don't:
- **Don't** reintroduce the default Media3 controller alongside custom controls.
- **Don't** spread the playback gradient or blur over the entire screen as permanent chrome.
- **Don't** replace native focus behavior with pointer-only hover feedback.
- **Don't** treat emulator fixtures as proof of live VIP, caption or physical-device acceptance.
- **Don't** turn clipped or cramped labels into documented component conventions.
