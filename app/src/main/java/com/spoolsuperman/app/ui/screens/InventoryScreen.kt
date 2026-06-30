package com.spoolsuperman.app.ui.screens

import android.os.Environment
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spoolsuperman.app.data.entity.InventoryItem
import com.spoolsuperman.app.ui.components.*
import com.spoolsuperman.app.viewmodel.InventoryViewModel
import com.spoolsuperman.app.viewmodel.SortBy
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InventoryScreen(
    viewModel: InventoryViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var showDeleteDialog by remember { mutableStateOf(false) }
    var showSortMenu by remember { mutableStateOf(false) }
    var showFilterSheet by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("库存", fontWeight = FontWeight.Bold)
                    if (uiState.selectedIds.isNotEmpty()) {
                        Spacer(Modifier.width(8.dp))
                        Badge { Text("${uiState.selectedIds.size}") }
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.primary,
                titleContentColor = MaterialTheme.colorScheme.onPrimary
            ),
            actions = {
                IconButton(onClick = { showSortMenu = true }) {
                    Icon(Icons.Default.Sort, null, tint = MaterialTheme.colorScheme.onPrimary)
                }
                IconButton(onClick = { showFilterSheet = true }) {
                    Icon(Icons.Default.FilterList, null, tint = MaterialTheme.colorScheme.onPrimary)
                }
                IconButton(
                    onClick = {
                        if (uiState.selectedIds.isEmpty()) {
                            viewModel.toggleSelectAll()
                        } else {
                            viewModel.exportSelectedToExcel(
                                File(
                                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                                    "库存清单_${java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault()).format(java.util.Date())}.xlsx"
                                ).absolutePath
                            )
                        }
                    }
                ) {
                    Icon(
                        if (uiState.selectedIds.isNotEmpty()) Icons.Default.FileDownload else Icons.Default.SelectAll,
                        null,
                        tint = MaterialTheme.colorScheme.onPrimary
                    )
                }
                if (uiState.selectedIds.isNotEmpty()) {
                    IconButton(onClick = { showDeleteDialog = true }) {
                        Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.onPrimary)
                    }
                }
            }
        )

        // 搜索
        OutlinedTextField(
            value = uiState.searchQuery,
            onValueChange = { viewModel.updateSearchQuery(it) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            placeholder = { Text("搜索材料...") },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            singleLine = true,
            trailingIcon = {
                if (uiState.searchQuery.isNotBlank()) {
                    IconButton(onClick = { viewModel.updateSearchQuery("") }) {
                        Icon(Icons.Default.Clear, null)
                    }
                }
            }
        )

        // 筛选标签
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (uiState.filterCategory.isNotBlank()) {
                InputChip(
                    selected = true,
                    onClick = { viewModel.setFilterCategory("") },
                    label = { Text("品类: ${uiState.filterCategory}") },
                    trailingIcon = { Icon(Icons.Default.Close, null, Modifier.size(16.dp)) }
                )
            }
            if (uiState.filterStatus.isNotBlank()) {
                InputChip(
                    selected = true,
                    onClick = { viewModel.setFilterStatus("") },
                    label = { Text("状态: ${uiState.filterStatus}") },
                    trailingIcon = { Icon(Icons.Default.Close, null, Modifier.size(16.dp)) }
                )
            }
            if (uiState.filterLocation.isNotBlank()) {
                InputChip(
                    selected = true,
                    onClick = { viewModel.setFilterLocation("") },
                    label = { Text("位置: ${uiState.filterLocation}") },
                    trailingIcon = { Icon(Icons.Default.Close, null, Modifier.size(16.dp)) }
                )
            }
        }

        // 导出消息
        if (uiState.exportMessage != null) {
            Snackbar(
                modifier = Modifier.padding(16.dp),
                action = {
                    TextButton(onClick = { viewModel.clearExportMessage() }) { Text("确定") }
                }
            ) {
                Text(uiState.exportMessage!!)
            }
        }

        if (uiState.isLoading) {
            LoadingOverlay(true)
        } else if (uiState.filteredItems.isEmpty()) {
            EmptyState(
                icon = Icons.Default.Inbox,
                title = "库存为空",
                subtitle = "请先入库耗材"
            )
        } else {
            Text(
                "共 ${uiState.filteredItems.size} 项",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(uiState.filteredItems, key = { it.id }) { item ->
                    val isSelected = uiState.selectedIds.contains(item.id)
                    InventoryItemRow(
                        item = item,
                        onClick = { viewModel.toggleItemSelection(item.id) },
                        trailing = {
                            Checkbox(
                                checked = isSelected,
                                onCheckedChange = { viewModel.toggleItemSelection(item.id) }
                            )
                        }
                    )
                }
            }
        }
    }

    // 删除确认对话框
    if (showDeleteDialog) {
        ConfirmDialog(
            title = "删除确认",
            message = "确定要删除选中的 ${uiState.selectedIds.size} 项吗？此操作无法撤销。",
            onConfirm = {
                viewModel.deleteSelected()
                showDeleteDialog = false
            },
            onDismiss = { showDeleteDialog = false },
            confirmText = "删除"
        )
    }

    // 排序菜单
    DropdownMenu(
        expanded = showSortMenu,
        onDismissRequest = { showSortMenu = false }
    ) {
        SortBy.entries.forEach { sort ->
            DropdownMenuItem(
                text = {
                    Text(when (sort) {
                        SortBy.TIME_DESC -> "最近操作 (降序)"
                        SortBy.TIME_ASC -> "最近操作 (升序)"
                        SortBy.NAME_ASC -> "名称 (A-Z)"
                        SortBy.QUANTITY_DESC -> "数量 (多→少)"
                        SortBy.QUANTITY_ASC -> "数量 (少→多)"
                    })
                },
                onClick = {
                    viewModel.setSortBy(sort)
                    showSortMenu = false
                },
                leadingIcon = {
                    if (uiState.sortBy == sort) Icon(Icons.Default.Check, null)
                }
            )
        }
    }

    // 筛选底单
    if (showFilterSheet) {
        AlertDialog(
            onDismissRequest = { showFilterSheet = false },
            title = { Text("筛选") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("品类", fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        uiState.categories.take(8).forEach { cat ->
                            FilterChip(
                                selected = uiState.filterCategory == cat,
                                onClick = { viewModel.setFilterCategory(cat) },
                                label = { Text(cat) }
                            )
                        }
                    }
                    Divider()
                    Text("状态", fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf("充足", "不足", "缺货", "待采购").forEach { status ->
                            FilterChip(
                                selected = uiState.filterStatus == status,
                                onClick = { viewModel.setFilterStatus(status) },
                                label = { Text(status) }
                            )
                        }
                    }
                    Divider()
                    Text("位置", fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        uiState.locations.take(8).forEach { loc ->
                            FilterChip(
                                selected = uiState.filterLocation == loc,
                                onClick = { viewModel.setFilterLocation(loc) },
                                label = { Text(loc) }
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.setFilterCategory("")
                    viewModel.setFilterStatus("")
                    viewModel.setFilterLocation("")
                    showFilterSheet = false
                }) { Text("清除筛选") }
            },
            dismissButton = {
                TextButton(onClick = { showFilterSheet = false }) { Text("关闭") }
            }
        )
    }
}
