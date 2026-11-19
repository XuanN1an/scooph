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
import dev.scooph.install.InstallService
import dev.scooph.ui.detail.DetailScreen
import dev.scooph.ui.detail.DetailViewModel
import dev.scooph.ui.main.MainScreen
import dev.scooph.ui.main.MainViewModel
import dev.scooph.ui.sources.EntriesScreen
import dev.scooph.ui.sources.EntriesViewModel
import dev.scooph.ui.sources.SourcesScreen
import dev.scooph.ui.sources.SourcesViewModel
import dev.scooph.ui.theme.ScoophTheme

/**
 * 不引导航库的导航宿主：一个可保存的路由字符串 + 一个 when。
 *
 * 路由形如 `main`、`sources`、`detail:<包名>`；返回键统一回到主屏。
 */
class MainActivity : ComponentActivity() {

    /** 唯一注入点：Repository 来自 [ScoophApp]，ViewModel 跨配置变更保留。 */
    private val mainViewModel: MainViewModel by viewModels { MainViewModel.factory(application) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ScoophTheme {
                var route by rememberSaveable { mutableStateOf(ROUTE_MAIN) }
                BackHandler(enabled = route != ROUTE_MAIN) { route = ROUTE_MAIN }
                when {
                    route == ROUTE_SOURCES -> {
                        val sourcesViewModel: SourcesViewModel =
                            viewModel(factory = SourcesViewModel.factory(application))
                        SourcesScreen(
                            viewModel = sourcesViewModel,
                            onBack = { route = ROUTE_MAIN },
                            onOpenEntries = { url -> route = ROUTE_ENTRIES_PREFIX + url },
                        )
                    }

                    route.startsWith(ROUTE_ENTRIES_PREFIX) -> {
                        val url = route.removePrefix(ROUTE_ENTRIES_PREFIX)
                        val entriesViewModel: EntriesViewModel =
                            viewModel(
                                key = "entries-$url",
                                factory = EntriesViewModel.factory(application, url),
                            )
                        EntriesScreen(
                            viewModel = entriesViewModel,
                            onBack = { route = ROUTE_SOURCES },
                            onOpenDetail = { packageName ->
                                route = ROUTE_DETAIL_PREFIX + packageName
                            },
                            onInstall = { packageName ->
                                // 未安装行直接开始安装，不跳详情页（spec §11.4）。
                                startService(
                                    InstallService.intent(application, listOf(packageName)),
                                )
                            },
                        )
                    }

                    route.startsWith(ROUTE_DETAIL_PREFIX) -> {
                        val packageName = route.removePrefix(ROUTE_DETAIL_PREFIX)
                        val detailViewModel: DetailViewModel =
                            viewModel(
                                key = "detail-$packageName",
                                factory = DetailViewModel.factory(application, packageName),
                            )
                        DetailScreen(
                            viewModel = detailViewModel,
                            onBack = { route = ROUTE_MAIN },
                        )
                    }

                    else -> {
                        MainScreen(
                            viewModel = mainViewModel,
                            onOpenDetail = { packageName -> route = ROUTE_DETAIL_PREFIX + packageName },
                            onOpenSources = { route = ROUTE_SOURCES },
                        )
                    }
                }
            }
        }
    }

    private companion object {
        const val ROUTE_MAIN = "main"
        const val ROUTE_SOURCES = "sources"
        const val ROUTE_ENTRIES_PREFIX = "entries:"
        const val ROUTE_DETAIL_PREFIX = "detail:"
    }
}
