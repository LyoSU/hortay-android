# Hortay — Visual & UX Polish Specification

> Working spec for the implementing agent. Created at the owner's explicit request
> (2026-06-09 design audit session). Delete after all workstreams land or fold the
> surviving rules into `ARCHITECTURE.md`. This document is the single source of truth
> for the polish effort — read it END TO END before touching code, then read
> `ARCHITECTURE.md` (hard rules, load-bearing table) before each workstream.

---

## 1. Goal & diagnosis

The app is functionally rich but reads as "stock Material 3 sample" rather than a
premium product. The 2026-06-09 audit (8 device screenshots + code review) found four
systemic root causes, not isolated defects:

1. **Tonal soup** — nearly every element (chips, pills, nav bar, icon discs, hero
   cards) used the same `surfaceContainer*` lavender-grey value. No figure-ground
   separation; accent colour carried no meaning.
2. **Inverted type hierarchy** — post body (16 sp) rendered larger than the author
   name (14 sp); positive letter-spacing made Inter look loose at reading sizes.
3. **Competing feed signals** — unread strip + full-card wash + divider + "Нові пости"
   divider + arrivals pill + unread FAB badge all shouting at once.
4. **Craft gaps** — duplicated handle rows, "@" placeholder avatars, a trash icon on
   the archive row, heart-mask-with-bookmark-glyph empty state, white stickers
   dissolving into white background, content scrolling into the header uncovered.

**References (in priority order):**
- **Threads** — typographic rhythm, calm white canvas, thread connectors, restraint.
- **Telegram (official Android)** — reading density, semantic colour, list idioms.
- **Airbnb** — craft culture: micro-detail consistency, motion-as-feedback, state
  handling (loading / empty / error are designed, never default).

## 2. Feedback doctrine (owner-approved, applies to every workstream)

Every change must pass these five rules. They are acceptance criteria, not vibes:

1. **Every tap answers visibly within ~100 ms** — press state (squish morph, scale,
   ripple) even when the result is still loading. Existing vocabulary:
   `rememberPressedSelectedCornerRadius` (ui/theme/Shape.kt), `MorphShape`, haptics.
2. **No naked states** — every surface has designed loading / empty / error / offline
   states. No default centered `CircularProgressIndicator` on a blank screen.
3. **Nothing jumps under the finger** — counters use tabular figures, placeholders
   reserve final size, selection must not change text weight/width, skeleton grace
   rules in `rememberDeferredLoading` stay intact.
4. **One signal = one meaning** — lavender (primary/secondary container fill) means
   *selected / active / yours*, the 3 dp edge strip means *unread*. Never reuse either
   for decoration.
5. **Irreversible confirms, reversible is instant** — destructive actions get a
   confirm; everything else applies immediately, with undo where cheap.

## 3. Owner decisions already made (do NOT re-litigate)

| Decision | Choice |
|---|---|
| Canvas character | **Clean canvas** (Threads/Airbnb): near-white background, minimal fills, lavender only on selected/active |
| Unread signal | **3 dp edge strip only** (full-card wash removed) |
| Dynamic color default | **Brand periwinkle by default**; "Кольори зі шпалер" stays as opt-in toggle |

## 4. Already landed in the working tree (2026-06-09, uncommitted)

Do not redo; verify they survive rebases. All compile (`:app:compileDebugKotlin` clean).

- `data/SettingsStore.kt` — `dynamicColor` default flipped to `false`.
- `ui/theme/Type.kt` — `titleSmall` 14→15 sp, `bodyLarge` 16→15 sp / lh 22,
  `bodyMedium` tracking → 0; hierarchy contract documented in the file KDoc.
- `ui/timeline/PostCard.kt` — unread full-card wash removed (strip + its collapse
  animation kept); divider now inset 68 dp at `outlineVariant @ 0.25`; `ReactionChip`
  rest state = hairline outline ghost (no fill), chosen = `primaryContainer`
  (was `tertiaryContainer` on `surfaceContainer`).
- `ui/timeline/FoldersBar.kt` — resting chips transparent (bare text), padding
  18×12 → 14×9.
- `ui/discover/DiscoverChannels.kt` — handle line hidden when `name == handle`;
  avatar fallback strips the `@` prefix.
- `ui/settings/SettingsScreen.kt` + `ui/icons/Symbol.kt` +
  `res/drawable/sym_history.xml` (new, Solar `history-outline`) — archive row icon
  `delete_sweep` → `history`.
- `ui/timeline/TimelineEmptyHero.kt` — Saved empty-state mask Heart → Flower
  (`EmptyStateMask`), glyph stays `bookmark`.

