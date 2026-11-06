package com.beamdrop.app.ui

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import com.beamdrop.app.BeamDropViewModel
import com.beamdrop.app.ui.components.BeamBackground
import com.beamdrop.app.ui.components.BeamBottomBar
import com.beamdrop.app.ui.screens.ConnectScreen
import com.beamdrop.app.ui.screens.ReceiveScreen
import com.beamdrop.app.ui.screens.MySharesScreen
import com.beamdrop.app.ui.screens.HistoryScreen
import com.beamdrop.app.ui.screens.HomeScreen
import com.beamdrop.app.ui.screens.ProfileScreen
import com.beamdrop.app.ui.screens.ReceivePreviewScreen
import com.beamdrop.app.ui.screens.ScannerScreen
import com.beamdrop.app.ui.screens.AuthScreen
import com.beamdrop.app.ui.screens.SendFilesScreen
import com.beamdrop.app.ui.screens.TransferScreen

object Routes {
    const val HOME = "home"
    const val HISTORY = "history"
    const val DEVICES = "devices"
    const val PROFILE = "profile"
    const val SEND = "send"
    const val CONNECT = "connect"
    const val SCANNER = "scanner"
    const val RECEIVE = "receive"
    const val TRANSFER = "transfer"
    const val AUTH = "auth"
    const val MY_SHARES = "my_shares"
    const val CONTACTS = "contacts"
    const val SUPPORT = "support"
    const val STATS = "stats"
    const val VAULT = "vault"
    const val PLANS = "plans"
}

private val topLevelRoutes = setOf(Routes.HOME, Routes.HISTORY, Routes.PROFILE)

