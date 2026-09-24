// 米哈游接口客户端：合并 Web 版 client.ts（传输）+ mihoyo.ts（unwrap/700ms 重试）为一层，
// 内建设计文档 §2.2 三条策略：
// 1. retcode 集中判定（AUTH/RETRYABLE 集合见 ApiError.kt，页面禁止自行判定）；
// 2. Cookie 自动注入（凭据明文仅本类内部拦截器可见，业务层拿不到）；
// 3. 全局 1 秒详情节流（tag == TAG_DETAIL 的请求先过 domain.Throttle）。

package com.gigi.tcg.data.api

import com.gigi.tcg.domain.Throttle
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.serializer
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import kotlin.coroutines.resume

class MihoyoClient(
    http: OkHttpClient,
    private val json: Json,
    private val credentials: CredentialSource,
) {
    /** 构造期装好 Cookie 注入拦截器：凭据明文不出网络层，业务调用方不可见 */
    private val client: OkHttpClient = http.newBuilder()
        .addInterceptor { chain ->
            val builder = chain.request().newBuilder()
            val header = credentials.cookieHeader()
            if (!header.isNullOrEmpty()) {
                builder.header("Cookie", header)
            }
            chain.proceed(builder.build())
        }
        .build()

    /** 详情查询全局节流：对齐 Web 版 playerDetail 的 createThrottle(1000) 单实例语义（public-inline 可见 → @PublishedApi internal） */
    @PublishedApi
    internal val detailThrottle = Throttle(THROTTLE_DELAY_MS)

    /**
     * GET 米哈游接口并返回解析后的 data。
     * retcode == 0 且 data != null → data；限流/繁忙码等待 700ms 自动重试一次（仅一次）；
     * 其余（含 data 缺失）→ 抛 [ApiError]；网络/解析失败 → kind=network。
     */
    suspend inline fun <reified T> get(
        url: String,
        serializer: KSerializer<T> = serializer(),
        tag: String = "",
    ): T {
        // 节流只拦首次请求：命中限流码后的自动重试不重复计时
        if (tag == TAG_DETAIL && !detailThrottle()) {
            throw ApiError(API_ERROR_KIND_THROTTLED, "请求过于频繁，请稍后重试")
        }
        val first = requestEnvelope(url, serializer)
        val effective =
            if (RETRYABLE_RETCODES.contains(first.first)) {
                delay(RETRY_DELAY_MS)
                requestEnvelope(url, serializer)
            } else {
                first
            }
        val (retcode, message, data) = effective
        if (retcode != 0 || data == null) {
            throw ApiError(
                API_ERROR_KIND_RETCODE,
                message?.takeIf { it.isNotEmpty() } ?: "接口返回 retcode=$retcode",
                retcode,
            )
        }
        return data
    }

    @PublishedApi
    internal suspend fun <T> requestEnvelope(
        url: String,
        serializer: KSerializer<T>,
    ): Triple<Int, String?, T?> {
        val body = execute(url)
        val root = try {
            json.parseToJsonElement(body)
        } catch (e: SerializationException) {
            throw ApiError(API_ERROR_KIND_NETWORK, "响应不是有效的 JSON")
        }
        if (root !is JsonObject) {
            throw ApiError(API_ERROR_KIND_NETWORK, "响应不是有效的 JSON")
        }
        val retcode = (root["retcode"] as? JsonPrimitive)?.intOrNull ?: 0
        val message = (root["message"] as? JsonPrimitive)?.contentOrNull
        // data 解码失败与缺失同样记 null → 由 retcode 判定分支抛错，不误报 network
        val data: T? = root["data"]?.let { element ->
            if (element is JsonPrimitive && element.content == "null" && element.isString.not()) {
                null
            } else {
                try {
                    json.decodeFromJsonElement(serializer, element)
                } catch (e: SerializationException) {
                    null
                }
            }
        }
        return Triple(retcode, message, data)
    }

    /**
     * OkHttp 异步调用挂起化（Dispatchers.IO）。
     * HTTP 非 2xx 与 IO 失败均归 network（米哈游业务失败以 retcode 表达，HTTP 通常仍为 200）。
     */
    private suspend fun execute(url: String): String =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(url.toHttpUrl())
                .get()
                .build()
            val response = suspendingCall(request)
            response.use { resp ->
                if (!resp.isSuccessful) {
                    throw ApiError(API_ERROR_KIND_NETWORK, "网络请求失败（HTTP ${resp.code}）")
                }
                try {
                    resp.body?.string() ?: throw ApiError(API_ERROR_KIND_NETWORK, "响应不是有效的 JSON")
                } catch (e: IOException) {
                    throw ApiError(API_ERROR_KIND_NETWORK, "响应不是有效的 JSON")
                }
            }
        }

    private suspend fun suspendingCall(request: Request): Response =
        suspendCancellableCoroutine { continuation ->
            client.newCall(request).enqueue(
                object : Callback {
                    override fun onResponse(call: Call, response: Response) {
                        continuation.resume(response)
                    }

                    override fun onFailure(call: Call, e: IOException) {
                        continuation.resumeWith(
                            Result.failure(ApiError(API_ERROR_KIND_NETWORK, "网络请求失败"))
                        )
                    }
                }
            )
        }

    companion object {
        /** 触发详情节流的 tag：卡牌卡面详情（cardDetailUrl） */
        const val TAG_DETAIL: String = "detail"

        /** 限流/繁忙码的自动重试间隔：米游社保真限流窗口很短，等待后重发即可成功 */
        const val RETRY_DELAY_MS: Long = 700L

        const val THROTTLE_DELAY_MS: Long = 1000L
    }
}
