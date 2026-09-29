// 扫码登录编排：整体移植 web/server/auth-core.mjs（设计文档 §3.1–§3.3）。
// Web 版由本地代理转发，原生端直连米哈游；服务端"deviceId 两步携带"的无状态化设计
// 在客户端天然成立（同一 AuthManager 会话内生成一次、create/query 复用）。
// 凭据交换（第④步）为重写重点：NoRole 分支零副作用（设计红线 7：不写凭据、不切登录态）。
// V31（2026-09-28）：角色发现改用米游社「绑定角色」接口 getUserGameRolesByCookie ——
// getGameRecordCard 实测漏渠道服（cn_qd01）角色，渠道服账号被误判"未绑定"（详见 NoRole 注释）。
// V33（2026-09-28）：凭据交换新增**无服务器偏好入口**（finalize(cookies) 单参重载）——
// 服务器不再由登录页预选，改在确认后按绑定角色列表当场决策：唯一角色直接登录
//（渠道服用户不必先猜"渠道服=世界树"）、多角色返回 [AuthFinalizeResult.SelectRole]
// 交用户选择、零角色 NoRole。原"指定服务器"入口保留，供用户选择后携选中的区服重试。

package com.gigi.tcg.data.auth

import com.gigi.tcg.R
import com.gigi.tcg.data.ServerApi
import com.gigi.tcg.data.ServerId
import com.gigi.tcg.data.api.RETRYABLE_RETCODES
import com.gigi.tcg.i18n.LocaleStrings
import java.io.IOException
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

/** finalize 结果：Success 仅在凭据交换成功（已落盘）时返回；NoRole / SelectRole 绝不落盘（设计 §3.3） */
sealed interface AuthFinalizeResult {
    data class Success(
        val mergedCookie: String,
        val gameUid: String,
        val nickname: String?,
        val exchanged: Boolean,
        /**
         * 实际登录角色所在的区服标识（cn_gf01 / cn_qd01）—— 唯一可信的服务器归属来源。
         * V33 前由用户预选的 ServerId 决定 currentServer；拆除选择器后必须以本字段为准，
         * 否则添加账户场景会把新账户的 currentServer 停留在旧值。
         */
        val region: String,
    ) : AuthFinalizeResult

    /**
     * 账号未绑定**可登录的国服原神角色**：未产生任何写入/副作用。
     *
     * [boundRoles] 是该米游社账号**实际已绑定**的原神角色（去重、已排除空 region /
     * 空 game_uid 的噪声项），供提示精度使用。
     *
     * 🔴 V33 起语义收窄：登录页拆除服务器预选后，"所选服务器没角色"这一成因不复存在
     * （自动选择要么直接命中唯一角色、要么列候选让用户选）。本结果只剩"没有可登录的
     * 国服角色"一种实际形态，文案统一引导去米游社绑定/检查（见 noRoleNotice）。
     * [region] / [boundRoles] 字段保留作数据完整性（防御与未来扩展），展示层不再细分。
     *
     * 实测取证（2026-09-28 真机 + 官方接口原始返回）：角色发现必须用米游社「绑定角色」接口
     * getUserGameRolesByCookie，**不能用 getGameRecordCard** —— 后者是"展示卡片"口径，实测
     * 对同一账号（官服 cn_gf01 + 渠道服 cn_qd01 双角色）只返回官服卡片，渠道服角色被静默
     * 丢弃，渠道服用户因此被误判"未绑定"。绑定接口与米游社 App「我的角色」同源，完整返回
     * 两个角色（渠道服项 is_official=false / region_name=世界树）。
     */
    data class NoRole(val region: String, val boundRoles: List<BoundRole> = emptyList()) :
        AuthFinalizeResult {
        /** 兼容/展示：已绑定原神角色的区服标识（去重保序，口径同 [boundRoles]） */
        val boundRegions: List<String> get() = boundRoles.map { it.region }
    }

    /**
     * 账号绑定了**多个可登录的国服原神角色**（典型：官服 + 渠道服各一）：需由用户选择
     * 要登录哪一个，不替用户猜。未产生任何写入/副作用（同 [NoRole]）。
     * 用户在 UI 选定某个候选后，携该候选的 region 重试 [AuthManager.finalize]（指定服务器入口）。
     */
    data class SelectRole(val candidates: List<BoundRole>) : AuthFinalizeResult

