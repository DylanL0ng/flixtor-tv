---
name: FlixTor TV — Liquid Cinema
description: Remote-first SolidTV interface with cinematic artwork, restrained glass controls, and reliable focus.
colors:
  ground: "#080C14"
  panel: "#18212ECC"
  panelSolid: "#151D2B"
  accent: "#A9E8FF"
  foreground: "#F5F8FF"
  muted: "#B9C7D9"
  danger: "#FF8891"
typography:
  family: "Bundled Roboto"
  logicalCanvas: "1920 × 1080"
  display: "64–80 logical px"
  section: "34–40 logical px"
  action: "28–32 logical px"
  supporting: "22–26 logical px"
spacing:
  rhythm: 8
  posterRadius: "16–24 logical px"
  dialogRadius: "28–36 logical px"
  minimumActionHeight: "64 logical px"
---

# Design System: Liquid Cinema

## Runtime

Android's `SolidTvActivity` hosts the SolidTV canvas app in a WebView. The SolidTV renderer draws application UI; native Media3 draws video and captions outside that canvas. CSS blur and scene-sampling blur are not assumed. Verify renderer shader support before adding any advanced material effect. `src/ui/theme.ts`, `src/ui/components.tsx`, and `src/ui/artwork.tsx` define the current tokens and shared treatments.

The canvas uses 1920 × 1080 logical coordinates and scales to output. All interactive destinations must remain usable at 1280 × 720 and within the configured zero-to-ten-percent screen-fit margins. Browser mode also supports physical keyboard and pointer input.

## Visual hierarchy

- **Stage:** near-black cool ground, full-bleed supplied artwork, and localized dark scrims behind text. Posters and episode imagery stay crisp and opaque.
- **Floating controls:** translucent neutral tint, fine bright upper edge, soft shadow, and restrained focus response. Use for navigation, actions, and transport.
- **Task surfaces:** denser smoked glass for dialogs, drawers, and on-screen keyboards. Keep text readable over bright imagery.

Artwork carries atmosphere; glass is reserved for functional controls. Keep the title, metadata, and primary Play/Resume action legible and clearly ranked. Do not invent trailers, ratings, cast details, recommendations, or image assets unavailable from current data.

## Type and geometry

Use bundled Roboto, with deliberate weight and size contrast. Values above are starting values for the logical canvas and require viewing-distance review. Keep a regular eight-unit spacing rhythm, generous rail gaps, and capsules for primary actions and compact navigation. Focus scale and lift must not change layout bounds or clip at screen edges.

## Focus and interaction

Every screen has a visible initial target, deterministic D-pad neighbors, a return target, and a fallback if its selected item disappears. Focus, selected, pressed, disabled, and pending states stay distinct. Enter activates once; Back dismisses the top overlay before unwinding the screen. Modal scope disables background controls in both canvas and semantic DOM. Closing an overlay restores its initiating focus where that control still exists.

Search separates the text field, on-screen keyboard, and result region. Physical typing, caret movement, paste, and IME composition remain available. Directional movement stays within a key row until its edge; crossing into results and returning preserves the previous key. PIN entry is a separate four-digit keypad flow and never shares numeric/time field limits.

## Motion and accessibility

Focus responds immediately and settles quickly; presses compress briefly while activation runs immediately. Artwork resolves only after a short dwell and crossfades after decode. Dialogs and drawers enter with restrained opacity and scale/slide. Avoid autoplaying carousels, idle shimmer, and motion that owns input timing.

Honor reduced-motion and reduced-transparency preferences during a session. Reduced motion removes scale, parallax, sliding, and morphing while preserving instant focus contrast and status feedback. Named icon controls, semantic status updates, visible keyboard focus, and actionable validation are required.

## Performance and acceptance

Treat 60 fps at 1080p on target TVs as a goal to measure. Prioritize input responsiveness over material cost. Virtualize long grids/rails, bound artwork caches, cancel obsolete artwork and search work, and keep pointer/semantic hit targets aligned with canvas transforms. Test browser fixtures and physical Android TV separately; fixtures establish interaction and layout, not live VIP access or native-video performance.
