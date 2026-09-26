// 米哈游接口 URL 构造函数：逐字符移植 web/src/api/urls.ts。
//
// 服务器绑定的参数（badge_region / server）由调用方传入当前服务器标识符（data.ServerId）；
// 域名与 game_biz 对国服两个服务器完全一致，恒取 data.ServerApi 运行时访问器（BuildConfig 优先），
// 不允许在此硬编码 'cn_gf01' 等标识符或域名。
// lang 参数随界面语言（AppLanguage），默认简中仅为兼容既有调用点，
// TODO(F3)：MihoyoClient / GigiRepository 等调用点改为传 currentAppLanguage(ctx)。

package com.gigi.tcg.data.api

import com.gigi.tcg.BuildConfig
import com.gigi.tcg.data.ServerApi
import com.gigi.tcg.data.ServerId
import com.gigi.tcg.i18n.AppLanguage
import com.gigi.tcg.i18n.apiLangParam

/** 官方赛事页（登录引导入口） */
val LOGIN_URL: String get() = ServerApi.loginPageUrl

/** 默认头像（头像加载失败时的回退） */
const val DEFAULT_AVATAR_URL: String =
    "https://webstatic.mihoyo.com/upload/event/2023-05-16/7c0b9ec9dac9c3b75204bdebef3cb794_9053060040416908582.png"

/** 卡牌图鉴列表（米游社 Wiki，公开接口，与服务器无关） */
val CARD_INFO_URL: String get() = BuildConfig.CONTENT_LIST_URL

/** 卡牌图鉴详情（公开接口，与服务器无关）：lang 随当前界面语言（服务端认 zh-cn / zh-tw / en-us） */
fun cardDetailUrl(entryPageId: Int, lang: AppLanguage = AppLanguage.SimplifiedChinese): String =
    BuildConfig.WIKI_ENTRY_URL_TEMPLATE
        .replace("ENTRY_PAGE_ID", entryPageId.toString())
        .replace(Regex("lang=[^&]*"), "lang=${lang.apiLangParam()}")

/** 登录态 / game_uid 换取（需凭据）：lang 随当前界面语言 */
fun userInfoUrl(
    server: ServerId,
    lang: AppLanguage = AppLanguage.SimplifiedChinese,
): String = ServerApi.loginInfoUrl(lang)

/** 七圣赛事 · 活动接口（需凭据）：badge_region / game_biz 随服务器变化，lang 随界面语言 */
private fun eventEndpointUrl(
    server: ServerId,
    endpoint: String,
    query: String,
    lang: AppLanguage,
): String =
    "${ServerApi.eventOrigin}${ServerApi.EVENT_PATH_PREFIX}/$endpoint?" +
        "$query&badge_region=${server.id}&game_biz=${ServerApi.GAME_BIZ}&lang=${lang.apiLangParam()}"

/** 对局记录（需凭据） */
fun gameRecordsUrl(uid: String, server: ServerId, lang: AppLanguage = AppLanguage.SimplifiedChinese): String =
    eventEndpointUrl(server, "get_game_records", "badge_uid=$uid", lang)

/** 我的主页（需凭据） */
fun myHomePageUrl(uid: String, server: ServerId, lang: AppLanguage = AppLanguage.SimplifiedChinese): String =
    eventEndpointUrl(server, "my_home_page", "badge_uid=$uid", lang)

/** 他人主页（需凭据） */
fun otherHomePageUrl(
    code: String,
    myUid: String,
    server: ServerId,
    lang: AppLanguage = AppLanguage.SimplifiedChinese,
): String = eventEndpointUrl(server, "other_home_page", "code=$code&badge_uid=$myUid", lang)

/** 巅峰排行榜（需凭据） */
fun peakRankUrl(uid: String, server: ServerId, lang: AppLanguage = AppLanguage.SimplifiedChinese): String =
    eventEndpointUrl(server, "peak_rank", "page_size=999&page_token=&badge_uid=$uid", lang)

/** 赛事排行榜（需凭据） */
fun competitionRankUrl(uid: String, server: ServerId, lang: AppLanguage = AppLanguage.SimplifiedChinese): String =
    eventEndpointUrl(server, "rank", "page_size=999&page_token=&badge_uid=$uid", lang)

/** 个人卡牌使用统计（需凭据） */
fun cardListUrl(uid: String, server: ServerId): String =
    "${ServerApi.recordOrigin}${ServerApi.RECORD_PATH_PREFIX}/gcg/cardList?" +
        "limit=999&offset=0&server=${server.id}&role_id=$uid&need_action=true&need_avatar=true&need_stats=true"
