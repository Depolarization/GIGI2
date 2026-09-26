package com.gigi.tcg.ui.dialogs.about

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.gigi.tcg.R
import com.gigi.tcg.ui.about.AboutScreen
import com.gigi.tcg.ui.about.rememberAboutViewModel

// 说明/关于：弹窗外壳保留（GigiNavHost 以 aboutOpen 直接调本函数，换壳等于改导航），
// 正文与 V9-G 的更新检查/公告/Bug 上报接线一并搬到 ui/about/AboutScreen.kt。
// 原「反馈」按钮指向 B 站主页，该入口仍在正文「反馈」小节，功能未丢。

@Composable
fun AboutDialog(onClose: () -> Unit) {
    val viewModel = rememberAboutViewModel()
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.about_title)) },
        text = { AboutScreen(viewModel = viewModel) },
        confirmButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.action_close)) } },
    )
}