    /** 已绑定的原神角色摘要（仅国服可登录项），供 no-role 提示与角色选择器展示。 */
    data class BoundRole(
        val region: String,
        val regionName: String?,
        val uid: String,
        val nickname: String?,
        /** 角色等级（服务端下发；可能缺失）。选择器里帮用户辨识"是哪个角色"。 */
        val level: Int? = null,
    )
}

/**
 * V33 无服务器偏好入口（[AuthManager.finalize] 单参重载）的角色选择结果，由纯函数
 * [AuthManager.selectRoleForAuto] 产出。拆成独立 sealed 类型而非直接复用 finalize 结果：
 * 决策是纯函数（JVM 单测钉死），completeExchange 只做"翻译"（Single 继续交换 / Multiple 上抛）。
 */
sealed interface AutoRoleSelection {
    /** 账号没有可登录的国服原神角色（含"一个原神角色都没绑"与"只绑了注册表外区服"） */
    data object None : AutoRoleSelection

    /** 恰有一个可登录的国服原神角色：直接登录，不再打扰用户 */
    data class Single(val role: BoundGameRole) : AutoRoleSelection

    /** 多个可登录的国服原神角色（典型：官服 + 渠道服各一）：交由用户选择，不替用户猜 */
    data class Multiple(val candidates: List<AuthFinalizeResult.BoundRole>) : AutoRoleSelection
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
     * 步骤③④（V33 默认入口）：**不带服务器偏好**的凭据交换 —— 扫码确认后按绑定角色列表
     * 当场决策，见 [selectRoleForAuto]：唯一角色直接登录（渠道服用户不必先猜"渠道服=世界树"）、
     * 多角色返回 [AuthFinalizeResult.SelectRole] 交用户选择、零角色返回 [AuthFinalizeResult.NoRole]。
     * 交换成功（合并结果含 e_hk4e_token）→ credentialStore.save(merged) 后返回 Success。
     */
    suspend fun finalize(confirmedCookies: List<String>): AuthFinalizeResult =
        completeExchange(mergeFragments(confirmedCookies), server = null, previous = null)

    /**
     * 步骤③④（指定服务器入口）：用户在角色选择器里选定候选后携其 region 重试；
     * 该区服下无匹配角色 → [AuthFinalizeResult.NoRole]（🔴 不调用 save、零副作用）。
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
        server: ServerId?,
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

        // 🔴 角色发现用米游社「绑定角色」接口（getUserGameRolesByCookie），与 App「我的角色」
        // 同源；getGameRecordCard 是展示卡片口径，实测漏渠道服（cn_qd01）角色（见 NoRole 注释）。
        val rolesRequest = Request.Builder()
            .url("$BINDING_ROLES_URL?game_biz=${ServerApi.GAME_BIZ}")
            .get()
            .header("Cookie", baseCookie)
            .header("x-rpc-client_type", "5")
            .build()
        val rolesEnvelope = execute(rolesRequest) { it.parseAs<RolesEnvelope>() }
        if (rolesEnvelope.retcode != 0) {
            // 会话失效/服务端拒答不能与"未绑定角色"混为一谈（否则续命失败会被误报成未绑定）
            throw IOException(
                rolesEnvelope.message?.takeIf { it.isNotEmpty() }
                    ?: LocaleStrings.getOrDefault(
                        R.string.error_credential_expired,
                        "登录凭据已失效，请重新扫码",
                    ),
            )
        }
        val roleList = rolesEnvelope.data?.list.orEmpty()
        // V33：服务器要么由用户显式指定（选择器选定后重试 / 账户续命），要么为 null（扫码后首评，
        // 按真实角色列表自动决策）。auto 路径的 role 一定带注册表内 region —— selectRoleForAuto 已过滤。
        val role: BoundGameRole = if (server != null) {
            findGameRoleForRegion(roleList, server.id)
                ?: return AuthFinalizeResult.NoRole(
                    region = server.id,
                    boundRoles = boundRolesOf(roleList),
                )
        } else {
            when (val auto = selectRoleForAuto(roleList)) {
                AutoRoleSelection.None -> return AuthFinalizeResult.NoRole(
                    region = "",
                    boundRoles = boundRolesOf(roleList),
                )
                is AutoRoleSelection.Single -> auto.role
                is AutoRoleSelection.Multiple -> return AuthFinalizeResult.SelectRole(auto.candidates)
            }
        }

        val region = role.region.orEmpty()
        val gameRoleId = role.gameUid.orEmpty()
        // 落盘归属区服：显式指定的优先；auto 路径从命中的角色 region 反解注册表项。
        val accountServer: ServerId = server
            ?: ServerId.from(region)
            ?: throw IOException("角色区服不在支持范围：$region")
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
                serverId = accountServer.id,
                // V36/2 决策 2：头像随登录/续命落盘（角色发现接口下发就写，没下发留 null → UI 画占位）。
                // 🔴 本次响应没带头像时要沿用已存值，否则每次续命都会把已有头像擦成 null。
                avatar = role.avatarUrl?.takeIf { it.isNotBlank() } ?: previous?.avatar,
            ),
        )
        return AuthFinalizeResult.Success(
            mergedCookie = mergedCookie,
            gameUid = gameRoleId,
            nickname = exchange.nickname ?: previous?.nickname,
            exchanged = true,
            region = region,
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
        const val BADGE_LOGIN_URL: String = "https://api-takumi.mihoyo.com/common/badge/v1/login/account"

        /** 米游社「绑定角色」接口：角色发现权威来源（与 BADGE_LOGIN_URL 同域，未挂 BuildConfig 覆盖） */
        const val BINDING_ROLES_URL: String =
            "https://api-takumi.mihoyo.com/binding/api/getUserGameRolesByCookie"

