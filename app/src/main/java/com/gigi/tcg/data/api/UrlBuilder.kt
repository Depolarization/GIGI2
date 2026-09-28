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
 * 🔴 `role_id` 必须是**原神游戏内 9 位 UID**（如 261958214），不是米游社社区 UID（后者是
 * 另一个命名空间，两者不可混用）。
 * 🔴 未登录时 HTTP 仍是 200，但 body 是 `{"retcode":10001,"message":"Please login"}`
 * （不是 404），由 Repository 的 retcode 判定折算成 ApiError。
 */
fun gcgBasicInfoUrl(uid: String, server: ServerId): String =
    "${ServerApi.recordOrigin}${ServerApi.RECORD_PATH_PREFIX}/gcg/basicInfo?" +
        "server=${server.id}&role_id=$uid"

/**
 * record 域 gcg 端点的公共形状：与 [cardListUrl]/[gcgBasicInfoUrl] 同主机、同前缀、
 * 同鉴权口径（只注入 Cookie、不发 DS），查询参数恒为 `server` + `role_id`（真机实测）。
 */
private fun gcgRecordUrl(uid: String, server: ServerId, endpoint: String, extraQuery: String = ""): String =
    "${ServerApi.recordOrigin}${ServerApi.RECORD_PATH_PREFIX}/gcg/$endpoint?" +
        "server=${server.id}&role_id=$uid" +
        if (extraQuery.isEmpty()) "" else "&$extraQuery"

/**
 * 我的卡组（需凭据）：`gcg/deckList`。
 *
 * 🔴 实测（2026-09-28，`.task/p1-gcg-samples/FINDINGS.md` §0/§1）：除 `server`/`role_id` 外的参数
 * （`need_deck_detail` / `need_avatar` / `need_action`）**全部无效**——传与不传返回完全一致 ⇒ 不传，
 * 别按 web SDK 的签名"顺手补齐"。
 */
fun gcgDeckListUrl(uid: String, server: ServerId): String = gcgRecordUrl(uid, server, "deckList")

/**
 * 已收集卡背（需凭据）：`gcg/cardBackList`。
 *
 * 🔴 实测返回**全部**卡背（含未收集），靠 `has_obtained` 区分（样本 28 张 = 25 已得 / 3 未得）⇒
 * 无"只回已收集"的开关参数，置灰逻辑读字段即可。
 */
fun gcgCardBackListUrl(uid: String, server: ServerId): String = gcgRecordUrl(uid, server, "cardBackList")

/**
 * 最近对局 + 收藏对局（需凭据）：`gcg/matchList`。
 *
 * 🔴 实测 `limit` / `offset` / `need_favourite` **全部无效**（传 20 / 50 / 不传都返回同样条数）⇒ 不要加。
 * 收藏列表在未收藏任何对局时恒 `[]`，消费端必须容忍空。
 */
fun gcgMatchListUrl(uid: String, server: ServerId): String = gcgRecordUrl(uid, server, "matchList")

/**
 * 胜冠之试旬列表（需凭据）：`gcg/challenge/schedule`。实测按 id 倒序（最新在前）。
 * 返回的 `schedule_list[].id` 即 [gcgChallengeRecordUrl] 的 `scheduleId` 入参。
 */
fun gcgChallengeScheduleUrl(uid: String, server: ServerId): String = gcgRecordUrl(uid, server, "challenge/schedule")

/**
 * 单旬战绩（需凭据）：`gcg/challenge/record`。
 *
 * 🔴 参数名是 **`schedule_id`**——不是 `season_id`。传错时服务端不回 404、也不回空数据，而是
 * `retcode=-1` + `"param schedule_id error: value must be greater than 0"`（实测），
 * 该 retcode 落在 RETRYABLE 集合内 ⇒ 会白白重试一次，参数名写错时更难归因，故在此钉死。
 * 旬 id 来自 [gcgChallengeScheduleUrl] 的 `schedule_list[].id`。
 */
fun gcgChallengeRecordUrl(uid: String, server: ServerId, scheduleId: Int): String =
    gcgRecordUrl(uid, server, "challenge/record", "schedule_id=$scheduleId")
