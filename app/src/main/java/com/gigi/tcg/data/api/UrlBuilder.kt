// 米哈游接口 URL 构造函数：逐字符移植 web/src/api/urls.ts。
//
// 服务器绑定的参数（badge_region / server）由调用方传入当前服务器标识符（data.ServerId）；
// 域名与 game_biz 对国服两个服务器完全一致，恒取 data.ServerApi 运行时访问器（BuildConfig 优先），
// 不允许在此硬编码 'cn_gf01' 等标识符或域名。
// lang 参数随界面语言（AppLanguage），默认参数简中仅为兼容既有调用点与单测；
// 业务调用点（data.repo.GigiRepository）传 LocaleStrings.currentLanguage()。

package com.gigi.tcg.data.api

import com.gigi.tcg.BuildConfig
import com.gigi.tcg.data.ServerApi
import com.gigi.tcg.data.ServerId
import com.gigi.tcg.i18n.AppLanguage
import com.gigi.tcg.i18n.apiLangParam
import java.net.URLEncoder

/** 官方赛事页（登录引导入口） */
val LOGIN_URL: String get() = ServerApi.loginPageUrl

/** 默认头像（头像加载失败时的回退）：主机走 ServerApi 访问器（BuildConfig.LOGIN_URL 的 origin 优先），路径是静态资源相对路径 */
val DEFAULT_AVATAR_URL: String get() = ServerApi.defaultAvatarUrl

/** 卡牌图鉴列表（米游社 Wiki，公开接口，与服务器无关） */
val CARD_INFO_URL: String get() = BuildConfig.CONTENT_LIST_URL

/** 卡牌图鉴详情（公开接口，与服务器无关）：lang 随当前界面语言（服务端认 zh-cn / zh-tw / en-us） */
fun cardDetailUrl(entryPageId: Int, lang: AppLanguage = AppLanguage.SimplifiedChinese): String =
    BuildConfig.WIKI_ENTRY_URL_TEMPLATE
        .replace("ENTRY_PAGE_ID", entryPageId.toString())
        .replace(Regex("lang=[^&]*"), "lang=${lang.apiLangParam()}")

/**
 * 米游社社区用户资料（公开接口、免鉴权，实测裸请求 retcode=0）：
 * 玩家「个性签名」取响应 `data.user_info.introduce`（🔴 字段名是 introduce，不是 signature）。
 *
 * 🔴 参数 [communityUid] 必须是**米游社社区 UID**（如 361655932），**不是**原神游戏内 9 位 UID
 * （如 261958214）：两者命名空间不同。用游戏 UID 打这里，服务端**照样返回 retcode=0**，
 * 但 nickname 退化成 `用户 <uid>`、introduce 退化成占位「暂无签名」——看着成功、其实没拿到签名。
 * 因此调用方必须先确认 UID 来源（本工程登录链路目前只有 game_uid，见
 * ui.screens.cardstats.CardStatsViewModel 的 resolveCommunityUid）。
 *
 * uid 走 URL 编码：它来自外部数据，不能直接拼进 query。
 */
fun getUserFullInfoUrl(communityUid: String): String =
    "${ServerApi.BBS_API_ORIGIN}/user/api/getUserFullInfo?uid=" +
        URLEncoder.encode(communityUid, "UTF-8")

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

/**
 * 七圣召唤总手牌数（需凭据）：与 [cardListUrl] 同主机、同鉴权口径（record 域只注入 Cookie、不发 DS），
 * 走同一通路即可，**不要**自行塞 DS/salt。
 *
 * 🔴 `role_id` 必须是**原神游戏内 9 位 UID**（如 261958214），不是米游社社区 UID（与
 * [getUserFullInfoUrl] 恰好相反，两者命名空间不同）。
 * 🔴 未登录时 HTTP 仍是 200，但 body 是 `{"retcode":10001,"message":"Please login"}`
 * （不是 404），由 Repository 的 retcode 判定折算成 ApiError。
 */
fun gcgBasicInfoUrl(uid: String, server: ServerId): String =
    "${ServerApi.recordOrigin}${ServerApi.RECORD_PATH_PREFIX}/gcg/basicInfo?" +
        "server=${server.id}&role_id=$uid"
