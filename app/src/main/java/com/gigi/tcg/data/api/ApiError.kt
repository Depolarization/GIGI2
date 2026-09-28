// API 传输层错误语义：移植 web/src/api/client.ts 的 ApiError 与 retcode 集中判定
// （注释钉死"集中在此处判定，禁止各页面自行猜测"——设计红线 2）。

package com.gigi.tcg.data.api

/** kind 取值：retcode=米哈游业务失败；network=网络层失败（收不到米哈游 JSON）；throttled=详情节流拒绝 */
const val API_ERROR_KIND_RETCODE: String = "retcode"
const val API_ERROR_KIND_NETWORK: String = "network"
const val API_ERROR_KIND_THROTTLED: String = "throttled"

// i18n：本文件内的可读文案常量恒为简中；三语通道见 com.gigi.tcg.i18n.apiErrorText /
// apiErrorMessageRes（数据层不 import R，映射放在 i18n 层）。

/**
 * retcode 语义（按实测观测，集中在此处判定）：
 * - 凭据失效：仅这两个码代表"需要重新登录"，其余 retcode 一律不得据此登出；
 * - 限流/繁忙：hk4e-api 系列接口对并发/连续请求有严格限流，实测主页两个接口
 *   约 1/3 概率返回 -500004「操作频繁，请稍后再试」（带真实有效凭据时同样出现），
 *   属于可重试的瞬态失败。
 */
val AUTH_FAILED_RETCODES: Set<Int> = setOf(-100, -101)
val RETRYABLE_RETCODES: Set<Int> = setOf(-500004, -1, -110)

/**
 * 需要人机验证（CAPTCHA），目前已知唯一取值 1034。
 *
 * 🔴 实测（2026-09-28，见 .task/p1-gcg-samples/FINDINGS.md §7/§8）：`gcg/basicInfo` 在真实登录态下
 * 恒返回该码，服务端 message 为空串 ⇒ 被 MihoyoClient 兜底成「接口返回 retcode=1034」原样透传。
 * 7 组请求头变体（client_type 1/2/4/5、去 x-rpc-*、加 lang、换 UA）与 DS 签名**全部仍为 1034**
 * ⇒ 这是**账号/凭据级风控**，重试与改请求形态都无效；唯一解法是用户去米游社 App
 * 「我的 → 我的角色」按提示完成人机验证。
 *
 * 因此它**既不进** [AUTH_FAILED_RETCODES]（不代表凭据失效，不得据此登出），
 * 也**不进** [RETRYABLE_RETCODES]（重试不可能成功，别浪费一次请求）。
 */
val CAPTCHA_REQUIRED_RETCODES: Set<Int> = setOf(1034)

class ApiError(
    val kind: String,
    message: String,
    val retcode: Int? = null,
) : Exception(message)

/** 是否属于"凭据失效"（唯一应当触发重新登录的情形） */
fun isAuthFailureError(t: Throwable): Boolean =
    t is ApiError && t.kind == API_ERROR_KIND_RETCODE && AUTH_FAILED_RETCODES.contains(t.retcode ?: 0)

/**
 * 错误 → 用户可读文案（限流与网络失败给出不同指引）。
 * @Deprecated：内部常量为简中硬编码，三语文案请走 `com.gigi.tcg.i18n.apiErrorText(t)`
 * （资源接线完成前两者行为一致）。
 */
@Deprecated("改用 com.gigi.tcg.i18n.apiErrorText()：按系统语言取三语文案")
fun describeApiError(t: Throwable): String {
    if (t is ApiError) {
        if (RETRYABLE_RETCODES.contains(t.retcode ?: 0)) return "请求过于频繁，请稍后重试"
        if (t.kind == API_ERROR_KIND_THROTTLED) return "请求过于频繁，请稍后重试"
        if (CAPTCHA_REQUIRED_RETCODES.contains(t.retcode ?: 0)) return "米游社要求完成人机验证，请在米游社 App 中验证后重试"
        if (t.kind == API_ERROR_KIND_RETCODE) return t.message ?: "接口返回异常，请稍后重试"
    }
    return "请检查网络重试"
}
