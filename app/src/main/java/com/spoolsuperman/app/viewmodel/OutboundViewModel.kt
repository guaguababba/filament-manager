package com.spoolsuperman.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spoolsuperman.app.data.entity.InventoryItem
import com.spoolsuperman.app.data.repository.InventoryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class OutboundUiState(
    val searchQuery: String = "",
    val searchResults: List<InventoryItem> = emptyList(),
    val selectedItem: InventoryItem? = null,
    val outQuantity: String = "",
    val isSubmitting: Boolean = false,
    val submitResult: String? = null,
    val isScanning: Boolean = false
)

@HiltViewModel
class OutboundViewModel @Inject constructor(
    private val repository: InventoryRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(OutboundUiState())
    val uiState: StateFlow<OutboundUiState> = _uiState.asStateFlow()

    fun updateSearchQuery(query: String) {
        _uiState.value = _uiState.value.copy(searchQuery = query)
        if (query.isNotBlank()) {
            viewModelScope.launch {
                repository.searchItems(query).collect { results ->
                    _uiState.value = _uiState.value.copy(searchResults = results)
                }
            }
        } else {
            _uiState.value = _uiState.value.copy(searchResults = emptyList())
        }
    }

    fun selectItem(item: InventoryItem) {
        _uiState.value = _uiState.value.copy(
            selectedItem = item,
            outQuantity = "",
            searchQuery = item.materialName,
            searchResults = emptyList()
        )
    }

    fun clearSelection() {
        _uiState.value = _uiState.value.copy(
            selectedItem = null,
            outQuantity = "",
            searchQuery = "",
            searchResults = emptyList()
        )
    }

    fun updateOutQuantity(qty: String) {
        _uiState.value = _uiState.value.copy(outQuantity = qty)
    }

    fun setScanning(scanning: Boolean) {
        _uiState.value = _uiState.value.copy(isScanning = scanning)
        if (!scanning) {
            _uiState.value = _uiState.value.copy(searchResults = emptyList())
        }
    }

    fun onQrCodeScanned(code: String) {
        viewModelScope.launch {
            val item = repository.getItemByCode(code)
            if (item != null) {
                _uiState.value = _uiState.value.copy(
                    selectedItem = item,
                    searchQuery = item.materialName,
                    isScanning = false
                )
            } else {
                _uiState.value = _uiState.value.copy(
                    submitResult = "未找到库存编号: $code",
                    isScanning = false
                )
            }
        }
    }

    fun submitOutbound() {
        val state = _uiState.value
        val item = state.selectedItem ?: return
        val qty = state.outQuantity.toDoubleOrNull()

        if (qty == null || qty <= 0) {
            _uiState.value = state.copy(submitResult = "请输入有效的出库数量")
            return
        }
        if (qty > item.quantity) {
            _uiState.value = state.copy(submitResult = "出库数量($qty)超过库存(${item.quantity})")
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSubmitting = true)
            try {
                val updated = repository.outbound(item.id, qty)
                if (updated != null) {
                    _uiState.value = _uiState.value.copy(
                        selectedItem = updated,
                        isSubmitting = false,
                        submitResult = "出库成功: ${item.materialName} -${qty}, 剩余 ${updated.quantity}",
                        outQuantity = ""
                    )
                } else {
                    _uiState.value = _uiState.value.copy(
                        isSubmitting = false,
                        submitResult = "出库失败"
                    )
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isSubmitting = false,
                    submitResult = "出库失败: ${e.message}"
                )
            }
        }
    }

    fun clearSubmitResult() {
        _uiState.value = _uiState.value.copy(submitResult = null)
    }

    fun quickOutAll() {
        val item = _uiState.value.selectedItem ?: return
        _uiState.value = _uiState.value.copy(
            outQuantity = item.quantity.toInt().toString()
        )
    }
}
