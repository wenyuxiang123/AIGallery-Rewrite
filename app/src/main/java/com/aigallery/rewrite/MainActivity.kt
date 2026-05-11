package com.aigallery.rewrite

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.aigallery.rewrite.ui.navigation.AppNavHost
import com.aigallery.rewrite.ui.navigation.Screen
import com.aigallery.rewrite.ui.theme.AIGalleryRewriteTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            AIGalleryRewriteTheme {
                MainScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen() {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination
    val currentRoute = currentDestination?.route

    // Drawer state
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    // Open drawer function - 将传递给子页面
    val onMenuClick: () -> Unit = {
        if (drawerState.isClosed) {
            // 需要在协程中打开 drawer
            scope.launch {
                drawerState.open()
            }
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            DrawerContent(
                currentRoute = currentRoute,
                onItemClick = { screen ->
                    // 导航到对应页面
                    navController.navigate(screen.route) {
                        // 如果是子页面（如 ChatSession），不 popUpTo
                        val isMainScreen = screen.route in listOf("llm_chat", "model_manager", "memory", "custom_tasks", "single_turn", "settings", "about") || screen.route.startsWith("chat_session")
                        if (isMainScreen) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                        }
                        launchSingleTop = true
                        restoreState = true
                    }
                    // 关闭抽屉
                    scope.launch {
                        drawerState.close()
                    }
                }
            )
        },
        gesturesEnabled = drawerState.isOpen
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            AppNavHost(
                navController = navController,
                onMenuClick = onMenuClick
            )
        }
    }
}

@Composable
private fun DrawerContent(
    currentRoute: String?,
    onItemClick: (Screen) -> Unit
) {
    ModalDrawerSheet(
        modifier = Modifier.fillMaxHeight()
    ) {
        // 抽屉头部
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(160.dp)
                .background(MaterialTheme.colorScheme.primary)
                .padding(16.dp),
            contentAlignment = Alignment.BottomStart
        ) {
            Column {
                Text(
                    text = "AIGallery",
                    style = MaterialTheme.typography.headlineMedium,
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "本地 LLM 智能助手",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.8f)
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 导航项列表 - 直接用Column，不使用LazyColumn避免NPE
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
        ) {
            Screen.ChatSession.let { screen ->
                DrawerItem(
                    title = "AI对话",
                    icon = screen.selectedIcon,
                    isSelected = currentRoute == screen.route || currentRoute?.startsWith("chat_session") == true,
                    onClick = { onItemClick(screen) }
                )
            }
            Screen.LLMChat.let { screen ->
                DrawerItem(
                    title = "历史对话",
                    icon = Icons.Default.History,
                    isSelected = currentRoute == screen.route,
                    onClick = { onItemClick(screen) }
                )
            }
            Screen.Memory.let { screen ->
                DrawerItem(
                    title = "记忆中心",
                    icon = screen.selectedIcon,
                    isSelected = currentRoute == screen.route,
                    onClick = { onItemClick(screen) }
                )
            }
            Screen.CustomTasks.let { screen ->
                DrawerItem(
                    title = "自定义任务",
                    icon = screen.selectedIcon,
                    isSelected = currentRoute == screen.route,
                    onClick = { onItemClick(screen) }
                )
            }
            Screen.SingleTurn.let { screen ->
                DrawerItem(
                    title = "单轮任务",
                    icon = screen.selectedIcon,
                    isSelected = currentRoute == screen.route,
                    onClick = { onItemClick(screen) }
                )
            }
            Screen.Settings.let { screen ->
                DrawerItem(
                    title = "设置",
                    icon = screen.selectedIcon,
                    isSelected = currentRoute == screen.route,
                    onClick = { onItemClick(screen) }
                )
            }
            Screen.About.let { screen ->
                DrawerItem(
                    title = "关于",
                    icon = screen.selectedIcon,
                    isSelected = currentRoute == screen.route,
                    onClick = { onItemClick(screen) }
                )
            }
        }
    }
}

@Composable
private fun DrawerItem(
    title: String,
    icon: ImageVector,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val backgroundColor = if (isSelected) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
    } else {
        Color.Transparent
    }
    
    val contentColor = if (isSelected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .background(backgroundColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = title,
            tint = contentColor,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = contentColor,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}
