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

    private fun loadHomeData() {
        viewModelScope.launch {
            combine(
                repository.getTotalItemCount(),
                repository.getTotalQuantity(),
                repository.getCategoryCount(),
                repository.getLowStockCount(),
                repository.getRecentItems(10),
                repository.getLowStockItems()
            ) { items, qty, cats, lowCount, recent, lowItems ->
                _uiState.value = HomeUiState(
                    totalItems = items,
                    totalQuantity = qty ?: 0.0,
                    categoryCount = cats,
                    lowStockCount = lowCount,
                    recentItems = recent,
                    lowStockItems = lowItems,
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
