package com.spoolsuperman.app.viewmodel

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spoolsuperman.app.data.entity.InventoryItem
import com.spoolsuperman.app.data.repository.InventoryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: InventoryRepository
) : ViewModel() {

    private val _categories = MutableStateFlow<List<String>>(emptyList())
    val categories: StateFlow<List<String>> = _categories.asStateFlow()

    private val _locations = MutableStateFlow<List<String>>(emptyList())
    val locations: StateFlow<List<String>> = _locations.asStateFlow()

    private val _newCategory = MutableStateFlow("")
    val newCategory: StateFlow<String> = _newCategory.asStateFlow()

    private val _newLocation = MutableStateFlow("")
    val newLocation: StateFlow<String> = _newLocation.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    init {
        viewModelScope.launch {
            repository.getAllCategories().collect { _categories.value = it }
        }
        viewModelScope.launch {
            repository.getAllLocations().collect { _locations.value = it }
        }
    }

    fun updateNewCategory(value: String) { _newCategory.value = value }
    fun updateNewLocation(value: String) { _newLocation.value = value }

    fun clearMessage() { _message.value = null }

    companion object {
        val API_URL_KEY = stringPreferencesKey("ai_api_url")
        val API_KEY_KEY = stringPreferencesKey("ai_api_key")
        val DEFAULT_API_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1"
    }

    fun getApiUrl(context: Context): Flow<String> {
        return context.dataStore.data.map { prefs ->
            prefs[API_URL_KEY] ?: DEFAULT_API_URL
        }
    }

    fun getApiKey(context: Context): Flow<String> {
        return context.dataStore.data.map { prefs ->
            prefs[API_KEY_KEY] ?: ""
        }
    }

    fun saveApiUrl(context: Context, url: String) {
        viewModelScope.launch {
            context.dataStore.edit { prefs ->
                prefs[API_URL_KEY] = url
            }
            _message.value = "API 地址已保存"
        }
    }

    fun saveApiKey(context: Context, key: String) {
        viewModelScope.launch {
            context.dataStore.edit { prefs ->
                prefs[API_KEY_KEY] = key
            }
            _message.value = "API Key 已保存"
        }
    }

    fun updateAllItemsCategory(context: Context, oldCategory: String, newCategory: String) {
        viewModelScope.launch {
            try {
                repository.getItemsByCategory(oldCategory).first().forEach { item ->
                    repository.updateItem(item.copy(category = newCategory))
                }
                _message.value = "已更新品类名称"
            } catch (e: Exception) {
                _message.value = "更新失败: ${e.message}"
            }
        }
    }

    fun updateAllItemsLocation(context: Context, oldLocation: String, newLocation: String) {
        viewModelScope.launch {
            try {
                repository.getItemsByLocation(oldLocation).first().forEach { item ->
                    repository.updateItem(item.copy(location = newLocation))
                }
                _message.value = "已更新存放位置"
            } catch (e: Exception) {
                _message.value = "更新失败: ${e.message}"
            }
        }
    }
}
