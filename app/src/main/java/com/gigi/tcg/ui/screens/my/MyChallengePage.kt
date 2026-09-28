// 胜冠之试（设计 §3.3）：旬列表 → 页内切单旬战绩（gcg/challenge/schedule + challenge/record 串联）。
// 旬列表实测 9 条、按 id 倒序（最新在前），`schedule_list[].id` 就是 record 的 schedule_id 入参。
// 单旬按需拉（不预取 9 旬）；详情态未回包前留空 —— 沿用 loadProfile 的静默口径，不写「加载中」占位。
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
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

@Composable
fun MyChallengePage(modifier: Modifier = Modifier) {
    val app = LocalContext.current.applicationContext as Application
    val viewModel: MyViewModel = viewModel(factory = MyViewModel.factory(app))
    val activeUid by viewModel.activeUid.collectAsStateWithLifecycle()
    val scheduleData by viewModel.challengeSchedule.collectAsStateWithLifecycle()
    val recordData by viewModel.challengeRecord.collectAsStateWithLifecycle()
    var selectedSchedule by remember { mutableStateOf<GcgSchedule?>(null) }

    LaunchedEffect(activeUid) {
        selectedSchedule = null
        viewModel.loadChallengeSchedule()
    }

    val selected = selectedSchedule
    if (selected != null) {
        val scheduleId = selected.id
        LaunchedEffect(scheduleId) { scheduleId?.let { viewModel.loadChallengeRecord(it) } }
        ChallengeDetail(
            schedule = selected,
            record = recordData,
            onBack = { selectedSchedule = null },
            modifier = modifier,
        )
        return
    }

    val schedules = scheduleData?.scheduleList.orEmpty()
    MySubpageScaffold(title = stringResource(R.string.my_challenge_entry), modifier = modifier) {
        if (schedules.isEmpty()) {
            EmptyState(
                modifier = Modifier.padding(top = 32.dp),
                title = stringResource(R.string.my_empty_challenges),
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(schedules, key = { index, item -> item.id ?: "noid-$index" }) { _, item ->
                    ScheduleRow(schedule = item, onClick = { selectedSchedule = item })
                }
            }
        }
    }
}

@Composable
private fun ScheduleRow(schedule: GcgSchedule, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                schedule.name ?: stringResource(R.string.my_challenge_entry),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
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
private fun ChallengeDetail(
    schedule: GcgSchedule,
    record: GcgChallengeRecordData?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val basic = record?.basic
    val decks = record?.deckList.orEmpty()
    val hasRecord = basic?.hasData != false && decks.isNotEmpty()

    MySubpageScaffold(
        title = basic?.schedule?.name ?: schedule.name ?: stringResource(R.string.my_challenge_entry),
        onBack = onBack,
        modifier = modifier,
    ) {
        // 未回包（record == null）时整块留空：详情区没有比「先空着」更诚实的占位
        if (record != null) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
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
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(decks) { entry -> ChallengeDeckRow(entry) }
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
