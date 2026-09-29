// 胜冠之试（设计 §3.3）：**页面主体 + 底部 dock**（V37-E 重构）。
// 主体固定占满「容器高 − dock peek」并内部自滚 ⇒ 切旬时外框尺寸恒定，只有内部内容变，
// 这就是用户报的「顶部信息剧烈抖动」的根治（旧结构预览区 wrap-content，战绩多/少直接改高度）。
//
// 🔴 为什么是 BottomSheetScaffold（M3 标准/停靠式底部抽屉）而不是 ModalBottomSheet：
// ModalBottomSheet 在结构上做不到用户已确认的 C1（peek 露标题栏 + expand 约 60%），依据取自
// 本工程 material3 1.3.2 的实装（对照 AndroidX commonMain 源码）：
// - 它的 PartiallyExpanded 锚点**写死** `fullHeight / 2f`（ModalBottomSheet.kt 的 draggableAnchors）
//   ⇒ peek 只能是半屏或隐藏，给不了「只露标题栏」；Expanded 也是内容驱动，60% 无从约束；
// - 它是模态：scrim = `ScrimTokens.ContainerColor @ 0.32` 压住整页 ⇒ 主体发暗且不可点，
//   与「点列表项 → 页面主体随之更新」直接冲突；
// - `ModalBottomSheetProperties(shouldDismissOnBackPress = true)` 默认 ⇒ 系统返回手势**先关抽屉**，
//   而用户明确说主要退出方式是返回手势/导航键；
// - 它还 `consumeWindowInsets(top = sheetState.offset)`，与外层 Scaffold 的 innerPadding 叠加
//   （派单预警的双层间距）。
// BottomSheetScaffold 三条都对得上：`PartiallyExpanded at layoutHeight - peekHeightPx`
// （peek 由我按 M3 常量算）、`Expanded at layoutHeight - sheetHeight`（展开高度=内容高度，
// 可按容器高换算）、**签名里没有 scrim 参数**且 `skipHiddenState = true` ⇒ 抽屉是常驻 dock；
// content 拿到 `PaddingValues(bottom = sheetPeekHeight)` ⇒ 主体永不被 dock 盖住。
//
// 旬列表实测 9 条、按 id **倒序**（最新在前），`schedule_list[].id` 就是 record 的 schedule_id 入参。
// 🔴 默认选中第一项 = 最新旬；点列表项 / 前后翻页**只改选中态**，绝不碰 sheetState（见下面注释）。
// 单旬按需拉（不预取 9 旬）；未回包前主体战绩区留空 —— 沿用 loadProfile 的静默口径，不写「加载中」占位。
// basic.has_data = false 或 deck_list 为空是**正常状态**（该旬没打过），走文案不走错误态。

package com.gigi.tcg.ui.screens.my

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.material3.rememberStandardBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gigi.tcg.R
import com.gigi.tcg.data.model.GcgChallengeDeck
import com.gigi.tcg.data.model.GcgChallengeRecordData
import com.gigi.tcg.data.model.GcgSchedule
import com.gigi.tcg.ui.components.AppImage
import com.gigi.tcg.ui.components.EmptyState

// ───────────────────────── dock 尺寸（M3 实装常量推导，非拍脑袋） ─────────────────────────
// 本工程 material3 实际解析版本 = **1.3.2**（compose-bom 2025.09.00 下 `:app:dependencies` 实测
// `material3 -> 1.3.2`）。该版本**没有** `BottomSheetDefaults.MediumLargeExpandedHeight`
// （javap 全量成员只有 sheetPeekHeight / sheetMaxWidth / Elevation / DragHandle / WindowInsets 等），
// 故按派单兜底口径：peek 用 M3 手柄 + 标题栏算，expand 用「容器高 × 比例」换算，并在 KDoc 写明依据。

/**
 * sheet 手柄区高度 48dp = `DragHandleVerticalPadding(22) × 2 + DockedDragHandleHeight(4)`。
 * 三个值取自 material3 1.3.2 字节码常量池（`SheetDefaultsKt` / `SheetBottomTokens`），
 * 与 `BottomSheetDefaults.DragHandle()`（dock 的默认 dragHandle）实际画出来的高度一致。
 */
internal const val DOCK_HANDLE_HEIGHT_DP: Float = 48f

/** 标题栏高度 48dp = M3 `IconButton` 最小触控目标（左右翻页键与居中标题同一行） */
internal const val DOCK_TITLE_BAR_HEIGHT_DP: Float = 48f

/**
 * 展开态整块 dock（手柄 + 标题栏 + 列表）占容器高的比例。
 * 用户已确认的 C1 = 「peek 露标题栏，expand 到约 60% 屏高（M3 MediumLarge 档）」。
 * 用比例而非固定 dp：与 M3 一致地随容器高度缩放，横竖屏/分屏都不需要另算一套常量。
 */
