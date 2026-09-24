// 米哈游接口 URL 构造函数：逐字符移植 web/src/api/urls.ts。
//
// 服务器绑定的参数（badge_region / server）由调用方传入当前服务器标识符（data.ServerId）；
// 域名与 game_biz 对国服两个服务器完全一致，恒取 data.ServerApi 常量（servers.ts SERVER_API），
// 不允许在此硬编码 'cn_gf01' 等标识符或域名。

package com.gigi.tcg.data.api

import com.gigi.tcg.data.ServerApi
import com.gigi.tcg.data.ServerId

/** 官方赛事页（登录引导入口） */
const val LOGIN_URL: String = "https://webstatic.mihoyo.com/ys/event/tcgmatch/index.html#/homePage"

/** 默认头像（头像加载失败时的回退） */
const val DEFAULT_AVATAR_URL: String =
    "https://webstatic.mihoyo.com/upload/event/2023-05-16/7c0b9ec9dac9c3b75204bdebef3cb794_9053060040416908582.png"

/** 卡牌图鉴列表（米游社 Wiki，公开接口，与服务器无关） */
const val CARD_INFO_URL: String =
    "https://act-api-takumi-static.mihoyo.com/common/blackboard/ys_obc/v1/home/content/list?app_sn=ys_obc&channel_id=231"

/** 卡牌图鉴详情（公开接口，与服务器无关） */
fun cardDetailUrl(entryPageId: Int): String =
    "https://act-api-takumi-static.mihoyo.com/hoyowiki/genshin/wapi/entry_page?app_sn=ys_obc&entry_page_id=$entryPageId&lang=zh-cn"

/** 登录态 / game_uid 换取（需凭据） */
fun userInfoUrl(server: ServerId): String = ServerApi.LOGIN_INFO_URL

/** 七圣赛事 · 活动接口（需凭据）：badge_region / game_biz 随服务器变化 */
private fun eventEndpointUrl(server: ServerId, endpoint: String, query: String): String =
    "${ServerApi.EVENT_ORIGIN}${ServerApi.EVENT_PATH_PREFIX}/$endpoint?$query&badge_region=${server.id}&game_biz=${ServerApi.GAME_BIZ}&lang=zh-cn"

/** 对局记录（需凭据） */
fun gameRecordsUrl(uid: String, server: ServerId): String =
    eventEndpointUrl(server, "get_game_records", "badge_uid=$uid")

/** 我的主页（需凭据） */
fun myHomePageUrl(uid: String, server: ServerId): String =
    eventEndpointUrl(server, "my_home_page", "badge_uid=$uid")

/** 他人主页（需凭据） */
fun otherHomePageUrl(code: String, myUid: String, server: ServerId): String =
    eventEndpointUrl(server, "other_home_page", "code=$code&badge_uid=$myUid")

/** 巅峰排行榜（需凭据） */
fun peakRankUrl(uid: String, server: ServerId): String =
    eventEndpointUrl(server, "peak_rank", "page_size=999&page_token=&badge_uid=$uid")

/** 赛事排行榜（需凭据） */
fun competitionRankUrl(uid: String, server: ServerId): String =
    eventEndpointUrl(server, "rank", "page_size=999&page_token=&badge_uid=$uid")

/** 个人卡牌使用统计（需凭据） */
fun cardListUrl(uid: String, server: ServerId): String =
    "${ServerApi.RECORD_ORIGIN}${ServerApi.RECORD_PATH_PREFIX}/gcg/cardList?limit=999&offset=0&server=${server.id}&role_id=$uid&need_action=true&need_avatar=true&need_stats=true"
