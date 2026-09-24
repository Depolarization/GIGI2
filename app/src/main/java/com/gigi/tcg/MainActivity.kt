package com.gigi.tcg

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.gigi.tcg.ui.navigation.GigiNavHost
import com.gigi.tcg.ui.theme.GigiTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            GigiTheme {
                GigiNavHost()
            }
        }
    }
}
