package com.gigi.tcg

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gigi.tcg.ui.login.AppGate
import com.gigi.tcg.ui.login.GateViewModel
import com.gigi.tcg.ui.theme.GigiTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val app = application as GigiApp
        setContent {
            GigiTheme {
                val gateViewModel: GateViewModel = viewModel(
                    factory = object : ViewModelProvider.Factory {
                        @Suppress("UNCHECKED_CAST")
                        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                            GateViewModel(app) as T
                    },
                )
                AppGate(gateViewModel)
            }
        }
    }
}
