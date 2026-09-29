// 卡背图鉴（设计 §3.3）：gcg/cardBackList 网格，未收集置灰 + 角标。
// 🔴 接口返回的是**全部**卡背（实测 28 张 = 25 已得 / 3 未得），没有「只回已收集」的开关参数 ⇒
// 「图鉴」口径靠 has_obtained 自己标灰；顶部计数分母就是列表长度（全部卡背）。
// 图优先 image_v2（28/28 全有），image 只有 21/28 有 ⇒ 仅在 image_v2 缺失时回落。

package com.gigi.tcg.ui.screens.my

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gigi.tcg.R
import com.gigi.tcg.data.model.GcgCardBack
import com.gigi.tcg.ui.components.AppImage
import com.gigi.tcg.ui.components.EmptyState

private const val CARD_BACK_ASPECT_RATIO: Float = 160f / 275f

/** 未收集置灰：只压图，角标保持实色，否则「未收集」三个字会跟着一起淡掉 */
private const val LOCKED_ALPHA = 0.35f

private val CardBackShape = RoundedCornerShape(8.dp)

@Composable
fun MyCardBacksPage(modifier: Modifier = Modifier) {
    val app = LocalContext.current.applicationContext as Application
    val viewModel: MyViewModel = viewModel(factory = MyViewModel.factory(app))
    val activeUid by viewModel.activeUid.collectAsStateWithLifecycle()
    val cardBackData by viewModel.cardBackList.collectAsStateWithLifecycle()

    LaunchedEffect(activeUid) { viewModel.loadCardBackList() }

    val cardBacks = cardBackData?.cardBackList.orEmpty()
    MySubpageScaffold(title = stringResource(R.string.my_cardback_entry), modifier = modifier) {
        if (cardBacks.isEmpty()) {
            EmptyState(
                modifier = Modifier.padding(top = 32.dp),
                title = stringResource(R.string.my_empty_cardbacks),
            )
        } else {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(
                        R.string.my_cardback_count,
                        cardBacks.count { it.hasObtained == true },
                        cardBacks.size,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                LazyVerticalGrid(
                    // 固定 3 列：与图鉴页同一口径（393dp 宽每列约 118dp，竖版卡面仍清晰）
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    itemsIndexed(cardBacks, key = { index, item -> stableItemKey(item.id, index) }) { _, item ->
                        CardBackTile(cardBack = item)
                    }
                }
            }
        }
    }
}

@Composable
private fun CardBackTile(cardBack: GcgCardBack) {
    val obtained = cardBack.hasObtained == true
    Column(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(CARD_BACK_ASPECT_RATIO)
                .clip(CardBackShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            AppImage(
                model = (cardBack.imageV2 ?: cardBack.image)?.takeIf { it.isNotBlank() },
                // 卡背接口没有名字字段（只有 id），装饰性内容不给读屏播报
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(if (obtained) 1f else LOCKED_ALPHA),
                contentScale = ContentScale.Fit,
            )
            if (!obtained) {
                Text(
                    stringResource(R.string.my_cardback_locked),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(4.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.6f))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                )
            }
        }
    }
}
