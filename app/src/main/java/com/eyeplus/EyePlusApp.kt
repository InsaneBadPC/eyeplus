package com.eyeplus

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.eyeplus.ui.camera.CameraScreen
import com.eyeplus.ui.chat.ChatScreen
import com.eyeplus.ui.settings.SettingsScreen

sealed class Screen(val route: String, val label: String, val icon: ImageVector) {
    data object Camera : Screen("camera", "Kamera", Icons.Default.CameraAlt)
    data object Chat : Screen("chat", "Chat", Icons.Default.Chat)
    data object Settings : Screen("settings", "Nastavení", Icons.Default.Settings)
}

val bottomNavItems = listOf(Screen.Camera, Screen.Chat, Screen.Settings)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EyePlusApp() {
    val navController = rememberNavController()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("EyePlus AI") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface
            ) {
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentDestination = navBackStackEntry?.destination

                bottomNavItems.forEach { screen ->
                    NavigationBarItem(
                        icon = { Icon(screen.icon, contentDescription = screen.label) },
                        label = { Text(screen.label) },
                        selected = currentDestination?.hierarchy?.any { it.route == screen.route } == true,
                        onClick = {
                            navController.navigate(screen.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Camera.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Screen.Camera.route) {
                CameraScreen()
            }
            composable(Screen.Chat.route) {
                ChatScreen()
            }
            composable(Screen.Settings.route) {
                SettingsScreen()
            }
        }
    }
}
