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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
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
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.derivedStateOf
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.bigli.passes.barcode.BarcodeRenderer
import ch.bigli.passes.domain.BarcodeFormat
import ch.bigli.passes.domain.Pass
import ch.bigli.passes.domain.PassType
import ch.bigli.passes.images.PassImage
import ch.bigli.passes.images.PassImageLoader
import kotlin.math.roundToInt

/** A plain (non-Compose-state) mutable box, so writing to [value] doesn't trigger recomposition. */
private class BoundsHolder {
    var value: Rect? = null
}

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
    val isSquareBarcode = p?.barcode?.format == BarcodeFormat.QR || p?.barcode?.format == BarcodeFormat.AZTEC
    val barcodeRenderer = remember { BarcodeRenderer() }
    val barcodeBitmap = remember(p?.barcode) {
        p?.barcode?.let { bc ->
            if (isSquareBarcode) barcodeRenderer.render(bc, 1200, 1200) else barcodeRenderer.render(bc, 1600, 600)
        }
    }
    var fullscreenBarcode by remember { mutableStateOf(false) }
    var barcodeSourceBounds by remember { mutableStateOf<Rect?>(null) }
    var rootBounds by remember { mutableStateOf<Rect?>(null) }
    // The inline barcode sits inside a scrollable column, so its on-screen position changes on
    // every scroll frame. Tracking that continuously in Compose state (as barcodeSourceBounds
    // does) would recompose the whole screen while scrolling; instead the latest position is
    // stashed in a plain (non-state) holder, and only committed into real state once - at the
    // moment of the tap that actually needs it.
    val latestBarcodeBounds = remember { BoundsHolder() }
    val barcodeProgress by animateFloatAsState(
        targetValue = if (fullscreenBarcode) 1f else 0f,
        animationSpec = tween(300),
        label = "barcodeFullscreen",
    )
    // Reading barcodeProgress directly for barcodeHidden would recompose PassFrontContent on
    // every animation frame; derivedStateOf only invalidates when the boolean actually flips.
    val barcodeHidden by remember { derivedStateOf { barcodeProgress > 0f } }
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
                            barcodeHidden = barcodeHidden,
                            onBarcodeTap = {
                                barcodeSourceBounds = latestBarcodeBounds.value
                                fullscreenBarcode = true
                            },
                            onBarcodeBoundsChanged = { latestBarcodeBounds.value = it },
                        )
                    } else {
                        Box(Modifier.fillMaxSize().graphicsLayer { rotationY = 180f }) {
                            PassBackContent(
                                pass = p,
                                bg = bg,
                                fg = fg,
                                onSetAutoUpdateEnabled = viewModel::setAutoUpdateEnabled,
                            )
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
            // Same voided/expired-replaces-altText rule as the inline barcode caption below.
            val captionText = p?.voidedOrExpiredMessage() ?: p?.barcode?.altText
            FullscreenBarcodeOverlay(
                bitmap = bmp,
                sourceBounds = source,
                targetBounds = target,
                progress = barcodeProgress,
                rotateToLandscape = !isSquareBarcode,
                altText = captionText,
                dimmed = p?.isVoidedOrExpired() == true,
                onDismiss = { fullscreenBarcode = false },
            )
        }
    }
}

/**
 * Renders [bitmap] positioned/sized by interpolating between [sourceBounds] (the inline barcode's
 * captured on-screen position) and a fullscreen target, driven by [progress] (0f = collapsed at
 * sourceBounds, 1f = fullscreen). Lives outside the Scaffold's content padding so it can visually
 * grow over the TopAppBar too - genuinely fullscreen, not just the content area.
 *
 * When [rotateToLandscape] is true (non-square formats - PDF417/Code128), the target box is
 * centered and sized to the bitmap's own aspect ratio, scaled so that its footprint *after* a 90°
 * rotation fits the screen - a wide, short barcode's long axis then runs along the screen's much
 * longer height, letting it render far bigger than fitting it unrotated ever could. Square formats
 * (QR/Aztec) gain nothing from this, so they always target the plain full-screen rect, unrotated.
 */
