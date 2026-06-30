package com.spoolsuperman.app.viewmodel

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.speech.RecognizerIntent
import android.content.Intent
import android.util.Base64
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spoolsuperman.app.data.api.AiApiService
import com.spoolsuperman.app.data.api.AiContent
import com.spoolsuperman.app.data.api.AiMessage
import com.spoolsuperman.app.data.api.AiVisionRequest
import com.spoolsuperman.app.data.api.ImageUrl
import com.spoolsuperman.app.data.entity.InventoryItem
import com.spoolsuperman.app.data.repository.InventoryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import javax.inject.Inject

data class InboundFormState(
    val code: String = "",
    val materialName: String = "",
    val category: String = "",
    val brand: String = "",
    val filamentColor: String = "",
    val filamentMaterial: String = "",
    val filamentDiameter: String = "",
    val spec: String = "",
    val quantity: String = "1",
    val status: String = "充足",
    val location: String = "",
    val threshold: String = "1",
    val remark: String = "",
    val isSubmitting: Boolean = false,
    val submitResult: String? = null,
    val parsedItem: InventoryItem? = null,
    val isAiMode: Boolean = false,
    val isVoiceActive: Boolean = false,
    val isCameraActive: Boolean = false,
    val capturedImagePath: String? = null,
    val recognizedText: String? = null
)

