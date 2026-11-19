package dev.scooph

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import dev.scooph.ui.main.MainScreen
import dev.scooph.ui.main.MainViewModel
import dev.scooph.ui.theme.ScoophTheme

class MainActivity : ComponentActivity() {

    /** 唯一注入点：Repository 来自 [ScoophApp]，ViewModel 跨配置变更保留。 */
    private val viewModel: MainViewModel by viewModels { MainViewModel.factory(application) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ScoophTheme {
                MainScreen(viewModel = viewModel)
            }
        }
    }
}
