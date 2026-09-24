// 扫码登录编排：整体移植 web/server/auth-core.mjs（设计文档 §3.1–§3.3）。
// Web 版由本地代理转发，原生端直连米哈游；服务端"deviceId 两步携带"的无状态化设计
// 在客户端天然成立（同一 AuthManager 会话内生成一次、create/query 复用）。
// 凭据交换（第④步）为重写重点：NoRole 分支零副作用（设计红线 7：不写凭据、不切登录态）。

package com.gigi.tcg.data.auth

import com.gigi.tcg.data.ServerApi
import com.gigi.tcg.data.ServerId
import java.io.IOException
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** finalize 结果：Success 仅在凭据交换成功（已落盘）时返回；NoRole 绝不落盘（设计 §3.3） */
sealed interface AuthFinalizeResult {
    data class Success(
        val mergedCookie: String,
        val gameUid: String,
        val nickname: String?,
        val exchanged: Boolean,
    ) : AuthFinalizeResult

    /** 账号未绑定所选服务器的原神角色：未产生任何写入/副作用 */
    data class NoRole(val region: String) : AuthFinalizeResult
}

class AuthManager(
    private val http: OkHttpClient,
    private val credentialStore: CredentialStore,
    private val json: Json,
) {

    /** 步骤①：直连 createQRLogin，返回 [QrSession.Created]（url 供登录页生成二维码展示） */
    suspend fun createQrLogin(): QrSession.Created {
        val deviceId = UUID.randomUUID().toString()
        val request = Request.Builder()
            .url(PASSPORT_CREATE_URL)
            .post(EMPTY_BODY)
            .header("x-rpc-app_id", QR_APP_ID)
            .header("x-rpc-device_id", deviceId)
            .build()
        val envelope = execute(request) { it.parseAs<QrCreateEnvelope>() }
        val url = envelope.data?.url
        val ticket = envelope.data?.ticket
        if (envelope.retcode != 0 || url.isNullOrEmpty() || ticket.isNullOrEmpty()) {
            throw IOException(envelope.message?.takeIf { it.isNotEmpty() } ?: "二维码生成失败")
        }
        return QrSession.Created(url = url, ticket = ticket, deviceId = deviceId)
    }

    /**
     * 步骤②：Flow + delay(2500) 轮询 queryQRLoginStatus（对齐 auth-core.mjs qrQuery）。
     * 单次请求失败不终止：发出 [QrSession.Polling] 保持态继续轮询；
     * -3501/-3505 终态结束流；Confirmed 携带 Set-Cookie 收集的凭据片段并结束流。
     */
    fun pollQrStatus(created: QrSession.Created): Flow<QrSession> = flow {
        emit(created)
        while (true) {
            delay(POLL_INTERVAL_MS)
            val outcome = try {
                queryOnce(created.ticket, created.deviceId)
            } catch (e: IOException) {
                null
            } catch (e: SerializationException) {
                null
            }
            if (outcome == null) {
                emit(QrSession.Polling)
                continue
            }
            val (retcode, status, cookies) = outcome
            when {
                retcode == RETCODE_EXPIRED -> {
                    emit(QrSession.Expired)
                    return@flow
                }
                retcode == RETCODE_CANCELLED -> {
                    emit(QrSession.Cancelled)
                    return@flow
                }
                status == "Scanned" -> emit(QrSession.Scanned)
                status == "Confirmed" -> {
                    emit(QrSession.Confirmed(cookies))
                    return@flow
                }
                else -> emit(QrSession.Polling)
            }
        }
    }

    /**
     * 步骤③④：凭据交换编排，严格照 §3.2 四步。
     * 无匹配角色 → [AuthFinalizeResult.NoRole]（🔴 不调用 save、零副作用）；
     * 交换成功（合并结果含 e_hk4e_token）→ credentialStore.save(merged) 后返回 Success。
     */
    suspend fun finalize(confirmedCookies: List<String>, server: ServerId): AuthFinalizeResult {
        val fragments = mergeFragments(confirmedCookies)
        val baseCookie = fragments.joinToString("; ")
        val accountId =
            findPair(fragments, "account_id") ?: findPair(fragments, "account_id_v2")
        val cookieTokenV2 = findPair(fragments, "cookie_token_v2")
        if (accountId == null || cookieTokenV2 == null) {
            throw IOException("登录凭据不完整（缺少 account_id / cookie_token_v2）")
        }

        // ① 按所选服务器发现原神 game_uid（auth-core.mjs getGameRecordCard：Cookie 为完整基础凭据）
        val cardRequest = Request.Builder()
            .url(
                "$RECORD_ORIGIN/game_record/app/card/wapi/getGameRecordCard" +
                    "?gids=2&uid=${URLEncoder.encode(accountId, "UTF-8")}"
            )
            .get()
            .header("Cookie", baseCookie)
            .header("x-rpc-client_type", "5")
            .build()
        val recordList = execute(cardRequest) { it.parseAs<RecordCardEnvelope>().data?.list.orEmpty() }
        val role = findGameRoleForRegion(recordList, server.id)
            ?: return AuthFinalizeResult.NoRole(region = server.id)

        // ② 交换 e_hk4e_token（auth-core.mjs 原文：Cookie 用 cookie_token=<cookie_token_v2 的值>，而非 _v2 键名）
        val region = role.region.orEmpty()
        val gameRoleId = role.gameRoleId.orEmpty()
        val exchangeRequest = Request.Builder()
            .url(BADGE_LOGIN_URL)
            .post(badgeLoginBody(json, region, gameRoleId))
            .header("Cookie", "account_id=$accountId; cookie_token=$cookieTokenV2")
            .build()
        // auth-core.mjs 不校验交换接口 retcode：e_hk4e_token 是否下发是唯一判据（exchanged 语义）
        val exchange = execute(exchangeRequest) { resp ->
            ExchangePayload(setCookiePairs(resp), resp.parseAsOrNull<BadgeLoginEnvelope>()?.data?.nickname)
        }
        val merged = mergeFragments(fragments + exchange.extraCookies)
        val exchanged = merged.any { it.startsWith(E_HK4E_TOKEN_PREFIX) }
        // 🔴 仅交换成功才落盘（设计 §3.3：拒绝半登录状态）
        if (!exchanged) {
            throw IOException("凭据交换失败：响应未携带 e_hk4e_token")
        }
        val mergedCookie = merged.joinToString("; ")
        credentialStore.save(mergedCookie)
        return AuthFinalizeResult.Success(
            mergedCookie = mergedCookie,
            gameUid = gameRoleId,
            nickname = exchange.nickname,
            exchanged = true,
        )
    }

    // ===== 内部 =====

    /** 单次 queryQRLoginStatus：返回 (retcode, status, Set-Cookie 片段)；IO/解析异常向上抛由轮询吞掉 */
    private suspend fun queryOnce(ticket: String, deviceId: String): Triple<Int, String?, List<String>> {
        val request = Request.Builder()
            .url(PASSPORT_QUERY_URL)
            .post(
                json.encodeToString(
                    JsonElement.serializer(),
                    JsonObject(mapOf("ticket" to JsonPrimitive(ticket)))
                ).toRequestBody(JSON_MEDIA_TYPE)
            )
            .header("x-rpc-app_id", QR_APP_ID)
            .header("x-rpc-device_id", deviceId)
            .build()
        return execute(request) { resp ->
            val envelope = resp.parseAs<QrQueryEnvelope>()
            val cookies =
                if (envelope.retcode == 0 && envelope.data?.status == "Confirmed") {
                    setCookiePairs(resp)
                } else {
                    emptyList()
                }
            Triple(envelope.retcode, envelope.data?.status, cookies)
        }
    }

    /** OkHttp 异步调用挂起化（Dispatchers.IO），非 2xx 归 IOException（对齐 MihoyoClient 传输语义） */
    private suspend fun <T> execute(request: Request, parse: (Response) -> T): T =
        withContext(Dispatchers.IO) {
            val response = suspendCancellableCall(request)
            response.use { resp ->
                if (!resp.isSuccessful) {
                    throw IOException("网络请求失败（HTTP ${resp.code}）")
                }
                parse(resp)
            }
        }

    private suspend fun suspendCancellableCall(request: Request): Response =
        suspendCancellableCoroutine { continuation ->
            http.newCall(request).enqueue(
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

    private inline fun <reified T> Response.parseAs(): T =
        json.decodeFromString<T>(body?.string().orEmpty())

    private inline fun <reified T> Response.parseAsOrNull(): T? =
        try {
            parseAs<T>()
        } catch (e: SerializationException) {
            null
        }

    companion object {
        const val PASSPORT_BASE: String = "https://passport-api.miyoushe.com"
        const val PASSPORT_CREATE_URL: String = "$PASSPORT_BASE/account/ma-cn-passport/web/createQRLogin"
        const val PASSPORT_QUERY_URL: String = "$PASSPORT_BASE/account/ma-cn-passport/web/queryQRLoginStatus"
        const val RECORD_ORIGIN: String = "https://api-takumi-record.mihoyo.com"
        const val BADGE_LOGIN_URL: String = "https://api-takumi.mihoyo.com/common/badge/v1/login/account"

        /** 米游社网页端 app_id（auth-core.mjs QR_APP_ID，设计 §3.1 要求保留） */
        const val QR_APP_ID: String = "bll8iq97cem8"

        const val POLL_INTERVAL_MS: Long = 2500L
        const val RETCODE_EXPIRED: Int = -3501
        const val RETCODE_CANCELLED: Int = -3505
        const val E_HK4E_TOKEN_PREFIX: String = "e_hk4e_token="

        private val EMPTY_BODY = "".toRequestBody(null)
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()

        /**
         * mergeFragments：auth-core.mjs 同语义——片段合并为 name=value 列表，同名以后者覆盖。
         * 🔴 mjs 的 join('; ')+split(';') 会吃掉非首片段首字符：'=' 开头的坏键被静默丢弃，照抄不改。
         * 纯函数，可单测。
         */
        fun mergeFragments(fragments: List<String>): List<String> {
            val pairs = LinkedHashMap<String, String>()
            for (part in fragments.joinToString("; ").split(";")) {
                val idx = part.indexOf('=')
                if (idx <= 0) continue
                pairs[part.substring(0, idx).trim()] = part.substring(idx + 1).trim()
            }
            return pairs.entries.map { (k, v) -> "$k=$v" }
        }

        /** getPair 语义：取第一个 name=value 片段的值，未命中返回 null。纯函数。 */
        fun findPair(fragments: List<String>, name: String): String? {
            val prefix = "$name="
            return fragments.firstOrNull { it.startsWith(prefix) }?.substring(prefix.length)
        }

        /** find(g => g.region === region && g.game_role_id) 语义。纯函数，no-role 判定唯一入口。 */
        fun findGameRoleForRegion(list: List<GameRoleCard>, region: String): GameRoleCard? =
            list.firstOrNull { it.region == region && !it.gameRoleId.isNullOrEmpty() }

        /** 响应 Set-Cookie 头 → name=value 片段（attributes 截断丢弃，对齐 collectSetCookies）。 */
        fun setCookiePairs(response: Response): List<String> =
            response.headers("Set-Cookie").asSequence()
                .map { it.substringBefore(';').trim() }
                .filter { it.contains('=') && !it.startsWith("=") }
                .toList()

        /** 从 getGameRecordCard 响应 JSON 提取 list（供无网络单测复用）。 */
        fun recordCardListFromJson(json: Json, raw: String): List<GameRoleCard> =
            json.decodeFromString<RecordCardEnvelope>(raw).data?.list.orEmpty()

        /** badge login/account 请求体：{region, uid, game_biz}（§3.2，game_biz 恒取 ServerApi 常量） */
        @JvmName("badgeLoginBodyJson")
        internal fun badgeLoginBody(json: Json, region: String, gameRoleId: String) =
            json.encodeToString(
                JsonElement.serializer(),
                JsonObject(
                    mapOf(
                        "region" to JsonPrimitive(region),
                        "uid" to JsonPrimitive(gameRoleId),
                        "game_biz" to JsonPrimitive(ServerApi.GAME_BIZ),
                    )
                )
            ).toRequestBody(JSON_MEDIA_TYPE)
    }
}

/** getGameRecordCard data.list 元素（全字段可空，设计红线 1） */
@Serializable
data class GameRoleCard(
    val region: String? = null,
    @SerialName("game_role_id") val gameRoleId: String? = null,
)

private data class ExchangePayload(val extraCookies: List<String>, val nickname: String?)

@Serializable
private class QrCreateEnvelope(
    val retcode: Int = 0,
    val message: String? = null,
    val data: QrCreateData? = null,
)

@Serializable
private class QrCreateData(
    val url: String? = null,
    val ticket: String? = null,
)

@Serializable
private class QrQueryEnvelope(
    val retcode: Int = 0,
    val message: String? = null,
    val data: QrQueryData? = null,
)

@Serializable
private class QrQueryData(
    val status: String? = null,
)

@Serializable
internal class RecordCardEnvelope(
    val data: RecordCardData? = null,
)

@Serializable
internal class RecordCardData(
    val list: List<GameRoleCard> = emptyList(),
)

@Serializable
private class BadgeLoginEnvelope(
    val data: BadgeLoginData? = null,
)

@Serializable
private class BadgeLoginData(
    val nickname: String? = null,
)
