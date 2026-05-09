package com.aigallery.rewrite.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Navigation routes for the app
 */
sealed class Screen(
    val route: String,
    val title: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
) {
    // Main screens
    data object Home : Screen(
        route = "home",
        title = "首页",
        selectedIcon = Icons.Filled.Home,
        unselectedIcon = Icons.Filled.Home
    )

    data object ModelManager : Screen(
        route = "model_manager",
        title = "模型管理",
        selectedIcon = Icons.Filled.Download,
        unselectedIcon = Icons.Filled.Download
    )

    data object LLMChat : Screen(
        route = "llm_chat",
        title = "AI对话",
        selectedIcon = Icons.Filled.Email,
        unselectedIcon = Icons.Filled.Email
    )

    data object Memory : Screen(
        route = "memory",
        title = "记忆中心",
        selectedIcon = Icons.Filled.Storage,
        unselectedIcon = Icons.Filled.Storage
    )

    data object CustomTasks : Screen(
        route = "custom_tasks",
        title = "自定义任务",
        selectedIcon = Icons.Filled.Settings,
        unselectedIcon = Icons.Filled.Settings
    )

    data object SingleTurn : Screen(
        route = "single_turn",
        title = "单轮任务",
        selectedIcon = Icons.Filled.FlashOn,
        unselectedIcon = Icons.Filled.FlashOn
    )

    // Sub-screens
    data object ChatSession : Screen(
        route = "chat_session/{sessionId}",
        title = "聊天",
        selectedIcon = Icons.Filled.Email,
        unselectedIcon = Icons.Filled.Email
    ) {
        fun createRoute(sessionId: String) = "chat_session/$sessionId"
    }

    data object AgentChat : Screen(
        route = "agent_chat/{skillId}",
        title = "Agent对话",
        selectedIcon = Icons.Filled.Extension,
        unselectedIcon = Icons.Filled.Extension
    ) {
        fun createRoute(skillId: String) = "agent_chat/$skillId"
    }

    data object MobileActions : Screen(
        route = "mobile_actions",
        title = "手机控制",
        selectedIcon = Icons.Filled.Phone,
        unselectedIcon = Icons.Filled.Phone
    )

    data object MemoryDetail : Screen(
        route = "memory_detail/{memoryId}",
        title = "记忆详情",
        selectedIcon = Icons.Filled.Info,
        unselectedIcon = Icons.Filled.Info
    ) {
        fun createRoute(memoryId: String) = "memory_detail/$memoryId"
    }

    data object Settings : Screen(
        route = "settings",
        title = "设置",
        selectedIcon = Icons.Filled.Settings,
        unselectedIcon = Icons.Filled.Settings
    )

    data object About : Screen(
        route = "about",
        title = "关于",
        selectedIcon = Icons.Filled.Info,
        unselectedIcon = Icons.Filled.Info
    )

    companion object {
        val bottomNavItems = listOf(
            ModelManager,
            LLMChat,
            Memory,
            CustomTasks,
            SingleTurn
        )
    }
}
