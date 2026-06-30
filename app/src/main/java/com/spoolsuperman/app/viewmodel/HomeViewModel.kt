package com.spoolsuperman.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spoolsuperman.app.data.entity.InventoryItem
import com.spoolsuperman.app.data.repository.InventoryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HomeUiState(
    val totalItems: Int = 0,
    val totalQuantity: Double = 0.0,
    val categoryCount: Int = 0,
    val lowStockCount: Int = 0,
    val recentItems: List<InventoryItem> = emptyList(),
    val lowStockItems: List<InventoryItem> = emptyList(),
    val isLoading: Boolean = true
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repository: InventoryRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        loadHomeData()
    }

    @Suppress("UNCHECKED_CAST")
    private fun loadHomeData() {
        viewModelScope.launch {
            combine(
                listOf<Flow<Any?>>(
                    repository.getTotalItemCount(),
                    repository.getTotalQuantity(),
                    repository.getCategoryCount(),
                    repository.getLowStockCount(),
                    repository.getRecentItems(10),
                    repository.getLowStockItems()
                )
            ) { values ->
                _uiState.value = HomeUiState(
                    totalItems = values[0] as Int,
                    totalQuantity = (values[1] as? Double) ?: 0.0,
                    categoryCount = values[2] as Int,
                    lowStockCount = values[3] as Int,
                    recentItems = values[4] as List<InventoryItem>,
                    lowStockItems = values[5] as List<InventoryItem>,
                    isLoading = false
                )
            }.collect()
        }
    }

    fun refresh() {
        _uiState.value = _uiState.value.copy(isLoading = true)
        loadHomeData()
    }
}
