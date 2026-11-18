// 详情「技能 / 效果」Tab 顶部的基础信息卡（报告 §5.4）：
// OutlinedCard：surface + 1dp outlineVariant，12dp 圆角，16dp 内边距；
// 缩略图 88 × 151（7:12）、8dp 圆角；卡名 titleMedium；徽章 / 标签 24dp、labelMedium；
// 字段全部来自 entry_page 基础信息 attr，缺字段整行隐藏。
//
// 2026-11-18 用户拍板：不再展示「获取方式」（获取途径是运营信息，图鉴页不承载）。
//
// 冒号修复（报告 §8.2）：attr 的 key / value 冒号位置官方不统一，
// 所有上屏文本都先过 normalizeAttrValue。

package com.gigi.tcg.ui.screens.carddetail

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gigi.tcg.R
import com.gigi.tcg.data.model.CardAttr
import com.gigi.tcg.data.model.CardBasicInfo
import com.gigi.tcg.domain.CardDetailParser
import com.gigi.tcg.ui.components.AppImage

private val THUMB_WIDTH = 88.dp
private val THUMB_HEIGHT = 151.dp

/** 静态标签统一 24dp 高（报告 §5.1：非交互标签不用 M3 Chip） */
private val PILL_HEIGHT = 24.dp

/** 徽章数据：生命/充能/花费要套三语文案，类型直接展示 */
private sealed interface BadgeData {
    data class Life(val amount: Int) : BadgeData
    data class Energy(val amount: Int) : BadgeData
    data class Cost(val text: String) : BadgeData
    data class Plain(val text: String) : BadgeData
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CardBasicInfoHeader(
    info: CardBasicInfo,
    isAction: Boolean,
    modifier: Modifier = Modifier,
) {
    val attrs = info.attr.orEmpty()
    val name = info.name?.takeIf { it.isNotBlank() }
    val chips = chipTexts(attrs)
    val badges = badgeData(info, attrs, isAction)

    OutlinedCard(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.outlinedCardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(
                modifier = Modifier
                    .width(THUMB_WIDTH)
                    .height(THUMB_HEIGHT)
                    .clip(MaterialTheme.shapes.small)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            ) {
                // 少数卡只有动态卡面（common_img 为空），缩略图回退到 gold_img，
                // 否则基础信息卡左侧只剩灰底占位。
                val url = info.commonImg?.takeIf { it.isNotBlank() }
                    ?: info.goldImg?.takeIf { it.isNotBlank() }
                if (url != null) {
                    AppImage(
                        model = url,
                        contentDescription = name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                if (name != null) {
                    Text(
                        text = name,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (badges.isNotEmpty()) {
                    FlowRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        badges.forEach { InfoBadge(it) }
                    }
                }
                if (chips.isNotEmpty()) {
                    FlowRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        chips.forEach { AttrChip(it) }
                    }
                }
            }
        }
    }
}

/** 生命/充能（角色、魔物）或类型/花费（行动牌） */
private fun badgeData(
    info: CardBasicInfo,
    attrs: List<CardAttr>,
    isAction: Boolean,
): List<BadgeData> = if (isAction) {
    buildList {
        attrValues(attrs, "类型").firstOrNull()?.let { add(BadgeData.Plain(it)) }
        val cost = attrValues(attrs, "花费").firstOrNull()
            ?: info.life?.takeIf { it > 0 }?.toString()
        if (!cost.isNullOrBlank()) add(BadgeData.Cost(cost))
    }
} else {
    buildList {
        info.life?.takeIf { it > 0 }?.let { add(BadgeData.Life(it)) }
        info.energy?.takeIf { it > 0 }?.let { add(BadgeData.Energy(it)) }
    }
}

@Composable
private fun InfoBadge(data: BadgeData) {
    val text = when (data) {
        is BadgeData.Life -> stringResource(R.string.cover_badge_life, data.amount)
        is BadgeData.Energy -> stringResource(R.string.cover_badge_energy, data.amount)
        is BadgeData.Cost -> stringResource(R.string.cover_badge_cost, data.text)
        is BadgeData.Plain -> data.text
    }
    Surface(
        modifier = Modifier.heightIn(min = PILL_HEIGHT),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
            )
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun AttrChip(text: String) {
    Surface(
        modifier = Modifier.heightIn(min = PILL_HEIGHT),
        shape = CircleShape,
        color = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
            )
        }
    }
}

/** 元素/武器/阵营（角色）/ 标签（行动牌）；跳过已做成徽章的键 */
private fun chipTexts(attrs: List<CardAttr>): List<String> {
    // 获取途径一律不上屏（用户 2026-11-18 拍板）：官方文档里 key 有「获取方式 / 获取 / 获得方式」等多种写法
    val skip = setOf(
        "获取方式", "获取", "获得方式", "获得", "获取途径", "获得途径",
        "类型", "花费", "充能",
    )
    return attrs.flatMap { attr ->
        val key = CardDetailParser.normalizeKey(attr.key)
        if (key in skip) return@flatMap emptyList()
        attr.value.orEmpty().flatMap { value ->
            val normalized = CardDetailParser.normalizeAttrValue(value)
            if (key == "标签") {
                normalized.split('，', ',', '、')
                    .map { CardDetailParser.normalizeAttrValue(it) }
            } else {
                listOf(normalized)
            }
        }
    }.filter { it.isNotEmpty() }.distinct()
}

private fun attrValues(attrs: List<CardAttr>, key: String): List<String> =
    attrs.firstOrNull { CardDetailParser.normalizeKey(it.key) == key }
        ?.value.orEmpty()
        .map { CardDetailParser.normalizeAttrValue(it) }
        .filter { it.isNotEmpty() }
