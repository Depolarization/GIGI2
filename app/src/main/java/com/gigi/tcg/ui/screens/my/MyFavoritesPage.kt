// 收藏对局（设计 §3.3）：gcg/matchList 的 favourite_matches 列表。
// 🔴 实测该字段在未收藏任何一局时**恒为 []**（两份样本都是），空态是正常状态、不是错误。
// 对局对象没有卡组名/卡组 id，只有若干角色牌头像 URL（键名是接口拼错的 `linups`，勿改）。
// 胜负色走 LocalSemanticColors（设计红线 8：胜/负语义色固定，不参与动态取色、不新造 hex）。
// 🔴 V39-H2：语义色**只作前景**；本轮（V39-G1）徽标方块整块删掉，胜负改成中间列顶部的
// 前景文字，`background(semantic.*)` 依旧一次都不许出现。
// V39-G1：照官方应用截图改成**三段式**——左右各一「头像簇 + 昵称」竖向 Column（昵称在簇正下方），
// 中间独立一列 = 胜负 / 模式 / 时间 三行全部水平居中。
// V39-H1（修 G1 的两个真机缺陷）：
//   ① 中轴恒定——G1 两侧各按本侧簇宽摆列，中间列 weight(1f) 的剩余空间左右不对称，中轴随
//     1v1/3v3/4v4 每档漂 20px。改为两侧列**同宽** = max(己方簇宽, 对手簇宽)：两侧等宽 ⇒ 中间列
//     左右剩余必然相等 ⇒ 三行中轴恒等于卡内几何中心。
//   ② 头像重叠降档——32dp 头像沿用 18dp 重叠（56%）时白边互相切割糊成一团；且 32/12 的 3 人簇
//     72dp 破 R4「3 人簇 ≤ 内容宽 20%」上限（67.2dp），故头像 32→30、重叠 18→12（40%）。
// V39-H2（真机复验后用户仍不满意的两点）：
//   ③ 簇贴边——H1 的侧列内容是 CenterHorizontally，人数不等时小簇在等宽列里居中，整簇漂离
//     该侧卡内边距（实测 2v4 卡我方簇左缘 x=137 而非 84）。两侧列内容改为按侧靠边
//     （己方 Start / 对手 End），见下方 MatchRow 的推理链。
//   ④ 重叠再降——用户要求「再适当减少」，取 28/9（32.1%）：簇宽 28/47/66/85，
//     3 人簇 66dp = 19.6% 仍守住 R4 ≤20%（见常量注释里的取舍算式）。
// 尺寸口径一律抽成本文件的 *_DP 常量：那套占比是拿真机密度算出来的，
// 写成散落的字面量就没法被单测钉住，改一处就悄悄漂出官方的横向预算。

package com.gigi.tcg.ui.screens.my

import android.app.Application
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gigi.tcg.R
import com.gigi.tcg.data.model.GcgMatch
import com.gigi.tcg.ui.components.Avatar
import com.gigi.tcg.ui.components.EmptyState
import com.gigi.tcg.ui.theme.LocalSemanticColors
import kotlin.math.max
import kotlin.math.min

// ─────────── 三段式布局尺寸口径（单位 dp；取整数值好让 JVM 单测直接算占比）───────────

/** 真机基准：Redmi Note 7 = 1080px / 440dpi ⇒ 密度 2.75 ⇒ 屏宽 392dp */
internal const val SCREEN_WIDTH_DP = 392

/** 卡片内边距维持 12dp：官方量出来左右两簇加中间列在 336dp 内容宽里正好摊开，别动 */
internal const val CARD_SIDE_PADDING_DP = 12

/** 头像 32dp：官方实测 27.6dp。V39-H3 终调再放大到 32（1.16×，仍在「略微调大」的 1.0~1.25 带内），
 *  目的是让每张脸在缩小重叠后仍留得住 24dp 可见弧（28/9 时只有 19dp），落实用户「重叠是为了确保
 *  信息清晰」。为何不再往上：33dp 起 4v4 的中间列压到 104dp，逼近时间串实测 101.8dp，余量太薄 */
internal const val LINEUP_AVATAR_SIZE_DP = 32

/** 叠压量 8dp（25.0%）：官方 68% 是配 27.6dp 小图的，放大后深叠白边互切糊成一团（V39-H1 真机）；
 *  H2 降到 9/32.1%，H3 随头像放大同步收到 8/25.0%——**绝对叠压量与比率双降**，不是只降比率。
 *  步距 24 ⇒ 簇宽 32/56/80/104，3v3（主场景）簇 80dp = 内容宽 23.8%，
 *  4v4 最坏情况中间列 336−104×2−16 = 112dp ≥ 110（时间行实测 101.8dp）✓ */
internal const val LINEUP_AVATAR_OVERLAP_DP = 8

/** 簇宽按 4 张封顶：官方 1~4 人不等（用户明确），旧版 3 张封顶会把四人局最后一张吞掉 */
internal const val LINEUP_MAX_AVATARS = 4

