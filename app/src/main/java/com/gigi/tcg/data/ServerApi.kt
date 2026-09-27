package com.gigi.tcg.data

import com.gigi.tcg.BuildConfig
import com.gigi.tcg.i18n.AppLanguage
import com.gigi.tcg.i18n.apiLangParam

/**
 * 国服接口端点常量，逐字符移植 servers.ts SERVER_API（对照原工程 api.lua，已实测）。
 * 两个服务器共用同一套端点，差异只在 badge_region / server 参数。
 *
 * 域名**事实源是 [BuildConfig]**（构建期可覆盖/镜像化）；本对象的 const 仅作
 * 纯 JVM 单测环境下的兜底字面量，运行时代码一律走 `xxxOrigin` / `badgeLoginUrl()` 等访问器。
 */
object ServerApi {
    /** 账号/登录态接口的 game_biz */
    const val GAME_BIZ: String = "hk4e_cn"

    /** 七圣赛事 · 活动接口路径前缀 */
    const val EVENT_PATH_PREFIX: String = "/event/geniusinvokationtcg"

    /** 游戏记录接口路径前缀 */
    const val RECORD_PATH_PREFIX: String = "/game_record/app/genshin/api"

    /** 兜底：七圣赛事 · 活动接口域名。运行时请用 [eventOrigin]。 */
    const val EVENT_ORIGIN: String = "https://hk4e-api.mihoyo.com"

    /** 兜底：游戏记录接口域名（个人卡牌统计等）。运行时请用 [recordOrigin]。 */
    const val RECORD_ORIGIN: String = "https://api-takumi-record.mihoyo.com"

    /** 兜底：登录态校验接口完整 URL（lang 写死简中）。运行时请用 [loginInfoUrl]。 */
    const val LOGIN_INFO_URL: String =
        "https://api-takumi.mihoyo.com/common/badge/v1/login/info?game_biz=hk4e_cn&lang=zh-cn"

    /** 兜底：登录态校验接口域名（api-takumi 主站）。 */
    const val TAKUMI_ORIGIN: String = "https://api-takumi.mihoyo.com"

    /** 兜底：徽章/账号登录接口完整 URL（BuildConfig.BADGE_LOGIN_URL 的回落值）。 */
    const val BADGE_LOGIN_ACCOUNT_URL: String =
        "https://api-takumi.mihoyo.com/common/badge/v1/login/account"

    /** 兜底：官方赛事页 H5（BuildConfig.LOGIN_URL 的回落值）。 */
    const val EVENT_HOME_PAGE_URL: String = "https://webstatic.mihoyo.com/ys/event/tcgmatch/index.html#/homePage"

    /** 兜底：米游社静态内容主机（卡牌图鉴列表/详情共用）。 */
    const val CONTENT_ORIGIN: String = "https://act-api-takumi-static.mihoyo.com"

    /**
     * 米游社社区接口主机（社区用户资料 = 玩家个性签名来源）。
     * 与 takumi/mihoyo 系不同主机，BuildConfig 暂无对应字段 ⇒ 只有 const（不参与构建期覆盖）。
     */
    const val BBS_API_ORIGIN: String = "https://bbs-api.miyoushe.com"

    /** 默认头像的静态资源路径（主机随 [loginPageUrl] 的 origin 收敛，两者同属 webstatic 静态资源域）。 */
    private const val DEFAULT_AVATAR_PATH: String =
        "/upload/event/2023-05-16/7c0b9ec9dac9c3b75204bdebef3cb794_9053060040416908582.png"

    /** 兜底：默认头像完整 URL（BuildConfig.LOGIN_URL 缺省/为空时的回落）。运行时请用 [defaultAvatarUrl]。 */
    const val DEFAULT_AVATAR_URL: String = "https://webstatic.mihoyo.com" + DEFAULT_AVATAR_PATH

    /** 运行时域名：优先 BuildConfig（BuildConfig 字段缺失/为空时回落 const 兜底）。 */
    private fun configured(value: String, fallback: String): String =
        runCatching { value }.getOrNull()?.takeIf { it.isNotBlank() } ?: fallback

    /**
     * 提取 URL 的 origin（scheme://host[:port]，丢弃路径/查询/锚点），纯字符串运算 ⇒ JVM 单测可直调。
     * 无 `://` 视为裸主机并补 https。
     */
    internal fun originOf(url: String): String {
        val schemeSep = url.indexOf("://")
        val scheme = if (schemeSep >= 0) url.substring(0, schemeSep) else "https"
        val authority = (if (schemeSep >= 0) url.substring(schemeSep + 3) else url)
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')
        return "$scheme://$authority"
    }

    val eventOrigin: String get() = configured(BuildConfig.EVENT_ORIGIN, EVENT_ORIGIN)

    val recordOrigin: String get() = configured(BuildConfig.RECORD_ORIGIN, RECORD_ORIGIN)

    val badgeLoginUrl: String get() = configured(BuildConfig.BADGE_LOGIN_URL, BADGE_LOGIN_ACCOUNT_URL)

    /** 官方赛事页（登录引导入口） */
    val loginPageUrl: String get() = configured(BuildConfig.LOGIN_URL, EVENT_HOME_PAGE_URL)

    /** 默认头像：主机随 [loginPageUrl]（BuildConfig 优先）收敛，路径为静态资源相对路径；解析异常回落 [DEFAULT_AVATAR_URL]。 */
    val defaultAvatarUrl: String
        get() = runCatching { originOf(loginPageUrl) + DEFAULT_AVATAR_PATH }.getOrDefault(DEFAULT_AVATAR_URL)

    /** 登录态校验接口（完整 URL，lang 随界面语言）。默认简中仅为兼容旧调用点。 */
    fun loginInfoUrl(lang: AppLanguage = AppLanguage.SimplifiedChinese): String =
        "$TAKUMI_ORIGIN/common/badge/v1/login/info?game_biz=$GAME_BIZ&lang=${lang.apiLangParam()}"
}
