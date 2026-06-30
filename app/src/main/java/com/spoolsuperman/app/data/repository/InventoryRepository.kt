package com.spoolsuperman.app.data.repository

import com.spoolsuperman.app.data.dao.InventoryDao
import com.spoolsuperman.app.data.entity.InventoryItem
import kotlinx.coroutines.flow.Flow
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class InventoryRepository @Inject constructor(
    private val dao: InventoryDao
) {
    fun getAllItems(): Flow<List<InventoryItem>> = dao.getAllItems()

    suspend fun getItemById(id: Long): InventoryItem? = dao.getItemById(id)

    suspend fun getItemByCode(code: String): InventoryItem? = dao.getItemByCode(code)

    fun getLowStockItems(): Flow<List<InventoryItem>> = dao.getLowStockItems()

    fun getTotalQuantity(): Flow<Double?> = dao.getTotalQuantity()

    fun getTotalItemCount(): Flow<Int> = dao.getTotalItemCount()

    fun getCategoryCount(): Flow<Int> = dao.getCategoryCount()

    fun getLowStockCount(): Flow<Int> = dao.getLowStockCount()

    fun getItemsByCategory(category: String): Flow<List<InventoryItem>> =
        dao.getItemsByCategory(category)

    fun getItemsByStatus(status: String): Flow<List<InventoryItem>> =
        dao.getItemsByStatus(status)

    fun getItemsByLocation(location: String): Flow<List<InventoryItem>> =
        dao.getItemsByLocation(location)

    fun getRecentItems(limit: Int = 10): Flow<List<InventoryItem>> =
        dao.getRecentItems(limit)

    fun getAllCategories(): Flow<List<String>> = dao.getAllCategories()

    fun getAllLocations(): Flow<List<String>> = dao.getAllLocations()

    fun getAllBrands(): Flow<List<String>> = dao.getAllBrands()

    fun getAllColors(): Flow<List<String>> = dao.getAllColors()

    fun getAllMaterials(): Flow<List<String>> = dao.getAllMaterials()

    fun getItemsByDateRange(startTime: Long, endTime: Long): Flow<List<InventoryItem>> =
        dao.getItemsByDateRange(startTime, endTime)

    suspend fun getItemsByIds(ids: List<Long>): List<InventoryItem> = dao.getItemsByIds(ids)

    fun searchItems(query: String): Flow<List<InventoryItem>> = dao.searchItems(query)

    suspend fun generateInboundCode(category: String): String {
        val sdf = SimpleDateFormat("yyMMdd", Locale.getDefault())
        val datePart = sdf.format(Date())
        val prefix = getCategoryAbbreviation(category)
        val codePrefix = "$prefix-$datePart-"
        val maxCode = dao.getMaxCodeByPrefix(codePrefix)
        val sequence = if (maxCode != null) {
            maxCode.substringAfterLast("-").toIntOrNull()?.plus(1) ?: 1
        } else {
            1
        }
        return String.format("%s-%s-%03d", prefix, datePart, sequence)
    }

    suspend fun inbound(item: InventoryItem): Long {
        val duplicate = dao.findDuplicate(
            item.materialName,
            item.filamentColor,
            item.filamentMaterial,
            item.spec
        )
        return if (duplicate != null) {
            val updated = duplicate.copy(
                quantity = duplicate.quantity + item.quantity,
                lastOperationTime = System.currentTimeMillis(),
                inboundDate = System.currentTimeMillis(),
                remark = if (item.remark.isNotBlank() && duplicate.remark.isNotBlank())
                    "${duplicate.remark}; ${item.remark}"
                else if (item.remark.isNotBlank()) item.remark
                else duplicate.remark
            )
            dao.update(updated)
            duplicate.id
        } else {
            dao.insert(item)
        }
    }

    suspend fun outbound(id: Long, outQuantity: Double): InventoryItem? {
        val item = dao.getItemById(id) ?: return null
        val remaining = item.quantity - outQuantity
        if (remaining < 0) return null
        val updated = item.copy(
            quantity = remaining,
            status = when {
                remaining <= 0 -> "缺货"
                remaining <= item.threshold -> "不足"
                else -> "充足"
            },
            lastOperationTime = System.currentTimeMillis()
        )
        dao.update(updated)
        return updated
    }

    suspend fun updateItem(item: InventoryItem) = dao.update(item)

    suspend fun deleteItem(item: InventoryItem) = dao.delete(item)

    suspend fun deleteById(id: Long) = dao.deleteById(id)

    private fun getCategoryAbbreviation(category: String): String {
        return when {
            category.contains("耗材") -> "HC"
            category.contains("配件") -> "PJ"
            category.contains("工具") -> "GJ"
            category.contains("原料") -> "YL"
            category.contains("成品") -> "CP"
            category.contains("半成品") -> "BCP"
            else -> {
                if (category.length >= 2) category.substring(0, 2).uppercase()
                else category.uppercase()
            }
        }
    }
}
