package com.gigi.tcg

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.GifDecoder
import com.gigi.tcg.di.AppContainer
import com.gigi.tcg.i18n.LocaleStrings

/**
 * V10-D：卡面图鉴的卡面含 GIF 动图，必须给 ImageLoader 显式装 GifDecoder。
 *
 * coil 2.x **不走 ServiceLoader**（1.x 才有）——coil-gif artifact 只提供
 * `coil.decode.GifDecoder.Factory`，不会被自动发现。不显式注册的话，
 * 遇到 GIF 时没有任何 DecoderFactory 能处理，AsyncImagePainter 直接走
 * State.Error，卡面显示为加载失败占位图。
 *
 * 实现 ImageLoaderFactory 后，全工程（包括 Compose 侧的
 * rememberAsyncImagePainter）都会拿到这个带 GIF 解码器的实例。
 */
class GigiApp : Application(), ImageLoaderFactory {
    val container: AppContainer by lazy { AppContainer(this) }

    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .components { add(GifDecoder.Factory()) }
            .build()

    override fun onCreate() {
        super.onCreate()
        // 数据层/ViewModel 取三语文案的桥（只存 applicationContext）
        LocaleStrings.attach(this)
    }
}
