// 更新检查的 UI 状态机（V9-G）。
// 只描述「用户看到什么」，判定逻辑一律留在 data/github 的纯函数里：
// 同一个 source 字段在 UpToDate 上保留，是因为「镜像说的没更新」与「API 说的没更新」可信度不同，
// 排障时要在 UI 上直接看出来，不必再翻 logcat。

package com.gigi.tcg.ui.about

import com.gigi.tcg.data.github.UpdateCheckResult

sealed interface UpdateState {
    /** 还没点过「检查更新」 */
    data object Idle : UpdateState

    data object Checking : UpdateState

    /** 已是最新（含「仓库尚无 release」这一正常态，数据层已折算成 hasUpdate=false） */
    data class UpToDate(val source: String) : UpdateState

    data class Available(val info: UpdateCheckResult) : UpdateState

    /** 两条路径（镜像 + 官方 API）都失败；message 面向排障，可为空 */
    data class Failed(val message: String? = null) : UpdateState
}