        /** 米游社网页端 app_id（auth-core.mjs QR_APP_ID，设计 §3.1 要求保留） */
        const val QR_APP_ID: String = "bll8iq97cem8"

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

        /** find(g => g.region === region && g.game_uid) 语义 + 业务线过滤。
         *  纯函数，no-role 判定唯一入口；game_biz 过滤理由见 [isGenshinBinding]。 */
        fun findGameRoleForRegion(list: List<BoundGameRole>, region: String): BoundGameRole? =
            list.firstOrNull {
                it.isGenshinBinding() && it.region == region && !it.gameUid.isNullOrEmpty()
            }

        /**
         * 该账号**实际已绑定**的原神角色摘要（去重、保持服务端返回顺序）。
         * 噪声项过滤口径与 [findGameRoleForRegion] 一致：原神绑定项（game_biz 过滤）+ region 非空
         * + game_uid 非空。⚠️ 本函数**不过滤注册表**（提示口径：宁可多显示，诊断不丢信息）；
         * 决定"能登录谁"的 [selectRoleForAuto] 口径更严（多一条注册表过滤），两者刻意不同。
         */
        fun boundRolesOf(list: List<BoundGameRole>): List<AuthFinalizeResult.BoundRole> =
            list.asSequence()
                .filter {
                    it.isGenshinBinding() &&
                        !it.region.isNullOrEmpty() &&
                        !it.gameUid.isNullOrEmpty()
                }
                .map(::toBoundRole)
                .distinctBy { it.region }
                .toList()

        /** BoundGameRole → 展示用 BoundRole（调用方保证 region / game_uid 非空，噪声项已被过滤）。 */
        internal fun toBoundRole(role: BoundGameRole): AuthFinalizeResult.BoundRole =
            AuthFinalizeResult.BoundRole(
                region = role.region.orEmpty(),
                regionName = role.regionName,
                uid = role.gameUid.orEmpty(),
                nickname = role.nickname,
                level = role.level,
            )

