package com.spoolsuperman.app.data.dao

import androidx.room.*
import com.spoolsuperman.app.data.entity.InventoryItem
import kotlinx.coroutines.flow.Flow

@Dao
interface InventoryDao {

    @Query("SELECT * FROM inventory ORDER BY last_operation_time DESC")
    fun getAllItems(): Flow<List<InventoryItem>>

    @Query("SELECT * FROM inventory WHERE id = :id")
    suspend fun getItemById(id: Long): InventoryItem?

    @Query("SELECT * FROM inventory WHERE code = :code LIMIT 1")
    suspend fun getItemByCode(code: String): InventoryItem?

    @Query("""
        SELECT * FROM inventory 
        WHERE material_name = :materialName 
        AND filament_color = :filamentColor 
        AND filament_material = :filamentMaterial 
        AND spec = :spec 
        LIMIT 1
    """)
    suspend fun findDuplicate(
        materialName: String,
        filamentColor: String,
        filamentMaterial: String,
        spec: String
    ): InventoryItem?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: InventoryItem): Long

    @Update
    suspend fun update(item: InventoryItem)

    @Delete
    suspend fun delete(item: InventoryItem)

    @Query("DELETE FROM inventory WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM inventory WHERE quantity <= threshold AND status != '待采购'")
    fun getLowStockItems(): Flow<List<InventoryItem>>

    @Query("SELECT SUM(quantity) FROM inventory")
    fun getTotalQuantity(): Flow<Double?>

    @Query("SELECT COUNT(*) FROM inventory")
    fun getTotalItemCount(): Flow<Int>

    @Query("SELECT COUNT(DISTINCT category) FROM inventory")
    fun getCategoryCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM inventory WHERE quantity <= threshold AND status != '待采购'")
    fun getLowStockCount(): Flow<Int>

    @Query("SELECT * FROM inventory WHERE category LIKE :category ORDER BY last_operation_time DESC")
    fun getItemsByCategory(category: String): Flow<List<InventoryItem>>

    @Query("SELECT * FROM inventory WHERE status = :status ORDER BY last_operation_time DESC")
    fun getItemsByStatus(status: String): Flow<List<InventoryItem>>

    @Query("SELECT * FROM inventory WHERE location = :location ORDER BY last_operation_time DESC")
    fun getItemsByLocation(location: String): Flow<List<InventoryItem>>

    @Query("SELECT * FROM inventory ORDER BY inbound_date DESC LIMIT :limit")
    fun getRecentItems(limit: Int = 10): Flow<List<InventoryItem>>

    @Query("SELECT DISTINCT category FROM inventory ORDER BY category")
    fun getAllCategories(): Flow<List<String>>

    @Query("SELECT DISTINCT location FROM inventory ORDER BY location")
    fun getAllLocations(): Flow<List<String>>

    @Query("SELECT DISTINCT brand FROM inventory ORDER BY brand")
    fun getAllBrands(): Flow<List<String>>

    @Query("SELECT DISTINCT filament_color FROM inventory ORDER BY filament_color")
    fun getAllColors(): Flow<List<String>>

    @Query("SELECT DISTINCT filament_material FROM inventory ORDER BY filament_material")
    fun getAllMaterials(): Flow<List<String>>

    @Query("SELECT * FROM inventory WHERE inbound_date BETWEEN :startTime AND :endTime ORDER BY inbound_date DESC")
    fun getItemsByDateRange(startTime: Long, endTime: Long): Flow<List<InventoryItem>>

    @Query("SELECT * FROM inventory WHERE id IN (:ids)")
    suspend fun getItemsByIds(ids: List<Long>): List<InventoryItem>

    @Query("SELECT MAX(code) FROM inventory WHERE code LIKE :codePrefix || '%'")
    suspend fun getMaxCodeByPrefix(codePrefix: String): String?

    @Query("""
        SELECT * FROM inventory 
        WHERE material_name LIKE '%' || :query || '%' 
        OR code LIKE '%' || :query || '%'
        OR brand LIKE '%' || :query || '%'
        OR category LIKE '%' || :query || '%'
        OR remark LIKE '%' || :query || '%'
        ORDER BY last_operation_time DESC
    """)
    fun searchItems(query: String): Flow<List<InventoryItem>>
}
