package com.gigi.tcg.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive

/**
 * 逐字移植 web/src/types/api.ts：米哈游接口响应类型。
 * 所有字段按"可能缺失"建模，消费端必须带默认值读取。
 */

/** 米哈游接口统一外层结构：retcode == 0 才代表业务成功 */
@Serializable
data class MihoyoResponse<T>(
    @SerialName("retcode") val retcode: Int? = null,
    @SerialName("message") val message: String? = null,
    @SerialName("data") val data: T? = null,
)

/**
 * 登录态接口 common/badge/v1/login/info 的 data（原 App 仅消费 game_uid）。
 * 🔴 这里**没有**米游社社区 UID，需要社区维度数据时得另找来源（登录流程/用户提供），
 * 不能拿 gameUid 顶替。
 *
 * [region]：账号所持角色的区服（cn_gf01 / cn_qd01）。实测口径（2026-09-28 真机 + 原始返回）：
 * `e_hk4e_token` 是 **per-角色** 的，本接口返回值完全由 cookie 中所持 token 决定、
 * 与请求的 `badge_region` 参数无关——渠道服 token 恒返回渠道服角色（region=cn_qd01），
 * 官服 token 恒返回官服角色。旧版单槽凭据被收养为正式账户时必须以此校准服务器归属，
 * 不能用 currentServer（冷启动恒为默认官服），否则渠道服账户会被错标成官服账户。
 */
@Serializable
data class LoginInfoData(
    @SerialName("game_uid") val gameUid: String? = null,
    @SerialName("nickname") val nickname: String? = null,
    @SerialName("region") val region: String? = null,
)

/** 天梯/巅峰积分及变化量 */
@Serializable
data class ScoreChange(
    @SerialName("score") val score: Int? = null,
    @SerialName("score_change") val scoreChange: Int? = null,
)

/** 展示角色（玩家详情） */
@Serializable
data class RoleInfo(
    @SerialName("name") val name: String? = null,
    @SerialName("proficiency") val proficiency: Int? = null,
)

/** 参赛经历（玩家详情） */
@Serializable
data class EntryExperience(
    @SerialName("competition_name") val competitionName: String? = null,
    @SerialName("competition_result") val competitionResult: String? = null,
    @SerialName("score") val score: Int? = null,
)

/** 玩家主页 page_info（my_home_page / other_home_page 共用） */
@Serializable
data class PageInfo(
    @SerialName("nickname") val nickname: String? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null,
    @SerialName("ladder_score") val ladderScore: Int? = null,
    @SerialName("peak_score") val peakScore: Int? = null,
    @SerialName("is_shield") val isShield: Boolean? = null,
    @SerialName("roles") val roles: List<RoleInfo>? = null,
    @SerialName("entry_experience") val entryExperience: List<EntryExperience>? = null,
)

@Serializable
data class MyHomePageData(
    @SerialName("page_info") val pageInfo: PageInfo? = null,
)

@Serializable
data class OtherHomePageData(
    @SerialName("page_info") val pageInfo: PageInfo? = null,
)

/** 最近对局记录（get_game_records） */
@Serializable
data class GameRecord(
    @SerialName("nickname") val nickname: String? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null,
    @SerialName("result") val result: String? = null,
    @SerialName("trans_no") val transNo: String? = null,
    /** 服务端实测为字符串形式的秒级时间戳（如 "1786515594"），保持 String? 不做假设 */
    @SerialName("timestamp") val timestamp: String? = null,
    @SerialName("ladder_score") val ladderScore: ScoreChange? = null,
    @SerialName("peak_score") val peakScore: ScoreChange? = null,
)

@Serializable
data class GameRecordsData(
    @SerialName("game_records") val gameRecords: List<GameRecord>? = null,
)

/** 排行榜条目：巅峰榜取 peak_score，赛事榜取 score（两接口数据结构一致） */
@Serializable
data class RankInfo(
    @SerialName("nickname") val nickname: String? = null,
    /** api.ts 为 number | string（RankPage 消费时 String(info.uid ?? '')），用 JsonPrimitive 容错两种形态 */
    @SerialName("uid") val uid: JsonPrimitive? = null,
    @SerialName("peak_score") val peakScore: Int? = null,
    @SerialName("score") val score: Int? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null,
)

@Serializable
data class RankData(
    @SerialName("rank_infos") val rankInfos: List<RankInfo>? = null,
)

/**
 * 个人卡牌使用统计（game_record gcg/cardList）
 * 注意：该接口的 stats 不含头像字段——头像需另从 my_home_page 的
 * page_info.avatar_url 获取（见 CardStatsPage）。
 */
