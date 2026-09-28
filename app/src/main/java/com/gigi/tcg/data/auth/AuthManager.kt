// 扫码登录编排：整体移植 web/server/auth-core.mjs（设计文档 §3.1–§3.3）。
// Web 版由本地代理转发，原生端直连米哈游；服务端"deviceId 两步携带"的无状态化设计
// 在客户端天然成立（同一 AuthManager 会话内生成一次、create/query 复用）。
// 凭据交换（第④步）为重写重点：NoRole 分支零副作用（设计红线 7：不写凭据、不切登录态）。

package com.gigi.tcg.data.auth

import com.gigi.tcg.R
import com.gigi.tcg.data.ServerApi
import com.gigi.tcg.data.ServerId
import com.gigi.tcg.data.api.RETRYABLE_RETCODES
import com.gigi.tcg.i18n.LocaleStrings
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

    /**
     * 账号未绑定所选服务器的原神角色：未产生任何写入/副作用。
     *
     * 🔴 [boundRoles] 是该米游社账号**实际已绑定**的原神角色（仅 game_id=2，去重、已排除空
     * region / 空 game_role_id 的噪声项）。它区分了两种此前被混成同一句提示的失败：
     * - 空 ⇒ 账号真的一个原神角色都没绑（提示应引导去米游社绑定，而非"切换服务器"）；
     * - 非空且不含所选服 ⇒ 角色绑在别的区服上（提示应改为"该账号绑的是 X，请切换服务器"，
     *   否则用户会以为自己没绑角色，反复扫码）。
     *
     * 实测取证（2026-09-28 真机 + 官方接口原始返回）：getGameRecordCard **不按 gids=2 过滤**，
     * 会一并返回同一米游社账号绑定的其他游戏卡片（实测混入绝区零 game_id=8 /
     * region=prod_gf_cn / region_name=新艾利都）。不过滤 game_id 会让这些卡片被误当成
     * "原神角色绑在别的区服"，把提示污染成用户看不懂的服务器标识。
     */
    data class NoRole(val region: String, val boundRoles: List<BoundRole> = emptyList()) :
        AuthFinalizeResult {
        /** 兼容/展示：已绑定原神角色的区服标识（去重保序，口径同 [boundRoles]） */
        val boundRegions: List<String> get() = boundRoles.map { it.region }
    }

    /** 已绑定的原神角色摘要（仅 game_id=2），供 no-role 提示精确说明"角色实际在哪"。 */
    data class BoundRole(
        val region: String,
        val regionName: String?,
        val uid: String,
        val nickname: String?,
    )
}

/**
 * 二维码创建重试耗尽（或遇到不可重试的业务码）后的最终失败，由 [AuthManager.createQrLogin] 抛出。
 * [isNetwork] 供上层分流文案：true=网络不稳定；false=米哈游业务拒绝（message 即其原始文案）。
 * 继承 IOException：调用方既有的 IOException 处理路径不变。
 */
class QrCreateException(
    message: String,
    val isNetwork: Boolean,
    internal val retryable: Boolean,
    cause: Throwable? = null,
) : IOException(message, cause)

