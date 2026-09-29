// 米哈游接口客户端：合并 Web 版 client.ts（传输）+ mihoyo.ts（unwrap/退避重试）为一层，
// 内建设计文档 §2.2 三条策略：
// 1. retcode 集中判定（AUTH/RETRYABLE/CAPTCHA 集合见 ApiError.kt，页面禁止自行判定）；
// 2. Cookie 自动注入（凭据明文仅本类内部拦截器可见，业务层拿不到）；
// 3. 全局 1 秒详情节流（tag == TAG_DETAIL 的请求先过 domain.Throttle）。

package com.gigi.tcg.data.api

import com.gigi.tcg.domain.Throttle
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.random.Random
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

/**
 * 退避重试的「该等多久」纯函数（顶层 internal：JVM 单测无协程依赖直接断言）。
 * attempt 从 1 起（第 1 次重试 = 基准 700ms，其后指数翻倍），jitterMs 为本次叠加的随机抖动。
 */
internal fun retryDelayMsFor(attempt: Int, jitterMs: Long): Long =
    MihoyoClient.RETRY_BASE_DELAY_MS * (1L shl (attempt - 1).coerceIn(0, 16)) + jitterMs

/**
 * 是否值得再打一次（业务层退避重试的唯一判据）。
 * - kind=network（连接失败/超时/IO/解析失败）→ 可重试：瞬态网络抖动能自愈；
 * - kind=throttled（本地详情节流拒绝）→ 可重试：等一个窗口即可；
 * - 鉴权码 -100/-101 → 不可重试：应当走续命，不是重发；
 * - CAPTCHA（1034）→ 不可重试：账号/凭据级风控，重试无意义且会加重风控（见 ApiError.kt）；
 * - 其余沿用 [RETRYABLE_RETCODES]（限流/繁忙码）。
 */