        /**
         * V33 扫码确认后的自动角色选择（纯函数，JVM 单测钉死）：登录页不再让用户预选服务器，
         * 服务器归属由本函数按**真实角色列表**当场决策。
         *
         * 过滤口径 = 原神绑定（game_biz）× **国服注册表内 region**（[ServerId.isValid]）×
         * game_uid 非空，按 region 去重保序（原神同服单角色）。注册表过滤理由：本应用只做国服
         * 两服，注册表外区服（os_* 等）没有对应 ServerId，`StoredAccount.serverId` 无法落盘。
         * 🔴 [ServerId] 在此处被消费的语义已从"用户预选"改为"落盘校验"，不是循环依赖：
         * AuthManager 本就依赖 ServerId（ServerId.kt 是纯数据注册表，不依赖 auth 包）。
         */
        fun selectRoleForAuto(list: List<BoundGameRole>): AutoRoleSelection {
            val candidates = list
                .filter { role ->
                    role.isGenshinBinding() &&
                        !role.gameUid.isNullOrEmpty() &&
                        ServerId.isValid(role.region)
                }
                .distinctBy { it.region }
            return when (candidates.size) {
                0 -> AutoRoleSelection.None
                1 -> AutoRoleSelection.Single(candidates[0])
                else -> AutoRoleSelection.Multiple(candidates.map(::toBoundRole))
            }
        }

        /** 响应 Set-Cookie 头 → name=value 片段（attributes 截断丢弃，对齐 collectSetCookies）。 */
        fun setCookiePairs(response: Response): List<String> =
            response.headers("Set-Cookie").asSequence()
                .map { it.substringBefore(';').trim() }
                .filter { it.contains('=') && !it.startsWith("=") }
                .toList()

        /** 从 getUserGameRolesByCookie 响应 JSON 提取 list（供无网络单测复用）。 */
        fun boundRoleListFromJson(json: Json, raw: String): List<BoundGameRole> =
            json.decodeFromString<RolesEnvelope>(raw).data?.list.orEmpty()

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

/**
 * getUserGameRolesByCookie data.list 元素（全字段可空，设计红线 1）。
 * 实测（2026-09-28）响应另含 is_chosen/is_banned/unmask 字段，本应用不读取、不声明
 * （反序列化 ignoreUnknownKeys；如需透出再补）。avatar_url 自 V36/2 起声明并落盘到账户索引，
 * 但**不作为登录的前置条件**：服务端不下发时头像留空、UI 走占位。
 */
@Serializable
data class BoundGameRole(
    /** 业务线：原神 hk4e_cn / 绝区零 nap_cn（均为实测值） */
    @SerialName("game_biz") val gameBiz: String? = null,
    val region: String? = null,
    /** 角色 UID（对应卡片接口的 game_role_id） */
    @SerialName("game_uid") val gameUid: String? = null,
    val nickname: String? = null,
    /** 服务端本地化的区服名（天空岛/世界树/新艾利都）——提示文案直接用官方口径 */
    @SerialName("region_name") val regionName: String? = null,
    val level: Int? = null,
    /** 官方服=true / 渠道服=false（实测：世界树 cn_qd01 = false） */
    @SerialName("is_official") val isOfficial: Boolean? = null,
    /**
     * 角色头像 URL（V36/2 新增，供「我的」页账户行落盘）。
     * 🔴 全可空口径（设计红线 1）：这个键**按服务端下发与否**决定是否写入，
     * 不下发就是 null —— 判定逻辑不许因为缺字段而拒绝登录，也不要在 UI 侧当它必存在。
     */
    @SerialName("avatar_url") val avatarUrl: String? = null,
)

/**
 * 该绑定项是否属于原神。
 * 🔴 不写成 `gameBiz == ServerApi.GAME_BIZ`：设计红线 1 要求字段全可空，若服务端哪天不再
 * 下发 game_biz，严格判定会让**所有**账号都无法登录。故只排除"明确属于其他业务线"的项——
 * 匹配仍有 region 精确相等兜底（原神 cn_gf01/cn_qd01 与新游戏的 prod_* 体系不重叠），
 * 宽松判定的风险面仅限提示文案。
 */
internal fun BoundGameRole.isGenshinBinding(): Boolean =
    gameBiz == null || gameBiz == ServerApi.GAME_BIZ

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
internal class RolesEnvelope(
    val retcode: Int = 0,
    val message: String? = null,
    val data: RolesData? = null,
)

@Serializable
internal class RolesData(
    val list: List<BoundGameRole> = emptyList(),
)

@Serializable
private class BadgeLoginEnvelope(
    val data: BadgeLoginData? = null,
)

@Serializable
private class BadgeLoginData(
    val nickname: String? = null,
)