@HiltViewModel
class InboundViewModel @Inject constructor(
    private val repository: InventoryRepository
) : ViewModel() {

    private val _formState = MutableStateFlow(InboundFormState())
    val formState: StateFlow<InboundFormState> = _formState.asStateFlow()

    private val _categories = MutableStateFlow<List<String>>(emptyList())
    val categories: StateFlow<List<String>> = _categories.asStateFlow()

    private val _locations = MutableStateFlow<List<String>>(emptyList())
    val locations: StateFlow<List<String>> = _locations.asStateFlow()

    private var apiService: AiApiService? = null

    init {
        viewModelScope.launch {
            repository.getAllCategories().collect { _categories.value = it }
        }
        viewModelScope.launch {
            repository.getAllLocations().collect { _locations.value = it }
        }
    }

    fun updateField(field: String, value: String) {
        _formState.value = when (field) {
            "materialName" -> _formState.value.copy(materialName = value)
            "category" -> _formState.value.copy(category = value)
            "brand" -> _formState.value.copy(brand = value)
            "filamentColor" -> _formState.value.copy(filamentColor = value)
            "filamentMaterial" -> _formState.value.copy(filamentMaterial = value)
            "filamentDiameter" -> _formState.value.copy(filamentDiameter = value)
            "spec" -> _formState.value.copy(spec = value)
            "quantity" -> _formState.value.copy(quantity = value)
            "location" -> _formState.value.copy(location = value)
            "threshold" -> _formState.value.copy(threshold = value)
            "remark" -> _formState.value.copy(remark = value)
            "status" -> _formState.value.copy(status = value)
            else -> _formState.value
        }
    }

    fun toggleAiMode() {
        _formState.value = _formState.value.copy(
            isAiMode = !_formState.value.isAiMode,
            capturedImagePath = null,
            recognizedText = null,
            parsedItem = null
        )
    }

    fun setCapturedImage(path: String) {
        _formState.value = _formState.value.copy(capturedImagePath = path)
    }

    fun setVoiceActive(active: Boolean) {
        _formState.value = _formState.value.copy(isVoiceActive = active)
    }

    fun setCameraActive(active: Boolean) {
        _formState.value = _formState.value.copy(isCameraActive = active)
    }

    fun createVoiceIntent(): Intent {
        return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "请说出耗材信息")
        }
    }

    fun onVoiceResult(text: String) {
        _formState.value = _formState.value.copy(recognizedText = text)
        parseAiText(text)
    }

    fun recognizeWithAi(
        imagePath: String,
        apiUrl: String,
        apiKey: String
    ) {
        viewModelScope.launch {
            _formState.value = _formState.value.copy(isSubmitting = true)
            try {
                val bitmap = withContext(Dispatchers.IO) {
                    BitmapFactory.decodeFile(imagePath)
                }
                val base64Image = bitmapToBase64(bitmap)
                val service = getOrCreateApiService(apiUrl)
                val response = withContext(Dispatchers.IO) {
                    service.visionInference(
                        AiVisionRequest(
                            messages = listOf(
                                AiMessage(
                                    content = listOf(
                                        AiContent(
                                            type = "image_url",
                                            imageUrl = ImageUrl("data:image/jpeg;base64,$base64Image")
                                        ),
                                        AiContent(
                                            type = "text",
                                            text = """识别这张图片中的耗材/材料信息，以JSON格式返回：
{
  "material_name": "材料名称",
  "category": "耗材分类",
  "brand": "品牌",
  "filament_color": "颜色",
  "filament_material": "材质",
  "filament_diameter": "直径",
  "spec": "规格",
  "quantity": 1
}
只返回JSON，不要其他文字。"""
                                        )
                                    )
                                )
                            )
                        ),
                        auth = "Bearer $apiKey"
                    )
                }
                val content = response.choices?.firstOrNull()?.message?.content ?: ""
                parseAiResponse(content)
            } catch (e: Exception) {
                _formState.value = _formState.value.copy(
                    isSubmitting = false,
                    submitResult = "识别失败: ${e.message}"
                )
            }
        }
    }

    private fun parseAiResponse(content: String) {
        val jsonStr = content.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        try {
            val gson = com.google.gson.Gson()
            val map = gson.fromJson(jsonStr, Map::class.java) as Map<String, Any>
            val item = InventoryItem(
                materialName = map["material_name"]?.toString() ?: "",
                category = map["category"]?.toString() ?: "",
                brand = map["brand"]?.toString() ?: "",
                filamentColor = map["filament_color"]?.toString() ?: "",
                filamentMaterial = map["filament_material"]?.toString() ?: "",
                filamentDiameter = map["filament_diameter"]?.toString() ?: "",
                spec = map["spec"]?.toString() ?: "",
                quantity = (map["quantity"] as? Double) ?: 1.0
            )
            _formState.value = _formState.value.copy(
                parsedItem = item,
                isSubmitting = false,
                recognizedText = content
            )
        } catch (e: Exception) {
            _formState.value = _formState.value.copy(
                isSubmitting = false,
                recognizedText = content
            )
        }
    }

    fun parseAiText(text: String) {
        val jsonStr = text.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        try {
            val gson = com.google.gson.Gson()
            val map = gson.fromJson(jsonStr, Map::class.java) as Map<String, Any>
            val item = InventoryItem(
                materialName = map["material_name"]?.toString() ?: "",
                category = map["category"]?.toString() ?: "",
                brand = map["brand"]?.toString() ?: "",
                filamentColor = map["filament_color"]?.toString() ?: "",
                filamentMaterial = map["filament_material"]?.toString() ?: "",
                filamentDiameter = map["filament_diameter"]?.toString() ?: "",
                spec = map["spec"]?.toString() ?: "",
                quantity = (map["quantity"] as? Double) ?: 1.0
            )
            _formState.value = _formState.value.copy(parsedItem = item)
        } catch (_: Exception) {
            // 非JSON文本，尝试从自然语言提取
            extractFromNaturalLanguage(text)
        }
    }

    private fun extractFromNaturalLanguage(text: String) {
        val item = InventoryItem(materialName = text.take(50))
        _formState.value = _formState.value.copy(parsedItem = item)
    }

    fun fillFromParsed() {
        val item = _formState.value.parsedItem ?: return
        _formState.value = _formState.value.copy(
            materialName = item.materialName,
            category = item.category,
            brand = item.brand,
            filamentColor = item.filamentColor,
            filamentMaterial = item.filamentMaterial,
            filamentDiameter = item.filamentDiameter,
            spec = item.spec,
            quantity = item.quantity.toInt().toString()
        )
    }

    fun submitInbound() {
        val state = _formState.value
        val qty = state.quantity.toDoubleOrNull() ?: 1.0
        val threshold = state.threshold.toDoubleOrNull() ?: 1.0

        if (state.materialName.isBlank()) {
            _formState.value = state.copy(submitResult = "请输入材料名称")
            return
        }

        viewModelScope.launch {
            _formState.value = _formState.value.copy(isSubmitting = true)
            try {
                val code = withContext(Dispatchers.IO) {
                    repository.generateInboundCode(state.category.ifBlank { "未分类" })
                }
                val item = InventoryItem(
                    code = code,
                    materialName = state.materialName,
                    category = state.category.ifBlank { "未分类" },
                    brand = state.brand,
                    filamentColor = state.filamentColor,
                    filamentMaterial = state.filamentMaterial,
                    filamentDiameter = state.filamentDiameter,
                    spec = state.spec,
                    quantity = qty,
                    status = if (qty <= threshold) "不足" else "充足",
                    location = state.location,
                    threshold = threshold,
                    remark = state.remark
                )
                repository.inbound(item)
                _formState.value = InboundFormState().copy(
                    submitResult = "入库成功: $code"
                )
            } catch (e: Exception) {
                _formState.value = _formState.value.copy(
                    isSubmitting = false,
                    submitResult = "入库失败: ${e.message}"
                )
            }
        }
    }

    fun resetForm() {
        _formState.value = InboundFormState()
    }

    fun clearSubmitResult() {
        _formState.value = _formState.value.copy(submitResult = null)
    }

    private fun getOrCreateApiService(apiUrl: String): AiApiService {
        if (apiService == null) {
            val baseUrl = apiUrl.trimEnd('/') + "/"
            val okHttpClient = OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build()
            val retrofit = Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(okHttpClient)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
            apiService = retrofit.create(AiApiService::class.java)
        }
        return apiService!!
    }

    private fun bitmapToBase64(bitmap: Bitmap): String {
        val baos = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 80, baos)
        val bytes = baos.toByteArray()
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }
}
