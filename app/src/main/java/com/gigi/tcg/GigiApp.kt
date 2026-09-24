package com.gigi.tcg

import android.app.Application
import com.gigi.tcg.di.AppContainer

class GigiApp : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}
