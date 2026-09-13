package com.enajid.apkbuilder

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.enajid.apkbuilder.ui.ApkBuilderRoot
import com.enajid.apkbuilder.ui.theme.ApkBuilderTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ApkBuilderTheme {
                ApkBuilderRoot()
            }
        }
    }
}
