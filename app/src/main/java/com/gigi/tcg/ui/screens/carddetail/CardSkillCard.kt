// 技能卡 / 行动牌效果卡（报告 §5.5 / §5.6）：
// OutlinedCard（surface + 1dp outlineVariant，12dp 圆角，16dp 内边距）；
// 图标 56dp（点按弹下载图标对话框，右下 20dp 下载角标）；技能名 titleSmall；
// 类型芯片 / 费用徽章 24dp、labelMedium；描述 bodyMedium，元素词着色 + Medium；
// 注释默认全部展开、无芯片、无左侧竖线（surfaceContainerHigh，12dp 圆角，12dp 内边距）。
// 🔴 不再渲染 desc.key：技能卡与效果卡上的「技能效果」「效果描述」都是固定文案，
// 语义已由章节标题承担（报告 §5.5 的两处标签都删）。

package com.gigi.tcg.ui.screens.carddetail

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gigi.tcg.R
import com.gigi.tcg.domain.CardEffect
import com.gigi.tcg.domain.CardElement
import com.gigi.tcg.domain.CardSkill
import com.gigi.tcg.domain.RichNote
import com.gigi.tcg.ui.components.AppImage
import com.gigi.tcg.ui.theme.ElementColors

/** 正文里需要着色的元素词：物理伤害 / X元素(伤害) / 「火/水/雷/冰」这类单字列表 */
private val ELEMENT_TERM = Regex("物理伤害|[风水雷冰岩草]元素(?:伤害)?|[风水雷冰岩草](?=/)")

private val SKILL_ICON_SIZE = 56.dp
private val SKILL_ICON_BADGE = 20.dp

/** 角标相对图标外扩的距离；外层可点击 Box 需要把这部分也算进触摸区域 */
private val SKILL_ICON_BADGE_OVERHANG = 4.dp
private val PILL_HEIGHT = 24.dp

@Composable
internal fun CardSkillCard(
    skill: CardSkill,
    element: CardElement,
    onDownloadIcon: (CardSkill) -> Unit,
    modifier: Modifier = Modifier,
) {
    SkillCardShell(modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SkillIcon(
                url = skill.iconUrl,
                name = skill.name,
                onClick = { onDownloadIcon(skill) },
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = skill.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (skill.type.isNotBlank() || skill.costs.isNotEmpty()) {
                    FlowRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        if (skill.type.isNotBlank()) SkillTypeChip(skill.type)
                        skill.costs.forEach { cost ->
                            CardCostBadge(cost = cost, element = element)
                        }
                    }
                }
            }
        }
        SkillDescriptionBlock(
            description = skill.description,
            notes = skill.notes,
            element = element,
            firstTopPadding = 12.dp,
        )
    }
}

@Composable
internal fun CardEffectCard(
    effect: CardEffect,
    element: CardElement,
    modifier: Modifier = Modifier,
) {
    SkillCardShell(modifier) {
        // 效果卡没有技能名/图标头，描述就是卡片首行：不能再加 12dp 顶部间距，
        // 否则 16dp 内边距 + 12dp 会在正文前留出一条空行（用户 2026-11-18 反馈）。
        SkillDescriptionBlock(
            description = effect.description,
            notes = effect.notes,
            element = element,
            firstTopPadding = 0.dp,
        )
    }
}

/** 章节标题：「卡牌技能」/「卡牌效果」，右侧不再有数量文本（报告 §5.4） */
@Composable
internal fun CardSectionLabel(
    title: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = title,
        modifier = modifier.fillMaxWidth(),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun SkillCardShell(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    OutlinedCard(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.outlinedCardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(modifier = Modifier.padding(16.dp), content = content)
    }
}

/**
 * 技能图标：56dp + 12dp 圆角；有图时**整块 60dp（含右下 4dp 角标外扩）都可点击**，
 * 右下 20dp 圆形下载角标（surface 底 + 1dp outline + 12dp Download）。
 * 无图时不可点击、不显示角标（报告 §7.1：技能无图标不打开对话框）。
 * 🔴 点击事件挂在最外层 Box 上：此前只挂在 56dp 图片 Surface 上，
 * 用户点到外扩的下载角标（4dp 区域）没有任何反应（2026-11-18 反馈）。
 */
@Composable
private fun SkillIcon(
    url: String?,
    name: String,
    onClick: () -> Unit,
) {
    val icon = url?.takeIf { it.isNotBlank() }
    val description = stringResource(R.string.cd_download_skill_icon, name)
    val clickable = if (icon != null) {
        Modifier
            .clickable(onClick = onClick)
            .semantics { contentDescription = description }
    } else {
        Modifier
    }
    Box(
        modifier = Modifier
            .size(SKILL_ICON_SIZE + SKILL_ICON_BADGE_OVERHANG)
            .then(clickable),
    ) {
        if (icon == null) {
            Box(
                modifier = Modifier
                    .size(SKILL_ICON_SIZE)
                    .align(Alignment.TopStart)
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            )
        } else {
            Surface(
                modifier = Modifier
                    .size(SKILL_ICON_SIZE)
                    .align(Alignment.TopStart),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
            ) {
                AppImage(
                    model = icon,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(SKILL_ICON_BADGE),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Outlined.Download,
                        contentDescription = null,
                        modifier = Modifier.size(12.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun SkillTypeChip(text: String) {
    Surface(
        modifier = Modifier.heightIn(min = PILL_HEIGHT),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
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

@Composable
private fun SkillDescriptionBlock(
    description: String,
    notes: List<RichNote>,
    element: CardElement,
    firstTopPadding: Dp,
) {
    var hasContent = false
    if (description.isNotBlank()) {
        Text(
            text = descriptionText(description, element),
            modifier = Modifier.padding(top = firstTopPadding),
            style = MaterialTheme.typography.bodyMedium.copy(letterSpacing = 0.25.sp),
            color = MaterialTheme.colorScheme.onSurface,
        )
        hasContent = true
    }
    notes.forEachIndexed { index, note ->
        // 首个注释承接描述间距（描述不存在时用 firstTopPadding），后续注释固定 8dp
        val top = if (index == 0) {
            if (hasContent) 12.dp else firstTopPadding
        } else {
            8.dp
        }
        NoteBlock(
            note = note,
            modifier = Modifier.padding(top = top),
        )
    }
}

/** 注释块：默认展开、无芯片、无左侧装饰竖线；标题 labelLarge、正文 bodySmall（不着色） */
@Composable
private fun NoteBlock(note: RichNote, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            if (note.title.isNotBlank()) {
                Text(
                    text = note.title,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            if (note.body.isNotBlank()) {
                Text(
                    text = note.body,
                    modifier = Modifier.padding(top = 2.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 元素词只做 element 色 + Medium（M3 允许的 emphasis，不改字号/行高） */
@Composable
private fun descriptionText(text: String, element: CardElement): AnnotatedString {
    val dark = ElementColors.isDark()
    return remember(text, element, dark) {
        buildAnnotatedString {
            append(text)
            ELEMENT_TERM.findAll(text).forEach { match ->
                val term = match.value
                val termElement = if (term.startsWith("物理")) {
                    CardElement.Physical
                } else {
                    CardElement.fromName(term)
                }
                addStyle(
                    style = SpanStyle(
                        color = ElementColors.color(termElement, dark),
                        fontWeight = FontWeight.Medium,
                    ),
                    start = match.range.first,
                    end = match.range.last + 1,
                )
            }
        }
    }
}