**OPEN QUESTION (owner has not decided):** the inset divider. The owner noticed it and
asked whether the cut-off is intentional. Options presented: (a) keep inset,
(b) full-bleed at alpha 0.25, (c) no divider, whitespace-only with larger vertical
padding. **Ask the owner before changing**; default = keep inset (a).

## 5. Design-system rules for all new work

### Colour semantics
- `primary` fill / `primaryContainer` = *selected, yours, active*. Nothing else.
- `secondaryContainer` = *current location* (nav tab, folder chip) — the established
  pair with `onSecondaryContainer`; keep nav and folder chips on it.
- `tertiary` = inline informational accents (forward chip, translation chip) — already
  the convention in PostCard; don't expand it to fills.
- Information rests on `onSurfaceVariant` text/icons with **no container**, or a
  hairline `outlineVariant` border. Reach for a grey fill last, never first.
- `PremiumGold` (Color.kt) only for Telegram-Premium marks.

### Typography
- Post header name (`titleSmall`, 15 SemiBold) ≥ post body (`bodyLarge`, 15/22). Never invert.
- Counters/timestamps: `labelMedium`/`labelSmall` in `onSurfaceVariant`.
- All numeric counters that update live get tabular figures
  (`fontFeatureSettings = "tnum"`) — see WS-C2.
- Plus Jakarta Sans only for display/headline (brand surfaces); Inter for everything else.

### Shape & motion
- Stay inside `HortayShapes` + `HortayExpressive` (ui/theme/Shape.kt). New corner radii
  must map to an existing token; no fresh literals.
- All animation through `MaterialTheme.motionScheme.*` — `fastSpatialSpec` for
  geometry, `fastEffectsSpec` for colour/alpha. Literal `tween(...)` is forbidden
  (ARCHITECTURE.md hard rule).
- Press feedback baseline: the three-state corner morph
  (`rememberPressedSelectedCornerRadius`) for stadium surfaces; scale 0.98 (spatial
  spring) for card-sized surfaces.

### Icons
- Solar set via `Symbol(name=…)` (ui/icons/Symbol.kt). New glyphs follow the documented
  Iconify → VectorDrawable pipeline in that file's KDoc, registered in `symbolDrawable`.
- Semantic honesty: trash = delete, history = archive, eye = views. If a glyph's
  literal meaning contradicts the action, replace the glyph.

### Process (repo hard rules — non-negotiable)
- User-facing strings → `values/strings.xml` **and all 12 `values-<lang>/` mirrors in
  the same commit**; plurals per CLDR forms (see ARCHITECTURE.md language policy).
- No new `.md` files. No TODO comments. Conventional Commits with package scope.
- Changelog: one user-POV sentence per bullet under `[Unreleased]`, no file paths.
- Gate: `./gradlew :app:lintRelease` + `./gradlew test` before commit; check
  `app/build/compose_compiler/` for new unstable classes after Compose-visible changes.
- `@Immutable`/`@Stable` on any new data class that reaches composition; lambdas in
  lazy lists `remember`-wrapped with stable keys.

---

## 6. Workstreams

Ordered by user-visible impact. Each item: **Current → Target → Notes**.
Files are starting points; follow the call graph.

### WS-B — Feed chrome (highest impact remaining)

**B1. Merge the arrivals pill and the unread FAB into one floating element.**
- *Current:* `NewPostsPill.kt` (primary stadium, avatar stack, BottomCenter) and
  `UnreadCounterPill.kt` (secondaryContainer FAB + Badge, BottomEnd) can fire
  simultaneously and visually collide near the navbar (audit screenshot 1: pill,
  FAB and badge stacked in one corner region). The KDocs argue
  "silhouette-disjoint Gestalt clusters" — in practice it reads as clutter.
- *Target:* ONE bottom-anchored floating control with two states driven by one
  priority rule: if `scopedPendingNew > 0` → expanded stadium (avatar stack +
  "N нових постів" + arrow); else if live unread count > 0 → collapsed circular
  state ("↓ N"). Morph between states with `MorphShape`/corner-radius spring
  (the FabPressMorph vocabulary). Tap in expanded state = current pill action
  (acceptIds → awaitItemsCommitted → smartScrollTo); tap collapsed = current FAB
  action (scroll to read→unread boundary).
- *Notes:* Do **not** touch the arrivals-commit contract (ARCHITECTURE.md row
  "Arrivals commit only via NewPostsPill" — no atTop/atBottom auto-accept). Both
  current behaviours must survive exactly; only the chrome merges. Keep the
  entrance spring keyed on `count > 0`. Keep a11y: one merged contentDescription
  per state. Both feed orders (pill anchors/arrows differ — see `arrowGlyph`).

