package com.spoolsuperman.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spoolsuperman.app.ui.components.*
import com.spoolsuperman.app.viewmodel.HomeViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        TopAppBar(
            title = { Text("线轴超人", fontWeight = FontWeight.Bold) },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.primary,
                titleContentColor = MaterialTheme.colorScheme.onPrimary
            ),
            actions = {
                IconButton(onClick = { viewModel.refresh() }) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = "刷新",
                        tint = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }
        )

        if (uiState.isLoading) {
            LoadingOverlay(true)
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // 统计卡片
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        StatCard(
                            title = "库存种类",
                            value = uiState.totalItems.toString(),
                            icon = Icons.Default.Category,
                            modifier = Modifier.weight(1f)
                        )
                        StatCard(
                            title = "总数量",
                            value = formatQuantity(uiState.totalQuantity),
                            icon = Icons.Default.Inventory,
                            modifier = Modifier.weight(1f),
                            containerColor = MaterialTheme.colorScheme.secondaryContainer
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        StatCard(
                            title = "品类数",
                            value = uiState.categoryCount.toString(),
                            icon = Icons.Default.GridView,
                            modifier = Modifier.weight(1f),
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer
                        )
                        StatCard(
                            title = "低库存预警",
                            value = uiState.lowStockCount.toString(),
                            icon = Icons.Default.Warning,
                            modifier = Modifier.weight(1f),
                            containerColor = if (uiState.lowStockCount > 0)
                                MaterialTheme.colorScheme.errorContainer
                            else MaterialTheme.colorScheme.primaryContainer
                        )
                    }
                }

                // 低库存预警列表
                if (uiState.lowStockItems.isNotEmpty()) {
                    item {
                        SectionHeader(
                            title = "低库存预警",
                            action = {
                                Text(
                                    text = "${uiState.lowStockItems.size} 项",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        )
                    }
                    items(uiState.lowStockItems.take(3)) { item ->
                        InventoryItemRow(
                            item = item,
                            onClick = {}
                        )
                    }
                }

                // 最近入库
                item {
                    SectionHeader(title = "最近入库")
                }
                if (uiState.recentItems.isEmpty()) {
                    item {
                        EmptyState(
                            icon = Icons.Default.Inbox,
                            title = "暂无入库记录",
                            subtitle = "点击入库标签开始录入耗材"
                        )
                    }
                } else {
                    items(uiState.recentItems.take(10)) { item ->
                        InventoryItemRow(
                            item = item,
                            onClick = {}
                        )
                    }
                }
            }
        }
    }
}
