package com.spoolsuperman.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.zxing.integration.android.IntentIntegrator
import com.spoolsuperman.app.data.entity.InventoryItem
import com.spoolsuperman.app.ui.components.*
import com.spoolsuperman.app.viewmodel.OutboundViewModel
import androidx.compose.ui.platform.LocalContext
import androidx.activity.compose.rememberLauncherForActivityResult
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OutboundScreen(
    viewModel: OutboundViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let { code ->
            viewModel.onQrCodeScanned(code)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("出库", fontWeight = FontWeight.Bold) },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.primary,
                titleContentColor = MaterialTheme.colorScheme.onPrimary
            ),
            actions = {
                IconButton(onClick = {
                    val options = ScanOptions().apply {
                        setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                        setPrompt("扫描耗材二维码")
                        setBeepEnabled(true)
                        setOrientationLocked(true)
                    }
                    scanLauncher.launch(options)
                }) {
                    Icon(Icons.Default.QrCodeScanner, null, tint = MaterialTheme.colorScheme.onPrimary)
                }
            }
        )

        if (uiState.submitResult != null) {
            val result = uiState.submitResult
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (result.contains("成功"))
                        MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        if (result.contains("成功")) Icons.Default.CheckCircle
                        else Icons.Default.Error,
                        null
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(result, Modifier.weight(1f))
                    TextButton(onClick = { viewModel.clearSubmitResult() }) { Text("确定") }
                }
            }
        }

        // 搜索栏
        OutlinedTextField(
            value = uiState.searchQuery,
            onValueChange = { viewModel.updateSearchQuery(it) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            placeholder = { Text("搜索材料名称或编号...") },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            singleLine = true
        )

        // 搜索结果或已选项
        if (uiState.selectedItem != null) {
            SelectedItemSection(
                item = uiState.selectedItem!!,
                outQuantity = uiState.outQuantity,
                onQuantityChange = { viewModel.updateOutQuantity(it) },
                onQuickAll = { viewModel.quickOutAll() },
                onSubmit = { viewModel.submitOutbound() },
                onClear = { viewModel.clearSelection() },
                isSubmitting = uiState.isSubmitting
            )
        } else if (uiState.searchResults.isNotEmpty()) {
            Text(
                "搜索结果 (${uiState.searchResults.size})",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(uiState.searchResults) { item ->
                    InventoryItemRow(
                        item = item,
                        onClick = { viewModel.selectItem(item) }
                    )
                }
            }
        } else {
            EmptyState(
                icon = Icons.Default.SearchOff,
                title = "搜索材料进行出库",
                subtitle = "输入名称/编号搜索，或点击右上角扫码"
            )
        }
    }
}

@Composable
private fun SelectedItemSection(
    item: InventoryItem,
    outQuantity: String,
    onQuantityChange: (String) -> Unit,
    onQuickAll: () -> Unit,
    onSubmit: () -> Unit,
    onClear: () -> Unit,
    isSubmitting: Boolean
) {
    Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(item.materialName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(item.code, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(getItemSubtitle(item), style = MaterialTheme.typography.bodySmall)
                    }
                    StatusBadge(item.status)
                }
                Spacer(Modifier.height(8.dp))
                Text("库存数量: ${formatQuantity(item.quantity)}", style = MaterialTheme.typography.titleMedium)
                if (item.location.isNotBlank()) {
                    Text("位置: ${item.location}", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = outQuantity,
                onValueChange = onQuantityChange,
                label = { Text("出库数量") },
                modifier = Modifier.weight(1f),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
            OutlinedButton(onClick = onQuickAll) {
                Text("整卷")
            }
        }

        Button(
            onClick = onSubmit,
            modifier = Modifier.fillMaxWidth().height(50.dp),
            enabled = !isSubmitting
        ) {
            if (isSubmitting) {
                CircularProgressIndicator(Modifier.size(24.dp), color = MaterialTheme.colorScheme.onPrimary)
            } else {
                Icon(Icons.Default.RemoveCircle, null)
                Spacer(Modifier.width(8.dp))
                Text("确认出库", style = MaterialTheme.typography.titleMedium)
            }
        }

        TextButton(
            onClick = onClear,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.ArrowBack, null)
            Spacer(Modifier.width(4.dp))
            Text("返回搜索")
        }
    }
}
