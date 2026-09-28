// 「我的」页四个二级页（V35 P0 占位，设计 §3.3）：我的卡组 / 卡背图鉴 / 收藏对局 / 胜冠之试。
// P0 只交付导航骨架与占位说明 —— 四个端点的元素级字段在 SDK 里是 unknown（设计 §1「核心未知项」），
// 按「先抓样本，再写代码」的既定路径：P1 真机抓样本 → P2 按样本定型模型后填充真实内容。
// 占位页用统一的 MySubpagePlaceholder，P2 逐个替换为真实页面。

package com.gigi.tcg.ui.screens.my

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.gigi.tcg.R

/** 二级页占位：居中标题 + 一句「待接入」说明（P2 逐个替换为真实页面） */
@Composable
fun MySubpagePlaceholder(titleRes: Int, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            stringResource(titleRes),
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
        )
        Text(
            stringResource(R.string.my_placeholder_note),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}