@Composable
private fun FullscreenBarcodeOverlay(
    bitmap: Bitmap,
    sourceBounds: Rect,
    targetBounds: Rect,
    progress: Float,
    rotateToLandscape: Boolean,
    altText: String?,
    dimmed: Boolean,
    onDismiss: () -> Unit,
) {
    // targetBounds is the raw Compose window size, which on an edge-to-edge window includes the
    // area the status/navigation bars visually draw over. Shrinking it to the actual system-bars
    // safe area keeps the (possibly rotated) barcode from appearing to run off-screen under them.
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val systemBars = WindowInsets.systemBars
    val safeBounds = Rect(
        left = targetBounds.left + systemBars.getLeft(density, layoutDirection),
        top = targetBounds.top + systemBars.getTop(density),
        right = targetBounds.right - systemBars.getRight(density, layoutDirection),
        bottom = targetBounds.bottom - systemBars.getBottom(density),
    )
    // Scales the bitmap's own (unswapped) aspect ratio to fit within safeBounds, centered,
    // without stretching. [constraintWidth]/[constraintHeight] are what the scale is computed
    // against - for the landscape case these are the bitmap's dimensions SWAPPED, since
    // rotationZ will swap the box's visual footprint right back once applied, so it's the
    // POST-rotation footprint that actually needs to fit the screen, even though the box itself
    // (pre-rotation) always keeps the bitmap's true width/height.
    fun centeredFit(constraintWidth: Float, constraintHeight: Float): Rect {
        val scale = minOf(safeBounds.width / constraintWidth, safeBounds.height / constraintHeight)
        val boxSize = Size(bitmap.width * scale, bitmap.height * scale)
        val topLeft = Offset(
            safeBounds.center.x - boxSize.width / 2f,
            safeBounds.center.y - boxSize.height / 2f,
        )
        return Rect(topLeft, boxSize)
    }
    val target = if (rotateToLandscape) {
        centeredFit(constraintWidth = bitmap.height.toFloat(), constraintHeight = bitmap.width.toFloat())
    } else {
        centeredFit(constraintWidth = bitmap.width.toFloat(), constraintHeight = bitmap.height.toFloat())
    }
    val topLeft = lerp(sourceBounds.topLeft, target.topLeft, progress)
    val size = lerp(sourceBounds.size, target.size, progress)
    val rotation = if (rotateToLandscape) 90f * progress else 0f
    val imageBitmap = remember(bitmap) { bitmap.asImageBitmap() }
    val textMeasurer = rememberTextMeasurer()
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
        // Drawn directly on a Canvas (rather than an Image with an offset+size+graphicsLayer
        // modifier chain) so the rotation pivot is an explicit, unambiguous Offset in the same
        // absolute coordinate space as topLeft/size/safeBounds - no reliance on how nested
        // layout/placement/graphicsLayer modifiers interact with constraints. altText (if any) is
        // drawn in the SAME rotate {} block as the barcode image, immediately below it in the
        // pre-rotation coordinate space, so it's physically locked to the barcode - rotating and
        // moving with it as a single unit through the whole animation, exactly like the caption
        // on a real Wallet-app barcode card, rather than being tracked/positioned separately.
        Canvas(
            Modifier
                .fillMaxSize()
                // altText is included here (not just drawn as pixels) so screen-reader users get
                // the same caption sighted users see - Canvas has no per-drawing-call semantics.
                .semantics { contentDescription = if (altText != null) "Barcode, $altText" else "Barcode" },
        ) {
            val dstOffset = IntOffset(topLeft.x.roundToInt(), topLeft.y.roundToInt())
            val dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt())
            val captionGap = 16.dp.toPx()
            // Matches the inline barcode's own dimming (PassFrontContent applies the same
            // 0.35f alpha to its white card) so a voided/expired pass looks consistently
            // greyed-out whether inline or fullscreen.
            val contentAlpha = if (dimmed) 0.35f else 1f
            val textLayout = altText?.let {
                textMeasurer.measure(
                    text = it,
                    style = TextStyle(color = Color.Black.copy(alpha = contentAlpha), fontSize = 12.sp, textAlign = TextAlign.Center),
                    // fixedWidth, not maxWidth alone: a min of 0 would let the layout shrink to
                    // the text's own natural (short) width, leaving textAlign=Center nothing to
                    // center within - it needs to actually BE the full barcode width.
                    constraints = Constraints.fixedWidth(dstSize.width),
                )
            }
            fun drawBarcodeAndCaption() {
                drawImage(imageBitmap, dstOffset = dstOffset, dstSize = dstSize, alpha = contentAlpha)
                if (textLayout != null) {
                    drawText(textLayout, topLeft = Offset(dstOffset.x.toFloat(), dstOffset.y + dstSize.height + captionGap))
                }
            }
            if (rotation == 0f) {
                drawBarcodeAndCaption()
            } else {
                val pivot = Offset(topLeft.x + size.width / 2f, topLeft.y + size.height / 2f)
                rotate(degrees = rotation, pivot = pivot) {
                    drawBarcodeAndCaption()
                }
            }
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
        val isBoarding = pass.type == PassType.BOARDING
        Column(Modifier.fillMaxSize().background(bg)) {
            strip?.let {
                Image(
                    it.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxWidth(),
                    contentScale = ContentScale.FillWidth,
                )
            }
            if (isBoarding) {
                BoardingHeaderRow(pass, fg)
            }
            Column(
                Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Top,
            ) {
                if (isBoarding) {
                    BoardingFieldsLayout(pass, fg)
                } else {
                    GenericFieldsLayout(pass, fg)
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
                            // Sized to the bitmap's own aspect ratio within a 240dp box, rather
                            // than a fixed 240x240 square - a wide/short PDF417/Code128 bitmap in
                            // a square box would otherwise be letterboxed by Image's default
                            // ContentScale.Fit, leaving invisible empty space below the visible
                            // barcode that pushed the alt text/voided-expired text too far away.
                            val aspect = barcodeBitmap.width.toFloat() / barcodeBitmap.height.toFloat()
                            val (imageWidth, imageHeight) = if (aspect >= 1f) {
                                240.dp to 240.dp / aspect
                            } else {
                                240.dp * aspect to 240.dp
                            }
                            Image(
                                barcodeBitmap.asImageBitmap(),
                                // Null while the fullscreen overlay owns the same "Barcode"
                                // description, so accessibility services don't announce it twice.
                                contentDescription = if (barcodeHidden) null else "Barcode",
                                modifier = Modifier
                                    .size(width = imageWidth, height = imageHeight)
                                    .alpha(if (barcodeHidden) 0f else 1f)
                                    .onGloballyPositioned { onBarcodeBoundsChanged(it.boundsInRoot()) }
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                        onClick = onBarcodeTap,
                                    ),
                            )
                        }
                        // A voided/expired status message replaces the alt text entirely,
                        // rather than stacking below it - the barcode's own caption isn't
                        // meaningful once the pass can't actually be used to scan.
                        val captionText = pass.voidedOrExpiredMessage() ?: bc.altText
                        captionText?.let {
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
private fun PassBackContent(
    pass: Pass,
    bg: Color,
    fg: Color,
    onSetAutoUpdateEnabled: (Boolean) -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(bg)
            .verticalScroll(rememberScrollState())
            // Extra bottom padding keeps content clear of the flip icon's touch target, which
            // floats on top of this content in the bottom-right corner.
            .padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 80.dp),
    ) {
        // Only shown when the pass actually carries a webServiceURL - toggling it for a
        // non-updatable pass would have nothing to turn off.
        if (pass.updateInfo != null) {
            Row(
                Modifier.fillMaxWidth().padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Auto-update", color = fg, fontSize = 14.sp)
                Switch(
                    checked = pass.autoUpdateEnabled,
                    onCheckedChange = onSetAutoUpdateEnabled,
                    colors = SwitchDefaults.colors(checkedThumbColor = fg, checkedTrackColor = fg.copy(alpha = 0.5f)),
                )
            }
            HorizontalDivider(color = fg.copy(alpha = 0.2f), modifier = Modifier.padding(bottom = 16.dp))
        }
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
