package com.gigi.tcg.ui.dialogs.about

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

// 说明/关于：移植 Web 版 AboutDialog.tsx 文案与分区结构，适配 M3 Dialog。

private data class Fact(val label: String, val value: String)

private val PROJECT_FACTS = listOf(
    Fact("项目名", "GIGI（Genshin Impact Genius Invokation TCG Tool）"),
    Fact("性质", "免费开源项目，仅供学习交流使用"),
    Fact("形态", "网页应用 —— 浏览器打开即用，米游社或云·原神扫码登录"),
)

private val USAGE_TIPS = listOf(
    "与抽卡记录类似，新对局数据的获取存在一定延迟；若未即时获取到最新记录，请稍后重试。",
    "急着更新数据时，请小退原神（返回开门界面）后再次进门，可加快数据更新速度。",
    "首页的对局查询最多可查看最近十局的对局详情，单击列表任一项即可查看对手信息。",
    "顶栏的玩家查询功能支持手动输入 UID，查询该玩家的七圣相关信息。",
    "鉴于赛事已经关停，无法确保赛事信息部分长期有效。",
)

private val DATA_SOURCE_TIPS: List<AnnotatedString.Builder.() -> Unit> = listOf(
    {
        append("• ")
        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("卡面下载") }
        append("数据无需登录即可浏览；")
        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("卡牌使用详情") }
        append("需要登录凭据支持。")
    },
    { append("• 所有信息均来自七圣赛事与米游社，卡牌数据及卡面图片来自米游社七圣 Wiki。") },
    { append("• 登录凭据仅保存在本机浏览器，不会上传至任何服务器。") },
)

@Composable
fun AboutDialog(onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("说明") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                SectionTitle("关于本软件")
                PROJECT_FACTS.forEach { fact ->
                    AboutParagraph {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(fact.label) }
                        append("：${fact.value}")
                    }
                }

                SectionTitle("使用要点")
                USAGE_TIPS.forEach { tip ->
                    AboutParagraph { append("• $tip") }
                }

                SectionTitle("数据来源与权限")
                DATA_SOURCE_TIPS.forEach { build ->
                    AboutParagraph(build = build)
                }

                SectionTitle("原理与致谢")
                AboutParagraph {
                    append("最早借助七圣赛事官网查询对手信息的原理见")
                    appendLink("B 站原理讲解视频", "https://www.bilibili.com/video/BV1boF5z6E9G")
                    append("。")
                }
                AboutParagraph {
                    append("项目基于米游社公开接口与官方赛事数据构建。感谢所有提供公开数据与接口研究资料的社区贡献者。")
                }

                SectionTitle("反馈")
                AboutParagraph {
                    append("若在使用中遇到任何异常错误，欢迎通过")
                    appendLink("@Dexphase 的 B 站空间", "https://space.bilibili.com/560719483")
                    append("反馈。")
                }

                SectionTitle("版本")
                AboutParagraph { append("版本 1.0.0") }
            }
        },
        confirmButton = {
            TextButton(onClick = onClose) { Text("关闭") }
        },
    )
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun AboutParagraph(build: AnnotatedString.Builder.() -> Unit) {
    Text(
        text = buildAnnotatedString(build),
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(vertical = 2.dp),
    )
}

private fun AnnotatedString.Builder.appendLink(text: String, url: String) {
    pushLink(LinkAnnotation.Url(url = url))
    withStyle(SpanStyle(textDecoration = TextDecoration.Underline)) { append(text) }
    pop()
}
