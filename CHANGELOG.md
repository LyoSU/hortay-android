# Changelog

[Keep a Changelog](https://keepachangelog.com) · [SemVer](https://semver.org). One user-visible change per bullet, one sentence. Rationale lives in commits and KDoc — not here.

## [Unreleased]

### Changed
- Refreshed the app's icons to a softer, more modern rounded set throughout the interface.
- The app now uses Hortay's own periwinkle colours by default — wallpaper-matched colours stay available in Appearance.
- Cleaner reading canvas — post cards lighten, an unread post shows a single edge strip instead of a full-card tint, and reaction chips rest as outlined ghosts that fill with lavender when you pick one.
- The "new posts" alert stays centred and the "↓ N" unread button keeps its corner, laid out so they can never overlap — instead of two badges colliding or one button changing meaning under your finger.
- The feed's wordmark, folder tabs and the status bar above them now tint as one surface when posts scroll under them, so the first post no longer collides with the tabs; channel screens tint their header and status bar the same way.
- Photos, videos and link previews carry a faint hairline frame so a light image no longer dissolves into the background.
- Comment replies indent with smaller reply avatars and a capped depth, reading as a conversation instead of a staircase running off the screen edge.
- Long-pressing a comment now highlights the whole row edge to edge, instead of a tight grey box pressed against the text.
- Settings use one consistent icon style, section headers are quieter, and the profile header gradient is softened to harmonise with the app's palette.
- Channel rows show when each channel last posted, mark unread channels, and flag channels you've hidden from the feed.
- In Discover and Add channel, the Subscribe button keeps a fixed size and shows a spinner then a checkmark as it works, and the search box now reads as a rounded search bar instead of a form field.
- Pull-to-refresh shows a custom indicator with a haptic tick at the release threshold — a clean disc while you pull, the app's morphing loading shape while it refreshes.
- The fullscreen media viewer rests its controls on a subtle gradient so they stay legible over any photo.

### Fixed
- Photo albums that arrived while the app was closed now fill in all their photos when you scroll to them, instead of sometimes staying stuck on a single image.
- A fresh album landing in the feed no longer briefly shows with only part of its photos while the rest arrive.
- An album of documents or audio files no longer renders as a blank card.
- Reactions and view counts on the post you're reading now keep updating live even when the previous post you read was from the same channel.
- Brand typography now renders identically on every launch — including offline and on devices without Google Play Services — instead of briefly showing a fallback font and reflowing the whole screen.
- Videos in the fullscreen viewer no longer flash black before the first frame — the poster stays visible until the video is ready.

### Performance
- Live counters (views, reactions, unread) keep a fixed width so neighbouring elements don't shift when a number ticks, and reaction counts roll their digits in place.
- The feed gently staggers its cards into place on a cold start.

### Build
- StrictMode (main-thread disk/network I/O and leaked-resource detection) in debug builds.
- Automated dependency updates and vulnerability scanning via Dependabot and OSV-Scanner.
- Third-party dependencies consolidated into the Gradle version catalog; androidx.browser updated to 1.10.0.
- Inter and Plus Jakarta Sans are now bundled with the app instead of fetched at runtime, dropping the Google Fonts / Play Services font dependency.

## [0.10.5] — 2026-06-01

### Added
- Connect through a proxy — add SOCKS5, HTTP or MTProto servers by pasting a Telegram proxy link or entering the details by hand, pick the active one, and check each server's reachability and latency.
- Proxy can be set up on the sign-in screen too, so you can get online to sign in from a network that blocks Telegram.
- If the active proxy stops responding, the app automatically switches to the next working one in your list instead of getting stuck.

### Changed
- The login-code screen now tells you the code arrives in your Telegram on another signed-in device, not by SMS, so you're not left waiting for a text that never comes.

### Fixed
- In guest mode, typing a channel name or link by hand into "Add channel" now starts the search — tap the keyboard's search key or the "Find" button — instead of only working when a link was auto-filled from the clipboard.
- After restarting the app the feed again shows several recent posts per channel, instead of collapsing to just the single latest post from each channel.

## [0.10.4] — 2026-05-31

### Fixed
- Fixed a crash when an animated WebM sticker or custom emoji (transparent, animated) appeared in the feed on many arm64 phones — the transparent-sticker decoder now picks code paths that match the device's processor instead of assuming the newest instruction set.

## [0.10.3] — 2026-05-31

### Added
- Find and add channels without leaving Hortay — search public channels by name when signed in, and pick from curated suggestions grouped by topic (news, technology, culture, science, humour…) in both signed-in and guest modes.
- Channel suggestions now show each channel's avatar, real name and subscriber count, with the author's own channels highlighted at the top.

### Changed
- The suggested channels are a refreshed, verified set loaded from an online list — Ukrainian channels first for Ukrainian readers, an international set otherwise — so it stays current without an app update.

### Fixed
- Guest mode's empty feed no longer offers "Open Telegram to subscribe" — a Telegram account subscription never reaches the anonymous feed, so the empty state now points to adding a public channel with the + button.
- A long feed post with a block quote no longer cuts a line of text in half where it collapses — the "Показати більше" trim now lands on a clean line boundary, including inside the quote.
- Collapsible quotes now preview collapsed to a few lines in the feed too, instead of always showing in full, with a chevron marking there's more — tap the post to open it and expand them.

## [0.10.1] — 2026-05-31

### Changed
- The home feed's top bar and bottom navigation now slide away as you scroll without dragging on the gesture — the feed follows your finger 1:1 instead of stalling while the bar collapses.

### Fixed
- On a fresh launch the feed's folder tabs no longer cover the top of the first post and the brand bar now collapses fully on scroll, even when your folders or archive finish loading a moment after the feed appears.
- In guest mode the bottom navigation now slides away as you scroll the feed, and the add-channel button lowers to follow it instead of both staying fixed.

## [0.10.0] — 2026-05-30

### Added
- Collapsible quotes open collapsed to a few lines with a chevron to expand or collapse them everywhere — feed, channels, comments and the open post — instead of always showing whole.
- Tapping a link, @mention or hashtag now highlights it with a rounded, padded fill, giving the same clear press feedback across every link type.

### Changed
- Block quotes now look and collapse the same on every surface — a padded accent box sized to its text with a quote mark in the corner — instead of a cramped, always-open band in the feed and channels.
- Code blocks now render in a box with their language label, instead of a ragged inline highlight that broke across lines.
- Web link previews now sit in the same accent-bar-and-tint frame as block quotes, so a pulled-in link reads as the same kind of reference.
- Tapping a post or its "Show more" opens the full post and its comments scrolled to keep your reading position, instead of jumping to the top of a long post.

### Fixed
- The "next unread" button now marks the post you were on as read immediately and steps to the next one, instead of sometimes re-landing on the same post and leaving it unread until you scrolled.
- A tapped link, @mention or hashtag that wraps across lines now highlights as one connected shape, instead of a separate rounded pill on each line.
- Link highlights and entity styling (inline code background, underline, strikethrough, spoiler covers) now hug the actual text instead of spilling onto leading or trailing blank space.
- A post with a quote or code block now collapses as one with a single "Show more", instead of showing a separate "Show more" for each stretch of text between the blocks.
- A quote or code block at the very top or bottom of a post now keeps its padding instead of sitting flush against the text edge.
- Posts and quotes no longer render with stray empty lines hanging off the top or bottom, and the gap around quote and code blocks is now consistent.
- Switching between folder tabs (Archive ↔ All ↔ a folder) no longer flashes a row of skeleton bars over the see-through feed for a moment before the posts settle.
- Custom emoji now display inside poll questions, descriptions and options, instead of showing as an empty placeholder box.
- Photo and video albums now open complete on the first launch instead of occasionally showing a single item until you tapped the post.

### Architecture
- Navigation migrated to Jetpack Navigation 3 (`NavDisplay`), replacing the hand-rolled overlay stack across both authenticated and guest modes — predictive back, per-screen saved state and view-models are now framework-owned.

## [0.9.1] — 2026-05-29

### Fixed
- The "next unread" button now reliably marks the post it lands you on as read, so the counter ticks down and each tap steps to the next unread instead of occasionally sticking on the same post.
- A post's reactions, view count and reply count now keep updating live while you're reading its comments, instead of freezing at the values they had when you opened it.
- Custom-emoji reactions no longer briefly show the same emoji twice when a post's reactions re-order while loading — each reaction now keeps its own image instead of borrowing a neighbour's.

## [0.9.0] — 2026-05-29

### Added
- Video stickers and animated custom emoji now show their real transparency and animate inline, instead of appearing as a flat square or a frozen image.
- Guest mode's empty Channels screen now offers a few popular channels to add with one tap, instead of leaving you with a blank field.
- Screen readers now announce which posts are unread, matching the unread strip sighted users see.
- Screen readers now announce the "new posts" divider as a section heading, so the read/unread boundary is reachable by heading navigation.
- Reactions, bookmarks, poll votes and the main action buttons now answer with a subtle haptic tap.
- Long-press a post or a comment to react with any available reaction, not just the ones already on it — picked from a Telegram-style strip at the top of the menu.
- The post menu now has a "Select text" action that opens the text so you can select and copy any part of it; long-pressing a comment also offers "Copy text".
- Channel posts now show their forward count alongside views and comments.
- Settings → Appearance lets you keep Hortay's own colours instead of your wallpaper's palette (Android 12+).
- Your profile and other people's profiles now show their own Telegram colour — a matching gradient behind the photo and a coloured ring around the avatar.

### Changed
- Unread posts now carry a faint tint across the whole card, not just the edge strip, so the read/unread boundary reads at a glance and fades away as a post is marked read.
- The post long-press menu now leads with Comments, Share and Open as a single button group, with the remaining actions listed below.
- Spoiler reveals and the fullscreen photo viewer now honour the system "remove animations" setting, staying still for motion-sensitive users.
- "Next unread" and home-tap jumps now land your oldest still-unread post at the top of the screen with its header fully visible and a brief highlight, and step forward to the next unread as you read — instead of bouncing back onto a post you had already opened or landing it cut off under the bar.
- Tapping a post's channel name now opens that channel scrolled straight to the post you tapped, with a brief highlight, instead of landing at your last-read position.
- Revealing a spoiler now disintegrates the dot cloud as a left-to-right sweep that crumbles evenly and blows away, matching Telegram's dissolve, instead of the old uniform burst from the centre.
- Ukrainian interface now speaks in a consistent informal tone, with wording and grammar fixes throughout.
- The Profile tab is reorganised Telegram-style: quick Appearance and Feed switches stay up top, while Data & storage, Privacy and About each open as their own page.

### Fixed
- Posts now get marked as read as soon as 60% of the card is on screen for half a second, instead of staying unread until you scroll them further into view — including posts taller than the screen, which would previously stay unread forever and trap the "next unread" pill on the same row.
- Tapping a reply quote, a forwarded source, or a channel name now lands with the post's header at the top of the screen — tall posts no longer open with the header cut off above and the middle of the post in view.
- The "Newest at the bottom" feed now behaves like a real chat — the newest post sits at the bottom, new posts arrive there and the channel follows them as they come in, and switching feed direction no longer leaves the scroll in a strange spot.
- Opening a channel in "Newest at the bottom" now lands on your oldest unread post with the unread queue visible below it, instead of pinning it to the bottom edge with already-read posts filling the screen above.
- Signing out now warns that it will erase your local post archive — shown only when the archive is turned on.
- A post you're viewing no longer gets marked as read while the screen is off or the app is in the background.
- Media now dissolves into place as it loads instead of popping in — photos, channel and author avatars, stickers, custom emoji and GIFs no longer blink a grey placeholder before appearing.
- Switching to Channels or Settings and back to the feed no longer flashes a skeleton — the feed paints in place at the same scroll position instead of replaying a loading state.
- Channel avatar on a deleted-post tombstone no longer flickers between the initial letter and the photo when other channels in the archive update.
- Settings → Source code row and the long-press link sheet's Share action now show their proper icons instead of a generic `?` placeholder.
- Tapping the empty space between a post's channel name and its timestamp no longer drills into the channel — only the channel name, verification badge and chevron itself respond now.
- Scrolling past deleted posts no longer stalls the feed or slows neighbouring posts from loading — a deleted post shows its cached preview instead of endlessly retrying media Telegram has already removed.
- Photo albums now reopen with all their photos after relaunching the app, instead of showing a single photo until you scrolled to the post.
- The post archive no longer crashes the archive screen when a saved revision can't be read — the unreadable entry shows blank and the rest of the history still opens.
- Archive cleanup no longer leaves behind orphaned media files on disk when trimming old snapshots.
- Tapping Submit twice in quick succession on the phone-number screen no longer clears the number you typed.

## [0.8.0] — 2026-05-25

### Added
- Post archive: edited and deleted channel posts are saved locally with a visual diff between versions — disabled by default, configurable in Settings → Post archive.
- Archive now snapshots photo, video, animation, document, audio, voice and round-video media at capture time, so the revision sheet shows the deleted post's media instead of just its text. When the full file isn't in Telegram's cache the inline preview is shown blurred with an honest "preview only" label.
- App now reads in 11 more languages — Russian, Spanish, German, French, Italian, Portuguese (Brazil), Polish, Turkish, Indonesian, Persian and Arabic — pick yours in Settings → Language or via the system per-app picker.

### Changed
- Feed-order toggle in Settings is now positional — "Newest on top" / "Newest at the bottom" with one-line subtitles — and switching it pops a confirmation explaining where new posts will appear.
- The post's unread strip now collapses with a brief shrink-and-fade when dwell-ack marks the post read, instead of a passive opacity fade.
- Empty Timeline and Channels tabs now offer an "Open Telegram to subscribe" button — channels you join there flow back into Hortay via the existing update stream, no manual refresh needed.
- The "Action required" auth screen (banned number, premium required, email confirmation, etc.) now has a one-tap "Open Telegram" button next to Retry.
- Guest mode now offers a tappable "Sign in" — inside the post-detail screen when comments are gated, and as the action on the "sign in to open private channels" snackbar.
- Paid (⭐) reactions are visibly dimmed and tapping one offers to open *that specific post* in Telegram, not the Telegram root.
- Channel-drill author chips show a subtle "›" chevron when the tap leaves the current screen, separating navigation from in-place actions.
- Replies and inline quotes in posts and comments now sit inside a soft accent-tinted block with a small corner glyph, matching Telegram's modern reply and quote look.
- Swipe-to-dismiss in the fullscreen photo viewer now fades the dim away with the gesture and no longer carries a light-grey sheet around the photo's letterboxed edges — the feed underneath becomes visible as you pull the photo down.

### Fixed
- A rare custom emoji-status with a malformed animation no longer crashes the app on draw — the affected emoji falls back to its static thumbnail instead.
- First sign-in on a fresh install no longer leaves the feed with only one post per channel — the top channels you read most start populated with a few recent posts each, so the feed lands with real scroll headroom instead of two screens of single-post cards.
- TDLib operation errors no longer surface the internal numeric code to users ("Couldn't refresh feed (400)" → "Couldn't refresh feed").
- Hashtag tap no longer promises the feature in "the next update"; the snackbar now describes the actual limitation (search works inside a channel).
- The "X new posts" pill now stays visible at every scroll position, including at the very top of "Newest on top" and the very bottom of "Newest at the bottom" — previously it auto-hid at the freshness edge and the arrivals could end up off-screen with no way to see them short of a pull-to-refresh.
- The "Unread" divider in "Newest at the bottom" feed now reads "New posts" — same wording the floating arrival pill uses, since both surfaces mean "posts you haven't read yet" from the reader's point of view.
- Voting in a poll no longer creates a fake "edit" entry in the archive — only real admin edits are recorded.
- Live-location ticks, paid-media reveals, self-destruct timer expiries and other internal TDLib content updates no longer fill the archive with false revisions.
- The post-archive revision timeline now anchors the first dot at the post's publication time, so a freshly-edited post reads as "published → edited" rather than the first dot misleadingly carrying the edit timestamp.
- Archive purge and retention sweep no longer leak media files on disk — clearing snapshots also reclaims their image and video bytes.
- Deleted comments no longer appear as ghost cards in the channel feed — the tombstone reconstruction now scopes itself to channel posts and leaves comment deletions inside their discussion thread.
- The post-archive revision timeline keeps the publication-time anchor even on the rare cold-start race where an admin's edit reaches the app before the as-published snapshot — earlier the two dots could read out of order.
- Edits and deletions made while the app was closed are now archived on the next launch — previously only posts visible in the feed at the moment of the change were recorded, so a delete arriving via Telegram's catch-up stream for an older post slipped past the archive.
- Empty tombstone cards no longer appear at the bottom of the feed — deleted posts whose content was never captured (typically posts that were already edited the first time the app saw them) are now either archived on ingest with their first-observed state, or hidden from the feed if their content is genuinely unrecoverable.

## [0.7.0] — 2026-05-21

### Added
- Settings → Profile now opens with a Telegram-style header card: avatar, display name and @handle or phone number, with a premium star indicator if your account has it.
- Comment authors who have Telegram Premium now show a small gold star next to their name, matching the indicator in user profiles.
- Custom emoji-status renders in place of the gold star wherever a user identity is shown — settings hero, user-profile sheet, and comments — including NFT / collectible-gift statuses (rendered as the gift's model emoji), and respects the status's expiration date.
- Settings → Author now has a "Source code" row that opens the app's GitHub repository.

### Changed
- The Premium badge in your own and other users' profiles is now a gold filled star, matching Telegram's own indicator instead of the previous placeholder glyph.
- Settings has a single "Sign out" action again — the duplicate "Continue without account" row is gone. Sign out lands you on the auth screen where "Read anonymously" is already one tap away.

### Fixed
- Reactions, view counts and reply counts now refresh live on the post you're reading in the feed — previously the feed often opened a chat for a neighbouring partially-visible card and the post you were actually looking at stayed silent until you scrolled past it.
- Reactions on an opened channel update in real time instead of staying stuck on the values they had when the screen first painted.
- A reaction change that arrived in the same ~200 ms window as a view-count heartbeat is no longer swallowed by the heartbeat.
- Channels with no posts in the last few seconds before sign-in no longer go missing from the feed until you pull-to-refresh — every subscribed channel's latest post lands on first sign-in regardless of cold-start update timing.
- Archived channels show up in the "Архів" tab immediately after first sign-in instead of only after you toggle archive state in another client.

### Performance
- Cold-start feel: the daily storage sweep no longer runs alongside the chat-list load, so the first feed paint after launch lands sooner.

## [0.6.0] — 2026-05-19

### Added
- Round video messages play inline with progress ring, time chip, tap-to-pause and independent mute toggle.
- Hide channels from the home feed without unsubscribing (Channel info → "Hide from feed"; Settings → Hidden channels manages the list).
- Settings → Privacy → "Invisible reading" hides online status in Telegram while reading Hortay.

### Changed
- Posts now mark as read after 500 ms of viewport-stable dwell, down from 1 s.
- Opening a comments thread or user profile is instant — the destination enters its transition animation in the same frame as the tap, prefetch runs in parallel, and fast loads paint zero skeleton; only opens still loading past 120 ms surface a skeleton.
- Opening a channel from a feed post waits briefly (up to 400 ms) for the deeper history to land before mounting the screen, so the channel always opens with the full slice in place — no more "one post then 79 older posts visibly merge in above" on cold first opens.
- Skeleton anti-flicker grace tracks the system animator-duration-scale, so users who disabled animations see feedback immediately and users on x2 animation speed get a proportionally longer grace.
- Tapping the forwarded-from author on a forward chip drills into the source channel AT the original post, not just at its newest entry.
- Auto-download settings (Wi-Fi / Cellular / Roaming) now sync across Telegram clients via your account.
- Tapping an `@username` mention in a post opens the in-app user profile sheet instead of bouncing out to Telegram.
- The "↓ N unread" affordance in `OldestUnreadFirst` mode is now a circular scroll-to-bottom FAB with a count badge that softly bursts on press; tap returns you to the boundary where you left off reading, and the pill stops looking like a twin of the "X new posts" alert when both surface at once.
- Post timestamps and FLOOD_WAIT countdowns now space the number from its unit (`5 хв`, `45 с`) instead of gluing them together.

### Fixed
- Empty folders and folders without channel matches now auto-hide from the tab bar instead of staying visible until you tap them.
- Opening a channel from a feed post no longer shows a single post that suddenly grows into the full history mid-scroll; the screen waits for the deeper load and mounts the list in one frame at the right anchor.
- Returning from a channel to the feed no longer jumps to a different post when an album-tailed channel hydrates in the background.
- Channel scroll-up no longer hits an invisible "loading limit"; pagination now triggers near the older edge instead of running away on cold entry in OldestUnreadFirst mode.
- Scroll position is preserved on return from a channel and while scrolling inside a channel; reply chains no longer collapse into a Thread row, so LazyColumn's keyed anchor stays put through every ingest.
- Poll voting works — TDLib code 406 is treated as a silent no-op instead of surfacing as an error and reverting the vote.
- Channel cards paint on the first frame of entry; no white flash before the post appears.
- Channel entry no longer flashes a skeleton on fast resolves (now gated behind a 600 ms grace).
- Returning from Comments → ChannelScreen lands on the feed row you left, not at the top.
- Channel header subscriber count appears on the first frame instead of with a delay.
- Inline videos and GIFs render at correct aspect ratio on autoplay mount.
- Opening a cached channel from the feed no longer flashes a skeleton.
- Channel title no longer jumps upward when the subscriber count loads.
- Snap-scroll mode now lets you read tall posts and feels predictable on gentle flings (one fling = one logical step).
- Feed scroll position preserved across tab swaps even when read cursors advanced under the user.
- `OldestUnreadFirst` no longer lands on ancient or admin-owned posts on cold start.
- Albums render correctly in comments: no phantom comments from album mirrors, and albums posted as comments group as one card.
- "↓ N" unread pill no longer skips every second post; dwell-ack requires the row to be fully visible and the scroll idle.
- Deep-link to an old post no longer surfaces "link not found" on a busy feed; the merged-feed size cap is removed so the just-fetched anchor isn't evicted before the resolver sees it.
- Comments load in one chronological pass instead of revealing the newest first and then squeezing older comments in above.
- Long posts and media captions now collapse with "Показати більше" even when they contain a quote block.
- Tapping the channel chip from a post opened from the feed and swiping back now returns to the post instead of the feed; tapping a channel that's already one swipe-back away pops to it instead of stacking a duplicate.
- Cold-start feed now includes every subscribed channel's latest post, not only channels with an unread one — read context is back in Newest mode and `OldestUnreadFirst` no longer collapses to a tiny unread-only list.
- `OldestUnreadFirst` no longer lands on a weeks-old dormant unread when fresh unread exists; the cold-start anchor picks within a 7-day recency window and falls through to the newest post when nothing recent is unread.
- Never-opened / freshly-joined channels show the unread strip on their posts instead of silently appearing read until the user opens the chat.
- Cold-start anchor no longer lands on a self-authored post in an admin / outgoing-only channel — the `0 / 0` cursor shape (TDLib invariant for channels with no incoming reads) is no longer interpreted as "everything unread".
- Switching folder tabs no longer auto-scrolls onto a weeks-old dormant unread; the same 7-day recency floor that protects the cold-start landing now applies to every scope jump (folder switch, NavBar home re-tap, ↓N pill fallback).
- Returning to the feed from a deep drill (channel → comments → back-back) no longer lands on a post that loaded into the background while the overlay was up; the cold-start anchor is now pinned to the post identity instead of its row index, so ingested history above it can't shift the anchor onto a different row.
- Switching scope (Archive ↔ All, folder ↔ folder) no longer flashes the previous scope's row OR a skeleton for a frame before settling; the scope is now part of the scene identity, and the UI-state latcher initialises directly with the freshly-built Ready value, so LazyColumn mounts at the correct row on the very first paint.
- Video stickers loop cleanly from the start on every cycle instead of getting stuck replaying just the last fragment after the first pass.

## [0.5.0] — 2026-05-17

### Added
- Interactive polls: tap to vote, quiz reveal, multi-answer staging, live countdown, photo banners, explanation sheet.
- Tapping any author surface opens a user profile bottom sheet (avatar, name, presence, bio, personal channel).
- Settings → About → "App language" pins UI language independently of system locale (uk/en).

### Fixed
- Tapping a reply quote / channel chip / foreign-author header inside comments uses an atomic `replaceTop` push — no more flash-and-snap-back.
- Channel header avatar in `ChannelScreen` shows the channel photo, not the latest personal-author admin's avatar.
- Tapping a foreign-channel author header drills into that channel instead of being a dead surface.
- Tapping a reply quote on a comments anchor drills into the original and lands at the replied-to post.
- Icons across the app match their semantic role — Wi-Fi, Cellular, Roaming, Photos, GIFs, Report, etc.
- "Data Saver is on" banner uses the active-state glyph.
- Opening a channel issues `OpenChat` first, so cold-cache history loads instead of showing "No posts".
- In-app channel-opens for non-channel targets show a snackbar instead of an empty channel screen.
- Custom-emoji TGS no longer crashes on stickers with malformed gradients.
- Live comments overlay no longer splices in comments from sibling threads in the same discussion group.
- Feed no longer scrolls up onto the previous post after returning from a drill or comments overlay.

### Performance
- Comments, report sheet, and country picker switched to `ImmutableList` so Compose skips recompositions when state is unchanged.

### Build
- LeakCanary in debug builds.
- Compose Compiler stability reports now generated under `app/build/compose_compiler/`.
- `compose_stability.conf` marks `kotlinx.collections.immutable` types as stable.
- R8 `-repackageclasses ''` shrinks the DEX string pool.
- Cleaned a dead ProGuard rule and several K2 smart-cast leftovers.

## [0.4.0] — 2026-05-15

### Added
- Fullscreen photo viewer: double-tap zoom, pinch-pan with bounds clamp.
- Share button in fullscreen media viewer.
- Web link previews render every `LinkPreviewType*` TDLib ships.
- Telegram Stars paid posts show locked / unlocked state instead of being dropped.
- Paid (⭐) reactions render as star pills (read-only).
- Invoice / Giveaway / Game / Story / Gift posts render as "open in Telegram" cards instead of being filtered out.
- Document / Audio / Voice-note / Video-note cards route taps to Telegram.
- Feed mode `OldestUnreadFirst` with read/unread boundary anchor.
- Snap-scroll mode.
- Per-chat read state with unread strip on the card edge.
- Inline retry on failed guest-mode channel rows.
- Floating "↓ N" unread-remaining counter.
- Settings → Feed → "Autoplay videos in feed" toggle.
- Authenticated Settings → "Continue without account" routes to guest mode without reinstall.
- Guest-mode tap on a post body surfaces a snackbar explaining comments need sign-in.

### Changed
- Default feed order is now `OldestUnreadFirst` (chat-app idiom: read on top, unread queue below, lands at boundary).
- Spoilers reveal as a Telegram-style shimmering particle cloud with Thanos-disperse animation.
- Channels-row status folded into `@handle · status` subtitle.
- Channel-drill rendered as overlay above the always-mounted feed.
- Channel lists, channel-info sheet, and the country picker use Material 3 Expressive `SegmentedListItem` / `ListItem`.
- `OldestUnreadFirst` boundary rule rendered as a peripheral session anchor.
- Feed / channel / deep-link state unified into one declarative state machine; first paint lands at the correct anchor in one frame.
- "↓ N unread" / "↑ N new" / NavBar home-tap pills do an instant jump with brief highlight for far targets, smooth scroll for near ones.
- Deep-link landings show a skeleton, then snap to the target in one frame.
- Guest-mode "Clear cache" asks for confirmation and keeps bookmarks.
- Guest-mode retry available for `NotFound` / `Private` channels.
- Guest-mode polling runs only when in guest mode AND foreground.

### Fixed
- Guest fullscreen viewer Save / Copy / Share work via Coil's on-disk cache; web-mode videos share the CDN URL.
- Fullscreen photo: zoom-out via second double-tap now actually fires.
- Floating "↓ N" pill no longer obscured by the guest-mode FAB.
- Centre play button on an ended video restarts from the beginning.
- Short videos (≤ 60 s) loop in fullscreen too.
- "↑/↓ N new posts" pill lands at the first new post, not the one before.
- Inline reply quote tap scrolls and highlights in the destination, not under the feed.
- Cross-channel reply tap passes the target message id through to the new channel screen.
- Polls / checklists / audio / documents expose text in quote cards.
- Report action uses a moderation glyph instead of the `?` fallback.
- Reactions on the post-detail anchor and on comments actually toggle.
- Fresh posts reach `OldestUnreadFirst` feed without restart.
- Cold-start scroll-pin no longer fires on mid-session arrivals.
- Photo albums no longer ship with missing members on slow networks.
- Cold start waits for the fresh feed; falls back to the cached snapshot only on failure.
- `OldestUnreadFirst` doesn't auto-scroll to the bottom when cursors absent.
- `OldestUnreadFirst` doesn't flash a random ancient post as the first visible card on cold start.
- Editing an album caption no longer collapses the card to a single photo.
- 5-photo album restore on relaunch via targeted `GetMessage` upgrade.
- Snapshot preserves saved album siblings of currently-degraded albums.
- Album cards stay unread until the chat cursor crosses the highest member id.
- Album dwell-read advances the cursor past every member id.
- Share / Open-in-Telegram fallback URL fixed for real-channel ids in `[1, 2^32)`.
- Web-mode media URL rotation re-fetches when a CDN token expires.
- Feed ordering deterministic across refreshes on same-second timestamps.
- `OldestUnreadFirst` boundary divider latched on landing and PTR — no more migration under scroll.
- Deep-link to a pruned target surfaces "link not found" after a 1500 ms grace instead of hanging.
- Guest single-channel screen reads through the per-channel SQL DAO — no more truncated history.
- Guest single-channel screen no longer overlaps the status bar.
- Guest channel chip on a post opens that channel in-app.

### Performance
- Reactions flip optimistically across feed / channel / post detail / comments; server reconciles via `UpdateMessageInteractionInfo`.
- Feed scroll jank rework: snapshot-state cursors, per-item viewport-centre state, `contentType` per FeedItem, conditional autoplay probe, scroll-gated MediaCache resync, late-drop minithumb.
- `TdVideoPlayer` texture attach moved to `factory`.

### Architecture
- `TimelineUiState` / `ChannelUiState` sealed unions; pure `buildUiState` + latched `reduce` + `rememberLatched` replace ~110 lines of `LaunchedEffect` + `snapshotFlow` pinning.
- `LazyListState` constructed once per route via `rememberSaveable(saver)`.

### Build
- Removed three unused Gradle deps (`androidx-navigation-compose`, `compose-material-icons-extended`, `sqldelight-primitive-adapters`).

## [0.3.0] — 2026-05-12

### Added
- CSAE-compliant in-app reporting (`ReportChat`, guest-mode delegation, audit log).
- Safety section in Settings (Report, Child safety, Privacy).

### Changed
- `ChannelScreen` extracted; `TimelineScreen` is feed-only.
- Inline links no longer underlined.
- Reply pill hidden on zero-reply posts.

### Fixed
- Cold launch always lands on Home top-of-feed.
- `#hashtag` taps scope to current channel; `tg://search` URLs parsed.
- Deep-links to inaccessible posts no longer hang on skeleton.
- Link resolver hardening (scheme allowlist, logout invalidation).
- Auto-download skipped during boot; metered networks → photos only.

### Performance
- Cold-start RPC budget cut ~30×.
- Custom-emoji TGS: janky frames 28% → 14%.
- Comments prefetch debounce 700 → 1200 ms.

### Build
- `material3` 1.5.0-alpha19; `compileSdk` 37.
- Unstripped `libtdjni.so` debug symbols.

## [0.2.0] — 2026-05-06

### Added
- Anonymous (guest) mode: read `t.me/s/<channel>` without sign-in; subscriptions persisted across modes.
- Cross-channel local search in guest mode.
- Animated stickers (TGS / WebM / WEBP), emojis, custom-emoji reactions.
- Twitter-style "new posts" pill — feed frozen until accept.
- Predictive back for comments overlay.
- English localisation + plurals.
- Settings → Storage & Traffic.

### Changed
- AddChannelSheet auto-pastes valid clipboard links.
- Comments overlay re-opens instantly within 30 s.
- Cold launch reuses cached feed if < 60 s old.

### Fixed
- Inline-preview video black-square bug.
- Guest-mode text formatting rewritten.
- "Media too big" posts now offer "Open in Telegram".
- Crash on `UpdateMessageInteractionInfo` with null payload.

### Build
- Release/Beta fail at task-graph time without `keystore.properties`.