**B2. Brand bar with presence.**
- *Current:* `ui/main/Brand.kt` — `BrandRow` is plain `headlineLarge` text in
  `onBackground`, left-aligned, right side of the row empty. `BrandMark` (a "t"
  glyph disc — legacy from the telread name) appears inconsistent with the
  product name Hortay.
- *Target:* wordmark in Plus Jakarta Sans ExtraBold, slightly smaller than now
  (e.g. `headlineMedium` + (-0.8) tracking), tinted `primary` (brand voice on the
  clean canvas); trailing slot in the same row hosting (in order, when present):
  connection-state chip (already exists elsewhere — reuse `ConnectionBanner`
  vocabulary), search affordance if/when feed search ships. Fix `BrandMark` glyph
  "t" → "H" (or retire `BrandMark` if its call sites can take the wordmark).
- *Notes:* the feed's bar is the custom overlay (`rememberFloatingTopBarBehavior`,
  consumeScroll=false) — its slide-away behaviour is load-bearing and stays.

**B3. Chrome separation on scroll.**
- *Current:* `HortayTopBar` already morphs `background → surfaceContainer` when
  scrolled (components/HortayTopBar.kt:98-101) for standard screens. The feed's
  custom BrandRow + FoldersBar overlay does NOT tint when content slides under;
  on the audit screenshots the first post collides with the tabs visually.
- *Target:* when feed scroll offset > 0, the brand/folders overlay gets the same
  `surfaceContainer` (or scrim + hairline bottom border) treatment, animated on
  `fastEffectsSpec`. On the post-detail/comments screen add a top fade-edge so
  text doesn't hard-clip against the title (audit screenshot 4, first comment
  sliced by the header).

**B4. Folder chips — emoji sizing.**
- *Current:* a folder named with a single emoji renders the emoji at label size ×
  emoji font scaling → oversized chip (audit screenshot 1, 📢 chip taller than
  text chips).
- *Target:* clamp chip content height — render emoji-only labels at the same
  line-height as text labels (e.g. fixed `labelLarge` lineHeight with
  `platformStyle`/inline-content sizing). All chips in the row must be equal height.

### WS-C — Post card details

**C1. Stat row lightening.**
- *Current:* `PostCard.kt` `ActionRow`/`StatPill` — 16-18 dp icons + counts in
  `onSurfaceVariant`, vertical separator before reactions. Reads heavier than the
  reaction ghosts after the Phase-1 change.
- *Target:* icons 14-15 dp, counts `labelMedium`; drop the `VerticalSeparator`
  (the outline ghosts now separate themselves); keep 14 dp gaps. Goal: the stat
  row sits a full visual level below the post body.

**C2. Tabular figures on all live counters.**
- *Current:* views/comments/forwards/reaction counts re-layout when digits change
  width (`formatViews`).
- *Target:* apply `TextStyle(fontFeatureSettings = "tnum")` via one shared style
  token (e.g. `HortayTypography`-adjacent extension or a `NumericLabel`
  composable) to StatPill, ReactionChip count, UnreadCounterPill badge, NewPostsPill
  label number. Doctrine rule 3.

**C3. Card press feedback.**
- *Current:* `combinedClickable` on the post card body with default indication only.
- *Target:* scale 1.0 → 0.985 while pressed (spatial spring, `graphicsLayer`),
  riding the existing interactionSource. Must not add a compositing layer for
  resting cards (follow the `.then(if …)` pattern already used for `isDeleted`).
- *Notes:* verify no skippability regression in `app/build/compose_compiler/`.

**C4. Timestamp/meta polish.**
- *Current:* `formatRelative(date)` in `labelMedium`; edited pencil 14 dp.
- *Target:* timestamps to `labelSmall`, colour `onSurfaceVariant @ 0.8`; ensure
  the right-edge block (pin + edited + time) aligns optically with the first
  text line of the name (baseline, not centre, if cheap).

### WS-D — Media handling

**D1. Hairline border on every media surface.**
- *Current:* photos/stickers/video posters clip to `MaterialTheme.shapes.medium`
  (PostMediaBlocks.kt) with no border → white-on-white images dissolve into the
  canvas (audit screenshots 2, 4).
- *Target:* `border(0.5dp–1dp, outlineVariant @ ~0.5, sameShape)` on the media
  container in: feed media blocks, album grid tiles, comment media, reply-preview
  thumbnails (PostCard `ReplyBlock` 44 dp tile), web-preview images, fullscreen
  viewer EXCLUDED (immersive). One modifier extension (e.g.
  `Modifier.mediaFrame(shape)`) so the value can't drift per surface.
- *Notes:* transparent stickers (TGS/WebM) should NOT get the frame — only
  rectangular photographic/video content. Gate by content type, not by renderer.

