package com.aigallery.rewrite.ui.navigation

import androidx.compose.runtime.Composable
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.aigallery.rewrite.ui.screens.agentchat.AgentChatScreen
import com.aigallery.rewrite.ui.screens.customtasks.CustomTasksScreen
import com.aigallery.rewrite.ui.screens.customtasks.MobileActionsScreen
import com.aigallery.rewrite.ui.screens.home.HomeScreen
import com.aigallery.rewrite.ui.screens.llmchat.ChatSessionScreen
import com.aigallery.rewrite.ui.screens.memory.MemoryDetailScreen
import com.aigallery.rewrite.ui.screens.memory.MemoryScreen
import com.aigallery.rewrite.ui.screens.modelmanager.ModelManagerScreen
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
                onNavigateToChat = { modelId ->
                    navController.navigate(Screen.ChatSession.createRoute(modelId))
                }
            )
        }

        composable(Screen.ModelManager.route) {
            ModelManagerScreen(
                onNavigateToChat = { modelId ->
                    navController.navigate(Screen.ChatSession.createRoute(modelId))
                }
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
                onNavigateToAgentChat = { skillId ->
                    navController.navigate(Screen.AgentChat.createRoute(skillId))
                },
                onNavigateToMobileActions = {
                    navController.navigate(Screen.MobileActions.route)
                }
            )
        }

        composable(Screen.SingleTurn.route) {
            SingleTurnScreen()
        }

        // Detail screens
        composable(
            route = Screen.ChatSession.route,
            arguments = listOf(navArgument("sessionId") { type = NavType.StringType })
        ) { backStackEntry ->
            val sessionId = backStackEntry.arguments?.getString("sessionId") ?: return@composable
            ChatSessionScreen(
                onBack = { navController.popBackStack() }
            )
        }

        composable(
            route = Screen.AgentChat.route,
            arguments = listOf(navArgument("skillId") { type = NavType.StringType })
        ) { backStackEntry ->
            val skillId = backStackEntry.arguments?.getString("skillId") ?: return@composable
            AgentChatScreen(
                skillId = skillId,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(Screen.MobileActions.route) {
            MobileActionsScreen(
                onNavigateToTask = { navController.popBackStack() }
            )
        }

        composable(
            route = Screen.MemoryDetail.route,
            arguments = listOf(navArgument("memoryId") { type = NavType.StringType })
        ) { backStackEntry ->
            val memoryId = backStackEntry.arguments?.getString("memoryId") ?: return@composable
            MemoryDetailScreen(
                memoryId = memoryId,
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}
