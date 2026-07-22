package ch.bigli.passes.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.bigli.passes.domain.FieldPosition
import ch.bigli.passes.domain.Pass
import ch.bigli.passes.images.PassImage
import ch.bigli.passes.images.PassImageLoader

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PassListScreen(
    viewModel: PassListViewModel,
    imageLoader: PassImageLoader,
    onImportClick: () -> Unit,
    onScanClick: () -> Unit,
    onManualClick: () -> Unit,
    onPassClick: (String) -> Unit,
) {
    val passes by viewModel.passes.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var showAddSheet by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState()

    LaunchedEffect(Unit) {
        viewModel.errors.collect { snackbar.showSnackbar(it) }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Passes") }) },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddSheet = true }) {
                Icon(Icons.Filled.Add, contentDescription = "Import pass")
            }
        },
    ) { padding ->
        if (passes.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(padding), verticalArrangement = Arrangement.Center) {
                Text(
                    "No passes yet. Tap + to import a .pkpass file.",
                    Modifier.fillMaxWidth().padding(24.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(padding),
            ) {
                items(passes, key = { it.id }) { pass ->
                    PassCard(pass, imageLoader) { onPassClick(pass.id) }
                }
            }
        }
    }

    if (showAddSheet) {
        ModalBottomSheet(onDismissRequest = { showAddSheet = false }, sheetState = sheetState) {
            Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
                ListItem(
                    headlineContent = { Text("Import file") },
                    leadingContent = { Icon(Icons.Filled.Add, contentDescription = null) },
                    modifier = Modifier.clickable { showAddSheet = false; onImportClick() },
                )
                ListItem(
                    headlineContent = { Text("Scan barcode") },
                    leadingContent = { Icon(Icons.Filled.PhotoCamera, contentDescription = null) },
                    modifier = Modifier.clickable { showAddSheet = false; onScanClick() },
                )
                ListItem(
                    headlineContent = { Text("Enter manually") },
                    leadingContent = { Icon(Icons.Filled.Edit, contentDescription = null) },
                    modifier = Modifier.clickable { showAddSheet = false; onManualClick() },
                )
            }
        }
    }
}

@Composable
private fun PassCard(pass: Pass, imageLoader: PassImageLoader, onClick: () -> Unit) {
    val bgColor = pass.bgColor
    val bg = bgColor?.let { Color(it) } ?: MaterialTheme.colorScheme.primary
    val fg = if (bgColor != null) Color(legibleTextColor(bgColor, pass.fgColor))
             else pass.fgColor?.let { Color(it) } ?: Color.White
    val logo by produceState<Bitmap?>(initialValue = null, pass.rawFilePath) {
        value = imageLoader.load(pass.rawFilePath, PassImage.LOGO)
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(16.dp),
    ) {
        logo?.let {
            Image(
                it.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.height(24.dp).padding(bottom = 8.dp),
                contentScale = ContentScale.Fit,
            )
        }
        Text(
            (pass.subtitle ?: pass.organization ?: pass.type.name).uppercase(),
            color = fg.copy(alpha = 0.85f), fontSize = 11.sp,
        )
        if (pass.voided) {
            Text("VOIDED", color = fg.copy(alpha = 0.9f), fontSize = 10.sp, fontWeight = FontWeight.Bold)
        } else if (pass.expirationDate?.isBefore(java.time.Instant.now()) == true) {
            Text("EXPIRED", color = fg.copy(alpha = 0.9f), fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
        val primaryValue = pass.fields.firstOrNull { it.position == FieldPosition.PRIMARY }?.value?.takeIf { it.isNotEmpty() }
        primaryValue?.let {
            Text(it, color = fg, fontSize = 16.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 8.dp))
        }
        val summary = pass.fields.filter { it.position != FieldPosition.PRIMARY }
            .take(3).joinToString("   ") { "${it.label}: ${it.value}" }
        if (summary.isNotEmpty()) {
            val summaryTopPadding = if (primaryValue != null) 4.dp else 8.dp
            Text(summary, color = fg.copy(alpha = 0.9f), fontSize = 12.sp, modifier = Modifier.padding(top = summaryTopPadding))
        }
    }
}
