package com.gigi.tcg.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
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

/**
 * 年月日时分秒对象（服务端把时间拆成 6 个 int 下发，不给时间戳）。
 * 复用方：`matchList.match_time`、`challenge/schedule` 的 `begin`/`end`、
 * `challenge/record` 内联的 `basic.schedule`——实测三处**同构**，共用本类。
 */
@Serializable
data class GcgTime(
    @SerialName("year") val year: Int? = null,
    @SerialName("month") val month: Int? = null,
    @SerialName("day") val day: Int? = null,
    @SerialName("hour") val hour: Int? = null,
    @SerialName("minute") val minute: Int? = null,
    @SerialName("second") val second: Int? = null,
)

/** 我的卡组（game_record gcg/deckList） */
@Serializable
data class GcgDeckListData(
    @SerialName("deck_list") val deckList: List<GcgDeck>? = null,
    @SerialName("level") val level: Int? = null,
    @SerialName("nickname") val nickname: String? = null,
    @SerialName("role_id") val roleId: String? = null,
)

/**
 * 一副牌组。键集合实测恒为 `{id, name, is_valid, share_code, avatar_cards, action_cards}`
 * （deckList 11/11、challengeRecord 39/39 逐字一致）⇒ [GcgChallengeDeck.deck] 直接复用本类。
 */
@Serializable
data class GcgDeck(
    /** 牌组 id：实测 1..11 不连续、按创建序，不能当索引或假定连续 */
    @SerialName("id") val id: Int? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("is_valid") val isValid: Boolean? = null,
    /** 分享码（约 72 字符 base64 样串），「复制牌组」的唯一可用出口 */
    @SerialName("share_code") val shareCode: String? = null,
    /** 角色牌（实测恒 3 张） */
    @SerialName("avatar_cards") val avatarCards: List<GcgDeckCard>? = null,
    /** 行动牌（实测 22–25 张） */
    @SerialName("action_cards") val actionCards: List<GcgDeckCard>? = null,
)

/**
 * 牌组内的一张卡：`avatar_cards` 与 `action_cards` **同构**（20 键完全一致），共用本类。
 *
 * 🔴 只建模本页要消费的字段。样本里另有 desc/tags/card_skills/card_sources/deck_recommend/
 * card_wiki/icon/large_icon/rank_id/build_num 未建模——它们与已接入的 cardList 卡牌对象同源、
 * 本页用不到；生产 Json 配置 `ignoreUnknownKeys = true`（见 di.AppContainer），多余键不影响解析。
 */
@Serializable
data class GcgDeckCard(
    /** 卡牌 id：角色牌 4 位（1407）、行动牌 6 位（214071） */
    @SerialName("id") val id: Int? = null,
    @SerialName("name") val name: String? = null,
    /** 卡面图 URL，已带 `x-resource-process` 缩放参数，直取即可 */
    @SerialName("image") val image: String? = null,
    @SerialName("card_type") val cardType: String? = null,
    /** 牌组内张数 */
    @SerialName("num") val num: Int? = null,
    /** 角色牌生命；行动牌实测恒 0 */
    @SerialName("hp") val hp: Int? = null,
    @SerialName("proficiency") val proficiency: Int? = null,
    @SerialName("use_count") val useCount: Int? = null,
    @SerialName("category") val category: String? = null,
    /** 元素骰消耗：角色牌实测恒 [] */
    @SerialName("action_cost") val actionCost: List<GcgActionCost>? = null,
)

/** 一种骰子消耗，如 `{CostTypeElectro, 3}` / `{CostTypeEnergy, 2}` */
@Serializable
data class GcgActionCost(
    @SerialName("cost_type") val costType: String? = null,
    @SerialName("cost_value") val costValue: Int? = null,
)

/** 卡背收集（game_record gcg/cardBackList） */
@Serializable
data class GcgCardBackListData(
    /** 🔴 实测返回**全部**卡背（含未收集），靠 [GcgCardBack.hasObtained] 区分 */
    @SerialName("card_back_list") val cardBackList: List<GcgCardBack>? = null,
)

