package com.example.englishreader

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import com.example.englishreader.ui.AppRoot

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            // WindowSizeClass 用于手机/平板自适应布局。
            val windowSizeClass = calculateWindowSizeClass(this)
            AppRoot(windowSizeClass = windowSizeClass)
        }
    }
}
