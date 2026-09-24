// API 传输层错误语义：移植 web/src/api/client.ts 的 ApiError 与 retcode 集中判定
// （注释钉死"集中在此处判定，禁止各页面自行猜测"——设计红线 2）。

package com.gigi.tcg.data.api

/** kind 取值：retcode=米哈游业务失败；network=网络层失败（收不到米哈游 JSON）；throttled=详情节流拒绝 */
const val API_ERROR_KIND_RETCODE: String = "retcode"
const val API_ERROR_KIND_NETWORK: String = "network"
const val API_ERROR_KIND_THROTTLED: String = "throttled"

/**
 * retcode 语义（按实测观测，集中在此处判定）：
 * - 凭据失效：仅这两个码代表"需要重新登录"，其余 retcode 一律不得据此登出；
 * - 限流/繁忙：hk4e-api 系列接口对并发/连续请求有严格限流，实测主页两个接口
 *   约 1/3 概率返回 -500004「操作频繁，请稍后再试」（带真实有效凭据时同样出现），
 *   属于可重试的瞬态失败。
 */
val AUTH_FAILED_RETCODES: Set<Int> = setOf(-100, -101)
val RETRYABLE_RETCODES: Set<Int> = setOf(-500004, -1, -110)

class ApiError(
    val kind: String,
    message: String,
    val retcode: Int? = null,
) : Exception(message)

/** 是否属于"凭据失效"（唯一应当触发重新登录的情形） */
fun isAuthFailureError(t: Throwable): Boolean =
    t is ApiError && t.kind == API_ERROR_KIND_RETCODE && AUTH_FAILED_RETCODES.contains(t.retcode ?: 0)

/** 错误 → 用户可读文案（限流与网络失败给出不同指引） */
fun describeApiError(t: Throwable): String {
    if (t is ApiError) {
        if (RETRYABLE_RETCODES.contains(t.retcode ?: 0)) return "请求过于频繁，请稍后重试"
        if (t.kind == API_ERROR_KIND_THROTTLED) return "请求过于频繁，请稍后重试"
        if (t.kind == API_ERROR_KIND_RETCODE) return t.message ?: "接口返回异常，请稍后重试"
    }
    return "请检查网络重试"
}
