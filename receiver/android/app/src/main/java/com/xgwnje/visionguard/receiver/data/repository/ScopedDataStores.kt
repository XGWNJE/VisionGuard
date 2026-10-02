package com.xgwnje.visionguard.receiver.data.repository

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import com.xgwnje.visionguard.account.AccountStore
import java.util.concurrent.ConcurrentHashMap

internal object ScopedDataStores {
    private val stores = ConcurrentHashMap<String, androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>>()
    fun get(context: Context, name: String) = stores.getOrPut(name + "-" + AccountStore.cacheKey(context)) {
        val key = name + "-" + AccountStore.cacheKey(context)
        PreferenceDataStoreFactory.create(produceFile = { context.applicationContext.preferencesDataStoreFile(key) })
    }
}