**D2. Corner-radius audit.**
- *Target:* all in-card media = `shapes.medium` (18 dp) consistently; nested tiles
  (album inner tiles, reply thumb) = `shapes.small` (12 dp) per the M3E
  nested-radius rule already used by ReplyBlock. Fix any stragglers found.

### WS-E — Comments & threads

**E1. Thread connectors, Threads-style.**
- *Current:* `CommentsScreen.kt` `CommentNode` — depth × 12 dp indent with a
  straight 2 dp `outlineVariant` vertical bar per level. Functional but reads as
  an indent gutter, not a conversation thread (audit screenshot 2: staircase).
- *Target:* single connector line per reply chain drawn from the PARENT avatar's
  bottom edge curving (quarter-arc, ~8 dp radius) into the child row, 1.5-2 dp,
  `outlineVariant @ 0.6`. Visual depth cap at 2 levels: depth ≥ 2 renders at the
  depth-2 indent (data depth untouched) — beyond that the reply quote-card
  already carries the relation.
- *Notes:* draw with `drawBehind` keyed on row layout, no extra layout pass; LTR
  + RTL mirroring.

**E2. Avatar size stepping.**
- *Current:* every comment avatar 36 dp regardless of depth.
- *Target:* top-level 36 dp, replies (depth ≥ 1) 28 dp. Lines up with the
  connector geometry and gives instant parent/child parsing.

**E3. Comment reaction chips inherit the WS-A ghost style automatically** (they
reuse `ReactionChip`) — verify on device, no work expected.

### WS-F — Channels screen

**F1. Rows carry reading metadata.**
- *Current:* `ChannelsScreen.kt` `ChannelRow` — 48 dp avatar, title, one-line last
  post excerpt. No unread, no recency, no state (audit screenshot 3: list feels
  skeletal).
- *Target:* trailing column: relative time of last post (`labelSmall`,
  `onSurfaceVariant`) + unread-count Badge (primary container) when > 0, fed from
  the same `ReadCursors`/chat data the feed uses; muted channels get a small
  `notifications_off` glyph after the title and 0.7 title alpha.
- *Notes:* keep `SegmentedListItem`; data must come via the repository layer (no
  TDLib calls from UI — hard rule). Tabular figures on the badge (C2 token).

**F2. Hidden-from-feed channels** (if surfaced in this list) get an explicit
trailing "hidden" eye-off marker instead of being indistinguishable.

### WS-G — Settings & profile

**G1. One icon language.**
- *Current:* quick-switch rows at the top of Profile render icons inside tonal
  discs; the category rows below (`SettingsRow`) render naked 22 dp icons
  (audit screenshots 6-7 show both on one scroll).
