package com.example.englishreader.di

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import com.example.englishreader.EnglishReaderApp

/**
 * 在 ViewModel 的 `viewModelFactory { initializer { ... } }` 里获取 Application，
 * 从而拿到 [AppContainer]。
 */
fun CreationExtras.app(): EnglishReaderApp =
    this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as EnglishReaderApp

val CreationExtras.container: AppContainer
    get() = app().container
