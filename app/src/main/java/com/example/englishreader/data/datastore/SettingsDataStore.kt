package com.example.englishreader.data.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore

/** App 级别的 Preferences DataStore（阅读设置 + 词典/AI 配置）。 */
val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")
