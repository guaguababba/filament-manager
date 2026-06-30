package com.spoolsuperman.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spoolsuperman.app.ui.components.SectionHeader
import com.spoolsuperman.app.viewmodel.SettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val categories by viewModel.categories.collectAsStateWithLifecycle()
    val locations by viewModel.locations.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var apiUrl by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var showApiKey by remember { mutableStateOf(false) }
    var showCategoryDialog by remember { mutableStateOf<String?>(null) }
    var showLocationDialog by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        viewModel.getApiUrl(context).collect { apiUrl = it }
        viewModel.getApiKey(context).collect { apiKey = it }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("设置", fontWeight = FontWeight.Bold) },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.primary,
                titleContentColor = MaterialTheme.colorScheme.onPrimary
            )
        )

        if (message != null) {
            Snackbar(
                modifier = Modifier.padding(16.dp),
                action = {
                    TextButton(onClick = { viewModel.clearMessage() }) { Text("确定") }
                }
            ) { Text(message!!) }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // AI 配置
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.SmartToy, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text("AI 配置", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(12.dp))

                    OutlinedTextField(
                        value = apiUrl,
                        onValueChange = { apiUrl = it },
                        label = { Text("API 地址") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { viewModel.saveApiUrl(context, apiUrl) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("保存 API 地址") }

                    Spacer(Modifier.height(12.dp))

                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        label = { Text("API Key") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        visualTransformation = if (showApiKey) VisualTransformation.None
                            else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { showApiKey = !showApiKey }) {
                                Icon(
                                    if (showApiKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    null
                                )
                            }
                        }
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { viewModel.saveApiKey(context, apiKey) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("保存 API Key") }
                }
            }

            // 品类管理
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Category, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text("品类管理", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(12.dp))

                    if (categories.isEmpty()) {
                        Text("暂无品类", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        categories.forEach { cat ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(cat, Modifier.weight(1f))
                                TextButton(onClick = { showCategoryDialog = cat }) {
                                    Text("编辑")
                                }
                            }
                        }
                    }
                }
            }

            // 存放位置管理
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Place, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text("存放位置管理", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(12.dp))

                    if (locations.isEmpty()) {
                        Text("暂无位置", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        locations.forEach { loc ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(loc, Modifier.weight(1f))
                                TextButton(onClick = { showLocationDialog = loc }) {
                                    Text("编辑")
                                }
                            }
                        }
                    }
                }
            }

            // 库存编号规则说明
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Info, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text("库存编号规则", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("格式: 品类缩写-YYMMDD-三位流水号", style = MaterialTheme.typography.bodyMedium)
                    Text("示例: HC-260630-001", style = MaterialTheme.typography.bodyMedium)
                    Text("（耗材-2026年6月30日-第1个）", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                    Text("品类缩写对照:", fontWeight = FontWeight.Medium)
                    Text("耗材 → HC | 配件 → PJ | 工具 → GJ | 原料 → YL | 成品 → CP | 半成品 → BCP")
                }
            }

            // 关于
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Info, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text("关于", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("线轴超人 v1.0.0", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                    Text("3D打印耗材管理工具", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("离线可用 · 数据本地存储", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }

    // 品类编辑弹窗
    showCategoryDialog?.let { oldName ->
        var newName by remember { mutableStateOf(oldName) }
        AlertDialog(
            onDismissRequest = { showCategoryDialog = null },
            title = { Text("编辑品类") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("品类名称") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.updateAllItemsCategory(context, oldName, newName)
                    showCategoryDialog = null
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { showCategoryDialog = null }) { Text("取消") }
            }
        )
    }

    // 位置编辑弹窗
    showLocationDialog?.let { oldName ->
        var newName by remember { mutableStateOf(oldName) }
        AlertDialog(
            onDismissRequest = { showLocationDialog = null },
            title = { Text("编辑位置") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("位置名称") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.updateAllItemsLocation(context, oldName, newName)
                    showLocationDialog = null
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { showLocationDialog = null }) { Text("取消") }
            }
        )
    }
}
