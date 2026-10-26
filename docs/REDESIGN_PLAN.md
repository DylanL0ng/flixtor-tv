# FlixTor TV — Liquid Cinema redesign plan

Status: implemented in the current working tree. TypeScript, browser, and visual QA pass; Android verification awaits an available Java runtime.
Prepared: 26 October 2026.

## Outcome and assumptions

Make choosing and watching a title feel like a premium streaming service: cinematic imagery, clear hierarchy, tactile focus, fluid transitions, and reliable remote interaction. Carry the iOS Liquid Glass material language into a television interface, with layouts and navigation designed for a sofa and D-pad.

Primary target: the current SolidTV application in `src/`, shipped through Android's `SolidTvActivity`. Browser development must support physical keyboards and pointers. Preserve the existing service adapters, native Media3 playback, profile libraries, account rules, anime versions, updater, and skip-region behavior. This is a visual and interaction redesign, not a framework migration.

Interpret “keypad” as both the on-screen search keyboard and the four-digit profile PIN pad. Include numeric/time entry used by settings and skip-region editing. Scope includes Home, catalogues, search, detail, profiles, account/pairing, settings, player chrome, drawers, dialogs, and all loading/error states.

Primary success path: choose profile → resume or discover a title → inspect → play → return to exactly the same browsing position. The experience should reward each action without delaying it. Mode: Experience for discovery and playback; Operate for search, settings, and editing.

## Current evidence

Source inspected and fixture screens viewed at 1920 × 1080. Fixture artwork is absent by design; those captures establish layout and focus behavior, not live artwork quality or physical-TV performance.

- `src/app/App.tsx`: a permanent 250-unit sidebar occupies the screen, including detail. All non-player destinations share the same shell geometry. Navigation and ordinary actions use the same button treatment.
- `src/features/catalog/Catalog.tsx`: Home presents one selected shelf and five cards at a time, with shelf-selector buttons and “More titles” pagination. The hero is text, with metadata fading on focus changes. Home, search, and category browsing share almost the same composition.
- `src/ui/components.tsx`: poster imagery occupies 260 × 285 units, rather than a portrait 2:3 frame. Large filled title footers compete with the art. Focus combines cyan fill and enlargement. Browser semantic hit targets are repositioned on 100 ms intervals.
- `src/features/catalog/Detail.tsx`: text and menu rows dominate; the available `Title.backdrop` is not rendered. Series lack a prominent unified resume action.
- `src/features/profiles/Profiles.tsx`: profiles are text menu rows rather than visible identities. PIN entry uses generic secret text input.
- `src/features/playback/Player.tsx`: transport is a wide row of text buttons; dialogs and drawers repeat surface construction. Some entrance transitions mount at their final value, so a declared transition alone does not prove an entrance animation.
- `src/platform/browser.ts`, `test/fixture-platform.ts`, and `SolidTvActivity.kt`: input differs between browser HTML dialog, fixture `window.prompt`, and Android `AlertDialog`/IME. The redesigned experience needs one input contract with platform-specific text editing where necessary.
- `src/main.tsx`: a 1920 × 1080 logical canvas targets 60 fps, with a 64 MiB critical texture threshold. Reduced motion is read at startup. These are implementation settings, not evidence of sustained performance.
- Fixture captures show square-looking controls despite radius props in source. Verify renderer/backend support and shader registration before assuming a radius or glass token will render correctly.

`PRODUCT.md` and `DESIGN.md` still describe a Compose-only, no-WebView product. The manifest now launches `SolidTvActivity`, which hosts SolidTV in a WebView above the native player. Treat current executable code as implementation evidence. Updating those documents belongs to the eventual redesign implementation; this proposal does not overwrite the existing design record. The workspace graph contains no Flixtor nodes, so it cannot establish this app's relationships.

## Visual direction: Liquid Cinema

The first viewport should feel like entering a film: an image-led stage, one clear viewing action, and the next row of content visible below it. Artwork provides color and atmosphere. Floating controls provide the glass. Content remains crisp.

Use a near-black neutral base with a cool undertone, soft white type, restrained ice-blue focus, and artwork-derived ambient color. Replace the blanket navy panels with a stronger separation between background, content, and controls. Retain the FlixTor name and recognizable identity; avoid assuming new logos, licensed title treatments, or unavailable metadata.