internal const val DOCK_EXPANDED_HEIGHT_FRACTION: Float = 0.6f

/**
 * peek 高度 = 手柄 + 标题栏（收起态正好只露出标题栏 = 用户要的 dock 形态）。
 * 库默认 `BottomSheetDefaults.sheetPeekHeight` = 56dp 只够手柄 + 一行小字，露不全标题栏，
 * 故显式传入本函数结果，不用默认值。
 */
internal fun dockPeekHeightDp(): Dp = (DOCK_HANDLE_HEIGHT_DP + DOCK_TITLE_BAR_HEIGHT_DP).dp

/**
 * 展开态 dock 的**内容列**高度（不含手柄，手柄由 `BottomSheetDefaults.DragHandle()` 自己画）：
 * `容器高 × [DOCK_EXPANDED_HEIGHT_FRACTION] − 手柄高`，下限取标题栏高度 ——
 * 极矮容器（分屏/悬浮窗）下不让它算成 0 或负数，否则展开后连标题栏都看不见。
 */
internal fun dockExpandedContentHeightDp(containerHeightDp: Float): Dp =
    (containerHeightDp * DOCK_EXPANDED_HEIGHT_FRACTION - DOCK_HANDLE_HEIGHT_DP)
        .coerceAtLeast(DOCK_TITLE_BAR_HEIGHT_DP)
        .dp

// ───────────────────────── 旬选中/翻页（纯函数，JVM 单测钉死） ─────────────────────────

/**
 * 钳制选中下标：列表为空 ⇒ null；下标越界（刷新后旬数变少、或脏值）⇒ 收回边界。
 * null 也兜成 0 = 默认最新旬（实测 `schedule_list` 按 id 倒序，第一项就是最新一旬）。
 */
internal fun clampScheduleIndex(index: Int?, count: Int): Int? =
    if (count <= 0) null else index?.coerceIn(0, count - 1) ?: 0

/**
 * 能否「上一旬」（更早的一旬）。
 * 🔴 倒序映射钉死：列表 index 越大 = 时间越早 ⇒ **上一旬 = index + 1**、下一旬 = index − 1。
 * 实测首屏自上而下是 `26年9月下` → `26年9月上` → `26年8月下`，
 * 所以选中 index=0（最新）时「上一旬」可用（往 index=1 走）、「下一旬」禁用（没有更新的）。
 * 边界：index=last（最早一旬）⇒ 上一旬禁用。
 */
internal fun canGoPreviousSchedule(index: Int, count: Int): Boolean =
    count > 0 && index in 0 until count - 1

/** 能否「下一旬」（更新的一旬）：已经在列表头（index=0 = 最新旬）就禁用 */
internal fun canGoNextSchedule(index: Int, count: Int): Boolean =
    count > 0 && index > 0

/** 上一旬的下标（= index + 1）；越界返回 null（按钮同时 disabled） */
internal fun previousScheduleIndex(index: Int, count: Int): Int? =
    if (canGoPreviousSchedule(index, count)) index + 1 else null

/** 下一旬的下标（= index − 1）；越界返回 null（按钮同时 disabled） */
internal fun nextScheduleIndex(index: Int, count: Int): Int? =
    if (canGoNextSchedule(index, count)) index - 1 else null

/**
 * dock 标题文本 = 当前选中旬名。**只取列表项的 name**，不取 record 里的 `basic.schedule.name` ——
 * 后者要等单旬战绩回包才有，用它会让标题在加载前后闪一下（抖动的一部分）。
 * 名字缺失/空白回退条目名 [fallback]（`my_challenge_entry`），不画空标题。
 */
