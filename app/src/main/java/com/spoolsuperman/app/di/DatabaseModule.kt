package com.spoolsuperman.app.di

import android.content.Context
import androidx.room.Room
import com.spoolsuperman.app.data.dao.InventoryDao
import com.spoolsuperman.app.data.db.AppDatabase
import com.spoolsuperman.app.data.repository.InventoryRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase {
        return AppDatabase.getDatabase(context)
    }

    @Provides
    @Singleton
    fun provideInventoryDao(database: AppDatabase): InventoryDao {
        return database.inventoryDao()
    }

    @Provides
    @Singleton
    fun provideInventoryRepository(dao: InventoryDao): InventoryRepository {
        return InventoryRepository(dao)
    }
}
