// UID → code 编码：逐字对照 Web 版 utils/code.ts（源自 utils.lua id2code / generate_code）。
//
// 关键点（与直觉不同，勿"修正"）：
// - 字符表顺序固定，不是标准 base36 字符表；
// - 36 进制逐位取余，低位在前、不反转；
// - 结果固定包裹为 "CA0" + 累积字符 + "N"；
// - 组装 JSON 字符串 `{"Code":"<code>","Season":<season>}` 后做标准 Base64。

package com.gigi.tcg.domain

import java.util.Base64
import kotlin.math.floor

private const val CHARSET = "528XM1TVN6UHQ9DLG4R3CZSYB7W0FPIEOAJK"

fun id2code(x: Long): String {
    val result = StringBuilder()
    var value = floor(x.toDouble()).toLong()
    while (value > 0) {
        val remainder = (value % 36).toInt()
        result.append(CHARSET[remainder])
        value = floor(value.toDouble() / 36).toLong()
    }
    return "CA0${result}N"
}

fun generateCode(uid: Long, season: Int = 7): String {
    val jsonStr = """{"Code":"${id2code(uid)}","Season":$season}"""
    // jsonStr 仅含 ASCII 字符，Base64 安全
    return Base64.getEncoder().encodeToString(jsonStr.toByteArray(Charsets.US_ASCII))
}