internal fun scheduleTitleAt(index: Int, schedules: List<GcgSchedule>, fallback: String): String =
    schedules.getOrNull(index)?.name?.takeIf { it.isNotBlank() } ?: fallback

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyChallengePage(modifier: Modifier = Modifier) {
    val app = LocalContext.current.applicationContext as Application
    val viewModel: MyViewModel = viewModel(factory = MyViewModel.factory(app))
    val activeUid by viewModel.activeUid.collectAsStateWithLifecycle()
    val scheduleData by viewModel.challengeSchedule.collectAsStateWithLifecycle()
    val recordData by viewModel.challengeRecord.collectAsStateWithLifecycle()
    val entryTitle = stringResource(R.string.my_challenge_entry)

    // 选中态用**下标**而不是 GcgSchedule 对象：翻页就是 index ±1，且列表重新装载后
    // 同一旬的字段可能变（对象比较会掉回"默认最新旬"，下标不会）。
    var selectedIndex by remember { mutableStateOf<Int?>(null) }

    // 🔴 lastUid 守卫（写法照首页 HomeRoute）：重入只重装数据、不打掉当前选中的旬；
    // 换账户才回到"默认最新旬"（上一账户选中的旬不属于这个账户）。
    var lastUid by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(activeUid) {
        val uid = activeUid
        if (uid != null && uid != lastUid) selectedIndex = null
        if (uid != null) lastUid = uid
        viewModel.loadChallengeSchedule()
    }

    val schedules = scheduleData?.scheduleList.orEmpty()
    val selected = clampScheduleIndex(selectedIndex, schedules.size)
    val selectedSchedule = selected?.let { schedules[it] }

    // dock 状态：PartiallyExpanded(=peek 只露标题栏) ↔ Expanded(≈60% 容器高)，
    // skipHiddenState=true ⇒ 用户拖到底也只能收起、不能把 dock 拖没（它是常驻 palette）。
    // 🔴 切旬/翻页**绝不引用**这个 state：不调 expand()/partialExpand()/animateTo/snapTo，
    // 档位由用户手势（拖拽 dock、下滑收起）独占 —— 用户拍板"保持当前状态即可"。
    val dockState = rememberBottomSheetScaffoldState(
        bottomSheetState = rememberStandardBottomSheetState(),
    )

    val scheduleId = selectedSchedule?.id
    LaunchedEffect(scheduleId) { scheduleId?.let { viewModel.loadChallengeRecord(it) } }

    if (selected == null || selectedSchedule == null) {
        // 空态居中：EmptyState 不接管剩余空间，调用点用 Box + fillMaxSize 包住
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            EmptyState(title = stringResource(R.string.my_empty_challenges))
        }
        return
    }

    BottomSheetScaffold(
        modifier = modifier,
        scaffoldState = dockState,
        sheetPeekHeight = dockPeekHeightDp(),
        // 主壳 Scaffold 已把系统栏算进 innerPadding（GigiNavHost:271 `padding(innerPadding)`），
        // 页面盒子本身就落在安全区内 ⇒ 这里不再给 dock 补 navigationBarsPadding（补了就是双层间距）。
        // containerColor 走透明：旧结构页面本身没底色，保留 surface 反而在深色主题下多出一块底板。
        containerColor = Color.Transparent,
        sheetContent = {
            ChallengeDock(
                schedules = schedules,
                selectedIndex = selected,
                entryTitle = entryTitle,
                onSelect = { selectedIndex = it },
                onPrevious = { previousScheduleIndex(selected, schedules.size)?.let { selectedIndex = it } },
                onNext = { nextScheduleIndex(selected, schedules.size)?.let { selectedIndex = it } },
            )
        },
        content = { bodyPadding ->
            // 主体外框 = 容器 − peek（scaffold 给的 `PaddingValues(bottom = sheetPeekHeight)` 恒定）
            // ⇒ 切旬时外框尺寸不变，只有主体内部内容变 —— 抖动从结构上消掉
            ChallengeBody(
                schedule = selectedSchedule,
                record = recordData,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bodyPadding),
            )
        },
    )
}

/**
 * 底部 dock：标题栏（左「上一旬」/ 中当前旬名居中 / 右「下一旬」）+ 可点的旬列表。
 * 整块高度 = 手柄（[DOCK_HANDLE_HEIGHT_DP]，由 `BottomSheetDefaults.DragHandle()` 画）
 * \+ 本列 = 容器高的 [DOCK_EXPANDED_HEIGHT_FRACTION] ⇒ Expanded 锚点落在约 60% 屏高。
 */
@Composable
private fun ChallengeDock(
    schedules: List<GcgSchedule>,
    selectedIndex: Int,
    entryTitle: String,
    onSelect: (Int) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
) {
    // dock 里的旬列表用 LazyColumn（这是 sheet 的正常用法，展开态可能有十几条旬）；
    // 页面主体才是 Column + verticalScroll —— 两处各自一个滚动容器，不互相嵌套。
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(dockExpandedContentHeightDp(maxHeight.value)),
        ) {
            DockTitleBar(
                title = scheduleTitleAt(selectedIndex, schedules, entryTitle),
                canGoPrevious = canGoPreviousSchedule(selectedIndex, schedules.size),
                canGoNext = canGoNextSchedule(selectedIndex, schedules.size),
                onPrevious = onPrevious,
                onNext = onNext,
            )
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(schedules, key = { index, item -> stableItemKey(item.id, index) }) { index, item ->
                    ScheduleRow(
                        schedule = item,
                        selected = index == selectedIndex,
                        onClick = { onSelect(index) },
                    )
                }
            }
        }
    }
}

