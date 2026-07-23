package ch.bigli.passes

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import ch.bigli.passes.data.PassRepository
import ch.bigli.passes.domain.Barcode
import ch.bigli.passes.domain.Pass
import ch.bigli.passes.domain.PassType
import ch.bigli.passes.images.PassImageLoader
import ch.bigli.passes.importing.walletPassesTargetUrl
import ch.bigli.passes.ui.CreatePassScreen
import ch.bigli.passes.ui.PassDetailScreen
import ch.bigli.passes.ui.PassDetailViewModel
import ch.bigli.passes.ui.PassListScreen
import ch.bigli.passes.ui.PassListViewModel
import ch.bigli.passes.ui.ScanScreen
import ch.bigli.passes.ui.theme.PassesTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as PassApp
        setContent { PassesTheme { AppNav(app) } }
        handleIncomingPass(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingPass(intent)
    }

    /** Extracts a pass from a VIEW (file/content or walletpasses://) or SEND intent and imports it. */
    private fun handleIncomingPass(intent: Intent?) {
        val app = application as PassApp
        val action = intent?.action
        val viewUri: Uri? = if (action == Intent.ACTION_VIEW) intent.data else null

        if (viewUri?.scheme == "walletpasses") {
            val target = walletPassesTargetUrl(viewUri)
            if (target == null) {
                Toast.makeText(this, "Invalid pass link", Toast.LENGTH_LONG).show()
                return
            }
            lifecycleScope.launch {
                try {
                    app.pendingPass.value = app.repository.importFromUrl(target).toPending()
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, e.message ?: "Import failed", Toast.LENGTH_LONG).show()
                }
            }
            return
        }

        val uri: Uri? = when (action) {
            Intent.ACTION_VIEW -> viewUri
            Intent.ACTION_SEND -> @Suppress("DEPRECATION") (intent.getParcelableExtra(Intent.EXTRA_STREAM))
            else -> null
        }
        if (uri == null) return
        lifecycleScope.launch {
            try {
                app.pendingPass.value = app.repository.importFromUri(uri).toPending()
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, e.message ?: "Import failed", Toast.LENGTH_LONG).show()
            }
        }
    }
}

private fun Pass.toPending() = PendingPass(id)

private class VmFactory(private val create: () -> ViewModel) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = create() as T
}

@Composable
private fun AppNav(app: PassApp) {
    val repo: PassRepository = app.repository
    val imageLoader = app.imageLoader
    val nav = rememberNavController()

    val pending by app.pendingPass.collectAsState()
    LaunchedEffect(pending) {
        pending?.let { p ->
            nav.navigate("detail/${p.id}")
            app.pendingPass.value = null
        }
    }

    NavHost(nav, startDestination = "list") {
        composable("list") {
            val vm: PassListViewModel = viewModel(factory = VmFactory { PassListViewModel(repo) })
            val scope = rememberCoroutineScope()
            val picker = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocument()
            ) { uri ->
                if (uri != null) {
                    scope.launch {
                        try {
                            app.pendingPass.value = repo.importFromUri(uri).toPending()
                        } catch (e: Exception) {
                            vm.reportError(e.message ?: "Import failed")
                        }
                    }
                }
            }
            PassListScreen(
                viewModel = vm,
                imageLoader = imageLoader,
                onImportClick = { picker.launch(arrayOf("*/*")) },
                onScanClick = { nav.navigate("scan") },
                onManualClick = { app.pendingScan.value = null; nav.navigate("create") },
                onPassClick = { id -> nav.navigate("detail/$id") },
            )
        }
        composable("scan") {
            ScanScreen(
                onScanned = { barcode ->
                    app.pendingScan.value = barcode
                    nav.navigate("create") { popUpTo("scan") { inclusive = true } }
                },
                onBack = { nav.popBackStack() },
            )
        }
        composable("create") {
            val scope = rememberCoroutineScope()
            val prefill: Barcode? = remember { app.pendingScan.value }
            CreatePassScreen(
                prefill = prefill,
                onCreate = { format, value ->
                    scope.launch {
                        val pass = repo.createManualPass(
                            type = PassType.GENERIC,
                            organization = "",
                            fields = emptyList(),
                            relevantDate = null,
                            transitType = null,
                            barcodeFormat = format,
                            barcodeValue = value,
                        )
                        app.pendingScan.value = null
                        nav.navigate("detail/${pass.id}") { popUpTo("list") }
                    }
                },
                onBack = { app.pendingScan.value = null; nav.popBackStack() },
            )
        }
        composable(
            "detail/{id}",
            arguments = listOf(navArgument("id") { type = NavType.StringType }),
        ) { entry ->
            val id = entry.arguments!!.getString("id")!!
            val vm: PassDetailViewModel = viewModel(factory = VmFactory { PassDetailViewModel(repo, id) })
            PassDetailScreen(
                viewModel = vm,
                imageLoader = imageLoader,
                onBack = { nav.popBackStack() },
            )
        }
    }
}
