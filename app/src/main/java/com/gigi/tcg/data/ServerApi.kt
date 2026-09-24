package com.gigi.tcg.data

/**
 * 国服接口端点常量，逐字符移植 servers.ts SERVER_API（对照原工程 api.lua，已实测）。
 * 两个服务器共用同一套端点，差异只在 badge_region / server 参数。
 */
object ServerApi {
    /** 账号/登录态接口的 game_biz */
    const val GAME_BIZ: String = "hk4e_cn"

    /** 七圣赛事 · 活动接口域名 */
    const val EVENT_ORIGIN: String = "https://hk4e-api.mihoyo.com"

    /** 七圣赛事 · 活动接口路径前缀 */
    const val EVENT_PATH_PREFIX: String = "/event/geniusinvokationtcg"

    /** 游戏记录接口域名（个人卡牌统计等） */
    const val RECORD_ORIGIN: String = "https://api-takumi-record.mihoyo.com"

    /** 游戏记录接口路径前缀 */
    const val RECORD_PATH_PREFIX: String = "/game_record/app/genshin/api"

    /** 登录态校验接口（完整 URL，含 game_biz 与 lang） */
    const val LOGIN_INFO_URL: String =
        "https://api-takumi.mihoyo.com/common/badge/v1/login/info?game_biz=hk4e_cn&lang=zh-cn"
}
