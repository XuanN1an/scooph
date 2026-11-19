package dev.scooph

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.scooph.ui.detail.DetailScreen
import dev.scooph.ui.detail.DetailViewModel
import dev.scooph.ui.main.MainScreen
import dev.scooph.ui.main.MainViewModel
import dev.scooph.ui.theme.ScoophTheme

class MainActivity : ComponentActivity() {

    /** 唯一注入点：Repository 来自 [ScoophApp]，ViewModel 跨配置变更保留。 */
    private val mainViewModel: MainViewModel by viewModels { MainViewModel.factory(application) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ScoophTheme {
                // 不引导航库：一个 when 分发；详情包名存成可保存状态，
                // 旋转屏幕后仍停在详情页。
                var detailPackage by rememberSaveable { mutableStateOf<String?>(null) }
                val pkg = detailPackage
                BackHandler(enabled = pkg != null) { detailPackage = null }
                if (pkg == null) {
                    MainScreen(
                        viewModel = mainViewModel,
                        onOpenDetail = { opened -> detailPackage = opened },
                    )
                } else {
                    val detailViewModel: DetailViewModel =
                        viewModel(
                            key = "detail-$pkg",
                            factory = DetailViewModel.factory(application, pkg),
                        )
                    DetailScreen(
                        viewModel = detailViewModel,
                        onBack = { detailPackage = null },
                    )
                }
            }
        }
    }
}
