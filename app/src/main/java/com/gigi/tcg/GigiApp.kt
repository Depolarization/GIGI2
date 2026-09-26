package com.gigi.tcg

import android.app.Application
import com.gigi.tcg.di.AppContainer
import com.gigi.tcg.i18n.LocaleStrings

class GigiApp : Application() {
    val container: AppContainer by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        // 数据层/ViewModel 取三语文案的桥（只存 applicationContext）
        LocaleStrings.attach(this)
    }
}