class AuthManager(
    private val http: OkHttpClient,
    private val credentialStore: CredentialStore,
    private val json: Json,
) {

    /**
     * 步骤①：直连 createQRLogin，返回 [QrSession.Created]（url 供登录页生成二维码展示）。
     * 本调用落在 App 冷启动关键路径上（DNS/连接池/TLS 全冷），瞬时失败按 [QR_CREATE_MAX_ATTEMPTS]
     * 次重试，间隔与 MihoyoClient 限流退避同范式（基础 + 0..300ms 抖动）；
     * 重试只换传输时机，deviceId 沿用同一个。不可重试的业务码立即失败，全部失败抛 [QrCreateException]。
     */
    suspend fun createQrLogin(): QrSession.Created {
        val deviceId = UUID.randomUUID().toString()
        var attempt = 0
        while (true) {
            attempt++
            val failure = try {
                val envelope = execute(qrCreateRequest(deviceId)) { it.parseAs<QrCreateEnvelope>() }
                val url = envelope.data?.url
                val ticket = envelope.data?.ticket
                if (envelope.retcode == 0 && !url.isNullOrEmpty() && !ticket.isNullOrEmpty()) {
                    return QrSession.Created(url = url, ticket = ticket, deviceId = deviceId)
                }
                QrCreateException(
                    message = envelope.message?.takeIf { it.isNotEmpty() }
                        ?: LocaleStrings.getOrDefault(R.string.error_qr_failed, "二维码生成失败"),
                    isNetwork = false,
                    retryable = RETRYABLE_RETCODES.contains(envelope.retcode),
                )
            } catch (e: IOException) {
                // 传输层瞬时失败（DNS/连接/读取超时、TLS、socket 断开）都值得重发一次
                QrCreateException(
                    message = e.message ?: LocaleStrings.getOrDefault(R.string.error_network, "网络请求失败"),
                    isNetwork = true,
                    retryable = true,
                    cause = e,
                )
            }
            if (!failure.retryable || attempt >= QR_CREATE_MAX_ATTEMPTS) throw failure
            delay(QR_CREATE_RETRY_DELAY_MS + (0..QR_CREATE_RETRY_JITTER_SPAN_MS).random())
        }
    }

    /** 每次尝试都重建 Request（OkHttp 的 Call 不可复用），deviceId 由调用方保持同一会话 */
    private fun qrCreateRequest(deviceId: String): Request = Request.Builder()
        .url(PASSPORT_CREATE_URL)
        .post(EMPTY_BODY)
        .header("x-rpc-app_id", QR_APP_ID)
        .header("x-rpc-device_id", deviceId)
        .build()

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
    suspend fun finalize(confirmedCookies: List<String>, server: ServerId): AuthFinalizeResult =
        completeExchange(mergeFragments(confirmedCookies), server, previous = null)

    suspend fun refreshStoredSession(account: StoredAccount): AuthFinalizeResult {
        val cookie = credentialStore.cookieHeaderFor(account.uid)
            ?: throw IOException(LocaleStrings.getOrDefault(R.string.error_credential_expired, "登录凭据已失效，请重新扫码"))
        return completeExchange(
            fragments = mergeFragments(listOf(cookie)),
            server = account.server(),
            previous = account,
        )
    }

    private suspend fun completeExchange(
        fragments: List<String>,
        server: ServerId,
        previous: StoredAccount?,
    ): AuthFinalizeResult {
        val baseCookie = fragments.joinToString("; ")
        val accountId =
            findPair(fragments, "account_id") ?: findPair(fragments, "account_id_v2")
        val cookieTokenV2 = findPair(fragments, "cookie_token_v2")
        if (accountId == null || cookieTokenV2 == null) {
            throw IOException(
                LocaleStrings.getOrDefault(
                    R.string.error_credential_incomplete,
                    "登录凭据不完整（缺少 account_id / cookie_token_v2）",
                ),
            )
        }

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
            ?: return AuthFinalizeResult.NoRole(
                region = server.id,
                boundRoles = boundRolesOf(recordList),
            )

        val region = role.region.orEmpty()
        val gameRoleId = role.gameRoleId.orEmpty()
        if (previous != null && previous.uid != gameRoleId) {
            throw IOException(LocaleStrings.getOrDefault(R.string.error_role_mismatch, "账户角色不匹配"))
        }
        val exchangeRequest = Request.Builder()
            .url(BADGE_LOGIN_URL)
            .post(badgeLoginBody(json, region, gameRoleId))
            .header("Cookie", "account_id=$accountId; cookie_token=$cookieTokenV2")
            .build()
        val exchange = execute(exchangeRequest) { resp ->
            ExchangePayload(setCookiePairs(resp), resp.parseAsOrNull<BadgeLoginEnvelope>()?.data?.nickname)
        }
        val exchanged = hasFreshEhk4e(exchange.extraCookies)
        if (!exchanged) {
            throw IOException(
                LocaleStrings.getOrDefault(
                    R.string.error_token_exchange_failed,
                    "凭据交换失败：响应未携带 e_hk4e_token",
                ),
            )
        }
        if (previous != null && credentialStore.activeUid() != previous.uid) {
            throw IOException("账户已切换，放弃本次续命")
        }
        val merged = mergeFragments(fragments + exchange.extraCookies)
        val mergedCookie = merged.joinToString("; ")
        credentialStore.save(
            mergedCookie,
            StoredAccount(
                uid = gameRoleId,
                nickname = exchange.nickname ?: previous?.nickname,
                serverId = server.id,
            ),
        )
        return AuthFinalizeResult.Success(
            mergedCookie = mergedCookie,
            gameUid = gameRoleId,
            nickname = exchange.nickname ?: previous?.nickname,
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
                    throw IOException(
                        if (LocaleStrings.resolved) {
                            LocaleStrings.get(R.string.error_http_code, resp.code)
                        } else {
                            "网络请求失败（HTTP ${resp.code}）"
                        },
                    )
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

        /** getGameRecordCard 里原神的 game_id（实测 2026-09-28：绝区零=8 会混入同一响应，须过滤） */
        const val GAME_ID_GENSHIN: Int = 2

        const val POLL_INTERVAL_MS: Long = 2500L
        const val RETCODE_EXPIRED: Int = -3501
        const val RETCODE_CANCELLED: Int = -3505
        const val E_HK4E_TOKEN_PREFIX: String = "e_hk4e_token="

        /** 二维码创建重试的基础间隔（冷启动首请求走完整 DNS+TCP+TLS，米哈游侧限流窗口也很短） */
        const val QR_CREATE_RETRY_DELAY_MS: Long = 800L

        /** 二维码创建重试间隔之上的随机抖动上界（0..300ms），与 MihoyoClient 同范式 */
        const val QR_CREATE_RETRY_JITTER_SPAN_MS: Long = 300L

        /** 二维码创建最大尝试次数：首发 + 最多 2 次重试 */
        const val QR_CREATE_MAX_ATTEMPTS: Int = 3

        fun hasFreshEhk4e(fragments: List<String>): Boolean =
            fragments.any { it.startsWith(E_HK4E_TOKEN_PREFIX) }

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

        /** find(g => g.region === region && g.game_role_id) 语义 + game_id 过滤。
         *  纯函数，no-role 判定唯一入口；game_id 过滤理由见 [isGenshinCard]。 */
        fun findGameRoleForRegion(list: List<GameRoleCard>, region: String): GameRoleCard? =
            list.firstOrNull {
                it.isGenshinCard() && it.region == region && !it.gameRoleId.isNullOrEmpty()
            }

        /**
         * 该账号**实际已绑定**的原神角色摘要（去重、保持服务端返回顺序）。
         * 噪声项过滤口径与 [findGameRoleForRegion] 一致：原神卡片（game_id 过滤）+ region 非空
         * + game_role_id 非空。仅供 no-role 提示定位"角色其实绑在别的服"，不参与任何凭据判定。
         */
        fun boundRolesOf(list: List<GameRoleCard>): List<AuthFinalizeResult.BoundRole> =
            list.asSequence()
                .filter {
                    it.isGenshinCard() &&
                        !it.region.isNullOrEmpty() &&
                        !it.gameRoleId.isNullOrEmpty()
                }
                .map {
                    AuthFinalizeResult.BoundRole(
                        region = it.region!!,
                        regionName = it.regionName,
                        uid = it.gameRoleId!!,
                        nickname = it.nickname,
                    )
                }
                .distinctBy { it.region }
                .toList()

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
    /** 游戏编号：原神=2、绝区零=8（实测）。过滤判定见 [isGenshinCard] */
    @SerialName("game_id") val gameId: Int? = null,
    @SerialName("game_name") val gameName: String? = null,
    val nickname: String? = null,
    /** 服务端本地化的区服名（天空岛/世界树/新艾利都）——提示文案直接用官方口径 */
    @SerialName("region_name") val regionName: String? = null,
    val level: Int? = null,
)

/**
 * 该卡片是否属于原神。
 * 🔴 不写成 `gameId == GAME_ID_GENSHIN`：设计红线 1 要求字段全可空，若服务端哪天不再下发
 * game_id，严格判定会让**所有**账号都无法登录。故只排除"明确属于其他游戏"的卡片——
 * 匹配仍有 region 精确相等兜底，宽松判定的风险面仅限提示文案。
 */
internal fun GameRoleCard.isGenshinCard(): Boolean =
    gameId == null || gameId == AuthManager.GAME_ID_GENSHIN

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
