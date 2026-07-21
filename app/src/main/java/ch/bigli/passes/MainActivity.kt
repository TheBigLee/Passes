package ch.bigli.passes

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repo = (application as PassApp).repository
        setContent { PassesTheme { AppNav(repo) } }
    }
}

private class VmFactory(private val create: () -> ViewModel) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = create() as T
}

@Composable
private fun AppNav(repo: PassRepository) {
    val nav = rememberNavController()
    NavHost(nav, startDestination = "list") {
        composable("list") {
            val vm: PassListViewModel = viewModel(factory = VmFactory { PassListViewModel(repo) })
            val context = androidx.compose.ui.platform.LocalContext.current
            val picker = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocument()
            ) { uri ->
                if (uri != null) {
                    val name = uri.lastPathSegment ?: "pass.pkpass"
                    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    if (bytes != null) vm.importBytes(bytes, name)
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
