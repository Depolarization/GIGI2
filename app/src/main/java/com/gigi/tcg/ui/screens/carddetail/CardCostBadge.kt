// 费用徽章：元素骰 / 无色骰 / 充能 / 中性（报告 §5.5：24dp、labelMedium）。
// 🔴 禁止硬编码 Color.White：夜间元素底色是浅色 pastel，白字只有 1.30–1.90:1；
// 字色必须走 ElementColors.ink(dark)（亮白 / 暗 #101418）。
// 无色骰边框必须是 outline（不是 outlineVariant），否则非文本 3:1 不达标。

package com.gigi.tcg.ui.screens.carddetail

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.gigi.tcg.domain.CardElement
import com.gigi.tcg.domain.CostKind
import com.gigi.tcg.domain.SkillCost
import com.gigi.tcg.ui.theme.ElementColors

private val COST_SIZE = 24.dp

@Composable
internal fun CardCostBadge(
    cost: SkillCost,
    element: CardElement,
    modifier: Modifier = Modifier,
) {
    val dark = ElementColors.isDark()
    val background: Color
    val foreground: Color
    val border: BorderStroke?
    when (cost.kind) {
        CostKind.Element -> {
            background = ElementColors.color(element, dark)
            foreground = ElementColors.ink(dark)
            border = null
        }

        CostKind.Energy -> {
            background = ElementColors.energy(dark)
            foreground = ElementColors.energyInk(dark)
            border = null
        }

        CostKind.Untyped, CostKind.Neutral -> {
            background = ElementColors.untypedBackground(dark)
            foreground = ElementColors.untypedText(dark)
            border = BorderStroke(1.dp, ElementColors.untypedBorder(dark))
        }
    }

    Surface(
        // 24dp 是常规字号下的最小尺寸；200% 字体时用最小约束 + 内边距撑成胶囊，
        // 固定 size(24dp) 会把 24sp 的数字裁掉（真机 200% 字体复测）。
        modifier = modifier.defaultMinSize(minWidth = COST_SIZE, minHeight = COST_SIZE),
        shape = CircleShape,
        color = background,
        contentColor = foreground,
        border = border,
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = cost.amount.toString(),
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}
