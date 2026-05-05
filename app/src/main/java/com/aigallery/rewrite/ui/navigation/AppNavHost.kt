package com.aigallery.rewrite.ui.navigation

import androidx.compose.runtime.Composable
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.aigallery.rewrite.ui.screens.customtasks.CustomTasksScreen
import com.aigallery.rewrite.ui.screens.customtasks.MobileActionsScreen
import com.aigallery.rewrite.ui.screens.home.HomeScreen
import com.aigallery.rewrite.ui.screens.llmchat.ChatSessionScreen
import com.aigallery.rewrite.ui.screens.memory.MemoryDetailScreen
import com.aigallery.rewrite.ui.screens.memory.MemoryScreen
import com.aigallery.rewrite.ui.screens.modelmanager.ModelManagerScreen
import com.aigallery.rewrite.ui.screens.settings.SettingsScreen
import com.aigallery.rewrite.ui.screens.singleturn.SingleTurnScreen

/**
 * App导航宿主
 * 定义所有屏幕的路由和导航逻辑
 */
@Composable
fun AppNavHost(
    navController: NavHostController,
    startDestination: String = Screen.Home.route
) {
    NavHost(
        navController = navController,
        startDestination = startDestination
    ) {
        // Main screens
        composable(Screen.Home.route) {
            HomeScreen(
                onNavigateToModelManager = { navController.navigate(Screen.ModelManager.route) },
                onNavigateToLLMChat = { navController.navigate(Screen.LLMChat.route) },
                onNavigateToMemory = { navController.navigate(Screen.Memory.route) },
                onNavigateToCustomTasks = { navController.navigate(Screen.CustomTasks.route) },
                onNavigateToSingleTurn = { navController.navigate(Screen.SingleTurn.route) },
                onNavigateToSettings = { navController.navigate(Screen.Settings.route) }
            )
        }

        composable(Screen.ModelManager.route) {
            ModelManagerScreen(
                onModelSelected = { modelId ->
                    navController.navigate(Screen.ChatSession.createRoute(modelId))
                }
            )
        }

        composable(Screen.LLMChat.route) {
            ChatSessionScreen(
                onBack = { navController.popBackStack() }
            )
        }

        composable(Screen.Memory.route) {
            MemoryScreen(
                onMemoryClick = { memoryId ->
                    navController.navigate(Screen.MemoryDetail.createRoute(memoryId))
                }
            )
        }

        composable(Screen.CustomTasks.route) {
            CustomTasksScreen(
                onNavigateToMobileActions = {
                    navController.navigate(Screen.MobileActions.route)
                }
            )
        }

        composable(Screen.SingleTurn.route) {
            SingleTurnScreen()
        }

        composable(Screen.Settings.route) {
            SettingsScreen()
        }

        // Detail screens
        composable(
            route = Screen.ChatSession.route,
            arguments = listOf(navArgument("sessionId") { defaultValue = ""; type = NavType.StringType })
        ) { backStackEntry ->
            val sessionId = backStackEntry.arguments?.getString("sessionId") ?: ""
            ChatSessionScreen(
                onBack = { navController.popBackStack() }
            )
        }

        composable(Screen.MobileActions.route) {
            MobileActionsScreen(
                onNavigateToTask = { navController.popBackStack() }
            )
        }

        composable(
            route = Screen.MemoryDetail.route,
            arguments = listOf(navArgument("memoryId") { defaultValue = ""; type = NavType.StringType })
        ) { backStackEntry ->
            val memoryId = backStackEntry.arguments?.getString("memoryId") ?: ""
            MemoryDetailScreen(
                memoryId = memoryId,
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}
