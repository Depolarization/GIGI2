package com.gigi.tcg.i18n

import androidx.annotation.StringRes
import com.gigi.tcg.R
import com.gigi.tcg.data.api.API_ERROR_KIND_NETWORK
import com.gigi.tcg.data.api.API_ERROR_KIND_RETCODE
import com.gigi.tcg.data.api.API_ERROR_KIND_THROTTLED
import com.gigi.tcg.data.api.ApiError
import com.gigi.tcg.data.api.CAPTCHA_REQUIRED_RETCODES
import com.gigi.tcg.data.api.THROTTLED_FALLBACK_RETCODES
import com.gigi.tcg.data.api.describeApiError

/**
 * API 错误的三语通道：错误 → @StringRes → 当前系统语言文案。
 *
 * 分类语义与 [describeApiError] 大体一致（限流/网络/CAPTCHA 同一判据，勿单改一边）；
 * 服务端 retcode 消息不可本地化，按产品策略原样透传。Composable 上下文优先
 * `stringResource(apiErrorMessageRes(t)!!)`，非 Compose 层用 [apiErrorText]
 * （依赖 [LocaleStrings.attach]，未注册时回落简中）。
 * ⚠️ 唯一有意分叉见下：`-1` 在本函数走透传（返回 null），[describeApiError] 的简中兜底
 * 仍把 `-1` 归入 RETRYABLE；但 [apiErrorText] 对 null 直接 return 服务端原文，
 * 永远不会拿 `-1` 去调 [describeApiError]，故用户可见路径无残留误标。
 *
 * 🔴「限流文案」判据（V36-3c 起改为**按 kind 语义 + 折算集合判**，不再按 retcode 全集判）：
 * 数据层 [com.gigi.tcg.data.api.MihoyoClient] 只会把「真限流/繁忙码」——即
 * [THROTTLED_FALLBACK_RETCODES]（= RETRYABLE 扣掉 PARAM_ERROR）——在重试耗尽后折算成
 * `kind=throttled`；**参数错误码 `-1` 不折算**，原样按 retcode + 服务端原文上抛。
 * 故这里若仍写 `RETRYABLE_RETCODES.contains(retcode)`，`-1`（∈ RETRYABLE）会命中限流分支 ⇒
 * 用户参数写错却被提示「请求过于频繁，请稍后重试」，指引完全错位（V36-5 实测
 * `{"retcode":-1,"message":"param role_id error…"}`）。改用 [THROTTLED_FALLBACK_RETCODES] 后：
 * 既保留「未折算的 -500004/-110 也给限流文案」的兜底，又**硬性排除 -1**。
 * **勿改回 RETRYABLE_RETCODES**（单测「-1 参数错误不得显示限流文案」钉死此约束）。
 */
@StringRes
fun apiErrorMessageRes(t: Throwable): Int? = when {
    t !is ApiError -> R.string.error_check_network
    t.kind == API_ERROR_KIND_THROTTLED || THROTTLED_FALLBACK_RETCODES.contains(t.retcode ?: 0) ->
        R.string.error_throttled
    // 1034 必须在 message.isNullOrBlank() 之前判：风控时服务端 message 为空串，但 MihoyoClient
    // 兜底成「接口返回 retcode=1034」⇒ 非空，放到后面永远命中不到（FINDINGS §8.2）
    CAPTCHA_REQUIRED_RETCODES.contains(t.retcode ?: 0) -> R.string.error_captcha_required
    t.kind == API_ERROR_KIND_NETWORK -> R.string.error_check_network
    t.message.isNullOrBlank() -> R.string.error_api_generic
    else -> null // 服务端原样消息，无本地化资源
}

/** 错误 → 三语文案；服务端消息透传；桥未注册时回落简中（与 describeApiError 同结果） */
fun apiErrorText(t: Throwable): String {
    val res = apiErrorMessageRes(t)
        ?: return (t as ApiError).message.orEmpty()
    return if (LocaleStrings.resolved) {
        LocaleStrings.get(res)
    } else {
        @Suppress("DEPRECATION")
        describeApiError(t)
    }
}
