// GitHub 请求层（V9-D）：读取公开仓库的静态文件与 API，全程不带凭据。
// 为什么自建 OkHttpClient 副本而不复用 MihoyoClient：MihoyoClient 绑死了米游社语义
// （Cookie 注入、retcode 判定、1 秒详情节流），GitHub 一条都不需要，硬套反而会把米游社
// Cookie 发到 GitHub 域名。这里只 newBuilder() 换超时，连接池与线程仍与全局共享。
// 镜像策略（V39-H）：
//   · update.json —— **并行**拉全部候选，成功的全部交给调用方按 publishedAt 择优。
//     不能「第一个成功就返回」：jsDelivr 对 @main 缓存最长 12h，可能成功但内容过期。
//   · announcement.json —— 并行竞速，第一个成功即返回，其余请求取消。
// 单个镜像不做重试：镜像挂了重试也不会活，只会拖慢首屏。

package com.gigi.tcg.data.github

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
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

/** 并行竞速的内部尝试记录：成功时 value 非空，失败时 error 非空 */
private data class MirrorAttempt<T>(
    val url: String,
    val value: MirroredFetch<T>? = null,
    val error: Throwable? = null,
)

class GitHubApi(
    http: OkHttpClient,
    private val json: Json,
) {
    private val client: OkHttpClient = http.newBuilder()
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        // 整体上限：并行竞速时不能让某个慢镜像把总时长拖到 connect+read 叠加
        .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
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
     * 并行读取仓库内静态 JSON 的**全部成功镜像**。
     * 更新检查必须拿全部候选：jsDelivr 对 @main 的缓存可能成功但过期（最长 12h），
     * 只取「第一个成功」会把旧清单当成最新。调用方按 publishedAt 择优。
     *
     * 收集窗口 [CANDIDATE_WINDOW_MS]：到点即取消慢镜像，用已到的候选做决定。
     * 不能让一个卡住的镜像把检查拖到 callTimeout(10s)——用户点「检查更新」等 10 秒是不可接受的。
     * 全部失败才抛 IOException；错误消息按候选顺序拼，排障一眼能看出哪个镜像挂了。
     */
    suspend fun <T> fetchJsonCandidates(
        path: String,
        ref: String = GitHubMirror.DEFAULT_BRANCH,
        serializer: KSerializer<T>,
    ): List<MirroredFetch<T>> {
        val candidates = GitHubMirror.candidates(path, ref)
        return coroutineScope {
            val mailbox = Channel<MirrorAttempt<T>>(candidates.size)
            val jobs = candidates.map { url ->
                launch {
                    val attempt = try {
                        MirrorAttempt(
                            url,
                            MirroredFetch(decode(serializer, fetchText(url)), mirrorSource(url)),
                        )
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (error: Throwable) {
                        MirrorAttempt(url, error = error)
                    }
                    // 缓冲 = 候选数 ⇒ trySend 不会阻塞；超时分支已关闭协程时也只是安全丢弃
                    mailbox.trySend(attempt)
                }
            }
            val attempts = mutableListOf<MirrorAttempt<T>>()
            val allFinished = withTimeoutOrNull(CANDIDATE_WINDOW_MS) {
                repeat(candidates.size) { attempts += mailbox.receive() }
                true
            } ?: false
            if (!allFinished) jobs.forEach { it.cancel() }

            val successes = attempts.mapNotNull { it.value }
            if (successes.isNotEmpty()) return@coroutineScope successes
            if (allFinished) throw IOException(mirrorFailureMessage(path, attempts))
            throw IOException(
                "镜像候选在 ${CANDIDATE_WINDOW_MS}ms 内无成功响应" +
                    "（已收到失败 ${attempts.size}/${candidates.size}）",
            )
        }
    }

    /**
     * 并行竞速：第一个成功即返回，其余请求取消。
     * 只用于「不比较新鲜度」的文件（公告）；更新清单请走 [fetchJsonCandidates]。
     */
    private suspend fun <T> fetchFirstFromMirrors(
        path: String,
        ref: String,
        fetch: suspend (String) -> T,
    ): MirroredFetch<T> {
        val candidates = GitHubMirror.candidates(path, ref)
        return coroutineScope {
            val mailbox = Channel<MirrorAttempt<T>>(candidates.size)
            val jobs = candidates.map { url ->
                launch {
                    val attempt = try {
                        MirrorAttempt(url, MirroredFetch(fetch(url), mirrorSource(url)))
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (error: Throwable) {
                        MirrorAttempt(url, error = error)
                    }
                    mailbox.trySend(attempt)
                }
            }
            val failures = mutableListOf<MirrorAttempt<T>>()
            repeat(candidates.size) {
                val attempt = mailbox.receive()
                val value = attempt.value
                if (value != null) {
                    jobs.forEach { it.cancel() }
                    return@coroutineScope value
                }
                failures += attempt
            }
            throw IOException(mirrorFailureMessage(path, failures))
        }
    }

    /**
     * main 分支 announcement.json。
     * 顶层兼容两种写法：数组 `[...]` 与对象 `{"announcements":[...]}`——手维护的静态文件两种都自然，
     * 只认一种必然会在某天改文件时踩坑。
     */
    suspend fun fetchAnnouncements(
        path: String = ANNOUNCEMENT_PATH,
        ref: String = GitHubMirror.DEFAULT_BRANCH,
    ): MirroredFetch<List<Announcement>> = fetchFirstFromMirrors(path, ref) { url ->
        val root = parseElement(fetchText(url))
        if (root is JsonArray) {
            decode(ListSerializer(Announcement.serializer()), root)
        } else {
            decode(AnnouncementList.serializer(), root).announcements
        }
    }

    /** main 分支 update.json 的全部成功候选（调用方按 publishedAt 择优） */
    suspend fun fetchUpdateInfoCandidates(
        path: String = UPDATE_INFO_PATH,
        ref: String = GitHubMirror.DEFAULT_BRANCH,
    ): List<MirroredFetch<UpdateInfo>> =
        fetchJsonCandidates(path, ref, UpdateInfo.serializer())

    /** main 分支 update.json（取最新候选；需要看全部候选时用 [fetchUpdateInfoCandidates]） */
    suspend fun fetchUpdateInfo(
        path: String = UPDATE_INFO_PATH,
        ref: String = GitHubMirror.DEFAULT_BRANCH,
    ): MirroredFetch<UpdateInfo> = pickNewestUpdate(fetchUpdateInfoCandidates(path, ref))

    private fun mirrorSource(url: String): String = "mirror:${GitHubMirror.labelOf(url)}"

    private fun mirrorFailureMessage(
        path: String,
        outcomes: List<MirrorAttempt<*>>,
    ): String {
        val details = outcomes.joinToString("，") { attempt ->
            "${GitHubMirror.labelOf(attempt.url)}=${attempt.error?.javaClass?.simpleName ?: "Unknown"}"
        }
        return "已并行尝试 ${outcomes.size} 个镜像均无法读取 $path（$details）"
    }

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
            val call = client.newCall(request)
            // 竞速失败方会 cancel 协程：同步取消底层 Call，别让慢镜像继续占连接/流量
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(
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

        /** 三镜像并行：连接 3s / 读 5s / 整体 10s 封顶（V39-H 参照 Arco 实测口径收紧） */
        const val CONNECT_TIMEOUT_SECONDS: Long = 3L
        const val READ_TIMEOUT_SECONDS: Long = 5L
        const val CALL_TIMEOUT_SECONDS: Long = 10L

        /** 多镜像收集窗口：4s 内到几个用几个，避免被单个慢镜像拖满 callTimeout */
        const val CANDIDATE_WINDOW_MS: Long = 4_000L
    }
}
