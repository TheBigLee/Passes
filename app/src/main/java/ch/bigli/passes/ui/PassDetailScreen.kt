package ch.bigli.passes.ui

import android.app.Activity
import android.graphics.Bitmap
import android.view.WindowManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.bigli.passes.barcode.BarcodeRenderer
import ch.bigli.passes.domain.BarcodeFormat
import ch.bigli.passes.domain.FieldPosition
import ch.bigli.passes.images.PassImage
import ch.bigli.passes.images.PassImageLoader

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PassDetailScreen(
    viewModel: PassDetailViewModel,
    imageLoader: PassImageLoader,
    onBack: () -> Unit,
    openTitleEditor: Boolean = false,
) {
    val pass by viewModel.pass.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showEditDialog by rememberSaveable { mutableStateOf(openTitleEditor) }
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
                    IconButton(onClick = { showEditDialog = true }) {
                        Icon(Icons.Filled.Edit, contentDescription = "Edit title", tint = fg)
                    }
                    IconButton(onClick = { viewModel.delete(onBack) }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Delete", tint = fg)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = bg, titleContentColor = fg),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (p == null) return@Scaffold
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = { viewModel.refresh() },
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            val isVoidedOrExpired = p.isVoidedOrExpired()
            Column(Modifier.fillMaxSize()) {
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
                    Text(p.title, color = fg, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.size(12.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        p.fields.filter { it.position != FieldPosition.PRIMARY }.take(4).forEach { f ->
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(f.label, color = fg.copy(alpha = 0.7f), fontSize = 10.sp)
                                Text(f.value, color = fg, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                    Spacer(Modifier.size(32.dp))
                    p.barcode?.let { bc ->
                        val renderer = remember { BarcodeRenderer() }
                        val square = bc.format == BarcodeFormat.QR || bc.format == BarcodeFormat.AZTEC
                        val bmp = remember(bc) {
                            if (square) renderer.render(bc, 600, 600) else renderer.render(bc, 800, 300)
                        }
                        Column(
                            Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color.White)
                                .padding(16.dp)
                                .alpha(if (isVoidedOrExpired) 0.35f else 1f),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Image(bmp.asImageBitmap(), contentDescription = "Barcode", modifier = Modifier.size(240.dp))
                            if (p.voided) {
                                Text(
                                    "This pass has been voided by the issuer",
                                    color = Color.Black,
                                    fontSize = 12.sp,
                                    modifier = Modifier.padding(top = 8.dp),
                                )
                            } else if (p.expirationDate?.isBefore(java.time.Instant.now()) == true) {
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

    if (showEditDialog && p != null) {
        TitleEditDialog(
            initialTitle = p.title,
            onSave = { newTitle ->
                viewModel.updateTitle(newTitle)
                showEditDialog = false
            },
            onDismiss = { showEditDialog = false },
        )
    }
}

@Composable
private fun TitleEditDialog(
    initialTitle: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by rememberSaveable { mutableStateOf(initialTitle) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit title") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                label = { Text("Title") },
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(text) }, enabled = text.isNotBlank()) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