Three material levels:

1. **Stage:** full-bleed artwork with localized dark scrims behind text; opaque poster and episode imagery.
2. **Floating controls:** translucent neutral tint, gently bright upper edge, soft shadow, and a small specular response on focus. Navigation, action capsules, and transport use this treatment.
3. **Task surfaces:** denser smoked glass for dialogs, keyboards, and drawers. Text contrast must hold over the brightest available backdrop.

Apple describes Liquid Glass as a functional layer for navigation and controls, and recommends keeping it out of ordinary content surfaces. Adopt that hierarchy rather than applying blur to every card. Source: [Apple Materials](https://developer.apple.com/design/human-interface-guidelines/materials).

Use existing bundled Roboto regular/bold initially, with deliberate weight and size contrast. Do not depend on Apple system fonts. Starting type scale on the 1080p logical canvas: display 64–80, section headings 34–40, body/actions 28–32, supporting metadata 24–26. Validate from TV viewing distance and at 720p; these are prototype values, not locked tokens.

Starting geometry: an 8-unit spacing rhythm; 24–32 rail gaps; 64–72 minimum action/key height; restrained 16–24 poster corners; larger 28–36 dialog corners; capsule navigation and primary actions. Reserve space around focused items so enlargement never clips or changes layout. Honor the device's existing 0–10% screen-fit settings.

## Screen-by-screen composition

### Home

Compact floating navigation at the top: Home, Movies, TV Shows, Anime, My List, plus labelled Search and profile/settings controls. Continue Watching becomes the first content rail with access to its full destination. Keep every current destination reachable; profile menu carries Settings and Switch profile.

An artwork stage occupies roughly the upper 55–60% of the viewport. Title and short metadata sit left, with one primary Play/Resume action and a secondary Details action. The initial hero comes from existing catalogue or viewing data; do not invent editorial recommendations or a recommendation service.

Continue Watching uses landscape cards with visible progress, episode/version information when known, and one unambiguous resume action. Standard catalogue rails use portrait posters. Let part of the next rail remain visible to explain vertical navigation. Actual shelves stack vertically; remove shelf tabs and manual five-title window controls from the default browsing path.

Keep the hero stable during rapid navigation. A short dwell may update its preview; no auto-advancing carousel or unexpected audio. Up from the first rail enters hero actions; Up from hero enters navigation; Down returns to the last content focus.

### Movies, TV Shows, Anime, and My List

Use a compact header, a labelled sort/filter capsule, and a generous poster grid. Avoid repeating Home's large hero on every destination. Surface only filters the adapters actually support. Keep Anime search and source behavior intact.

Load the next page near the visible boundary, with virtualization and bounded retention. Preserve existing cards on page failure and show inline Retry. Initial loading, filter refresh, and pagination must have distinct states. A filter change resets position and selection from one canonical query key; returning from detail restores them.

My List gets a meaningful empty state with a Browse action. Missing images get a deliberate typographic poster treatment with the title shown once, not duplicate title blocks.

### Title detail

Hide global navigation. Put a discreet Back control within the safe area. Use a cinematic artwork stage, strong title, compact metadata, a synopsis with deliberate line limits, and primary Resume/Play. Resume labels include the relevant episode/time when known. Keep Play from start available as a secondary action.

Below the stage: season selector and episode rows with number, title, progress, remaining/watched status, and a selected/now-playing state independent of focus. Use episode stills only if supplied; the current model does not provide them. Open at the season/episode the profile is watching.

Add to My List responds with a short checkmark transition and accessible confirmation. Errors leave an actionable detail shell. Returning to the catalogue restores the exact title and rail, including when a transition is interrupted.

### Profiles and PIN

Centered avatar gallery, clear names, small lock indicators, and a quieter Manage action. Support one through seven profiles with a predictable row or scrollable gallery; do not shrink all targets to fit. Show the last-used profile as initial focus when still available.

Selecting a locked profile opens a compact glass modal with avatar, name, four masked digit indicators, and a stable 3 × 4 keypad: 1–9, Delete, 0, Cancel. Start on 1; keep digit positions fixed. Hardware digits and numpad digits enter directly. Backspace deletes; Back/Escape cancels. Holding a digit must not fill all four positions.

For unlocking, verify automatically after the fourth digit; show an immediate pending state, prevent duplicate submissions, and dismiss only after success. On incorrect PIN, clear digits, announce the error, and keep the keypad usable. Network/platform failures get their own retry explanation. Optional small error nudge disappears with reduced motion.

Set/change PIN is a distinct flow with confirmation entry and explicit Save. Removing a PIN is an explicit action, never an unexplained blank-input convention. Keep existing credential storage and verification boundaries; clear local digit buffers on cancel, dismissal, and disposal.

### Search and text entry

Search is a full destination: query field at top; a compact on-screen alphabet grid on the left; results on the right. Use a predictable six-column A–Z grid plus separate labelled Space, Delete, Clear, 123/symbols, and Done actions. Alphabetic order suits remote scanning; physical keyboard users can type directly without navigating the grid.

Keyboard and results are explicit focus regions. Right from the keyboard edge moves to results; Left from the first result restores the last key. Up from the first key row reaches the field. Avoid accidental row wrapping. Show a stable result count only when known.

Debounce committed query text around 300 ms, cancel obsolete requests, and ignore superseded responses. Show delayed refresh feedback around 120 ms. Do not send requests mid-IME composition. Keep input responsive while results load, and never let a new result set steal focus. Commit browser history at meaningful search boundaries rather than every keypress.

General text fields need a shared contract: label, initial value, text/password/PIN/integer/time mode, validation, commit, cancel, and focus origin. Preserve native IME for composition, dictation where available, and languages the custom grid cannot represent. Do not claim voice search without device support. QR pairing remains the easiest account path; email/password remains fully usable with native editing and masked password entry.

Numeric/time editing uses a suitable numeric field or pad with visible units, limits, and actionable validation. It must not inherit PIN auto-submit or four-digit limits.

### Dialogs, drawers, and settings

Use three deliberate overlay patterns: centered confirmation, anchored option menu, and side drawer for multi-step choices. One shared overlay controller owns stacking, initial focus, background inertness, dismissal, and focus restoration. Background controls must be inactive in both the canvas and semantic DOM layer.

Dialogs contain a short title, explanation, and a clear action row. Keep content scrollable and actions visible in short viewports. Safe defaults: Stay for exit; Cancel for deletion/clearing; Continue watching for attendance. Back closes the top layer or current submenu first. An asynchronous completion cannot reopen a dismissed overlay or steal focus.

Settings becomes a category column with a focused content pane. Keep profile identity and truthful account status visible. Account, Profiles, Playback, Skip segments, Updates, Display, and Library remain available. Option rows display current values; checkmarks indicate selection while a separate treatment indicates focus. Screen-fit changes get a visual preview with explicit Save/Cancel.

Pairing gets a large high-contrast QR, concise instructions, checking/expired/error states, and fresh-code recovery. Place QR on an opaque light surface. Preserve account verification and pending-playback return behavior.

### Player

Video remains the dominant surface. Compact lower transport capsule: play/pause, seek controls, episodes, and settings. Timeline sits above it with distinct played/buffered/preview states. Secondary actions such as buffer-ahead and editing belong in the drawer, with discoverable labels.

Use coherent vector icons with accessible names and focused labels. Separate temporary seek preview from committed position. Back cancels a preview before leaving playback. Preserve actual duration bounds and existing playback state rules.

The settings/episodes drawer uses denser glass and a predictable category → choice → category flow. Captions reserve space above transport or beside the drawer. Skip, Undo, Next episode, and attendance prompts share priority rules so they never overlap or compete for focus. Keep countdown suspension, cancellation, anime-version selection, and resource cleanup behavior intact.

## Input and focus contract

Every screen has a visible initial target, deterministic directional neighbors, a return target, and a fallback when that target disappears. Selected, focused, pressed, disabled, and pending are distinct states. Remote navigation must work without a pointer. This follows Android TV's focus and navigation guidance: [TV navigation](https://developer.android.com/training/tv/get-started/navigation).

- Arrows/D-pad move within the active region immediately. Rail navigation scrolls content to keep the target visible. Up/Down preserve the nearest horizontal position across rails; no unexpected first-to-last wrap.
- Enter/remote OK activates exactly once. Repeated keydown never duplicates Play, PIN verification, destructive actions, or navigation. Held arrows may repeat navigation; held Delete may repeat deletion.
- Escape/Android Back dismisses the top overlay, then unwinds the current screen. Preserve root exit confirmation and the player's existing hidden-controls behavior. DOM and Android bridge events must not perform the same action twice.
- Tab/Shift+Tab follow the same meaningful order in browser mode. Modal traversal stays inside the modal. Pointer focus and remote focus agree; pointer movement alone does not continuously replace keyboard focus.
- `/` opens Search only outside editable fields and modal tasks. Printable typing in Search edits the query. Backspace deletes in fields and never exits them. Preserve caret movement, selection, paste, and IME composition.
- Space/media play-pause controls playback only in playback context, outside text entry, and without double-activating a focused button. Left/Right seek only while timeline/seek mode owns focus. Global media keys respect active modal tasks.
- Focus identity includes destination, shelf, title, and relevant episode/control. A title-only key is insufficient when the same title appears in multiple rails. Store logical identity and scroll anchors, not retained detached nodes.
- Animation never owns input timing. New input retargets ongoing movement; focus does not wait for a spring or backdrop request to finish.

## Motion and reward

The signature sequence: focus settles on artwork → card lifts and edge catches light → backdrop gently resolves → opening detail carries the artwork into its larger stage. This is a proposed choreography, not a dependency on a specific animation library.

Starting timings to validate on hardware:

- Focus response starts on the next rendered frame; visual settle 140–180 ms. Card scale around 1.035–1.05, with 4–8 units of lift. Layout bounds remain fixed.
- Press feedback compresses slightly for 70–100 ms, then releases. Interaction executes immediately; animation does not delay the action.
- Navigation selection indicator glides between destinations in 180–240 ms. Destination changes require activation, not merely focus.
- Backdrop preview starts after roughly 250 ms dwell, then crossfades for 350–450 ms. Decode the new image before replacing the old one. Keep only the current and incoming visual layers.
- Detail transition lasts 280–360 ms. Use artwork continuity only when matching artwork is already available; otherwise a clean fade and small directional movement. This should be a bounded scene transition in SolidTV, not an assumed DOM View Transition feature.
- Dialog enters with opacity and a small 0.97 → 1 scale over 180–220 ms; drawer slides 24–40 units over 220–280 ms. Exit completes faster. Keep the surface mounted through its actual exit animation.
- My List confirmation morphs add → check in 180–240 ms. A short local acknowledgement replaces a large blocking toast. PIN dots fill immediately; success transitions straight into Home.
- No persistent idle shimmer or automatic camera drift. Loading placeholders may use a restrained pulse; stop it when content settles or the app backgrounds.

Reduced motion removes scale, parallax, sliding, and morphing while preserving instant focus contrast and status changes. Add a Reduce transparency setting and a stronger-contrast material variant. Observe preference changes during a session, not only at startup. Sound/haptic feedback is optional and off by default; neither is needed to understand success.

## Renderer and performance plan

SolidTV is a canvas scene graph; CSS `backdrop-filter` is not the material implementation for these views. The installed renderer exposes gradients, rounded/shadow shader types, custom shader registration, and an image Blur shader. Its Blur declaration describes a nine-sample texture filter, not arbitrary scene backdrop capture. Verify each required capability in a small material scene first.

Implement a reliable baseline using translucent fills, a controlled highlight edge, shadow, and contrast scrims. For catalogue chrome, prototype a downsampled copy of the current artwork, blurred/tinted within the control region with matching crop alignment. Actual refraction is an optional enhancement only after scene sampling, clipping, and performance are proven. Never call the baseline true optical refraction.

Android's native player is outside the WebView renderer's texture scene. Do not assume its video can be sampled for backdrop blur. Player chrome uses the baseline smoked-glass treatment; any true video-blur approach needs its own feasibility decision. Browser HTML video and native video must remain functionally equivalent even if material fidelity differs.

Targets, not current measurements:

- Aim for 60 fps at 1080p on the target ONN/Chromecast devices. Measure input-to-highlight latency and frame times during rail scrolling, image decode, modal opening, and playback with controls visible.
- Input-to-visible-focus p95 under 50 ms; steady-navigation frame-time p95 around the 16.7 ms frame budget on the chosen reference device. If this fails, reduce material cost before reducing input responsiveness.
- Virtualize long rails/grids and semantic controls. Keep selected and nearby items resident, preload at most the next useful image/rail window, and bound artwork/detail caches. Check peak texture allocation during two-layer crossfades against the existing renderer threshold with headroom.
- Coalesce DOM hit-target updates with actual scene transforms instead of letting animated controls and their 100 ms hit-target timer drift apart. Verify clipping and hidden/offscreen semantics too.
- Cancel image work on focus/query changes; use request identity so late artwork cannot appear on the wrong title. Dispose animation handles, listeners, observers, timers, and object URLs. Suspend animation on backgrounding and recover from renderer/context loss without losing saved data.

## Iterative delivery

Each iteration should end with a usable flow, a short captured interaction clip, and screenshots at 720p/1080p. Inspect once, batch corrections, then perform one confirmation pass. Carry unresolved issues explicitly into the next iteration rather than repeatedly polishing a static screen.

### 0 — Establish the visual and interaction proof

Create high-fidelity Home, Detail, Search, and PIN compositions within this direction. Use a deliberately varied artwork set: bright, dark, busy, missing, long titles, one/many profiles, and long episode lists. Label synthetic data; include actual supported metadata shapes.

Build a small local material/focus experiment using the installed renderer: rounded panel, glass capsule, poster lift, clipped image blur, modal focus trap, and aligned pointer/semantic target. Compare baseline and enhanced material on a physical target device.

Exit: demonstrated material fidelity, reliable arrows/OK/Back, legible bright-art contrast, and measured performance. Resolve shader support and native-video limits here. Estimate later work after this proof, rather than promising unmeasured animation cost.

### 1 — Shared foundation and complete profile flow

Establish tokens, material primitives, button/icon states, focus identities, and overlay ownership. Rebuild profile gallery and PIN unlock end-to-end, including keyboard entry, wrong PIN, cancellation, and restoration. Reuse repository/platform credential APIs.

Exit: one coherent production-quality flow proves the interaction system; no background activation through dialogs; keyboard and remote produce the same result.

### 2 — Home and browse

Replace wide sidebar with floating navigation. Build artwork stage, Continue Watching cards, stacked portrait rails, browse grids, and incremental pagination. Preserve query cancellation and profile/auth scoping. Create explicit focus/scroll restoration by rail and title.

Exit: catalogue navigation works with rapid held input, missing artwork, slow pages, and return from detail. No manual five-item stepping in the main browse experience; no focus jumps when data arrives.

### 3 — Search and reusable input

Build query field, on-screen keyboard, numeric/symbol mode, results focus boundary, and native-IME path. Apply shared field/validation patterns to profile editing, account forms, and numeric/time values. Keep fixture input behavior representative of production.

Exit: complete search → title → Back flow using remote only and keyboard only. Composition, paste, delete, clear, empty results, stale response, and rapid changes covered.

### 4 — Detail and cinematic transitions

Build artwork-led details, unified Resume/Play, list feedback, season chooser, episode progress, and catalogue/detail continuity. Keep series/anime resume rules and version chooser explicit. Add transition cancellation and fallback behavior.

Exit: movie, series, and anime paths work; return restores the exact origin; long metadata and absent backdrops remain polished.

### 5 — Player, settings, and account surfaces

Rebuild transport, timeline, drawers, settings panes, pairing, confirmations, skip/next overlays, buffering, and region editing in the shared material system. Preserve caption safe areas and existing playback state transitions. Review the player on actual native video, not only browser fixtures.

Exit: playback controls and captions remain clear; Back unwinds one layer; dialogs never interrupt active playback unexpectedly; selected options remain distinct from focus.

### 6 — Accessibility, device acceptance, and design record

Complete semantic roles/names/states for the canvas UI, browser keyboard testing, Android TalkBack checks, reduced-motion/transparency variants, safe-margin extremes, and low-memory/long-session checks. Reconcile `PRODUCT.md` and `DESIGN.md` with the shipped implementation and record final tokens and behavior.

Exit: release candidate satisfies the acceptance matrix below. APK release remains a separate action after implementation validation.

## Likely code ownership

- `src/main.tsx`: renderer setup, supported material registration, font loading, motion preferences.
- `src/ui/components.tsx`: current button/poster/menu and semantic infrastructure. Extract focused modules only as the new material, overlay, focus, and input responsibilities become real; do not grow one universal component file.
- `src/app/App.tsx` and `src/app/context.ts`: shell, navigation, Back ownership, profile context, shared input boundary.
- `src/features/catalog/Catalog.tsx`: separate Home, browse, and search compositions while reusing catalogue services and query identity.
- `src/features/catalog/Detail.tsx`: artwork, resume, seasons, episodes, origin restoration.
- `src/features/profiles/Profiles.tsx`: avatar gallery and PIN flow.
- `src/features/playback/Player.tsx`: transport and overlay presentation; preserve and test behavior in `playback.ts` and `timing.ts`.
- `src/features/settings/Settings.tsx`, `src/features/account/Pairing.tsx`: settings panes, forms, confirmations, pairing states.
- `src/platform/browser.ts`, `src/platform/platform.ts`, `SolidTvActivity.kt`: typed input capability, native IME, Back/media events, and lifecycle integration.
- `src/ui/copy.ts`: extend existing copy ownership; do not import Avete Dashboard's unrelated locale/framework conventions into this SolidTV app.
- `test/e2e/app.spec.ts`, `test/e2e/live.spec.ts`, `test/state.test.ts`, and `test/playback.test.ts`: extend behavioral coverage for changed interactions; Android instrumented tests verify the native boundary.

Use current Solid, router, TanStack Solid Query, and renderer capabilities first. No new animation framework, recommendation backend, font service, or graphics engine is assumed.

## Acceptance and regression matrix

Validate at 1280 × 720 and 1920 × 1080, default and 0%/10% safe margins, then on the target physical TV devices. Add 4K output scaling where supported; keep the logical layout coherent. Desktop resize must preserve reachable inputs and modals, but a separate mobile product is outside this plan.

- **Browse/state:** fast, slow, failed, empty, missing art, partial pages, retry, rapid sort/query changes, filter reset, browser Back/Forward, grid → detail → grid, same title in multiple rails, profile switch.
- **Navigation:** arrows alone, held arrows, OK repeats, Back at every layer, Tab/Shift+Tab, pointer then remote, disappearing selected item, interrupted transitions, reload with a route but missing cached title.
- **Inputs:** hardware letters/digits/numpad, caret and selection, paste, IME composition, remote keyboard, symbol mode, numeric bounds, wrong/incomplete PIN, double submit, cancel while verifying, reopening after failure.
- **Overlays:** background controls inert, correct initial focus, focus restored on close, long content scrolls, action rows remain visible, safe destructive defaults, asynchronous completions after dismissal, nested submenu Back.
- **Content ranges:** zero/one/many results, one/seven profiles, very long title/profile names, missing synopsis/year/rating/backdrop, multi-season series, many episodes, unavailable anime version.
- **Playback:** seeking commit/cancel, pause/buffering, captions with transport and drawer, next-episode cancellation, attendance, skip/Undo, region edit Save/Cancel, error recovery, media keys, foreground/background, updated controls over native video.
- **Accessibility:** focus visible on bright/dark art; focus and selection independently announced; named icon buttons; readable text and actionable errors; status announcements; browser and Android assistive technology; reduced motion and transparency.
- **Performance:** cold start, sustained rail navigation, overlapping image requests, scene transitions, modal stack, playback with overlays, memory after repeated navigation, background cleanup.

For each implementation slice run `npm run typecheck`, `git diff --check`, targeted Vitest for changed state/input/query logic, and the relevant Playwright flows. Broad UI/bundling changes require `npm run build`. Native bridge changes also require applicable Android unit/lint/build and instrumented checks. Report existing failures separately; never treat browser fixtures as physical-device acceptance.

## Decisions and limits for the builder

Liquid Glass is the chosen aesthetic. Floating top navigation, stacked Home rails, separate search keyboard, avatar profiles, and explicit modal focus ownership are the proposed structural decisions. Timing, geometry, exact tint/opacity, and optional refraction remain prototype values until device validation.

Home catalogue data often lacks backdrops; detail parsing can supply them. Use existing/cached detail art when available, otherwise a composed poster stage. Bound any focused-title detail prefetch; do not fetch every card's detail to populate a hero. Do not invent trailers, episode stills, cast data, ratings, resolution badges, or personalized recommendations that the model/service does not supply.

The first implementation milestone is the material/focus proof plus profile → PIN → Home navigation. It establishes the quality bar and hardware cost before the redesign spreads to every screen.
