package com.gigi.tcg.i18n

import androidx.annotation.StringRes
import com.gigi.tcg.R
import com.gigi.tcg.data.api.API_ERROR_KIND_NETWORK
import com.gigi.tcg.data.api.API_ERROR_KIND_RETCODE
import com.gigi.tcg.data.api.API_ERROR_KIND_THROTTLED
import com.gigi.tcg.data.api.ApiError
import com.gigi.tcg.data.api.RETRYABLE_RETCODES
import com.gigi.tcg.data.api.describeApiError

/**
 * API 错误的三语通道：错误 → @StringRes → 当前系统语言文案。
 *
 * 分类语义与 [describeApiError] 严格一致（勿单改一边）；服务端 retcode 消息不可本地化，
 * 按产品策略原样透传。Composable 上下文优先 `stringResource(apiErrorMessageRes(t)!!)`，
 * 非 Compose 层用 [apiErrorText]（依赖 [LocaleStrings.attach]，未注册时回落简中）。
 */
@StringRes
fun apiErrorMessageRes(t: Throwable): Int? = when {
    t !is ApiError -> R.string.error_check_network
    RETRYABLE_RETCODES.contains(t.retcode ?: 0) || t.kind == API_ERROR_KIND_THROTTLED ->
        R.string.error_throttled
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