/**
 * dock 标题栏。左右各一个 48dp 触控目标 ⇒ 中间 `weight(1f)` + `TextAlign.Center` 的标题
 * 在**视觉上真正居中**（两侧留白等宽），不能只靠 padding 凑。
 * 不可翻页时 `enabled = false`（M3 disabled 自己降到 38% alpha，不手动涂灰）。
 */
@Composable
private fun DockTitleBar(
    title: String,
    canGoPrevious: Boolean,
    canGoNext: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(DOCK_TITLE_BAR_HEIGHT_DP.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 左 = 上一旬（更早）。倒序列表里它对应 index+1，映射由 previousScheduleIndex 钉死
        IconButton(onClick = onPrevious, enabled = canGoPrevious) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowLeft,
                contentDescription = stringResource(R.string.my_challenge_prev_period),
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        // 右 = 下一旬（更新）= index−1
        IconButton(onClick = onNext, enabled = canGoNext) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = stringResource(R.string.my_challenge_next_period),
            )
        }
    }
}

/**
 * 页面主体：当前旬的详细信息（期间 + 胜场 + 战绩明细）。
 * 外框由调用点定死（`fillMaxSize` + dock peek 预留），内容超高时**内部自滚**；
 * 切旬时滚动位置复位到顶部（按旬 id 的 [LaunchedEffect]），否则停在第 8 行却切到只有 1 行的旬，
 * 会看见一片中间空白。
 */
@Composable
private fun ChallengeBody(schedule: GcgSchedule, record: GcgChallengeRecordData?, modifier: Modifier = Modifier) {
    val scrollState = rememberScrollState()
    val scheduleId = schedule.id
    LaunchedEffect(scheduleId) { scrollState.scrollTo(0) }

    val basic = record?.basic
    val decks = record?.deckList.orEmpty()
    val hasRecord = basic?.hasData != false && decks.isNotEmpty()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(scrollState),
    ) {
        // 旬名在 dock 标题栏上（且切旬时不动 sheet），主体只补期间，不重复画标题
        val period = listOfNotNull(
            formatGcgDate(basic?.schedule?.begin ?: schedule.begin),
            formatGcgDate(basic?.schedule?.end ?: schedule.end),
        ).joinToString(" ~ ")
        if (period.isNotBlank()) MySectionTitle(period)

        basic?.winCnt?.let { winCount ->
            // 🔴 V37-E 用户要求：「胜场 X」右侧的奖牌图标已移除。
            // 移除的另一层理由：实测 has_data == false 的旬服务端照样下发 medal（challenge_medal_0.png），
            // 旧代码要专门判空才压得住；0 胜画奖牌本身是误导，文本已把信息说全。
            Text(
                stringResource(R.string.my_challenge_win_count, winCount),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = MyRowHorizontalPadding, vertical = 4.dp),
            )
        }

        // record == null（未回包）时战绩区整块留空：沿用 V36 的静默口径，不写「加载中」占位
        if (record != null) {
            if (!hasRecord) {
                Text(
                    stringResource(R.string.my_challenge_no_data),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = MyRowHorizontalPadding, vertical = 8.dp),
                )
            } else {
                // 主体在 verticalScroll 的 Column 里，**不套 LazyColumn**（嵌套 lazy 容器会崩）；
                // 一旬牌组实测只有几副，铺开即可
                decks.forEach { entry ->
                    Box(Modifier.padding(horizontal = MyRowHorizontalPadding, vertical = 4.dp)) {
                        ChallengeDeckRow(entry)
                    }
                }
            }
        }
    }
}

@Composable
private fun ScheduleRow(schedule: GcgSchedule, selected: Boolean, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = if (selected) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        } else {
            CardDefaults.cardColors()
        },
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                schedule.name ?: stringResource(R.string.my_challenge_entry),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (selected) MaterialTheme.colorScheme.primary else Color.Unspecified,
            )
            val period = listOfNotNull(formatGcgDate(schedule.begin), formatGcgDate(schedule.end))
                .joinToString(" ~ ")
            if (period.isNotEmpty()) {
                Text(
                    period,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun ChallengeDeckRow(entry: GcgChallengeDeck) {
    val avatars = entry.deck?.avatarCards.orEmpty()
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    deckDisplayName(entry.deck),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                entry.winCnt?.let { winCount ->
                    Text(
                        stringResource(R.string.my_challenge_win_count, winCount),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
            // 角色牌头像（实测恒 3 张，字段与卡组页 DeckRow 同构）；为空时整排不画，不摆空 Box
            if (avatars.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    avatars.forEach { card ->
                        Box(
                            Modifier
                                .width(52.dp)
                                .aspectRatio(CARD_FACE_ASPECT_RATIO)
                                .clip(DeckCardShape)
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                        ) {
                            AppImage(
                                model = card.image?.takeIf { it.isNotBlank() },
                                contentDescription = card.name,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }
            }
        }
    }
}
