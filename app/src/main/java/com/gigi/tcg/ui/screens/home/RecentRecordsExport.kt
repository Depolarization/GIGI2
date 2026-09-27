// 「最近对局」导出卡片图的内容组装（V26，用户反馈「首页那个最近对局的布局写得很好，
// 导出图片也省得用户去截屏」；V27 追加个人信息卡的规格）。本文件只做字符串与算术，
// 🔴 不得引用 android.graphics —— 渲染在 ui/export/RecentRecordsCardRenderer.kt
// （版式照抄 HomeRoute.ProfileCard / RecordItem）。
// 分界线是为了让字段口径/退化规则能在纯 JVM 单测里覆盖（工程无 Robolectric）。

package com.gigi.tcg.ui.screens.home

import androidx.annotation.StringRes
import com.gigi.tcg.R
import com.gigi.tcg.data.model.GameRecord
import com.gigi.tcg.data.model.PageInfo
import com.gigi.tcg.domain.UNKNOWN_OPPONENT_UID
import com.gigi.tcg.domain.TierStars
import com.gigi.tcg.domain.extractOpponentUid
import com.gigi.tcg.domain.formatRecordTime
import com.gigi.tcg.domain.formatScoreChange
import com.gigi.tcg.domain.getTierStars
import com.gigi.tcg.i18n.LocaleStrings

/**
 * 文案通道：本文件是**纯函数层**（JVM 单测直接调 buildRecentRecordsCards），拿不到 Compose 的
 * stringResource。故走 [LocaleStrings.getOrDefault]：有 resolver 时按当前语言取资源，
 * 没有（纯 JVM 测试）时回落到这里的中文默认值。
 * 🔴 默认值必须与 values/strings.xml 里的同 id 文案逐字一致。
 */
private fun exportText(@StringRes id: Int, default: String): String =
    LocaleStrings.getOrDefault(id, default)

/** 服务端一次最多返回 10 条，导出同样封顶 10（渲染器画布按它预留行高） */
const val RECENT_RECORDS_MAX_CARDS = 10

/** 胜负语义色枚举：数据层不放 ARGB（保持无 android 引用），由渲染器映射定版色值 */
enum class ResultColorType { Win, Lose, Neutral }

/** 一行「前缀 + 积分 + 变化量」的拆分形态，供渲染器双色绘制（前缀正文色、变化量语义色）。
 *  null = 该模式无积分（整对缺失/全零），渲染器画占位符，对齐首页 home_peak_placeholder 语义 */
data class ScoreLine(
    @StringRes val prefixId: Int,
    val score: Int,
    val change: Int,
)

/**
 * 一张导出卡片 = 首页 RecordItem 的可绘制镜像。
 * 注意：卡片上的昵称/头像是**对手**的（解析口径见 GameRecordsParseTest），
 * 「我」的昵称不进卡片，与首页一致。
 */
data class RecentRecordsCard(
    val avatarUrl: String?,
    val nickname: String,
    /** 对手 UID；解析失败为 null，渲染画占位符而不是 "unknown" 字样 */
    val opponentUid: String?,
    /** 对局时间，formatRecordTime 口径（跨年自动补两位年份前缀） */
    val timeText: String,
    val resultDisplay: String,
    val resultColor: ResultColorType,
    val ladder: ScoreLine?,
    val peak: ScoreLine?,
)

/** 内容超过 [RECENT_RECORDS_MAX_CARDS] 时按首页顺序截取前 10 条 */
fun buildRecentRecordsCards(
    records: List<GameRecord>,
    uid: String,
): List<RecentRecordsCard> = records.take(RECENT_RECORDS_MAX_CARDS).map { record ->
    val opponentUid = extractOpponentUid(record.transNo, uid)
    RecentRecordsCard(
        avatarUrl = record.avatarUrl,
        nickname = record.nickname ?: exportText(R.string.common_unknown, "未知"),
        opponentUid = opponentUid.takeUnless { it == UNKNOWN_OPPONENT_UID },
        timeText = formatRecordTime(record.timestamp),
        resultDisplay = resultName(record.result),
        resultColor = when (record.result) {
            "Win" -> ResultColorType.Win
            "Lose" -> ResultColorType.Lose
            else -> ResultColorType.Neutral
        },
        ladder = scoreLine(record.ladderScore?.score, record.ladderScore?.scoreChange, R.string.home_ladder_prefix),
        peak = scoreLine(record.peakScore?.score, record.peakScore?.scoreChange, R.string.home_peak_prefix),
    )
}

