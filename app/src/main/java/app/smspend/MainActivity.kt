package app.smspend

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.smspend.domain.CategoryBucket
import app.smspend.ui.BiometricGate
import app.smspend.ui.CategoryScreen
import app.smspend.ui.CategoryViewModel
import app.smspend.ui.ExportsScreen
import app.smspend.ui.ExportsViewModel
import app.smspend.ui.HomeScreen
import app.smspend.ui.HomeViewModel
import app.smspend.ui.OnboardingScreen
import app.smspend.ui.OnboardingViewModel
import app.smspend.ui.SearchScreen
import app.smspend.ui.SearchViewModel
import app.smspend.ui.SettingsScreen
import app.smspend.ui.SettingsViewModel
import app.smspend.ui.TrendsScreen
import app.smspend.ui.TrendsViewModel
import app.smspend.ui.theme.Paper
import app.smspend.ui.theme.SmSpendTheme
import app.smspend.util.DateRanges

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application.smSpend
        setContent {
            SmSpendTheme {
                val onboarded by app.prefs.onboarded.collectAsStateWithLifecycle(initialValue = false)
                val biometricEnabled by app.prefs.biometricLockEnabled.collectAsStateWithLifecycle(
                    initialValue = false,
                )
                var permissionTick by remember { mutableIntStateOf(0) }
                val context = LocalContext.current
                val hasSms = remember(permissionTick) {
                    ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) ==
                        PackageManager.PERMISSION_GRANTED
                }
                val smsPermission = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission(),
                ) { permissionTick++ }
                val notifPermission = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission(),
                ) { }

                LaunchedEffect(hasSms) {
                    if (hasSms) app.ensureSmsInboxWatch()
                }

                LaunchedEffect(onboarded) {
                    if (onboarded && Build.VERSION.SDK_INT >= 33) {
                        val granted = ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.POST_NOTIFICATIONS,
                        ) == PackageManager.PERMISSION_GRANTED
                        if (!granted) {
                            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }
                }

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = Paper,
                ) { padding ->
                    BiometricGate(enabled = biometricEnabled && onboarded) {
                        SmSpendNav(
                            modifier = Modifier.padding(padding),
                            app = app,
                            onboarded = onboarded,
                            hasSmsPermission = hasSms,
                            onRequestSms = { smsPermission.launch(Manifest.permission.READ_SMS) },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun SmSpendNav(
    modifier: Modifier,
    app: SmSpendApp,
    onboarded: Boolean,
    hasSmsPermission: Boolean,
    onRequestSms: () -> Unit,
) {
    val nav = rememberNavController()
    val start = if (onboarded) "main" else "onboarding"
    val fadeSpec = tween<Float>(100, easing = FastOutSlowInEasing)
    val slideSpec = tween<IntOffset>(100, easing = FastOutSlowInEasing)
    val appContext = LocalContext.current.applicationContext

    SharedTransitionLayout {
        NavHost(
            navController = nav,
            startDestination = start,
            modifier = modifier,
            enterTransition = {
                fadeIn(fadeSpec) + slideInHorizontally(slideSpec) { it / 10 }
            },
            exitTransition = {
                fadeOut(fadeSpec) + slideOutHorizontally(slideSpec) { -it / 10 }
            },
            popEnterTransition = {
                fadeIn(fadeSpec) + slideInHorizontally(slideSpec) { -it / 10 }
            },
            popExitTransition = {
                fadeOut(fadeSpec) + slideOutHorizontally(slideSpec) { it / 10 }
            },
        ) {
            composable("onboarding") {
                val vm: OnboardingViewModel = viewModel(
                    factory = OnboardingViewModel.Factory(app.prefs, app.repository),
                )
                OnboardingScreen(
                    hasSmsPermission = hasSmsPermission,
                    onRequestSms = onRequestSms,
                    viewModel = vm,
                    onFinished = {
                        nav.navigate("main") {
                            popUpTo("onboarding") { inclusive = true }
                        }
                    },
                )
            }
            navigation(route = "main", startDestination = "home") {
            composable("home") { entry ->
                val owner = remember(entry) { nav.getBackStackEntry("main") }
                val vm: HomeViewModel = viewModel(
                    viewModelStoreOwner = owner,
                    factory = HomeViewModel.Factory(app.repository, app.prefs, appContext),
                )
                val lifecycleOwner = LocalLifecycleOwner.current
                LaunchedEffect(lifecycleOwner, hasSmsPermission) {
                    if (!hasSmsPermission) return@LaunchedEffect
                    lifecycleOwner.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                        vm.catchUpInbox()
                    }
                }
                val apiKey by app.prefs.geminiApiKey.collectAsStateWithLifecycle(initialValue = "")
                HomeScreen(
                    viewModel = vm,
                    repo = app.repository,
                    hasApiKey = apiKey.isNotBlank(),
                    onOpenSettings = { nav.openTop("settings") },
                    onOpenSearch = { nav.openTop("search") },
                    onOpenTrends = { nav.openTop("trends") },
                    onOpenExports = { nav.openTop("exports") },
                    onOpenCategory = { bucket, startMs, endMs, title ->
                        nav.navigate(
                            "category/${Uri.encode(bucket.key)}/$startMs/$endMs/${Uri.encode(title)}",
                        ) { launchSingleTop = true }
                    },
                    sharedTransitionScope = this@SharedTransitionLayout,
                    animatedContentScope = this,
                )
            }
            composable("trends") { entry ->
                val owner = remember(entry) { nav.getBackStackEntry("main") }
                val vm: TrendsViewModel = viewModel(
                    viewModelStoreOwner = owner,
                    factory = TrendsViewModel.Factory(app.repository),
                )
                val apiKey by app.prefs.geminiApiKey.collectAsStateWithLifecycle(initialValue = "")
                TrendsScreen(
                    viewModel = vm,
                    hasApiKey = apiKey.isNotBlank(),
                    onBack = { nav.popBackStack() },
                    onOpenCategory = { bucket ->
                        val month = DateRanges.monthWindow(0)
                        nav.navigate(
                            "category/${Uri.encode(bucket.key)}/${month.startMs}/${month.endExclusiveMs}/${Uri.encode(month.title())}",
                        ) { launchSingleTop = true }
                    },
                )
            }
            composable("exports") { entry ->
                val owner = remember(entry) { nav.getBackStackEntry("main") }
                val vm: ExportsViewModel = viewModel(
                    viewModelStoreOwner = owner,
                    factory = ExportsViewModel.Factory(app.repository, appContext),
                )
                val apiKey by app.prefs.geminiApiKey.collectAsStateWithLifecycle(initialValue = "")
                ExportsScreen(
                    viewModel = vm,
                    hasApiKey = apiKey.isNotBlank(),
                    onBack = { nav.popBackStack() },
                )
            }
            composable("search") { entry ->
                val owner = remember(entry) { nav.getBackStackEntry("main") }
                val vm: SearchViewModel = viewModel(
                    viewModelStoreOwner = owner,
                    factory = SearchViewModel.Factory(app.repository),
                )
                val apiKey by app.prefs.geminiApiKey.collectAsStateWithLifecycle(initialValue = "")
                SearchScreen(
                    viewModel = vm,
                    repo = app.repository,
                    hasApiKey = apiKey.isNotBlank(),
                    onBack = { nav.popBackStack() },
                )
            }
            composable(
                route = "category/{name}/{from}/{to}/{title}",
                arguments = listOf(
                    navArgument("name") { type = NavType.StringType },
                    navArgument("from") { type = NavType.LongType },
                    navArgument("to") { type = NavType.LongType },
                    navArgument("title") { type = NavType.StringType },
                ),
            ) { entry ->
                val name = Uri.decode(entry.arguments?.getString("name").orEmpty())
                val from = entry.arguments?.getLong("from") ?: 0L
                val to = entry.arguments?.getLong("to") ?: 0L
                val title = Uri.decode(entry.arguments?.getString("title").orEmpty()).ifBlank { "This month" }
                val bucket = CategoryBucket.fromStored(name)
                val apiKey by app.prefs.geminiApiKey.collectAsStateWithLifecycle(initialValue = "")
                val vm: CategoryViewModel = viewModel(
                    factory = CategoryViewModel.Factory(app.repository, bucket.key, from, to),
                )
                CategoryScreen(
                    bucket = bucket,
                    weekTitle = title,
                    viewModel = vm,
                    repo = app.repository,
                    hasApiKey = apiKey.isNotBlank(),
                    onBack = { nav.popBackStack() },
                    sharedTransitionScope = this@SharedTransitionLayout,
                    animatedContentScope = this,
                )
            }
            composable("settings") { entry ->
                val owner = remember(entry) { nav.getBackStackEntry("main") }
                val vm: SettingsViewModel = viewModel(
                    viewModelStoreOwner = owner,
                    factory = SettingsViewModel.Factory(app.repository, app.prefs, appContext),
                )
                SettingsScreen(viewModel = vm, onBack = { nav.popBackStack() })
            }
            }
        }
    }
}

private fun NavHostController.openTop(route: String) {
    navigate(route) { launchSingleTop = true }
}