/** 4 张封顶簇宽 = size + (n-1)*(size-overlap) = 104dp；3 张时 80dp = 内容宽 336dp 的 23.8%
 *  （H3 按用户「大多数是 3v3，优先考虑 3v3」把 R4 占比上限从 20% 放宽到 24% 换头像尺寸）*/
internal const val LINEUP_CLUSTER_WIDTH_DP =
    LINEUP_AVATAR_SIZE_DP + (LINEUP_MAX_AVATARS - 1) * (LINEUP_AVATAR_SIZE_DP - LINEUP_AVATAR_OVERLAP_DP)

/** 头像间的细白边：重叠后唯一的分层线索，画在每张头像自己身上（不是簇的分隔线） */
internal const val LINEUP_AVATAR_STROKE_DP = 1

/** 中间列高 = titleMedium 24 + 间距 2*2 + labelMedium 16 + bodySmall 16 = 60，整行高度由它决定；
 *  侧列高 = 头像 32 + 间距 2 + 昵称 18 = 52 < 60，不反客为主 */
internal const val MATCH_ROW_CONTENT_HEIGHT_DP = 60

/** 簇宽纯函数：n 张 ⇒ size + (n-1)*步距（步距 = size - overlap），n=1..4 = 32/56/80/104。
 *  LineupCluster、两侧列宽与单测共用这一份算式，簇 Box 和侧列预算不会各算各的 */
internal fun lineupClusterWidth(avatarCount: Int): Int {
    val n = min(avatarCount, LINEUP_MAX_AVATARS)
    if (n <= 0) return 0
    return LINEUP_AVATAR_SIZE_DP + (n - 1) * (LINEUP_AVATAR_SIZE_DP - LINEUP_AVATAR_OVERLAP_DP)
}

@Composable
fun MyFavoritesPage(modifier: Modifier = Modifier) {
    val app = LocalContext.current.applicationContext as Application
    val viewModel: MyViewModel = viewModel(factory = MyViewModel.factory(app))
    val activeUid by viewModel.activeUid.collectAsStateWithLifecycle()
    val matchData by viewModel.matchList.collectAsStateWithLifecycle()

    // 装载幂等：同账户重入不清空（VM 只在 uid 变化时清空），命中内存缓存后静默替换 ⇒ 列表不闪。
    LaunchedEffect(activeUid) { viewModel.loadMatchList() }

    val matches = matchData?.favouriteMatches.orEmpty()

    MySubpageScaffold(modifier = modifier) {
        if (matches.isEmpty()) {
            // 空态居中：EmptyState 不接管剩余空间（且把 fillMaxWidth 写在传入 modifier 之后），
            // 在调用点用 weight(1f) + Box 居中包住，不去改公共组件。
            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                EmptyState(title = stringResource(R.string.my_empty_favorites))
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // 🔴 game_id 实测重复（3 条全是 "10"）：裸 id 当 key 会「Key was already used」闪退，
                // 必须复合下标（见 stableItemKey）
                itemsIndexed(matches, key = { index, item -> stableItemKey(item.gameId, index) }) { _, item ->
                    MatchRow(match = item)
                }
            }
        }
    }
}