@Serializable
data class GcgCardBack(
    /** 0 是默认卡背 */
    @SerialName("id") val id: Int? = null,
    /** 旧格式图：实测 28 份里只有 21 份有 ⇒ 不可靠 */
    @SerialName("image") val image: String? = null,
    /** 新格式图：28/28 全有 ⇒ 优先用它 */
    @SerialName("image_v2") val imageV2: String? = null,
    @SerialName("has_obtained") val hasObtained: Boolean? = null,
    @SerialName("category") val category: String? = null,
)

/** 最近对局 + 收藏对局（game_record gcg/matchList） */
@Serializable
data class GcgMatchListData(
    @SerialName("recent_matches") val recentMatches: List<GcgMatch>? = null,
    /** 实测未收藏时恒 []；结构按同响应兄弟字段的常规设计与 recent 同构（尚未取到非空样本） */
    @SerialName("favourite_matches") val favouriteMatches: List<GcgMatch>? = null,
)

@Serializable
data class GcgMatch(
    /** 🔴 实测是**字符串**（"10"），不是数字 ⇒ 类型必须 String?，改成 Int? 会解析崩 */
    @SerialName("game_id") val gameId: String? = null,
    @SerialName("is_win") val isWin: Boolean? = null,
    /** 实测为中文赛名（"胜冠之试"），服务端本地化字段，别当枚举判定 */
    @SerialName("match_type") val matchType: String? = null,
    @SerialName("match_time") val matchTime: GcgTime? = null,
    @SerialName("self") val self: GcgMatchSide? = null,
    @SerialName("opposite") val opposite: GcgMatchSide? = null,
)

/** 对局一方。🔴 Match 没有卡组名/卡组 id，只有 3 张角色牌头像 URL */
@Serializable
data class GcgMatchSide(
    @SerialName("name") val name: String? = null,
    /**
     * 🔴 键名照抄接口的拼写错误 `linups`（正确拼写是 lineups）——实测两服样本一致，
     * 改成 lineups 会静默解析成 null。
     */
    @SerialName("linups") val linups: List<String>? = null,
    /** 阵容是否被服务端折叠 */
    @SerialName("is_overflow") val isOverflow: Boolean? = null,
)

/** 胜冠之试旬列表（game_record gcg/challenge/schedule） */
@Serializable
data class GcgChallengeScheduleData(
    /** 实测按 id 倒序（最新在前） */
    @SerialName("schedule_list") val scheduleList: List<GcgSchedule>? = null,
)

@Serializable
data class GcgSchedule(
    /** 旬 id = challenge/record 的 schedule_id 入参 */
    @SerialName("id") val id: Int? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("begin") val begin: GcgTime? = null,
    @SerialName("end") val end: GcgTime? = null,
)

/** 单旬战绩（game_record gcg/challenge/record） */
@Serializable
data class GcgChallengeRecordData(
    @SerialName("basic") val basic: GcgChallengeBasic? = null,
    @SerialName("deck_list") val deckList: List<GcgChallengeDeck>? = null,
    /**
     * 🔴 11 份样本恒为 `[]` ⇒ 元素结构未知，用 [JsonElement] 无损保留（不消费），
     * 拿到真实样本再展开成具体类型。声明成 Any? 会让 kotlinx.serialization 直接报错。
     */
    @SerialName("honor_character") val honorCharacter: List<JsonElement>? = null,
    @SerialName("recommend_url") val recommendUrl: String? = null,
)

@Serializable
data class GcgChallengeBasic(
    /** 内联的完整旬对象（含 id/name/begin/end），无需再回查 schedule 列表 */
    @SerialName("schedule") val schedule: GcgSchedule? = null,
    @SerialName("nickname") val nickname: String? = null,
    @SerialName("uid") val uid: String? = null,
    @SerialName("win_cnt") val winCnt: Int? = null,
    /** 奖牌图 URL（challenge_medal_{0..3}.png） */
    @SerialName("medal") val medal: String? = null,
    /** 该旬是否有战绩；false 时 [GcgChallengeRecordData.deckList] 为空数组 */
    @SerialName("has_data") val hasData: Boolean? = null,
)

@Serializable
data class GcgChallengeDeck(
    /** 与 deckList 的 [GcgDeck] 完全同构（跨 9 份样本 39/39 键集合逐字相同）⇒ 复用同一类 */
    @SerialName("deck") val deck: GcgDeck? = null,
    /** 该牌组在该旬的胜场（实测取值 {0,1,2,3}） */
    @SerialName("win_cnt") val winCnt: Int? = null,
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
