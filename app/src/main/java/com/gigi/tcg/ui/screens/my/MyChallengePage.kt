// 胜冠之试（设计 §3.3）：**一体化单页** —— 上方固定预览区 + 下方按旬列表（gcg/challenge/schedule + record）。
// 旬列表实测 9 条、按 id 倒序（最新在前），`schedule_list[].id` 就是 record 的 schedule_id 入参。
// 🔴 V36/2 用户第 16 项：不要把「查看某期胜冠之试」做成单独子页面，要集成在按旬查看里 ——
// 上方预览展示当前选中旬的战绩且**不随下方列表滚动**，下方列表自己滚；默认选中第一项 = 最新旬。
// 理由：米游社可能只保留最近 9 旬，两级结构会把最常看的东西藏到一次跳转之后。
// 单旬按需拉（不预取 9 旬）；未回包前预览整块留空 —— 沿用 loadProfile 的静默口径，不写「加载中」占位。
// basic.has_data = false 或 deck_list 为空是**正常状态**（该旬没打过），走文案不走错误态。

package com.gigi.tcg.ui.screens.my

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gigi.tcg.R
import com.gigi.tcg.data.model.GcgChallengeDeck
import com.gigi.tcg.data.model.GcgChallengeRecordData
import com.gigi.tcg.data.model.GcgSchedule
import com.gigi.tcg.ui.components.AppImage
import com.gigi.tcg.ui.components.EmptyState

/** 预览区高度上限：超了自己滚（一旬牌组多时不至于把下方列表挤没），不占用列表的滚动 */
private val PREVIEW_MAX_HEIGHT = 260.dp

@Composable
fun MyChallengePage(modifier: Modifier = Modifier) {
    val app = LocalContext.current.applicationContext as Application
    val viewModel: MyViewModel = viewModel(factory = MyViewModel.factory(app))
    val activeUid by viewModel.activeUid.collectAsStateWithLifecycle()
    val scheduleData by viewModel.challengeSchedule.collectAsStateWithLifecycle()
    val recordData by viewModel.challengeRecord.collectAsStateWithLifecycle()
    var selectedSchedule by remember { mutableStateOf<GcgSchedule?>(null) }

    // 🔴 lastUid 守卫（写法照首页 HomeRoute）：重入只重装数据、不打掉当前选中的旬；
    // 换账户才回到"默认最新旬"（上一账户选中的旬不属于这个账户）。
    var lastUid by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(activeUid) {
        val uid = activeUid
        if (uid != null && uid != lastUid) selectedSchedule = null
        if (uid != null) lastUid = uid
        viewModel.loadChallengeSchedule()
    }

    val schedules = scheduleData?.scheduleList.orEmpty()
    // 默认选中第一项 = 最新旬（实测 schedule_list 按 id 倒序）
    val selected = selectedSchedule ?: schedules.firstOrNull()

    MySubpageScaffold(modifier = modifier) {
        if (schedules.isEmpty()) {
            // 空态居中：EmptyState 不接管剩余空间，调用点用 weight(1f) + Box 包住
            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                EmptyState(title = stringResource(R.string.my_empty_challenges))
            }
        } else {
            val scheduleId = selected?.id
            LaunchedEffect(scheduleId) { scheduleId?.let { viewModel.loadChallengeRecord(it) } }
            // 固定预览区（在列表之外，不随列表滚动）
            ChallengePreview(schedule = selected, record = recordData)
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(schedules, key = { index, item -> stableItemKey(item.id, index) }) { _, item ->
                    ScheduleRow(
                        schedule = item,
                        selected = item.id != null && item.id == selected?.id,
                        onClick = { selectedSchedule = item },
                    )
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

/**
 * 上方固定预览：当前选中旬的战绩（胜场 + 奖牌 + 牌组行）。
 * 不随下方列表滚动；内容超高时自己滚（[PREVIEW_MAX_HEIGHT] 封顶），保证列表始终可见。
 */
@Composable
private fun ChallengePreview(schedule: GcgSchedule?, record: GcgChallengeRecordData?) {
    if (schedule == null) return
    val basic = record?.basic
    val decks = record?.deckList.orEmpty()
    val hasRecord = basic?.hasData != false && decks.isNotEmpty()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = PREVIEW_MAX_HEIGHT)
            .verticalScroll(rememberScrollState()),
    ) {
        MySectionTitle(
            basic?.schedule?.name ?: schedule.name ?: stringResource(R.string.my_challenge_entry),
        )
        // 未回包（record == null）时标题之下整块留空：预览区没有比「先空着」更诚实的占位
        if (record != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = MyRowHorizontalPadding, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                basic?.winCnt?.let { winCount ->
                    Text(
                        stringResource(R.string.my_challenge_win_count, winCount),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                // medal 可能为空串/缺失：传 null 交给 AppImage 的占位，空地址会白跑一次请求。
                // 🔴 has_data==false 时服务端仍下发 medal（实测 challenge_medal_0.png）——给 0 胜画奖牌是误导
                val medalUrl = if (basic?.hasData != false) {
                    basic?.medal?.takeIf { it.isNotBlank() }
                } else {
                    null
                }
                if (medalUrl != null) {
                    AppImage(
                        model = medalUrl,
                        contentDescription = null,
                        modifier = Modifier
                            .padding(start = 12.dp)
                            .size(32.dp)
                            .clip(RoundedCornerShape(6.dp)),
                        contentScale = ContentScale.Fit,
                    )
                }
            }
            if (!hasRecord) {
                Text(
                    stringResource(R.string.my_challenge_no_data),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = MyRowHorizontalPadding, vertical = 8.dp),
                )
            } else {
                // 预览区**不套 LazyColumn**：它在 verticalScroll 的 Column 里，嵌套 lazy 容器会崩，
                // 而一旬牌组实测只有几副，直接铺开即可 —— 也因此不存在"lazy 行错绑"要靠 key 解决的问题。
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
