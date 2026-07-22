# Fullscreen Barcode/QR Code Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. **Every gradle command in this repo must be run bare — `./gradlew <task>`, no `JAVA_HOME` prefix. That override is obsolete here (Gradle 9.5's daemon toolchain resolves its own JDK) — do not use it.**

**Goal:** Tapping the barcode on `PassDetailScreen` grows it into a fullscreen, plain-white view via a continuous animated transform (not a fade or a separate dialog), and shrinks it back the same way on dismissal.

**Architecture:** The barcode `Bitmap` is rendered once at `PassDetailScreen` level (larger resolution than today) so the same bitmap backs both the small inline image and the large fullscreen overlay. Tapping the inline image captures its on-screen bounds and flips a boolean; an `animateFloatAsState`-driven progress value interpolates between the inline bounds and the whole-screen bounds using `androidx.compose.ui.geometry.lerp`, rendered via a plain overlay `Box` hoisted above the `Scaffold` (not a `Dialog`, since a `Dialog` is a separate window and can't visually grow from a specific on-screen position).

**Tech Stack:** Kotlin, Jetpack Compose (Material 3), no new dependencies (`BackHandler` comes from the already-present `androidx.activity:activity-compose`).

---

### Task 1: Fullscreen barcode overlay in PassDetailScreen

**Files:**
- Modify: `app/src/main/java/ch/bigli/passes/ui/PassDetailScreen.kt`

This is UI-only Compose code with no extractable pure-logic component (the interpolation is a
direct standard-library `lerp` call) — per the design doc's Testing section, there's no unit test
to write first. Verification is: the file compiles, the full existing test suite still passes
(nothing here should break `PassDetailScreenTest.kt`'s `isBareUrlOrEmail` tests or any other
existing test), and a manual on-device check (Task 2).

- [ ] **Step 1: Replace the full contents of `PassDetailScreen.kt`**

```kotlin
package ch.bigli.passes.ui

import android.app.Activity
import android.graphics.Bitmap
import android.text.Html
import android.text.method.LinkMovementMethod
import android.text.util.Linkify
import android.util.Patterns
import android.view.WindowManager
import android.widget.TextView
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.bigli.passes.barcode.BarcodeRenderer
import ch.bigli.passes.domain.BarcodeFormat
import ch.bigli.passes.domain.FieldPosition
import ch.bigli.passes.domain.Pass
import ch.bigli.passes.images.PassImage
import ch.bigli.passes.images.PassImageLoader

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PassDetailScreen(
    viewModel: PassDetailViewModel,
    imageLoader: PassImageLoader,
    onBack: () -> Unit,
) {
    val pass by viewModel.pass.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var flipped by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) {
        viewModel.refreshMessage.collect { snackbar.showSnackbar(it) }
    }

    // Boost brightness while this screen is visible; restore on exit.
    DisposableEffect(Unit) {
        val window = (context as? Activity)?.window
        val original = window?.attributes?.screenBrightness
        window?.attributes = window?.attributes?.apply {
            screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL
        }
        onDispose {
            window?.attributes = window?.attributes?.apply {
                screenBrightness = original ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            }
        }
    }

    val p = pass
    val bgColor = p?.bgColor
    val bg = bgColor?.let { Color(it) } ?: Color(0xFF1A73E8)
    val fg = if (bgColor != null) Color(legibleTextColor(bgColor, p?.fgColor)) else Color.White

    val rawPath = p?.rawFilePath
    // Keyed on lastModified too, so a successful refresh (which overwrites the pkpass file
    // in place) causes the logo/strip images to reload instead of showing stale artwork.
    val logo by produceState<Bitmap?>(initialValue = null, rawPath, p?.lastModified) {
        value = rawPath?.let { imageLoader.load(it, PassImage.LOGO) }
    }
    val strip by produceState<Bitmap?>(initialValue = null, rawPath, p?.lastModified) {
        value = rawPath?.let { imageLoader.load(it, PassImage.STRIP) }
    }

    val rotation by animateFloatAsState(targetValue = if (flipped) 180f else 0f, animationSpec = tween(600), label = "cardFlip")

    // Fullscreen barcode: rendered once here (not inside PassFrontContent) so the exact same
    // Bitmap backs both the small inline display and the large fullscreen overlay below - no
    // re-render step, no quality loss when it grows.
    val barcodeRenderer = remember { BarcodeRenderer() }
    val barcodeBitmap = remember(p?.barcode) {
        p?.barcode?.let { bc ->
            val square = bc.format == BarcodeFormat.QR || bc.format == BarcodeFormat.AZTEC
            if (square) barcodeRenderer.render(bc, 1200, 1200) else barcodeRenderer.render(bc, 1600, 600)
        }
    }
    var fullscreenBarcode by remember { mutableStateOf(false) }
    var barcodeSourceBounds by remember { mutableStateOf<Rect?>(null) }
    var rootBounds by remember { mutableStateOf<Rect?>(null) }
    val barcodeProgress by animateFloatAsState(
        targetValue = if (fullscreenBarcode) 1f else 0f,
        animationSpec = tween(300),
        label = "barcodeFullscreen",
    )
    BackHandler(enabled = fullscreenBarcode) { fullscreenBarcode = false }

    Box(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { rootBounds = Rect(Offset.Zero, it.size.toSize()) },
    ) {
        Scaffold(
            containerColor = bg,
            topBar = {
                TopAppBar(
                    title = {
                        val currentLogo = logo
                        if (currentLogo != null) {
                            Image(
                                currentLogo.asImageBitmap(),
                                contentDescription = p?.organization,
                                modifier = Modifier.height(28.dp),
                                contentScale = ContentScale.Fit,
                            )
                        } else {
                            Text(p?.organization ?: "")
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = fg)
                        }
                    },
                    actions = {
                        if (flipped) {
                            TextButton(onClick = { viewModel.delete(onBack) }) {
                                Text("Delete", color = fg)
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = bg, titleContentColor = fg),
                )
            },
            snackbarHost = { SnackbarHost(snackbar) },
        ) { padding ->
            if (p == null) return@Scaffold
            Box(Modifier.fillMaxSize().padding(padding)) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            rotationY = rotation
                            cameraDistance = 8 * density
                        },
                ) {
                    if (rotation <= 90f) {
                        PassFrontContent(
                            pass = p,
                            bg = bg,
                            fg = fg,
                            strip = strip,
                            isRefreshing = isRefreshing,
                            onRefresh = { viewModel.refresh() },
                            barcodeBitmap = barcodeBitmap,
                            barcodeHidden = fullscreenBarcode,
                            onBarcodeTap = { fullscreenBarcode = true },
                            onBarcodeBoundsChanged = { barcodeSourceBounds = it },
                        )
                    } else {
                        Box(Modifier.fillMaxSize().graphicsLayer { rotationY = 180f }) {
                            PassBackContent(pass = p, bg = bg, fg = fg)
                        }
                    }
                }
                IconButton(
                    onClick = { flipped = !flipped },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(16.dp)
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.25f)),
                ) {
                    Icon(Icons.Filled.Info, contentDescription = if (flipped) "Show front of pass" else "Show back of pass", tint = fg)
                }
            }
        }

        val bmp = barcodeBitmap
        val source = barcodeSourceBounds
        val target = rootBounds
        if (bmp != null && source != null && target != null && barcodeProgress > 0f) {
            FullscreenBarcodeOverlay(
                bitmap = bmp,
                sourceBounds = source,
                targetBounds = target,
                progress = barcodeProgress,
                onDismiss = { fullscreenBarcode = false },
            )
        }
    }
}

/**
 * Renders [bitmap] positioned/sized by interpolating between [sourceBounds] (the inline barcode's
 * captured on-screen position) and [targetBounds] (the whole screen), driven by [progress]
 * (0f = collapsed at sourceBounds, 1f = fullscreen). Lives outside the Scaffold's content padding
 * so it can visually grow over the TopAppBar too - genuinely fullscreen, not just the content area.
 */
@Composable
private fun FullscreenBarcodeOverlay(
    bitmap: Bitmap,
    sourceBounds: Rect,
    targetBounds: Rect,
    progress: Float,
    onDismiss: () -> Unit,
) {
    val topLeft = lerp(sourceBounds.topLeft, targetBounds.topLeft, progress)
    val size = lerp(sourceBounds.size, targetBounds.size, progress)
    val density = LocalDensity.current
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.White.copy(alpha = progress))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            ),
    ) {
        with(density) {
            Image(
                bitmap.asImageBitmap(),
                contentDescription = "Barcode",
                modifier = Modifier
                    .offset(x = topLeft.x.toDp(), y = topLeft.y.toDp())
                    .size(width = size.width.toDp(), height = size.height.toDp()),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PassFrontContent(
    pass: Pass,
    bg: Color,
    fg: Color,
    strip: Bitmap?,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    barcodeBitmap: Bitmap?,
    barcodeHidden: Boolean,
    onBarcodeTap: () -> Unit,
    onBarcodeBoundsChanged: (Rect) -> Unit,
) {
    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        modifier = Modifier.fillMaxSize(),
    ) {
        val isVoidedOrExpired = pass.isVoidedOrExpired()
        Column(Modifier.fillMaxSize().background(bg)) {
            strip?.let {
                Image(
                    it.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxWidth(),
                    contentScale = ContentScale.FillWidth,
                )
            }
            Column(
                Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    pass.fields.filter { it.position != FieldPosition.PRIMARY }.take(4).forEach { f ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(f.label, color = fg.copy(alpha = 0.7f), fontSize = 10.sp)
                            Text(f.value, color = fg, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }
                Spacer(Modifier.size(32.dp))
                pass.barcode?.let { bc ->
                    Column(
                        Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.White)
                            .padding(16.dp)
                            .alpha(if (isVoidedOrExpired) 0.35f else 1f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        if (barcodeBitmap != null) {
                            Image(
                                barcodeBitmap.asImageBitmap(),
                                contentDescription = "Barcode",
                                modifier = Modifier
                                    .size(240.dp)
                                    .alpha(if (barcodeHidden) 0f else 1f)
                                    .onGloballyPositioned { onBarcodeBoundsChanged(it.boundsInRoot()) }
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                        onClick = onBarcodeTap,
                                    ),
                            )
                        }
                        if (pass.voided) {
                            Text(
                                "This pass has been voided by the issuer",
                                color = Color.Black,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        } else if (pass.expirationDate?.isBefore(java.time.Instant.now()) == true) {
                            Text(
                                "This pass has expired",
                                color = Color.Black,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                        bc.altText?.let {
                            Text(it, color = Color.Black, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                        }
                    }
                    Text(
                        "☀ Screen brightened for scanning",
                        color = fg.copy(alpha = 0.8f),
                        fontSize = 11.sp,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                } ?: Text("No barcode on this pass", color = fg)
            }
        }
    }
}

@Composable
private fun PassBackContent(pass: Pass, bg: Color, fg: Color) {
    Column(
        Modifier
            .fillMaxSize()
            .background(bg)
            .verticalScroll(rememberScrollState())
            // Extra bottom padding keeps content clear of the flip icon's touch target, which
            // floats on top of this content in the bottom-right corner.
            .padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 80.dp),
    ) {
        pass.backFields.forEachIndexed { index, f ->
            if (index > 0) {
                HorizontalDivider(color = fg.copy(alpha = 0.2f), modifier = Modifier.padding(vertical = 12.dp))
            }
            Text(f.label, color = fg.copy(alpha = 0.7f), fontSize = 11.sp)
            HtmlText(html = f.value, color = fg, fontSize = 14.sp)
        }
    }
}

/**
 * True only if [value], trimmed, is nothing but a bare URL or email address — not free text that
 * merely contains one. Backing pkpass content is issuer-supplied and untrusted, and
 * [Patterns.WEB_URL] is a known ANR risk on adversarial input via catastrophic backtracking; a
 * real bare URL/email is never anywhere near this long, so anything longer is rejected before it
 * ever reaches the regex engine.
 */
internal fun isBareUrlOrEmail(value: String): Boolean {
    val trimmed = value.trim()
    if (trimmed.isEmpty() || trimmed.length > 512 || trimmed.contains(' ') || trimmed.contains('\n')) return false
    return Patterns.WEB_URL.matcher(trimmed).matches() || Patterns.EMAIL_ADDRESS.matcher(trimmed).matches()
}

/**
 * pkpass backFields values may contain HTML markup (not officially part of the PassKit spec, but
 * some real-world issuers embed it) — rendered via a plain [TextView] since Compose has no
 * built-in HTML support. The source is typically plain text with a few `<a>` tags sprinkled in
 * rather than well-formed HTML, so literal newlines are converted to `<br>` first — otherwise
 * [Html.fromHtml] collapses them the way a browser would collapse whitespace in real markup.
 * A value that's *only* a bare URL/email (no surrounding free text) is auto-linkified instead,
 * since those never carry an `<a>` tag of their own.
 */
@Composable
private fun HtmlText(html: String, color: Color, fontSize: TextUnit, modifier: Modifier = Modifier) {
    val colorArgb = color.toArgb()
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            TextView(ctx).apply { movementMethod = LinkMovementMethod.getInstance() }
        },
        update = { tv ->
            val trimmed = html.trim()
            if (isBareUrlOrEmail(trimmed)) {
                tv.text = trimmed
                Linkify.addLinks(tv, Linkify.WEB_URLS or Linkify.EMAIL_ADDRESSES)
            } else {
                val withLineBreaks = html.replace(Regex("\r\n|\r|\n"), "<br>")
                tv.text = Html.fromHtml(withLineBreaks, Html.FROM_HTML_MODE_LEGACY)
            }
            tv.setTextColor(colorArgb)
            tv.setLinkTextColor(colorArgb)
            tv.textSize = fontSize.value
        },
    )
}
```

- [ ] **Step 2: Run the full test suite to verify nothing broke**

Run: `./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL — nothing in this change touches domain/data/importer code, and
`PassDetailScreenTest.kt`'s `isBareUrlOrEmail` tests are unaffected (that function and `HtmlText`
are untouched by this diff other than moving further down the file).

- [ ] **Step 3: Build the debug APK to catch any Compose compile errors**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/ch/bigli/passes/ui/PassDetailScreen.kt
git commit -m "feat: fullscreen barcode with animated grow/shrink transition"
```

---

### Task 2: Device verification

Not a code task — manual checks on the real Pixel device before merging.

- [ ] **Step 1: Build and install**

Run: `./gradlew :app:installDebug`

- [ ] **Step 2: Verify the grow animation**

Open a pass with a barcode. Tap the barcode. Confirm:
- It visibly grows from its exact inline position/size to fill the whole screen (including
  covering the top app bar), ending on a plain white background.
- The barcode stays crisp at fullscreen size — no visible blur or re-render pop.
- Works correctly for both a square format (QR/Aztec) and a non-square one (PDF417/Code128)
  without stretching.

- [ ] **Step 3: Verify dismissal**

- Tap anywhere on the fullscreen barcode: it shrinks back down to exactly its original inline
  position/size, without a visible jump.
- Repeat, but dismiss via the system back button instead of a tap: same shrink animation, and the
  back press does NOT also navigate away from the detail screen (confirms `BackHandler` correctly
  intercepted it).

- [ ] **Step 4: Verify unrelated UI is unreachable while fullscreen**

While the barcode is fullscreen, confirm the bottom-right flip icon and the top app bar's back/
Delete controls are not tappable underneath the overlay.

- [ ] **Step 5: Verify the flip-to-back feature still works normally**

Flip a pass to its back view and back again (unrelated to this feature, but touches the same file)
to confirm no regression from the refactor.

- [ ] **Step 6: Report back**

Confirm with the user whether all of the above look correct before merging.
