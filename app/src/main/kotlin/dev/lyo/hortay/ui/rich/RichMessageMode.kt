package dev.lyo.hortay.ui.rich

/**
 * Whether [RichMessageBody] renders a bounded feed excerpt or the full reading surface.
 *
 * `FeedPreview` projects the block list to a short prefix ([RichDocument.previewProjection])
 * BEFORE composition, so a feed card never mounts the tables, collapsible details, slideshows
 * or media composables (state collectors, downloads) that sit far below the clamped fold. A
 * pixel clamp ([dev.lyo.hortay.ui.text.ClampedContent]) still trims the projected prefix; the
 * projection bounds the *work*, the clamp bounds the *height*.
 *
 * `Reading` renders the whole document — the detail / comments-anchor surface, and the basis
 * for the Instant-View-style reading mode being designed on top of this plumbing.
 */
enum class RichMessageMode { FeedPreview, Reading }