@Composable
private fun MatchRow(match: GcgMatch) {
    val semantic = LocalSemanticColors.current
    val isWin = match.isWin == true
    // 🔴 中轴恒定（V39-H1 问题 1）：两侧列同宽 = 「两侧簇宽的较大值」。
    // 数学依据：两侧各占同宽 W ⇒ 中间列剩余 = 内容宽 − 2W − 2×间距，其中线到卡内左右边界
    // 距离必然相等 ⇒ 中间列中线 ≡ 卡内几何中心，与人数/本侧簇宽无关。
    // G1 各侧按本侧簇宽摆列，1v1 与 4v4 的中轴差出整档（真机逐卡量到 20px 一跳）。
    // 两侧都缺头像时簇不渲染、宽度归零，用一枚头像宽托底，保证昵称仍有落点。
    val sideWidthDp = max(
        lineupClusterWidth(match.self?.linups.orEmpty().size),
        lineupClusterWidth(match.opposite?.linups.orEmpty().size),
    ).coerceAtLeast(LINEUP_AVATAR_SIZE_DP)
    Card(modifier = Modifier.fillMaxWidth()) {
        // 三段式一行：侧列高 = 28 + 2 + 昵称 18 = 48、中间列高 60，整卡高 = 60 + 上下 padding 各 12 = 84dp。
        // 所有文本 maxLines=1 截断，不为显示全名换行或加高——列表的滚动密度靠这条锁住。
        Row(
            modifier = Modifier.padding(CARD_SIDE_PADDING_DP.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // 己方侧：定宽侧列（= sideWidthDp），头像簇在上、昵称在簇正下方（官方口径）。
            // 🔴 V39-H2 判据 A+B 的推理链（两条要同时成立）：
            //   A（中轴恒定）只取决于**列的几何**——两侧列都吃同一个 sideWidthDp 定宽、中间列是
            //     行内唯一 weight(1f) 承载者 ⇒ 中间列剩余 = 内容宽 − 2W − 2×间距，左右必然对称
            //     ⇒ 中轴 ≡ 卡内几何中心。列内内容怎么对齐，动不了列本身的宽度与位置。
            //   B（簇贴边）取决于**列内对齐**——H1 用 CenterHorizontally，人数不等时小簇在等宽
            //     列里居中，整簇漂离该侧卡内边距（真机 2v4 卡我方簇左缘 x=137，4v4 卡是 84）。
            //     改 Start 后簇与昵称贴列左缘，留白全部倒到内侧（靠近中间列一侧）⇒ 贴边成立
            //     且不影响 A。对手侧镜像为 End。
            // 对齐方式是 Column 的**唯一一处** horizontalAlignment，簇与昵称天然共享，
            // 不许出现「簇贴左、昵称居中」的两张皮。
            Column(
                modifier = Modifier.width(sideWidthDp.dp),
                horizontalAlignment = Alignment.Start,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                LineupCluster(avatars = match.self?.linups)
                Text(
                    // name 可能是空串（不是 null，`?:` 不生效），空串一并兜底避免画出空标题
                    match.self?.name?.takeIf { it.isNotBlank() }
                        ?: stringResource(R.string.state_empty_response),
                    style = MaterialTheme.typography.labelMedium,
                    // 昵称被侧列定宽裁掉省略号：预算与列宽同源（都是 sideWidthDp），
                    // 不许给某一侧另开预算——两侧列一旦不等宽，中轴就重新开始漂
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // 中间列：胜负 / 模式 / 时间三行，全部水平居中——用户本轮的第一要求。
            // 中线落在卡内几何中心由「两侧列同宽」保证（见上方 sideWidthDp），不由本列自证。
            // 行内唯一的 weight 承载者：两列定宽、剩余横向预算全给它；
            // 反过来让簇吃 weight，长队名会先被挤成省略号。
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    // 官方是【胜利】带花纹样式；不新增字符串键，复用现有胜/负文案，
                    // 靠 titleMedium + 加粗 + 语义色前景把「中央大结果」的层级做出来
                    stringResource(if (isWin) R.string.home_result_win else R.string.home_result_lose),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    // V39-H2 口径原样保留：语义色只作前景文字色，不铺背景块
                    color = if (isWin) semantic.win else semantic.lose,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    match.matchType.orEmpty(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    // 官方模式/时间各占一行；缺字段时空串占位，行高不塌，中间列三行结构恒稳
                    formatGcgDateTime(match.matchTime).orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // 对手侧：与己方侧同规格的 Column（同一个 sideWidthDp 定宽，两侧等宽是中轴恒定的前提），
            // 内容镜像靠边 End——列宽同宽管中轴（A），靠边对齐管贴边（B），两件事互不干扰
            Column(
                modifier = Modifier.width(sideWidthDp.dp),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                LineupCluster(avatars = match.opposite?.linups)
                Text(
                    match.opposite?.name?.takeIf { it.isNotBlank() }
                        ?: stringResource(R.string.state_empty_response),
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * 1~4 人头像簇：宽按实际张数走 [lineupClusterWidth]（不足员时整簇收缩，右列不会凭空多出一截），
 * 叠压只靠 `offset`——不用负 padding，也不用 `spacedBy` 负值（负 spacedBy 只是把重叠藏进
 * arrangement 里，offset 不改测量尺寸，簇宽就没法算死）。
 *
 * 🔴 方向一致是用户明确要求，且**簇内部堆叠方向不镜像**：左右两簇只是处在卡片的对称位置
 * （己方在左、对手在右），但都从左往右叠——第 1 张在最左且压在最上层、末张最右且垫底。
 * 别被后人「顺手对称」改掉：镜像会读成「两簇互相推开」，反而看不出这是同一局的对阵双方。
 * 槽位位移恒为非负、层序恒为 `-index`，且两簇复用这同一段代码，方向不可能各自漂移。
 */
@Composable
private fun LineupCluster(avatars: List<String>?) {
    val urls = avatars.orEmpty().take(LINEUP_MAX_AVATARS)
    // 接口没给角色牌时整簇不渲染（沿用旧 LineupRow 语义），也不新造占位图；
    // 注意昵称在调用侧独立于簇存在——官方图里没头像也照样显示昵称。
    if (urls.isEmpty()) return
    val step = LINEUP_AVATAR_SIZE_DP - LINEUP_AVATAR_OVERLAP_DP
    Box(
        modifier = Modifier
            .size(
                width = lineupClusterWidth(urls.size).dp,
                height = LINEUP_AVATAR_SIZE_DP.dp,
            ),
        contentAlignment = Alignment.CenterStart,
    ) {
        urls.forEachIndexed { index, url ->
            Avatar(
                url = url,
                size = LINEUP_AVATAR_SIZE_DP.dp,
                modifier = Modifier
                    .offset(x = (step * index).dp)
                    // Compose 里后画的压在上面 ⇒ 显式反层，才能做到「左侧压右侧」
                    .zIndex(-index.toFloat())
                    .border(LINEUP_AVATAR_STROKE_DP.dp, Color.White, CircleShape),
            )
        }
    }
}
