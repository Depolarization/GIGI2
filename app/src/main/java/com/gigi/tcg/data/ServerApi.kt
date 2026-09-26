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

    /** 运行时域名：优先 BuildConfig（BuildConfig 字段缺失/为空时回落 const 兜底）。 */
    private fun configured(value: String, fallback: String): String =
        runCatching { value }.getOrNull()?.takeIf { it.isNotBlank() } ?: fallback

    val eventOrigin: String get() = configured(BuildConfig.EVENT_ORIGIN, EVENT_ORIGIN)

    val recordOrigin: String get() = configured(BuildConfig.RECORD_ORIGIN, RECORD_ORIGIN)

    val badgeLoginUrl: String get() = configured(BuildConfig.BADGE_LOGIN_URL, BADGE_LOGIN_ACCOUNT_URL)

    /** 官方赛事页（登录引导入口） */
    val loginPageUrl: String get() = configured(BuildConfig.LOGIN_URL, EVENT_HOME_PAGE_URL)

    /** 登录态校验接口（完整 URL，lang 随界面语言）。默认简中仅为兼容旧调用点。
     *  TODO(F3)：调用点改为传 currentAppLanguage(ctx)。 */
    fun loginInfoUrl(lang: AppLanguage = AppLanguage.SimplifiedChinese): String =
        "$TAKUMI_ORIGIN/common/badge/v1/login/info?game_biz=$GAME_BIZ&lang=${lang.apiLangParam()}"
}