internal fun isRetryableError(error: ApiError): Boolean =
    when {
        error.kind == API_ERROR_KIND_RETCODE &&
            (AUTH_FAILED_RETCODES.contains(error.retcode ?: 0) ||
                CAPTCHA_REQUIRED_RETCODES.contains(error.retcode ?: 0)) -> false
        error.kind == API_ERROR_KIND_NETWORK ||
            error.kind == API_ERROR_KIND_THROTTLED ||
            error.cause is IOException -> true
        error.kind == API_ERROR_KIND_RETCODE && RETRYABLE_RETCODES.contains(error.retcode ?: 0) -> true
        else -> false
    }


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
     * retcode == 0 且 data != null → data；命中可重试错误（限流/繁忙码、network、throttled）
     * 按 [retryDelayMsFor] 指数退避 + 抖动最多重试 [MAX_RETRY_ATTEMPTS] 次（流量防抖）；
     * 鉴权码 / CAPTCHA / 其余业务码 → 立即可判错误，不再重发；
     * 重试耗尽后限流类上抛 kind=throttled（文案「请求过于频繁」语义），CAPTCHA 透传 retcode
     * 供 i18n 出专属文案；网络/解析失败 → kind=network。
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
        val (retcode, message, data) = requestWithBackoff(url, serializer)
        if (retcode != 0 || data == null) {
            val code = retcode ?: 0
            throw ApiError(
                API_ERROR_KIND_RETCODE,
                message?.takeIf { it.isNotEmpty() }
                    ?: if (CAPTCHA_REQUIRED_RETCODES.contains(code)) {
                        CAPTCHA_REQUIRED_MESSAGE
                    } else {
                        "接口返回 retcode=$code"
                    },
                code,
            )
        }
        return data
    }

    /**
     * 退避重试循环：首次请求 + 最多 [MAX_RETRY_ATTEMPTS] 次重试（共 [MAX_RETRY_ATTEMPTS] + 1 发）。
     * 三种「再试」的入口都归一到 [isRetryableError]：
     * ① 响应信封的可重试 retcode；② 传输/解析抛出的 kind=network；③ 本地节流 kind=throttled。
     * 不可重试错误（鉴权 / CAPTCHA / 其它业务码）当轮即出循环，由调用方按 retcode 抛错。
     */
    @PublishedApi
    internal suspend fun <T> requestWithBackoff(
        url: String,
        serializer: KSerializer<T>,
    ): Triple<Int, String?, T?> {
        var lastPair: Triple<Int, String?, T?>? = null
        var lastTransientError: ApiError? = null
        repeat(MAX_RETRY_ATTEMPTS + 1) { index ->
            val outcome: Result<Triple<Int, String?, T?>> =
                try {
                    Result.success(requestEnvelope(url, serializer))
                } catch (e: ApiError) {
                    Result.failure(e)
                }
            val failure = outcome.exceptionOrNull() as ApiError?
            if (failure == null) {
                lastPair = outcome.getOrNull()
                lastTransientError = null
            } else if (isRetryableError(failure)) {
                // 只保留「还能救」的错误做兜底；鉴权 / CAPTCHA / 其它业务码当轮即出循环
                lastTransientError = failure
            } else {
                lastPair = null
            }
            val retryableNow =
                if (failure != null) isRetryableError(failure) else RETRYABLE_RETCODES.contains(lastPair!!.first)
            if (!retryableNow || index == MAX_RETRY_ATTEMPTS) return@repeat
            delay(retryDelayMsFor(index + 1, Random.nextLong(0L, RETRY_JITTER_SPAN_MS + 1)))
        }
        val pair = lastPair
        if (pair != null) {
            val code = pair.first
            // 可重试码耗尽 → 折算 kind=throttled（「请求过于频繁」语义），并保留 retcode 供上层判据
            if (RETRYABLE_RETCODES.contains(code)) {
                throw ApiError(API_ERROR_KIND_THROTTLED, "请求过于频繁，请稍后重试", code)
            }
            return pair
        }
        throw lastTransientError!!
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
            throw ApiError(API_ERROR_KIND_NETWORK, "响应不是有效的 JSON", cause = e)
        }
        if (root !is JsonObject) {
            throw ApiError(API_ERROR_KIND_NETWORK, "响应不是有效的 JSON")
        }
        val retcode = (root["retcode"] as? JsonPrimitive)?.intOrNull ?: 0
        val message = (root["message"] as? JsonPrimitive)?.contentOrNull
        // 🔴 data 解码失败 = 收到了 JSON 但结构与契约不符：归因 kind=network 并带上原异常，
        // 不再记 null 让上层按 retcode 报「服务出错了」（误导用户与日志）。data 缺失仍走 retcode 分支。
        val data: T? = root["data"]?.let { element ->
            if (element is JsonPrimitive && element.content == "null" && element.isString.not()) {
                null
            } else {
                try {
                    json.decodeFromJsonElement(serializer, element)
                } catch (e: SerializationException) {
                    throw ApiError(API_ERROR_KIND_NETWORK, "响应数据解析失败", cause = e)
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
                    throw ApiError(API_ERROR_KIND_NETWORK, "响应不是有效的 JSON", cause = e)
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
                            Result.failure(ApiError(API_ERROR_KIND_NETWORK, "网络请求失败", cause = e))
                        )
                    }
                }
            )
        }

    companion object {
        /** 触发详情节流的 tag：卡牌卡面详情（cardDetailUrl） */
        const val TAG_DETAIL: String = "detail"

        /**
         * @deprecated 一次性重试时代的固定间隔。退避重试请用 [RETRY_BASE_DELAY_MS] +
         * [retryDelayMsFor]；保留常量（勿删）——外部仍有引用，如 GigiRepository 构造参数默认值。
         */
        @Deprecated("改用 RETRY_BASE_DELAY_MS + retryDelayMsFor(attempt, jitter)：一次性重试已升级为指数退避")
        const val RETRY_DELAY_MS: Long = 700L

        /** 重试间隔之上的随机抖动上界（0..300ms）：避免多个并发请求同刻重发再次互撞 */
        const val RETRY_JITTER_SPAN_MS: Long = 300L

        // 退避重试总预算（流量防抖，非无限重试）：最坏总等待 ≈ (700+300) + (1400+300) + (2800+300) ≈ 5.8s；
        // 次数封顶 [MAX_RETRY_ATTEMPTS]，耗尽即上抛错误，由上层显示失败占位图、交用户手动刷新。
        /** 指数退避基准：第 1 次重试等 700ms，其后按 [RETRY_BACKOFF_FACTOR] 逐次翻倍 */
        const val RETRY_BASE_DELAY_MS: Long = 700L

        /** 退避倍率：第 n 次重试的基准等待 = 基准 × factor^(n-1) */
        const val RETRY_BACKOFF_FACTOR: Long = 2L

        /** 最多重试次数（不含首次请求，共 MAX_RETRY_ATTEMPTS + 1 发） */
        const val MAX_RETRY_ATTEMPTS: Int = 3

        const val THROTTLE_DELAY_MS: Long = 1000L

        /** 1034 兜底文案（服务端 message 为空串时用，语义对齐 i18n error_captcha_required） */
        const val CAPTCHA_REQUIRED_MESSAGE: String = "米游社要求完成人机验证，请在米游社 App 中验证后重试"
    }
}
