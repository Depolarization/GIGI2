// 展示格式化：对局时间、积分变化等 UI 文案。逐字对照 Web 版 utils/format.ts。
// 说明：throttle.test.ts 中内嵌了 formatRecordTime / formatScoreChange 的用例，故随行移植。

package com.gigi.tcg.domain

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.floor

/**
 * 对局时间：原版为 "M-D H:m"，现代化为 "MM-DD HH:mm"（本地时区，语义不变）。
 *
 * V25（用户 2026-09-27 拍板）：记录年份与「当前年份」不同时补两位年份前缀，
 * 即 "yy-MM-DD HH:mm"（如 `26-08-12 14:19`）—— 否则跨年后回看，`08-12 14:19`
 * 会被误读成今年。同年维持原样，不引入多余噪音。
 *
 * @param nowMillis 「当前时刻」，默认取系统时间；显式注入供单测固定年份。
 */
fun formatRecordTime(timestampSec: Any?, nowMillis: Long = System.currentTimeMillis()): String {
    val sec = when (timestampSec) {
        is Number -> timestampSec.toDouble()
        is String -> timestampSec.toDoubleOrNull() ?: return ""
        else -> return ""
    }
    if (!sec.isFinite()) {
        return ""
    }
    return try {
        val millis = floor(sec * 1000).toLong()
        // SimpleDateFormat 非线程安全，故每次调用新建，不做共享实例
        val pattern = if (yearOf(millis) == yearOf(nowMillis)) "MM-dd HH:mm" else "yy-MM-dd HH:mm"
        SimpleDateFormat(pattern, Locale.US).format(Date(millis))
    } catch (e: Exception) {
        ""
    }
}

/** 本地时区下的年份。用 Calendar 而非 java.time：minSdk 24，java.time 要 API 26。 */
private fun yearOf(millis: Long): Int =
    Calendar.getInstance().apply { timeInMillis = millis }.get(Calendar.YEAR)

/** 积分变化展示：(123) / (+45) / (-67) —— 原版 format("(%d)") 不带正负号，正数无前缀 */
fun formatScoreChange(change: Int): String = "($change)"