@Composable
fun BeamDropApp(viewModel: BeamDropViewModel) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val route = backStackEntry?.destination?.route ?: Routes.HOME
    val isLoggedIn by viewModel.isLoggedIn.collectAsStateWithLifecycle()
    val showBottomBar = route in topLevelRoutes && isLoggedIn
    val shareRequestId by viewModel.shareRequestId.collectAsStateWithLifecycle()

    LaunchedEffect(isLoggedIn) {
        if (!isLoggedIn && route != Routes.AUTH) {
            navController.navigate(Routes.AUTH) {
                popUpTo(0) { inclusive = true }
                launchSingleTop = true
            }
        }
    }

    // A "files for you" notification was tapped: open those files.
    val pendingOpen by viewModel.pendingOpenUrl.collectAsStateWithLifecycle()
    LaunchedEffect(pendingOpen, isLoggedIn) {
        if (pendingOpen != null && isLoggedIn) {
            viewModel.consumePendingOpen()?.let { url ->
                viewModel.openReceive(url)
                navController.navigate(Routes.RECEIVE) { launchSingleTop = true }
            }
        }
    }

    // Widget buttons
    val pendingScreen by viewModel.pendingScreen.collectAsStateWithLifecycle()
    LaunchedEffect(pendingScreen, isLoggedIn) {
        if (pendingScreen != null && isLoggedIn) {
            when (viewModel.consumePendingScreen()) {
                "send" -> navController.navigate(Routes.SEND) { launchSingleTop = true }
                "receive" -> navController.navigate(Routes.DEVICES) { launchSingleTop = true }
                "my_shares" -> navController.navigate(Routes.MY_SHARES) { launchSingleTop = true }
                "support" -> navController.navigate(Routes.SUPPORT) { launchSingleTop = true }
                "scan" -> navController.navigate(Routes.SCANNER) { launchSingleTop = true }
                "my_files" -> navController.navigateTopLevel(Routes.HISTORY)
                "vault" -> navController.navigate(Routes.VAULT) { launchSingleTop = true }
                "plans" -> navController.navigate(Routes.PLANS) { launchSingleTop = true }
            }
        }
    }

    LaunchedEffect(shareRequestId) {
        if (shareRequestId > 0 && route != Routes.SEND && isLoggedIn) {
            navController.navigate(Routes.SEND) {
                launchSingleTop = true
            }
        }
    }

    // Welcome tour, once, right after the first sign-in.
    val onboarded by viewModel.onboarded.collectAsStateWithLifecycle()

    BeamBackground {
        Scaffold(
            containerColor = Color.Transparent,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            bottomBar = {
                if (showBottomBar) {
                    BeamBottomBar(
                        currentRoute = route,
                        onNavigate = { target -> navController.navigateTopLevel(target) }
                    )
                }
            }
        ) { padding ->
            NavHost(
                navController = navController,
                startDestination = if (isLoggedIn) Routes.HOME else Routes.AUTH,
                modifier = Modifier
                    .fillMaxSize()
                    // Once here, so scrolling lists never slide under the clock (inner headers then add nothing).
                    .statusBarsPadding()
                    .padding(bottom = if (showBottomBar) padding.calculateBottomPadding() else 0.dp),
                enterTransition = {
                    fadeIn(tween(220)) + slideInHorizontally(tween(280)) { it / 10 }
                },
                exitTransition = { fadeOut(tween(160)) },
                popEnterTransition = { fadeIn(tween(200)) },
                popExitTransition = {
                    fadeOut(tween(180)) + slideOutHorizontally(tween(240)) { it / 10 }
                }
            ) {
                composable(Routes.HOME) {
                    HomeScreen(
                        viewModel = viewModel,
                        onOpenSend = { navController.navigate(Routes.SEND) },
                        onOpenReceive = { navController.navigate(Routes.DEVICES) },
                        onOpenTransfer = {
                            navController.navigate(Routes.TRANSFER) { launchSingleTop = true }
                        },
                        onOpenHistory = { navController.navigateTopLevel(Routes.HISTORY) },
                        onOpenSettings = { navController.navigateTopLevel(Routes.PROFILE) },
                        onOpenInboxItem = { item ->
                            viewModel.openInboxItem(item)
                            navController.navigate(Routes.RECEIVE)
                        }
                    )
                }

                composable(Routes.HISTORY) {
                    HistoryScreen(
                        viewModel = viewModel,
                        onOpenDevices = { navController.navigate(Routes.DEVICES) },
                        onOpenMyShares = { navController.navigate(Routes.MY_SHARES) },
                        onOpenVault = { navController.navigate(Routes.VAULT) }
                    )
                }

                composable(Routes.MY_SHARES) {
                    MySharesScreen(viewModel = viewModel, onBack = { navController.popBackStack() }, onOpenPlans = { navController.navigate(Routes.PLANS) })
                }

                composable(Routes.VAULT) {
                    com.beamdrop.app.ui.screens.VaultScreen(viewModel = viewModel, onBack = { navController.popBackStack() })
                }

                composable(Routes.PLANS) {
                    com.beamdrop.app.ui.screens.PlansScreen(viewModel = viewModel, onBack = { navController.popBackStack() })
                }

                composable(Routes.SUPPORT) {
                    com.beamdrop.app.ui.screens.SupportScreen(viewModel = viewModel, onBack = { navController.popBackStack() })
                }

                composable(Routes.CONTACTS) {
                    com.beamdrop.app.ui.screens.ContactsScreen(viewModel = viewModel, onBack = { navController.popBackStack() }, onScan = { navController.navigate(Routes.SCANNER) })
                }

                composable(Routes.DEVICES) {
                    ReceiveScreen(
                        viewModel = viewModel,
                        onBack = { navController.popBackStack() },
                        onScan = { navController.navigate(Routes.SCANNER) },
                        onReceiveUrl = { url ->
                            viewModel.openReceive(url)
                            navController.navigate(Routes.RECEIVE)
                        }
                    )
                }

                composable(Routes.PROFILE) {
                    ProfileScreen(
                        viewModel = viewModel,
                        onOpenAuth = { navController.navigate(Routes.AUTH) },
                        onOpenContacts = { navController.navigate(Routes.CONTACTS) },
                        onOpenSupport = { navController.navigate(Routes.SUPPORT) },
                        onOpenStats = { navController.navigate(Routes.STATS) },
                        onOpenVault = { navController.navigate(Routes.VAULT) },
                        onOpenPlans = { navController.navigate(Routes.PLANS) }
                    )
                }

                composable(Routes.STATS) {
                    com.beamdrop.app.ui.screens.StatsScreen(viewModel = viewModel, onBack = { navController.popBackStack() })
                }

                composable(Routes.AUTH) {
                    AuthScreen(
                        viewModel = viewModel,
                        onSuccess = {
                            navController.navigate(Routes.HOME) {
                                popUpTo(0) { inclusive = true }
                                launchSingleTop = true
                            }
                        }
                    )
                }

                composable(Routes.SEND) {
                    SendFilesScreen(
                        viewModel = viewModel,
                        onBack = { navController.popBackStack() },
                        onContinue = { navController.navigate(Routes.CONNECT) }
                    )
                }

                composable(Routes.CONNECT) {
                    ConnectScreen(
                        viewModel = viewModel,
                        onBack = { navController.popBackStack() },
                        onScan = { navController.navigate(Routes.SCANNER) },
                        onViewTransfer = {
                            navController.navigate(Routes.TRANSFER) { launchSingleTop = true }
                        },
                        onDone = { navController.navigateUpToHome() },
                        onOpenContacts = { navController.navigate(Routes.CONTACTS) },
                        onOpenPlans = { navController.navigate(Routes.PLANS) }
                    )
                }

                composable(Routes.SCANNER) {
                    ScannerScreen(
                        viewModel = viewModel,
                        onBack = { navController.popBackStack() },
                        onUrlReady = { url ->
                            when {
                                // Someone's "My Sendit QR": add them as a contact.
                                url.startsWith("sendit://add", ignoreCase = true) -> {
                                    viewModel.addContactFromQr(url) { err ->
                                        android.widget.Toast.makeText(
                                            navController.context, if (err == null) com.beamdrop.app.i18n.tr("Contact added") else com.beamdrop.app.i18n.tr(err), android.widget.Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                    navController.navigate(Routes.CONTACTS) {
                                        popUpTo(Routes.SCANNER) { inclusive = true }
                                        launchSingleTop = true
                                    }
                                }
                                // A sender's hotspot: join its Wi-Fi first.
                                url.startsWith("sendit-wifi://", ignoreCase = true) -> {
                                    viewModel.joinHotspotFromQr(url)
                                    navController.navigate(Routes.RECEIVE)
                                }
                                else -> {
                                    viewModel.openReceive(url)
                                    navController.navigate(Routes.RECEIVE)
                                }
                            }
                        }
                    )
                }

                composable(Routes.RECEIVE) {
                    ReceivePreviewScreen(
                        viewModel = viewModel,
                        onBack = {
                            viewModel.clearReceive()
                            navController.popBackStack()
                        },
                        onScanAgain = {
                            viewModel.clearReceive()
                            navController.popBackStack()
                        },
                        onDownload = { ids ->
                            viewModel.receiveOffer.value?.let { full ->
                                // Only the files the user ticked.
                                val offer = full.copy(manifest = full.manifest.copy(files = full.manifest.files.filter { it.id in ids }))
                                viewModel.startReceive(offer)
                                navController.navigate(Routes.TRANSFER) { launchSingleTop = true }
                            }
                        }
                    )
                }

                composable(Routes.TRANSFER) {
                    TransferScreen(
                        viewModel = viewModel,
                        onBack = { navController.popBackStack() },
                        onShowQr = {
                            navController.navigate(Routes.CONNECT) { launchSingleTop = true }
                        },
                        onDone = {
                            viewModel.finishTransfer()
                            navController.navigateUpToHome()
                        }
                    )
                }
            }
        }
        // Welcome tour, once, right after the first sign-in (drawn on top of the app).
        if (isLoggedIn && !onboarded) {
            com.beamdrop.app.ui.screens.OnboardingScreen(onDone = viewModel::finishOnboarding)
        }
        // The admin says this version is too old: nothing else works until the user updates.
        val update by viewModel.update.collectAsStateWithLifecycle()
        LaunchedEffect(Unit) { viewModel.refreshAppConfig() }
        update?.let { u ->
            com.beamdrop.app.ui.screens.UpdateScreen(u, onLater = viewModel::dismissUpdate)
        }
    }
}

private fun NavHostController.navigateTopLevel(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

private fun NavHostController.navigateUpToHome() {
    navigate(Routes.HOME) {
        popUpTo(graph.findStartDestination().id) { inclusive = false }
        launchSingleTop = true
    }
}
