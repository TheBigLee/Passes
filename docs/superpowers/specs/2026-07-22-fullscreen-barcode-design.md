# Fullscreen Barcode/QR Code — Design

## Goal

Tapping the barcode on `PassDetailScreen` opens it fullscreen (max size, plain white background)
for easier scanning, with a continuous grow/shrink transform animation between the inline and
fullscreen states — not a fade or a separate dialog window appearing abruptly. See
[GitHub issue #11](https://github.com/TheBigLee/Passes/issues/11) for the seed idea.

## Scope

**In scope:** an in-place, animated fullscreen overlay for the barcode image on
`PassDetailScreen`'s front content, triggered by tapping the existing inline barcode `Image`.

**Explicitly out of scope:** the voided/expired notice and `altText` shown below the inline
barcode today are not carried into the fullscreen view — that context was already visible on the
screen the user tapped from. Brightness handling needs no new code: `PassDetailScreen`'s existing
`DisposableEffect` already boosts brightness to full for the screen's whole lifetime, dialogs or
overlays included.

## Why an in-place overlay, not a `Dialog`

A Compose `Dialog` renders in a separate Android window. A separate window can fade or scale in,
but it cannot visually *grow from* the exact on-screen position/size of the tapped inline image —
there would always be a discontinuity between "small image in the main window" and "new window
appearing." Achieving a true continuous grow/shrink transform requires the animated element to
live in the same composition/window throughout, so this is implemented as an overlay `Box` within
`PassDetailScreen` itself rather than a `Dialog`. This means back-button dismissal is wired
manually via `BackHandler` instead of getting it for free from `Dialog`'s default behavior.

## Animation mechanics

- The inline barcode `Image` (currently a fixed 240dp box in `PassFrontContent`) captures its
  on-screen bounds via `Modifier.onGloballyPositioned { coords -> sourceBounds = coords.boundsInRoot() }`
  — `boundsInRoot()`, not `boundsInParent()`, since the overlay lives at the root of
  `PassDetailScreen` (see below), several composable layers above the inline image's immediate
  parent, so both ends of the interpolation need to share the same (root) coordinate space.
- The barcode is rendered once, at a larger fixed resolution than today (1200×1200 for
  square formats — QR/Aztec — matching the existing square/non-square split; 1600×600 for
  PDF417/Code128), and the *same* `Bitmap` is used for both the small inline display (scaled down
  by Compose's normal image layout) and the large fullscreen display (near-native resolution) — no
  separate re-render step, no quality loss when it grows.
- Tapping the inline image sets `var fullscreen by remember { mutableStateOf(false) }` to `true`.
  A `progress` float — `animateFloatAsState(targetValue = if (fullscreen) 1f else 0f, tween(300))`
  — drives a continuous interpolation between `sourceBounds` and the **whole-screen** bounds
  (`0,0` to the full window width/height in px, via `LocalConfiguration`/`LocalDensity` — not just
  the `Scaffold` content area below the `TopAppBar`, so the barcode can grow to genuinely maximum
  size, covering the app bar too), using `androidx.compose.ui.geometry.lerp(start, stop, fraction)`
  for both the `Offset` (top-left) and `Size` independently.
- The overlay is therefore hoisted to the top of `PassDetailScreen`'s composable — a sibling
  `Box(Modifier.fillMaxSize())` wrapping the whole `Scaffold` — rather than living inside
  `Scaffold`'s content padding, so it can visually cover the `TopAppBar` area as it grows. It hosts
  the animated `Image`, positioned via `Modifier.offset { IntOffset(interpolatedRect.left.roundToInt(), interpolatedRect.top.roundToInt()) }`
  and sized via `Modifier.requiredSize` computed in Dp from the interpolated `Size`. This overlay
  is only composed while `progress > 0f`, so it's removed from the tree once the shrink-back
  animation fully completes.
- The overlay's background is a scrim that fades from transparent to white as `progress` goes
  0→1: `Color.White.copy(alpha = progress)` (a plain white background rather than a black scrim,
  matching the earlier "white full-bleed gives better torch/reader contrast" decision).
- The original inline `Image` sets `Modifier.alpha(if (fullscreen) 0f else 1f)` while the overlay
  is active, so the same barcode is never rendered twice on screen at once during the transition.
- Dismissal: tapping anywhere on the overlay, or the system back button
  (`BackHandler(enabled = fullscreen) { fullscreen = false }`), both just flip `fullscreen` back to
  `false` — the same `progress` animation plays in reverse, shrinking the image back down to
  `sourceBounds` before the overlay is removed.
- No new dependencies: `BackHandler` is already available via the existing `activity-compose`
  dependency. No Compose BOM version change is needed (this avoids the experimental
  `SharedTransitionLayout` API entirely, using plain `Modifier.offset`/`requiredSize`
  interpolation instead).

## Files touched

| File | Change |
|---|---|
| `ui/PassDetailScreen.kt` | Add fullscreen-barcode overlay state/animation to `PassFrontContent` (or a small new private composable it delegates to) |

No domain, data, or importer changes — this is purely a `PassDetailScreen` UI addition.

## Testing

This is a Compose UI animation with no extractable pure-logic component worth unit testing (the
interpolation math is a direct, well-tested standard-library `lerp` call, not custom logic).
Verification is manual, on-device:

- Tapping the barcode grows it smoothly from its inline position/size to fill the screen, ending
  on a plain white background.
- Tapping the fullscreen barcode, or pressing system back, shrinks it back down to exactly where
  it started, without a visible jump.
- The barcode stays crisp/sharp at fullscreen size (no visible re-render, no blur from upscaling
  a too-small bitmap).
- Works for both square (QR/Aztec) and non-square (PDF417/Code128) formats without stretching.
- The bottom-right flip icon and top app bar aren't reachable/tappable while the barcode is
  fullscreen.
