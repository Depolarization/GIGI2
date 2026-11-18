// 下载资源组合（报告 §7）：一个对话框只做一件事 —— 确认要保存的资源。
// 这里只做纯 Kotlin 的组合判定（无 Composable / 无 Context），单测直接锁死 §7.1 特例矩阵：
// 普通+动态 → 两个选项；只有一种 → 一个选项（不渲染格式选择器）；两者皆无 → 不打开对话框。
// 技能图标永远只有一个选项、没有动态版本。

package com.gigi.tcg.ui.screens.carddetail

import androidx.annotation.StringRes
import com.gigi.tcg.R
import com.gigi.tcg.data.model.CardBasicInfo
import com.gigi.tcg.domain.CardSkill
import com.gigi.tcg.ui.dialogs.cardcover.imageExtensionFromUrl
import com.gigi.tcg.ui.dialogs.cardcover.imageMimeType

/** 落盘目标：卡面沿用旧行为落相册根；技能图标落 Pictures/GIGI/技能图标/<UID>/ */
enum class DownloadDir { AlbumRoot, SkillIcon }

/** 对话框预览形态：卡面 117×200（7:12），图标 96×96 */
enum class DownloadPreview { Cover, Icon }

/**
 * 一个可保存的资源。options.size == 1 时对话框完全不渲染格式选择器（报告 §7 决策④）。
 * [dir] 默认相册根，保持报告里 4 字段的卡面形态；技能图标显式传 [DownloadDir.SkillIcon]。
 */
data class DownloadOption(
    @StringRes val labelRes: Int,
    val url: String,
    val extension: String,
    val mimeType: String,
    val dir: DownloadDir = DownloadDir.AlbumRoot,
)

/** 打开中的下载对话框：预览 + （可选）格式选择 + 保存/取消 */
data class DownloadDialogState(
    @StringRes val titleRes: Int,
    val previewUrl: String,
    val options: List<DownloadOption>,
    val preview: DownloadPreview = DownloadPreview.Cover,
)

object CardDownloads {

    /** 卡面选项：普通（common_img）在前、动态（gold_img）在后；空串视同缺失 */
    fun coverOptions(info: CardBasicInfo): List<DownloadOption> = buildList {
        info.commonImg?.takeIf { it.isNotBlank() }?.let { url ->
            add(DownloadOption(R.string.download_option_common, url, "png", "image/png"))
        }
        info.goldImg?.takeIf { it.isNotBlank() }?.let { url ->
            add(DownloadOption(R.string.download_option_dynamic, url, "gif", "image/gif"))
        }
    }

    /** 技能图标：无图返回 null（图标不可点击、不打开对话框） */
    fun iconOption(skill: CardSkill): DownloadOption? {
        val url = skill.iconUrl?.takeIf { it.isNotBlank() } ?: return null
        val extension = imageExtensionFromUrl(url)
        return DownloadOption(
            labelRes = R.string.download_option_icon,
            url = url,
            extension = extension,
            mimeType = imageMimeType(extension),
            dir = DownloadDir.SkillIcon,
        )
    }

    /** 卡面对话框；两种格式都没有 ⇒ null（下载按钮禁用，不打开对话框） */
    fun coverDialog(info: CardBasicInfo): DownloadDialogState? {
        val options = coverOptions(info)
        if (options.isEmpty()) return null
        return DownloadDialogState(
            titleRes = R.string.download_dialog_title_cover,
            previewUrl = options.first().url,
            options = options,
            preview = DownloadPreview.Cover,
        )
    }

    /** 图标对话框；无图 ⇒ null */
    fun iconDialog(skill: CardSkill): DownloadDialogState? {
        val option = iconOption(skill) ?: return null
        return DownloadDialogState(
            titleRes = R.string.download_dialog_title_icon,
            previewUrl = option.url,
            options = listOf(option),
            preview = DownloadPreview.Icon,
        )
    }
}