- *Target:* pick ONE: naked Solar outline icons everywhere in settings
  (recommended on the clean canvas), tinted `onSurfaceVariant`, `primary` only
  for the active/selected row. Remove the tonal discs from the quick-switch rows
  (they're informational, not selected — doctrine rule 4).

**G2. Profile hero softening.**
- *Current:* `ProfileHero` (SettingsScreen.kt:495+) — full-card
  `profileCoverBrush(accentId)` two-shade vertical gradient; saturated
  Telegram-peer purples clash with the periwinkle app (audit screenshot 7);
  avatar sits directly on the gradient.
- *Target:* keep the peer-accent identity but blend ~25-30% toward `surface`
  (`lerp(cover, surface, 0.25-0.3f)` per stop), radius = `shapes.extraLarge`;
  add a 3 dp `surface`-coloured ring around the avatar so any avatar colour
  separates from any cover. `profileOnCoverColor` contrast logic must still pass
  after the blend — recompute, don't hardcode.

**G3. Section labels.**
- *Current:* `SectionLabel` — `titleSmall` SemiBold in `primary`.
- *Target:* on the clean canvas, primary-coloured section headers register as
  links. Switch to `labelLarge`/SemiBold in `onSurfaceVariant`; reserve primary
  for interactive text. (Also applies to `SectionHeader` in DiscoverChannels.kt.)

### WS-H — Discover / add-channel sheets

**H1. Subscribe button discipline.**
- *Current:* `FilledTonalButton(actionLabel)` — width varies with label, ragged
  right column (audit screenshot 8).
- *Target:* fixed `widthIn(min = 96.dp)` HEIGHT 36 dp compact tonal button; while
  the add is in flight swap label → 16 dp inline spinner (doctrine 1-2: pressed
  feedback, no layout jump — reserve the width); after success morph to a
  checkmark + "Додано" disabled state for ~1.5 s before the row leaves.
- *Strings:* any new label ("Додано"/"Added") → all 13 locales, same commit.

**H2. Search field idiom.**
- *Current:* `OutlinedTextField` (web/AddChannelSheet.kt:290) — boxy outline reads
  as a form, not a search.
- *Target:* stadium "search bar" idiom: `surfaceContainerHigh` fill (one of the
  few legitimate fills — it's an input), full corner radius, leading `search`
  glyph, no outline. Same treatment in `AddChannelTdSheet`.

**H3. Avatar hydration in guest mode** — investigate why curated suggestions show
initial-letter discs in guest mode (audit screenshot 8); if `t.me/s/` page fetch
can supply the avatar URL, hydrate lazily; otherwise the (already fixed)
letter-disc fallback stands.

### WS-I — States: loading / empty / error / offline

**I1. Pull-to-refresh indicator.**
- *Current:* stock `PullToRefreshBox` defaults (TimelineScreen).
- *Target:* custom indicator riding `HortayExpressive.LoadingPolygons` morph cycle
  (the loading vocabulary already exists) sized to the standard PTR slot;
  threshold-crossed haptic tick (`HapticFeedbackType.GestureThresholdActivate`).

**I2. Skeleton audit** (`SkeletonFeed.kt` + per-screen skeletons): shimmer pacing
and corner radii must match the real card metrics POST-WS-C (header line = 15 sp
band, body = 15/22 bands, media block = `shapes.medium`). A skeleton that doesn't
match the loaded layout violates doctrine 3 when it swaps.

**I3. Error surfaces:** any remaining full-screen error states get: Solar glyph in
a `EmptyStateMask` flower disc (same family as empty states) + one-line human
message + Retry filled-tonal button. No raw error codes (already a rule), no bare
snackbar-only failures for primary content.

**I4. Offline:** the `ConnectionBanner` (Bun shape) is the single offline surface —
verify it appears on feed, channel, comments; content below stays interactive
with cached data.

### WS-J — Motion & haptics finishing

**J1. First-paint stagger:** on cold feed mount (snapshot restore → first frames),
cards fade+rise 12 dp with ~25 ms stagger for the first ~8 items, ONCE per
process (not on tab returns — doctrine: returning must paint in place; see
CHANGELOG 0.9.0 "no skeleton on tab return"). Respect reduced-motion
(`ValueAnimator.getDurationScale()==0` → skip, same guard as
`effectiveSkeletonGrace`).

**J2. Haptics audit:** reactions/bookmarks/polls/nav already tick (CHANGELOG 0.9.0).
Add: PTR threshold (I1), pill/FAB tap (B1), folder chip select. Nothing on plain
scroll or card tap.

**J3. Dark-theme pass:** run every WS change in dark scheme; hairlines (D1, divider)
may need alpha bumps (+0.05-0.1) to stay visible on `#131318`; the ProfileHero
blend (G2) must lerp toward dark `surface`.

### WS-L — Flicker & glitch hunt («бліки»)

Perceived quality dies on single dropped/incorrect frames. Each item below is a
known or high-probability flicker source; the agent must verify each on device
(slow animations via developer options ×5 helps catch them) and fix where present.

**L1. Runtime font swap (FOUT) — confirmed risk, fix unconditionally.**
- *Current:* `ui/theme/Fonts.kt` loads Inter + Plus Jakarta Sans through the
  Google Fonts provider (Play Services) at runtime. First cold start, offline
  start, or any provider hiccup renders system Roboto first, then reflows the
  ENTIRE app when the real fonts land — a full-screen layout flicker, and on
  devices without Play Services the brand typography silently never loads.
- *Target:* bundle the static TTF/OTF subsets actually used (Inter
  Regular/Medium/SemiBold/Bold; PJS SemiBold/Bold/ExtraBold) in `res/font/` and
  drop the provider. (The KDoc's old objection was to Roboto Flex *variable*
  rendering light — static Inter/PJS files don't have that problem.) APK cost
  ~0.6-1 MB; zero first-frame reflow, deterministic rendering.

**L2. Splash → first frame seam.** Verify the themed splash/window background
matches `surface` in BOTH schemes (`values/themes.xml`, `values-night/`); any
mismatch = white/black blink on every launch. Also check
`android:windowBackground` vs first Compose frame during mode flips
(guest ↔ authed re-creation).

**L3. Edge-to-edge & system bars.** Audit: content draws edge-to-edge with
transparent system bars (`enableEdgeToEdge`); the floating navbar's
`navigationBarsPadding` leaves no opaque strip; status-bar icon contrast flips
with theme (exists in `HortayTheme`) AND with the profile-hero cover when it
scrolls under the status bar. Three-button-nav devices get a readable scrim,
gesture-nav gets none.

**L4. IME choreography.** Add-channel sheets, search fields, proxy editor:
keyboard must animate content via `imePadding()`/`imeNestedScroll` — no
hard jump when IME opens, no field hidden behind the keyboard, sheet doesn't
snap-resize after the keyboard settles.

**L5. List-reuse ghosts.** Fast-fling the feed and channel lists: recycled rows
must never show the PREVIOUS post's media/avatar for a frame. The reaction-chip
key fix (PostCard KDoc, `key(item.kind.stableKey)`) is the pattern; verify
avatars (`TdAvatar`) and media blocks key/reset on item identity, and Coil
requests set stable memory-cache keys + placeholder = previous-of-SAME-key only.

**L6. Video first-frame flash.** Inline autoplay and the fullscreen player: black
(or white) flash between poster and first decoded frame. Keep the poster
composited until `onFirstFrameRendered`, then crossfade 100-150 ms
(`fastEffectsSpec`).

**L7. Hairline seam artifacts.** Anti-aliased rounded corners over contrasting
fills can show 1 px seams (chip border + fill drawn separately, divider under
translucent chrome). Where found, draw border+fill in one layer or inset the
border by 0.5 px.

**L8. Predictive-back frame integrity.** Trigger predictive back slowly on every
overlay (channel, comments, viewer): no flash of the destination at gesture
start, no double-render at commit. The seekable `predictivePopTransitionSpec`
contract (ARCHITECTURE.md nav row) must stay BARE — fixes go to the screen
content, not the spec.

**L9. Theme-flip flicker.** Toggling dark/light/dynamic in Settings: one clean
crossfade, no intermediate mis-themed frame on Settings itself or the feed
behind it.

**L10. Overscroll consistency.** Stretch-overscroll must appear on feed, channel,
comments, settings, sheets — and nowhere fight a nested-scroll connection
(the floating-bar connection consumes Y; verify it doesn't eat the stretch).

### WS-M — Perceived performance («затупи»)

Where the app FEELS slow even when it isn't, and the standard tricks to mask
real latency. (Real jank → profile first; this section is about perception.)

**M1. Instant press acknowledgement everywhere.** Audit EVERY clickable for a
visible pressed state within one frame: settings rows, channel rows, stat
pills, link chips, sheet rows. Anything relying on a slow ripple on white gets
the corner-morph or a `surfaceContainerHigh` pressed tint. (Doctrine 1 made
mechanical.)

**M2. Optimistic UI completeness.** Reactions are optimistic (CHANGELOG 0.5.0).
Extend the same contract to: bookmark toggle, channel mute/unmute, hide/unhide,
subscribe in discover (H1 already specifies), poll vote staging. Rule: flip the
UI immediately, reconcile via the update stream, roll back + snackbar on error.

**M3. Counter/число transitions.** Live counters (views ticking, unread badge,
reaction counts) change value with a mini digit-roll: `AnimatedContent` with
`slideInVertically(±)` + fade on the NUMBER only (Telegram's counter idiom),
`fastEffectsSpec`, and tnum (C2) so width stays fixed. Apply to: reaction chip
count, unread FAB badge, channel unread badges (F1), comments count in the
post-detail subtitle.

**M4. Layout-change animation in lists.** Accepted arrivals (pill tap), folder
switches, and deletions animate with `Modifier.animateItem()`
(fade+slide) instead of teleporting rows. MUST NOT alter the arrivals-commit
contract or scroll-anchor logic — presentation only; verify no anchor jumps in
both feed orders.

**M5. Progressive media ladder visibility.** The minithumb → thumb → full ladder
exists; verify every media surface paints SOMETHING within one frame of
composition (inline minithumb is never skipped before network), and the final
swap is a crossfade, never a pop (0.9.0 shipped this for most surfaces — audit
stragglers: web-preview images, discover avatars, poll banners).

**M6. Transition-latency budget.** Screen pushes must mount within one frame of
tap (the 400 ms channel await is the sanctioned exception — ARCHITECTURE.md).
Audit remaining taps for accidental synchronous work before navigation
(profile sheet, archive sheet, settings sub-screens): heavy reads go behind
the push, not before it.

### WS-N — Perceptual design tricks (premium polish)

Cheap, high-signal tricks premium apps layer on. Each is optional individually,
but the batch is what closes the "expensive feel" gap. Respect reduced-motion
for every one.

**N1. Frosted chrome (blur-behind).** Floating navbar + feed brand/folders
overlay + comments top bar get background blur on Android 12+
(`Modifier.graphicsLayer + RenderEffect.createBlurEffect`, ~20-24 dp radius)
over a `surfaceContainer @ ~0.75` tint; below S, fall back to the opaque tint
(current look). Content visibly sliding UNDER glass chrome is the single
strongest "premium OS-grade" cue. Measure GPU cost on a mid-range device;
gate behind the same reduced-motion/battery-saver checks if needed.

**N2. Shared-element media open.** Feed/channel media tile → fullscreen viewer
via `SharedTransitionLayout` + `sharedBounds`: the image FLIES from its card
slot to fullscreen instead of cross-fading through black. This is the highest
effort item in this WS — prototype on photos only; videos/albums can keep the
current transition if the matrix math fights ExoPlayer surfaces.

**N3. Text scrims on imagery.** Any text/control over a photo (viewer chrome,
poll photo banners, hero covers) sits on a vertical gradient scrim
(`Color.Black @ 0 → 35%`), never raw. Audit viewer top/bottom bars, album
counters, video time chips.

**N4. Optical alignment pass.** Mechanical centering ≠ visual centering:
play triangles nudge ~1.5 dp right inside their circle; the `›` drill chevrons
align to text cap-height, not line box; icon-before-text rows (stat pills,
chips) get -0.5..1 dp optical baseline tweaks. One pass with layout-bounds on,
fix per call site. Smallest item in this doc, disproportionate craft signal.

**N5. Reaction micro-burst.** On choosing a reaction (not on un-choosing): a
6-8 particle radial micro-burst from the chip (reuse the spoiler particle
machinery at 10% scale), plus the existing haptic. One-shot, 250-300 ms,
effects spec. Skip under reduced motion.

**N6. Dwell-read afterglow.** When the unread strip collapses (dwell-ack), let
the strip's last frame emit a 1-frame-wide soft glow that fades (200 ms) —
makes "marked read" feel like an event without adding a persistent signal.
(Builds on the existing twin alpha/shrink springs in PostCard.)

**N7. App icon & monochrome audit.** Verify the launcher icon has a proper
monochrome layer (Android 13 themed icons), and its silhouette/colour matches
the new brand-first identity (BrandMark fix in B2 may cascade here).

**N8. Snackbar placement.** Snackbars/banners must rise above the floating
navbar and the merged pill (B1) — never overlap them. One `SnackbarHost`
offset rule in the scaffolds.

### WS-O — Surfaces NOT yet audited (coverage completion)

The 2026-06-09 audit covered: feed, comments, post detail, channels list, saved,
settings/profile, add-channel sheet. The agent must run the SAME audit lens
(sections 1-2 root causes + doctrine) over the remaining surfaces before calling
the polish done:

- **Auth flow** (`ui/auth/*` — phone, OTP, password, QR?, action-required, proxy
  setup entry): first impression of the whole app; check type scale, button
  hierarchy, error states, IME behaviour (L4), loading affordances.
- **Fullscreen media viewer** (`ui/media/*`): chrome scrims (N3), dismiss-gesture
  fade (0.8.0 shipped a version — re-verify against the clean canvas), share/save
  feedback, video controls family (`VideoPlayerControls.kt` morphs exist).
- **Poll block** (`PollBlock.kt`): result-bar animation, voted-state colour
  semantics under the new fill discipline (lavender = your choice only).
- **Web link preview cards** (`PostWebPreview.kt`): frame vs D1 media rule,
  favicon/site-name hierarchy.
- **Action sheets & dialogs** (PostActionSheet, link sheets, confirm dialogs,
  report flow, country picker): one corner family (`extraLarge` top corners),
  drag-handle consistency, button order (confirm right), destructive = error
  colour, list-item icon sizes consistent with G1.
- **Channel screen header** (`ChannelHeaderBar.kt`): subscriber-count layout shift
  was fixed (0.6.0) — re-verify; avatar/title/badge alignment vs B2 brand bar.
- **Archive / revision sheets** (`ui/archive/*`): diff colours derive from
  semantic tokens (added=tertiary-ish, removed=error-ish container tints), not
  hardcoded greens/reds; timeline dots vs the new clean canvas.
- **Guest (web) mode parity**: every WS change lands in `WebModeScaffold` surfaces
  too — guest mode must not lag one design generation behind (it's the first-run
  experience for unauthenticated users).
- **Tablet / landscape sanity** (not a redesign): nothing breaks at 600 dp+ width —
  feed max-width cap (~640 dp content column, centered) is a cheap premium win if
  currently stretching full-bleed.

### WS-K — A11y & i18n sweep (gate, runs last)

- New interactive surfaces: `Role.Button` + meaningful `contentDescription`
  (stringResource, never literal).
- Merged B1 control announces its current state, not both.
- Touch targets ≥ 48 dp everywhere chips shrank (FoldersBar row padding keeps the
  target — verify with layout-bounds on device).
- `./gradlew :app:lintRelease` (MissingTranslation gate) + `./gradlew test` +
  `./gradlew :app:detekt` all green; compose stability report shows no new
  unstable classes.

---

## 7. Suggested execution order & commits

Each numbered batch = one Conventional Commit; changelog bullet(s) per batch.

1. `feat(timeline): merge arrivals pill and unread counter into one morphing control` (B1)
2. `feat(main): brand bar accent, trailing actions, scroll tint` (B2+B3+B4)
3. `style(timeline): stat-row lightening, tabular counters, card press scale` (C1-C4)
4. `style(media): hairline frames and corner audit` (D1-D2)
5. `feat(comments): thread connectors and avatar stepping` (E1-E3)
6. `feat(channels): unread badges and recency metadata` (F1-F2)
7. `style(settings): icon unification, hero softening, section labels` (G1-G3)
8. `feat(discover): subscribe-button states and search idiom` (H1-H3)
9. `feat(app): custom pull-to-refresh, skeleton/error/offline polish` (I1-I4)
10. `fix(app): flicker hunt — bundled fonts, splash seam, IME, list ghosts, video first frame` (L1-L10; L1 early — it's a confirmed full-app flicker)
11. `perf(app): perceived-latency pass — optimistic toggles, digit rolls, item animations` (M1-M6)
12. `feat(app): first-paint stagger, haptics, dark-theme pass` (J1-J3)
13. `feat(app): perceptual polish — frosted chrome, scrims, optical alignment, micro-bursts` (N1, N3-N6, N8; N2 shared-element as its own stretch commit; N7 with B2)
14. `style(app): remaining-surface audit fixes` (WS-O findings, split by package scope as they surface)
15. K-sweep folds into each batch's gate, not a separate commit.

Note: L1 (bundled fonts) is independent and high-value — it may be cherry-picked
to the front of the queue, even before B1.

Phase-1 token work already in the worktree should be committed FIRST (after the
owner's device verification) as:
`style(app): clean-canvas pass — brand palette default, type hierarchy, single unread signal, ghost chips`
plus `[Unreleased]` changelog entries (user-POV, e.g. "The app now uses Hortay's
own colours by default — wallpaper-matched colours stay available in Appearance").

## 8. Out of scope / do not touch

- Everything in ARCHITECTURE.md "Load-bearing" table (TDLib pipelines, cold-start
  contract, album rehydration, nav stack semantics, arrivals-commit contract).
- No new feed signals or auto-accept behaviours.
- No new dependencies (no Lottie, no design libs) — the expressive vocabulary in
  `ui/theme/Shape.kt` is the toolkit.
- WebM/ffmpeg pipeline, baseline profiles, build config.
- Feature work (search, notifications, etc.) — this is a polish pass only.

## 9. Device QA script (owner verifies on hardware)

1. Fresh install → brand periwinkle out of the box; toggle wallpaper colours on/off.
2. Feed scroll: chrome slides 1:1, tint appears when content passes under; one
   floating control bottom-anchored, morphs between arrivals/unread states.
3. Long feed session: unread strips collapse on dwell; no other unread signals.
4. React to a post: ghost → lavender fill morph + haptic; counter doesn't shift
   neighbours (tnum).
5. Comments: connectors read parent→child; reply avatars smaller; white-background
   sticker shows a frame edge.
6. Channels: unread badges tick down as feed dwell-acks land.
7. Settings: one icon style; hero gradient harmonises; archive row reads as history.
8. Add channel (guest + authed): no duplicated handle lines, buttons aligned,
   in-flight spinner, "Додано" confirmation.
9. Airplane mode: banner appears, cached content stays readable, PTR fails politely.
10. Dark theme + "remove animations" accessibility setting: every change above
    still correct.
11. Cold start offline / Play-Services-less device: typography identical to online
    start (no font swap reflow); launch shows no white/black splash seam.
12. Fast-fling feed and channels: no recycled-row ghosts (wrong avatar/media for a
    frame); videos show poster → crossfade, never a black flash.
13. Slow predictive-back on every overlay (×5 animator scale): no destination
    flash at gesture start, no double frame at commit.
14. Keyboard open/close in add-channel and search: content follows the IME
    animation, nothing jumps or hides behind it.
15. Live counter tick (views/reactions/badges): digit rolls in place, neighbours
    don't shift.
16. Android 12+: chrome shows frosted blur with content sliding under it; below 12
    (or battery saver): clean opaque-tint fallback, no half-state.
17. Auth flow walkthrough (fresh install, wrong code, no network): every state
    designed, no naked spinners, IME correct.
18. Themed (monochrome) launcher icon renders correctly on Android 13+.
