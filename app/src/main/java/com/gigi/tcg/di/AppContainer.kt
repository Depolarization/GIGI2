// 手写依赖容器（禁用 Hilt/Dagger）：GigiApp 持有单实例，页面经 ViewModel 取用 repository。

package com.gigi.tcg.di

import android.content.Context
import com.gigi.tcg.data.api.CredentialSource
import com.gigi.tcg.data.api.MihoyoClient
import com.gigi.tcg.data.auth.CredentialStore
import com.gigi.tcg.data.cache.WikiDiskCache
import com.gigi.tcg.data.repo.GigiApiTransport
import com.gigi.tcg.data.repo.GigiRepository
import com.gigi.tcg.data.repo.MihoyoClientEnvelopeTransport
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

class AppContainer(private val appContext: Context) {
    val json: Json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
        encodeDefaults = false
    }

    val credentialStore: CredentialStore by lazy { CredentialStore(appContext) }

    val credentialSource: CredentialSource get() = credentialStore

    val wikiDiskCache: WikiDiskCache by lazy { WikiDiskCache(appContext) }

    private val mihoyoClient: MihoyoClient by lazy {
        val http = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .build()
        MihoyoClient(http, json, credentialStore)
    }

    private val apiTransport: GigiApiTransport by lazy { MihoyoClientEnvelopeTransport(mihoyoClient) }

    val repository: GigiRepository by lazy {
        GigiRepository(
            transport = apiTransport,
            wikiDiskCache = wikiDiskCache,
            json = json,
        )
    }
}
