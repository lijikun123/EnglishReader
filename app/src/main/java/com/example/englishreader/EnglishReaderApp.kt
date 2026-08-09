package com.example.englishreader

import android.app.Application
import com.example.englishreader.di.AppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class EnglishReaderApp : Application() {

    /** 应用级协程作用域，用于数据库初始化播种等长生命周期任务。 */
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this, applicationScope)
    }
}