@Serializable
data class GcgStats(
    @SerialName("nickname") val nickname: String? = null,
    @SerialName("level") val level: Int? = null,
    @SerialName("avatar_card_num_gained") val avatarCardNumGained: Int? = null,
    @SerialName("action_card_num_gained") val actionCardNumGained: Int? = null,
    /** 图鉴总数（导出图胶囊 `角色牌 147/147` 的分母）；老接口无此字段 ⇒ null */
    @SerialName("avatar_card_num_total") val avatarCardNumTotal: Int? = null,
    @SerialName("action_card_num_total") val actionCardNumTotal: Int? = null,
)

@Serializable
data class GcgCard(
    @SerialName("name") val name: String? = null,
    @SerialName("card_type") val cardType: String? = null,
    @SerialName("use_count") val useCount: Int? = null,
    @SerialName("proficiency") val proficiency: Int? = null,
) {
    /** api.ts GcgCardType 联合类型字面量（服务端仍按 String? 容错接收） */
    object CardTypes {
        const val CHARACTER: String = "CardTypeCharacter"
        const val MODIFY: String = "CardTypeModify"
        const val ASSIST: String = "CardTypeAssist"
        const val EVENT: String = "CardTypeEvent"
    }
}

@Serializable
data class GcgCardListData(
    @SerialName("stats") val stats: GcgStats? = null,
    @SerialName("card_list") val cardList: List<GcgCard>? = null,
)

/**
 * 七圣召唤总手牌数（game_record gcg/basicInfo）：`*_card_num_total` 是**官方真实总手牌数**，
 * 导出图胶囊 `角色牌 143/147` 的分母以此为准。
 *
 * 为什么不用 cardList 的同名字段：cardList 实测从不返回 total（恒 null），且它的口径来自**会去重手牌**的
 * 图鉴数据（行动牌只数出 568 张，真实 941）⇒ 分母只能取本接口。
 * 全字段可空：服务端可能缺字段，解析不能因此崩。
 */
@Serializable
data class GcgBasicInfoData(
    @SerialName("nickname") val nickname: String? = null,
    @SerialName("level") val level: Int? = null,
    @SerialName("avatar_card_num_gained") val avatarCardNumGained: Int? = null,
    @SerialName("action_card_num_gained") val actionCardNumGained: Int? = null,
    /** 官方角色牌总数（实测 147） */
    @SerialName("avatar_card_num_total") val avatarCardNumTotal: Int? = null,
    /** 官方行动牌总数（实测 941） */
    @SerialName("action_card_num_total") val actionCardNumTotal: Int? = null,
)

/** 卡牌图鉴（米游社 Wiki，公开接口）：频道树节点 */
@Serializable
data class WikiChannelNode(
    @SerialName("id") val id: Int? = null,
    @SerialName("name") val name: String? = null,
    /** JSON 字符串，需二次解析，内含 filter 筛选定义 */
    @SerialName("ch_ext") val chExt: String? = null,
    @SerialName("children") val children: List<WikiChannelNode>? = null,
    /** 本节点下的卡牌条目列表 */
    @SerialName("list") val list: List<WikiCardEntry>? = null,
)

/** 卡牌条目 */
@Serializable
data class WikiCardEntry(
    @SerialName("content_id") val contentId: Int? = null,
    @SerialName("title") val title: String? = null,
    /** JSON 字符串，需二次解析，内含 c_{233|234|235}.filter.text 归属标签 */
    @SerialName("ext") val ext: String? = null,
    @SerialName("icon") val icon: String? = null,
)

/** 卡面详情基础信息模块（entry_page 的 components[0].data 二次解析结果） */
@Serializable
data class CardBasicInfo(
    @SerialName("name") val name: String? = null,
    @SerialName("common_img") val commonImg: String? = null,
    @SerialName("gold_img") val goldImg: String? = null,
)

/** Wiki 卡面详情模块（entry_page.page.modules[]） */
@Serializable
data class EntryPageModule(
    @SerialName("name") val name: String? = null,
    @SerialName("components") val components: List<EntryPageComponent>? = null,
)

/** 组件：data 为 JSON 字符串，需二次解析 */
@Serializable
data class EntryPageComponent(
    @SerialName("data") val data: String? = null,
)

/** Wiki 卡面详情（hoyowiki entry_page） */
@Serializable
data class EntryPageData(
    @SerialName("page") val page: EntryPage? = null,
)

@Serializable
data class EntryPage(
    @SerialName("modules") val modules: List<EntryPageModule>? = null,
)
