// 扫码登录轮询状态机：语义对齐 web/server/auth-core.mjs qrQuery 与
// 设计文档 §3.1 终止条件（-3501/-3505 换码、Scanned 更新提示、Confirmed 进入交换）。

package com.gigi.tcg.data.auth

/**
 * AuthManager.pollQrStatus 发出的状态流元素。
 * 终态（[Expired]/[Cancelled]/[Confirmed]）之后流结束；[Polling]/[Scanned] 为保持态。
 */
sealed interface QrSession {
    /** 二维码已创建（createQrLogin 成功后的初始态），携带展示与交换所需的 payload */
    data class Created(val url: String, val ticket: String, val deviceId: String) : QrSession

    /** 等待扫码 / 单次轮询失败的保持态：不终止轮询，UI 维持当前二维码 */
    data object Polling : QrSession

    /** 已扫码、待手机确认（上游 data.status == "Scanned"） */
    data object Scanned : QrSession

    /** 已确认：[cookieFragments] 为 Set-Cookie 收集的凭据片段（name=value），供 finalize 交换 */
    data class Confirmed(val cookieFragments: List<String>) : QrSession

    /** 二维码过期（retcode -3501），终止，需换码重新生成 */
    data object Expired : QrSession

    /** 用户在手机上取消（retcode -3505），终止，需换码重新生成 */
    data object Cancelled : QrSession
}
