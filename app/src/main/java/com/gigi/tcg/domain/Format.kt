// 展示格式化：对局时间、积分变化等 UI 文案。逐字对照 Web 版 utils/format.ts。
// 说明：throttle.test.ts 中内嵌了 formatRecordTime / formatScoreChange 的用例，故随行移植。

package com.gigi.tcg.domain

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.floor

/** 对局时间：原版为 "M-D H:m"，现代化为 "MM-DD HH:mm"（本地时区，语义不变） */
fun formatRecordTime(timestampSec: Any?): String {
    val sec = when (timestampSec) {
        is Number -> timestampSec.toDouble()
        is String -> timestampSec.toDoubleOrNull() ?: return ""
        else -> return ""
    }
    if (!sec.isFinite()) {
        return ""
    }
    return try {
        // SimpleDateFormat 非线程安全，故每次调用新建，不做共享实例
        SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(floor(sec * 1000).toLong()))
    } catch (e: Exception) {
        ""
    }
}

/** 积分变化展示：(123) / (+45) / (-67) —— 原版 format("(%d)") 不带正负号，正数无前缀 */
fun formatScoreChange(change: Int): String = "($change)"
