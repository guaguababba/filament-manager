package com.spoolsuperman.app.ui.screens

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spoolsuperman.app.ui.components.LoadingOverlay
import com.spoolsuperman.app.viewmodel.InboundViewModel
import com.spoolsuperman.app.viewmodel.SettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InboundScreen(
    viewModel: InboundViewModel = hiltViewModel(),
    settingsViewModel: SettingsViewModel = hiltViewModel()
) {
    val formState by viewModel.formState.collectAsStateWithLifecycle()
    val categories by viewModel.categories.collectAsStateWithLifecycle()
    val locations by viewModel.locations.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var apiUrl by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var showScanner by remember { mutableStateOf(false) }
    var showVoiceInput by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        settingsViewModel.getApiUrl(context).collect { apiUrl = it }
        settingsViewModel.getApiKey(context).collect { apiKey = it }
    }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            val path = it.path ?: it.toString()
            viewModel.setCapturedImage(it.path ?: it.toString())
            viewModel.recognizeWithAi(it.path ?: it.toString(), apiUrl, apiKey)
        }
    }

    val voiceLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val text = result.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
        if (text != null) {
            viewModel.onVoiceResult(text)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        TopAppBar(
            title = { Text("入库", fontWeight = FontWeight.Bold) },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.primary,
                titleContentColor = MaterialTheme.colorScheme.onPrimary
            )
        )

        // AI 模式切换
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = !formState.isAiMode,
                onClick = { /* 手动模式 */ },
                label = { Text("手动录入") },
                leadingIcon = { Icon(Icons.Default.Edit, null, Modifier.size(18.dp)) }
            )
            FilterChip(
                selected = formState.isAiMode,
                onClick = { viewModel.toggleAiMode() },
                label = { Text("AI 识别") },
                leadingIcon = { Icon(Icons.Default.PhotoCamera, null, Modifier.size(18.dp)) }
            )
            Spacer(modifier = Modifier.weight(1f))
            OutlinedIconButton(
                onClick = {
                    showVoiceInput = true
                    voiceLauncher.launch(viewModel.createVoiceIntent())
                }
            ) {
                Icon(Icons.Default.Mic, null)
            }
        }

        if (formState.submitResult != null) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (formState.submitResult.contains("成功"))
                        MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        if (formState.submitResult.contains("成功")) Icons.Default.CheckCircle
                        else Icons.Default.Error,
                        null
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(formState.submitResult, Modifier.weight(1f))
                    TextButton(onClick = {
                        viewModel.clearSubmitResult()
                        if (formState.submitResult.contains("成功")) viewModel.resetForm()
                    }) { Text("确定") }
                }
            }
        }

        if (formState.isAiMode) {
            AiModeContent(
                formState = formState,
                onPickImage = { imagePickerLauncher.launch("image/*") },
                onFillFromParsed = { viewModel.fillFromParsed() }
            )
        }

        // 表单
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 品类
            ExposedDropdownMenuBox(
                expanded = false,
                onExpandedChange = {}
            ) {
                OutlinedTextField(
                    value = formState.category,
                    onValueChange = { viewModel.updateField("category", it) },
                    label = { Text("品类") },
                    modifier = Modifier.fillMaxWidth().menuAnchor(),
                    singleLine = true
                )
                // 简单自动补全提示
            }

            OutlinedTextField(
                value = formState.materialName,
                onValueChange = { viewModel.updateField("materialName", it) },
                label = { Text("材料名称 *") },
                placeholder = { Text("如: PLA 白色") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = formState.brand,
                    onValueChange = { viewModel.updateField("brand", it) },
                    label = { Text("品牌") },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                OutlinedTextField(
                    value = formState.filamentColor,
                    onValueChange = { viewModel.updateField("filamentColor", it) },
                    label = { Text("颜色") },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = formState.filamentMaterial,
                    onValueChange = { viewModel.updateField("filamentMaterial", it) },
                    label = { Text("材质") },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                OutlinedTextField(
                    value = formState.filamentDiameter,
                    onValueChange = { viewModel.updateField("filamentDiameter", it) },
                    label = { Text("直径") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    placeholder = { Text("如: 1.75mm") }
                )
            }

            OutlinedTextField(
                value = formState.spec,
                onValueChange = { viewModel.updateField("spec", it) },
                label = { Text("规格") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("如: 1kg/卷") }
            )

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = formState.quantity,
                    onValueChange = { viewModel.updateField("quantity", it) },
                    label = { Text("数量") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                OutlinedTextField(
                    value = formState.threshold,
                    onValueChange = { viewModel.updateField("threshold", it) },
                    label = { Text("预警阈值") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
            }

            OutlinedTextField(
                value = formState.location,
                onValueChange = { viewModel.updateField("location", it) },
                label = { Text("存放位置") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("如: A柜-3层") }
            )

            OutlinedTextField(
                value = formState.remark,
                onValueChange = { viewModel.updateField("remark", it) },
                label = { Text("备注") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2
            )

            Spacer(Modifier.height(8.dp))

            Button(
                onClick = { viewModel.submitInbound() },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                enabled = !formState.isSubmitting
            ) {
                if (formState.isSubmitting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Icon(Icons.Default.AddCircle, null)
                    Spacer(Modifier.width(8.dp))
                    Text("确认入库", style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }

    LoadingOverlay(formState.isSubmitting)
}

@Composable
private fun AiModeContent(
    formState: com.spoolsuperman.app.viewmodel.InboundFormState,
    onPickImage: () -> Unit,
    onFillFromParsed: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    Icons.Default.PhotoCamera,
                    null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(8.dp))
                Text("拍照或选择图片进行 AI 识别", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                Button(onClick = onPickImage) {
                    Icon(Icons.Default.Image, null)
                    Spacer(Modifier.width(8.dp))
                    Text("选择图片")
                }
            }
        }

        if (formState.parsedItem != null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer
                )
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text("识别结果:", fontWeight = FontWeight.Bold)
                    Text("材料: ${formState.parsedItem.materialName}")
                    Text("品类: ${formState.parsedItem.category}")
                    Text("品牌: ${formState.parsedItem.brand}")
                    Text("颜色: ${formState.parsedItem.filamentColor}")
                    Text("材质: ${formState.parsedItem.filamentMaterial}")
                    Text("规格: ${formState.parsedItem.spec}")
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = onFillFromParsed,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("填充到表单")
                    }
                }
            }
        }
    }
}
