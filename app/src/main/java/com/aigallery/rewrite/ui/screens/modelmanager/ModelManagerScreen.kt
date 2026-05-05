package com.aigallery.rewrite.ui.screens.modelmanager

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.aigallery.rewrite.ui.components.EmptyState
import com.aigallery.rewrite.ui.components.ErrorState
import com.aigallery.rewrite.ui.components.LoadingIndicator
import com.aigallery.rewrite.ui.components.ModelCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelManagerScreen(
    viewModel: ModelManagerViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("模型管理") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Search bar
            OutlinedTextField(
                value = state.searchQuery,
                onValueChange = viewModel::updateSearchQuery,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text("搜索模型...") },
                leadingIcon = {
                    Icon(Icons.Default.Search, contentDescription = null)
                },
                singleLine = true
            )

            when {
                state.isLoading -> {
                    LoadingIndicator(message = "加载模型列表...")
                }
                state.error != null -> {
                    ErrorState(
                        message = state.error!!,
                        onRetry = viewModel::retry
                    )
                }
                state.models.isEmpty() -> {
                    EmptyState(
                        title = "暂无模型",
                        description = "模型列表为空"
                    )
                }
                else -> {
                    val filteredModels = state.models.filter { model ->
                        val matchesSearch = state.searchQuery.isEmpty() ||
                                model.name.contains(state.searchQuery, ignoreCase = true) ||
                                model.description.contains(state.searchQuery, ignoreCase = true)
                        val matchesProvider = state.selectedProvider == null ||
                                model.provider.name == state.selectedProvider
                        matchesSearch && matchesProvider
                    }

                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        item {
                            Text(
                                text = "共 ${filteredModels.size} 个模型",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        items(filteredModels, key = { it.id }) { model ->
                            ModelCard(
                                model = model,
                                onDownload = { viewModel.downloadModel(model.id) },
                                onDelete = { viewModel.deleteModel(model.id) }
                            )
                        }
                    }
                }
            }
        }
    }
}
