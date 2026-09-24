// 全局点击节流：逐字对照 Web 版 utils/throttle.ts（原 main.lua / rank.lua / cards.lua / cover.lua 的
// is_clickable / last_click 语义）—— 首次调用放行，其后 delayMs 内的
// 调用一律忽略（"降低服务器压力"）。实例需在模块/页面级持有才构成全局节流。

package com.gigi.tcg.domain

class Throttle(
    private val delayMs: Long = 1000,
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    private var last = 0L

    operator fun invoke(): Boolean {
        val current = now()
        if (current - last < delayMs) {
            return false
        }
        last = current
        return true
    }
}
