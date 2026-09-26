// GitHub 请求层（V9-D）：读取公开仓库的静态文件与 API，全程不带凭据。
// 为什么自建 OkHttpClient 副本而不复用 MihoyoClient：MihoyoClient 绑死了米游社语义
// （Cookie 注入、retcode 判定、1 秒详情节流），GitHub 一条都不需要，硬套反而会把米游社
// Cookie 发到 GitHub 域名。这里只 newBuilder() 换超时，连接池与线程仍与全局共享。
// 镜像回退策略：依次试 GitHubMirror.candidates，首个 2xx 且能解析出 JSON 的即返回；
// 单镜像失败立刻换下一个、不重试同一镜像——镜像挂了重试也不会活，只会拖慢首屏。

package com.gigi.tcg.data.github

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** 一次镜像读取的结果：值 + 命中的镜像标签（排障时要知道数据究竟从哪个镜像拿到的） */
data class MirroredFetch<T>(val value: T, val source: String)

class GitHubApi(
    http: OkHttpClient,
    private val json: Json,
) {
    private val client: OkHttpClient = http.newBuilder()
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    /** 单个 URL GET → 响应体字符串。非 2xx / 空体 / IO 失败一律抛 IOException（交给回退链处理） */
    suspend fun fetchText(url: String, accept: String? = null): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url.toHttpUrl())
            .header(USER_AGENT_HEADER, USER_AGENT)
            .apply { accept?.let { header(ACCEPT_HEADER, it) } }
            .get()
            .build()
        val response = suspendingCall(request)
        response.use { resp ->
            if (!resp.isSuccessful) {
                throw IOException("HTTP ${resp.code}")
            }
            resp.body?.string() ?: throw IOException("响应体为空")
        }
    }

    /**
     * 沿镜像回退链读取仓库内静态 JSON 并解码。
     * 全部候选失败时抛 IOException，消息含「已尝试 N 个镜像」+ 各镜像失败原因，
     * 现场排障不必再翻代码复现。
     */
    suspend fun <T> fetchJsonFromMirrors(
        path: String,
        ref: String = GitHubMirror.DEFAULT_BRANCH,
        serializer: KSerializer<T>,
    ): MirroredFetch<T> {
        val candidates = GitHubMirror.candidates(path, ref)
        val failures = mutableListOf<String>()
        for (url in candidates) {
            try {
                val value = decode(serializer, fetchText(url))
                return MirroredFetch(value, "mirror:${GitHubMirror.labelOf(url)}")
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Throwable) {
                failures.add("${GitHubMirror.labelOf(url)}=${error.javaClass.simpleName}")
            }
        }
        throw IOException("已尝试 ${candidates.size} 个镜像均无法读取 $path（${failures.joinToString("，")}）")
    }

    /**
     * main 分支 announcement.json。
     * 顶层兼容两种写法：数组 `[...]` 与对象 `{"announcements":[...]}`——手维护的静态文件两种都自然，
     * 只认一种必然会在某天改文件时踩坑。
     */
    suspend fun fetchAnnouncements(
        path: String = ANNOUNCEMENT_PATH,
        ref: String = GitHubMirror.DEFAULT_BRANCH,
    ): MirroredFetch<List<Announcement>> {
        val candidates = GitHubMirror.candidates(path, ref)
        val failures = mutableListOf<String>()
        for (url in candidates) {
            try {
                val root = parseElement(fetchText(url))
                val list = if (root is JsonArray) {
                    decode(ListSerializer(Announcement.serializer()), root)
                } else {
                    decode(AnnouncementList.serializer(), root).announcements
                }
                return MirroredFetch(list, "mirror:${GitHubMirror.labelOf(url)}")
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Throwable) {
                failures.add("${GitHubMirror.labelOf(url)}=${error.javaClass.simpleName}")
            }
        }
        throw IOException("已尝试 ${candidates.size} 个镜像均无法读取 $path（${failures.joinToString("，")}）")
    }

    /** main 分支 update.json */
    suspend fun fetchUpdateInfo(
        path: String = UPDATE_INFO_PATH,
        ref: String = GitHubMirror.DEFAULT_BRANCH,
    ): MirroredFetch<UpdateInfo> = fetchJsonFromMirrors(path, ref, UpdateInfo.serializer())

    /**
     * 官方 API 单次 GET（实测国内直连 200，不加镜像）。
     * 仓库尚无 release 时 GitHub 返回 404 → IOException，由调用方区分「没有新版本可判」与「网络不通」。
     */
    suspend fun <T> fetchApi(apiPath: String, serializer: KSerializer<T>): T =
        decode(serializer, fetchText("$GITHUB_API_BASE$apiPath", accept = API_ACCEPT))

    suspend fun fetchLatestRelease(): GitHubRelease =
        fetchApi(
            "/repos/${GitHubMirror.REPO_OWNER}/${GitHubMirror.REPO_NAME}/releases/latest",
            GitHubRelease.serializer(),
        )

    private fun parseElement(text: String): JsonElement =
        try {
            json.parseToJsonElement(text)
        } catch (error: SerializationException) {
            throw IOException("响应不是有效 JSON", error)
        }

    @OptIn(ExperimentalSerializationApi::class)
    private fun <T> decode(serializer: KSerializer<T>, text: String): T =
        try {
            json.decodeFromString(serializer, text)
        } catch (error: SerializationException) {
            throw IOException("响应无法按 ${serializer.descriptor.serialName} 解析", error)
        }

    @OptIn(ExperimentalSerializationApi::class)
    private fun <T> decode(serializer: KSerializer<T>, element: JsonElement): T =
        try {
            json.decodeFromJsonElement(serializer, element)
        } catch (error: SerializationException) {
            throw IOException("响应无法按 ${serializer.descriptor.serialName} 解析", error)
        }

    private suspend fun suspendingCall(request: Request): Response =
        suspendCancellableCoroutine { continuation ->
            client.newCall(request).enqueue(
                object : Callback {
                    override fun onResponse(call: Call, response: Response) {
                        continuation.resume(response)
                    }

                    override fun onFailure(call: Call, e: IOException) {
                        continuation.resumeWithException(e)
                    }
                }
            )
        }

    companion object {
        /** GitHub API 强制要求 User-Agent，缺失返回 403 */
        const val USER_AGENT: String = "GIGI2-Android"
        const val USER_AGENT_HEADER: String = "User-Agent"
        const val ACCEPT_HEADER: String = "Accept"
        const val API_ACCEPT: String = "application/vnd.github+json"

        /** 镜像站点延迟高：连接 5s / 读 8s 封顶，宁可判失败换下一个，也不让 UI 干等 */
        const val CONNECT_TIMEOUT_SECONDS: Long = 5L
        const val READ_TIMEOUT_SECONDS: Long = 8L
    }
}
