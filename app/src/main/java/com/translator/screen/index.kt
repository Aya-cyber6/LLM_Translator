package com.translator.screen

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Headphones   // ← new icon for Interpreter tab
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.translator.ui.viewmodel.InterpreterViewModel   // ← replaces AudioTranslationViewModel
import com.translator.ui.viewmodel.TranslationViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranslatorApp(
    textViewModel: TranslationViewModel,
    interpreterViewModel: InterpreterViewModel,     // ← renamed from audioViewModel
) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination
    val currentRoute = currentDestination?.route

    val showBottomBar = currentRoute in listOf("text_route", "interpreter_route", "walkie_talkie_route")

    Scaffold(
        topBar = {
            if (showBottomBar) {
                TopAppBar(
                    title = { Text("LLM Translator") },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor    = MaterialTheme.colorScheme.primaryContainer,
                        titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                )
            }
        },
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    // ── Text translation Mode ──────────────────────────────────────
                    NavigationBarItem(
                        selected = currentDestination?.hierarchy?.any { it.route == "text_route" } == true,
                        onClick  = {
                            navController.navigate("text_route") {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState    = true
                            }
                        },
                        icon  = { Icon(Icons.Default.Translate, contentDescription = null) },
                        label = { Text("Text") },
                    )

                    // ── Interpreter Mode ────────────────────────
                    NavigationBarItem(
                        selected = currentDestination?.hierarchy?.any { it.route == "interpreter_route" } == true,
                        onClick  = {
                            navController.navigate("interpreter_route") {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState    = true
                            }
                        },
                        icon  = { Icon(Icons.Default.Headphones, contentDescription = null) },
                        label = { Text("Interpret") },
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController    = navController,
            startDestination = "text_route",
            modifier         = Modifier.padding(innerPadding),
        ) {
            // Route 1: Text translation (unchanged)
            composable("text_route") {
                TextTranslationScreen(
                    viewModel = textViewModel,
                    modifier  = Modifier.fillMaxSize(),
                )
            }

            // Route 2: Interpreter Mode  ← replaces audio_route / AudioTranslationScreen
            composable("interpreter_route") {
                InterpreterScreen(
                    viewModel = interpreterViewModel,
                    modifier  = Modifier.fillMaxSize(),
                )
            }
        }
    }
}