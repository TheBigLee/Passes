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
import ch.bigli.passes.ui.PassDetailScreen
import ch.bigli.passes.ui.PassDetailViewModel
import ch.bigli.passes.ui.PassListScreen
import ch.bigli.passes.ui.PassListViewModel
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

    /** Extracts a .pkpass Uri from a VIEW or SEND intent and imports it, signalling navigation on success. */
    private fun handleIncomingPass(intent: Intent?) {
        val uri: Uri? = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> @Suppress("DEPRECATION") (intent.getParcelableExtra(Intent.EXTRA_STREAM))
            else -> null
        }
        if (uri == null) return
        val app = application as PassApp
        lifecycleScope.launch {
            try {
                val pass = app.repository.importFromUri(uri)
                app.pendingPassId.value = pass.id
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, e.message ?: "Import failed", Toast.LENGTH_LONG).show()
            }
        }
    }
}

private class VmFactory(private val create: () -> ViewModel) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = create() as T
}

@Composable
private fun AppNav(app: PassApp) {
    val repo: PassRepository = app.repository
    val nav = rememberNavController()

    val pending by app.pendingPassId.collectAsState()
    LaunchedEffect(pending) {
        pending?.let { id ->
            nav.navigate("detail/$id")
            app.pendingPassId.value = null
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
                            val pass = repo.importFromUri(uri)
                            app.pendingPassId.value = pass.id
                        } catch (e: Exception) {
                            vm.reportError(e.message ?: "Import failed")
                        }
                    }
                }
            }
            PassListScreen(
                viewModel = vm,
                onImportClick = { picker.launch(arrayOf("*/*")) },
                onPassClick = { id -> nav.navigate("detail/$id") },
            )
        }
        composable(
            "detail/{id}",
            arguments = listOf(navArgument("id") { type = NavType.StringType }),
        ) { entry ->
            val id = entry.arguments!!.getString("id")!!
            val vm: PassDetailViewModel = viewModel(factory = VmFactory { PassDetailViewModel(repo, id) })
            PassDetailScreen(viewModel = vm, onBack = { nav.popBackStack() })
        }
    }
}
