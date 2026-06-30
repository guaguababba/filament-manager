package com.spoolsuperman.app.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "inventory",
    indices = [
        Index(value = ["code"], unique = true),
        Index(value = ["material_name"]),
        Index(value = ["category"]),
        Index(value = ["status"]),
        Index(value = ["location"])
    ]
)
data class InventoryItem(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "code")
    val code: String = "",

    @ColumnInfo(name = "material_name")
    val materialName: String = "",

    @ColumnInfo(name = "category")
    val category: String = "",

    @ColumnInfo(name = "brand")
    val brand: String = "",

    @ColumnInfo(name = "filament_color")
    val filamentColor: String = "",

    @ColumnInfo(name = "filament_material")
    val filamentMaterial: String = "",

    @ColumnInfo(name = "filament_diameter")
    val filamentDiameter: String = "",

    @ColumnInfo(name = "spec")
    val spec: String = "",

    @ColumnInfo(name = "quantity")
    val quantity: Double = 0.0,

    @ColumnInfo(name = "status")
    val status: String = "充足",

    @ColumnInfo(name = "location")
    val location: String = "",

    @ColumnInfo(name = "threshold")
    val threshold: Double = 1.0,

    @ColumnInfo(name = "inbound_date")
    val inboundDate: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "last_operation_time")
    val lastOperationTime: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "remark")
    val remark: String = "",

    @ColumnInfo(name = "qrcode_base64")
    val qrcodeBase64: String = ""
)
