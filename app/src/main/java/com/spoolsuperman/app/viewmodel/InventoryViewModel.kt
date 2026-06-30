package com.spoolsuperman.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spoolsuperman.app.data.entity.InventoryItem
import com.spoolsuperman.app.data.repository.InventoryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class InventoryUiState(
    val items: List<InventoryItem> = emptyList(),
    val filteredItems: List<InventoryItem> = emptyList(),
    val selectedIds: Set<Long> = emptySet(),
    val isAllSelected: Boolean = false,
    val filterCategory: String = "",
    val filterStatus: String = "",
    val filterLocation: String = "",
    val searchQuery: String = "",
    val categories: List<String> = emptyList(),
    val locations: List<String> = emptyList(),
    val sortBy: SortBy = SortBy.TIME_DESC,
    val isLoading: Boolean = true,
    val exportMessage: String? = null
)

enum class SortBy { TIME_DESC, TIME_ASC, NAME_ASC, QUANTITY_DESC, QUANTITY_ASC }

@HiltViewModel
class InventoryViewModel @Inject constructor(
    private val repository: InventoryRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(InventoryUiState())
    val uiState: StateFlow<InventoryUiState> = _uiState.asStateFlow()

    private val allItems = MutableStateFlow<List<InventoryItem>>(emptyList())

    init {
        viewModelScope.launch {
            repository.getAllItems().collect { items ->
                allItems.value = items
                applyFilters()
            }
        }
        viewModelScope.launch {
            repository.getAllCategories().collect {
                _uiState.value = _uiState.value.copy(categories = it)
            }
        }
        viewModelScope.launch {
            repository.getAllLocations().collect {
                _uiState.value = _uiState.value.copy(locations = it)
            }
        }
    }

    private fun applyFilters() {
        var items = allItems.value
        val state = _uiState.value

        if (state.searchQuery.isNotBlank()) {
            val q = state.searchQuery.lowercase()
            items = items.filter {
                it.materialName.lowercase().contains(q) ||
                it.code.lowercase().contains(q) ||
                it.brand.lowercase().contains(q) ||
                it.category.lowercase().contains(q)
            }
        }
        if (state.filterCategory.isNotBlank()) {
            items = items.filter { it.category == state.filterCategory }
        }
        if (state.filterStatus.isNotBlank()) {
            items = items.filter { it.status == state.filterStatus }
        }
        if (state.filterLocation.isNotBlank()) {
            items = items.filter { it.location == state.filterLocation }
        }

        items = when (state.sortBy) {
            SortBy.TIME_DESC -> items.sortedByDescending { it.lastOperationTime }
            SortBy.TIME_ASC -> items.sortedBy { it.lastOperationTime }
            SortBy.NAME_ASC -> items.sortedBy { it.materialName }
            SortBy.QUANTITY_DESC -> items.sortedByDescending { it.quantity }
            SortBy.QUANTITY_ASC -> items.sortedBy { it.quantity }
        }

        _uiState.value = _uiState.value.copy(
            filteredItems = items,
            isLoading = false
        )
    }

    fun updateSearchQuery(query: String) {
        _uiState.value = _uiState.value.copy(searchQuery = query)
        applyFilters()
    }

    fun setFilterCategory(category: String) {
        _uiState.value = _uiState.value.copy(
            filterCategory = if (_uiState.value.filterCategory == category) "" else category
        )
        applyFilters()
    }

    fun setFilterStatus(status: String) {
        _uiState.value = _uiState.value.copy(
            filterStatus = if (_uiState.value.filterStatus == status) "" else status
        )
        applyFilters()
    }

    fun setFilterLocation(location: String) {
        _uiState.value = _uiState.value.copy(
            filterLocation = if (_uiState.value.filterLocation == location) "" else location
        )
        applyFilters()
    }

    fun setSortBy(sortBy: SortBy) {
        _uiState.value = _uiState.value.copy(sortBy = sortBy)
        applyFilters()
    }

    fun toggleItemSelection(id: Long) {
        val current = _uiState.value.selectedIds.toMutableSet()
        if (current.contains(id)) {
            current.remove(id)
        } else {
            current.add(id)
        }
        _uiState.value = _uiState.value.copy(
            selectedIds = current,
            isAllSelected = current.size == _uiState.value.filteredItems.size
        )
    }

    fun toggleSelectAll() {
        val state = _uiState.value
        if (state.isAllSelected) {
            _uiState.value = state.copy(selectedIds = emptySet(), isAllSelected = false)
        } else {
            val allIds = state.filteredItems.map { it.id }.toSet()
            _uiState.value = state.copy(selectedIds = allIds, isAllSelected = true)
        }
    }

    fun clearSelection() {
        _uiState.value = _uiState.value.copy(selectedIds = emptySet(), isAllSelected = false)
    }

    fun deleteSelected() {
        viewModelScope.launch {
            val ids = _uiState.value.selectedIds
            ids.forEach { id ->
                try {
                    repository.deleteById(id)
                } catch (_: Exception) {}
            }
            _uiState.value = _uiState.value.copy(selectedIds = emptySet(), isAllSelected = false)
        }
    }

    fun exportSelectedToExcel(outputPath: String) {
        viewModelScope.launch {
            try {
                val ids = _uiState.value.selectedIds
                val items = if (ids.isEmpty()) {
                    allItems.value
                } else {
                    repository.getItemsByIds(ids.toList())
                }
                withContext(Dispatchers.IO) {
                    exportToExcel(items, outputPath)
                }
                _uiState.value = _uiState.value.copy(exportMessage = "导出成功: $outputPath")
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(exportMessage = "导出失败: ${e.message}")
            }
        }
    }

    fun clearExportMessage() {
        _uiState.value = _uiState.value.copy(exportMessage = null)
    }

    private fun exportToExcel(items: List<InventoryItem>, outputPath: String) {
        val workbook = org.apache.poi.xssf.usermodel.XSSFWorkbook()
        val sheet = workbook.createSheet("库存清单")

        val headerStyle = workbook.createCellStyle().apply {
            fillForegroundColor = org.apache.poi.xssf.usermodel.IndexedColors.GREY_25_PERCENT.index
            fillPattern = org.apache.poi.ss.usermodel.FillPatternType.SOLID_FOREGROUND
            val font = workbook.createFont()
            font.bold = true
            setFont(font)
        }

        val headers = arrayOf(
            "库存编号", "材料名称", "品类", "品牌", "颜色", "材质",
            "直径", "规格", "数量", "状态", "存放位置", "预警阈值",
            "入库日期", "最近操作", "备注"
        )
        val headerRow = sheet.createRow(0)
        headers.forEachIndexed { i, h ->
            val cell = headerRow.createCell(i)
            cell.setCellValue(h)
            cell.cellStyle = headerStyle
        }

        items.forEachIndexed { rowIdx, item ->
            val row = sheet.createRow(rowIdx + 1)
            row.createCell(0).setCellValue(item.code)
            row.createCell(1).setCellValue(item.materialName)
            row.createCell(2).setCellValue(item.category)
            row.createCell(3).setCellValue(item.brand)
            row.createCell(4).setCellValue(item.filamentColor)
            row.createCell(5).setCellValue(item.filamentMaterial)
            row.createCell(6).setCellValue(item.filamentDiameter)
            row.createCell(7).setCellValue(item.spec)
            row.createCell(8).setCellValue(item.quantity)
            row.createCell(9).setCellValue(item.status)
            row.createCell(10).setCellValue(item.location)
            row.createCell(11).setCellValue(item.threshold)
            row.createCell(12).setCellValue(
                java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
                    .format(java.util.Date(item.inboundDate))
            )
            row.createCell(13).setCellValue(
                java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
                    .format(java.util.Date(item.lastOperationTime))
            )
            row.createCell(14).setCellValue(item.remark)
        }

        for (i in 0 until headers.size) {
            sheet.autoSizeColumn(i)
        }

        val fileOut = java.io.FileOutputStream(outputPath)
        workbook.write(fileOut)
        fileOut.close()
        workbook.close()
    }
}
