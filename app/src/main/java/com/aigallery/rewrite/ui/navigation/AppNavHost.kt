package com.aigallery.rewrite.ui.navigation

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.aigallery.rewrite.ui.screens.customtasks.AgentChatScreen
import com.aigallery.rewrite.ui.screens.customtasks.CustomTasksScreen
import com.aigallery.rewrite.ui.screens.customtasks.MobileActionsScreen
import com.aigallery.rewrite.ui.screens.home.HomeScreen
import com.aigallery.rewrite.ui.screens.llmchat.LLMChatScreen
import com.aigallery.rewrite.ui.screens.llmchat.ChatSessionScreen
import com.aigallery.rewrite.ui.screens.memory.MemoryScreen
import com.aigallery.rewrite.ui.screens.memory.MemoryDetailScreen
import com.aigallery.rewrite.ui.screens.modelmanager.ModelManagerScreen
import com.aigallery.rewrite.ui.screens.settings.SettingsScreen
import com.aigallery.rewrite.ui.screens.singleturn.SingleTurnScreen

@Composable
fun AppNavHost(
    navController: NavHostController,
    paddingValues: PaddingValues,
    modifier: Modifier = Modifier
) {
    NavHost(
        navController = navController,
        startDestination = Screen.Home.route,
        modifier = modifier.padding(paddingValues)
    ) {
        // Main tabs
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
            ModelManagerScreen()
        }

        composable(Screen.LLMChat.route) {
            LLMChatScreen(
                onNavigateToSession = { sessionId ->
                    navController.navigate(Screen.ChatSession.createRoute(sessionId))
                }
            )
        }

        composable(Screen.Memory.route) {
            MemoryScreen(
                onNavigateToDetail = { memoryId ->
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
                sessionId = sessionId,
                onNavigateBack = { navController.popBackStack() }
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
                onNavigateBack = { navController.popBackStack() }
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

        composable(Screen.Settings.route) {
            SettingsScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}