/**
 * 个人信息卡的绘制规格 = 首页 ProfileCard 的可绘制镜像（V27：导出图整页复刻首页）。
 * [tierColorArgb] 是 ARGB Int 而非 Compose Color：段位色由 C 路的 @Composable
 * `tierColor(tier)` 在组合上下文解析后灌入——纯函数层拿不到组合上下文，
 * 也只能放 Int（本文件 🔴 不得引用 android.graphics / compose.ui）。
 */
data class ProfileCardSpec(
    val avatarUrl: String?,
    val nickname: String,
    val uid: String,
    /** tierLabel(getTierStars(ladderScore)) 口径（纯函数层走 [tierDisplayText] 同口径映射） */
    val tierText: String,
    val tierColorArgb: Int,
    val ladderScore: Int,
    val peakScore: Int,
)

/**
 * PageInfo + 会话 UID → 个人信息规格，口径照 HomeRoute.ProfileCard：
 * 昵称缺失回落 common_unknown；段位文本见 [tierDisplayText]；分数缺失按 0（首页同款）。
 */
fun buildProfileCardSpec(pageInfo: PageInfo, sessionUid: String, tierColorArgb: Int): ProfileCardSpec =
    ProfileCardSpec(
        avatarUrl = pageInfo.avatarUrl,
        nickname = pageInfo.nickname ?: exportText(R.string.common_unknown, "未知"),
        uid = sessionUid,
        tierText = tierDisplayText(getTierStars(pageInfo.ladderScore ?: 0)),
        tierColorArgb = tierColorArgb,
        ladderScore = pageInfo.ladderScore ?: 0,
        peakScore = pageInfo.peakScore ?: 0,
    )

/**
 * 段位文本的三语通道，逐字对齐 ui/components/tierLabel（StateViews 的 TIER_BY_NAME 是
 * private，纯函数层只能同口径重建映射）：接口中文段位名 → 当前语言资源，
 * 查不到回落原始值；★ 星缀语义同 domain formatTier（0 星不显示）；无段位画 home_tier_none。
 */
private val TIER_NAME_RES = mapOf(
    "黄铜" to R.string.tier_brass, "Brass" to R.string.tier_brass,
    "星银" to R.string.tier_silver, "Silver" to R.string.tier_silver,
    "赤金" to R.string.tier_gold, "Gold" to R.string.tier_gold,
    "影幻" to R.string.tier_phantom, "Phantom" to R.string.tier_phantom,
)

internal fun tierDisplayText(t: TierStars): String {
    if (t.tier.isEmpty()) return exportText(R.string.home_tier_none, "无段位")
    val name = TIER_NAME_RES[t.tier]?.let { exportText(it, t.tier) } ?: t.tier
    return if (t.stars > 0) name + "★".repeat(t.stars) else name
}

/** 积分行：与首页 `"$prefix $score $change"` 同口径；整对 0/缺失 → null（渲染画占位符） */
private fun scoreLine(score: Int?, change: Int?, @StringRes prefixId: Int): ScoreLine? {
    val s = score ?: 0
    val c = change ?: 0
    if (s == 0 && c == 0) return null
    return ScoreLine(prefixId, s, c)
}

/** 胜负：逐字对齐列表页 home_result_win / lose / none */
private fun resultName(result: String?): String = when (result) {
    "Win" -> exportText(R.string.home_result_win, "胜")
    "Lose" -> exportText(R.string.home_result_lose, "负")
    else -> exportText(R.string.home_result_none, "空")
}

/** 变化量文本（formatScoreChange 的 `(n)` 形态），渲染器与单测共用，保证口径唯一 */
internal fun scoreChangeText(change: Int): String = formatScoreChange(change)
