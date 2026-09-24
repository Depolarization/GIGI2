// UID → code 编码：逐字对照 Web 版 utils/code.ts（源自 utils.lua id2code / generate_code）。
//
// 关键点（与直觉不同，勿"修正"）：
// - 字符表顺序固定，不是标准 base36 字符表；
// - 36 进制逐位取余，低位在前、不反转；
// - 结果固定包裹为 "CA0" + 累积字符 + "N"；
// - 组装 JSON 字符串 `{"Code":"<code>","Season":<season>}` 后做标准 Base64。

package com.gigi.tcg.domain

import kotlin.math.floor

private const val CHARSET = "528XM1TVN6UHQ9DLG4R3CZSYB7W0FPIEOAJK"

private const val BASE64_ALPHABET =
    "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

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
    return base64Encode(jsonStr.toByteArray(Charsets.US_ASCII))
}

/** 标准 Base64（RFC 4648，带 = 补位、不换行）；java.util.Base64 需 API 26，故自实现 */
private fun base64Encode(data: ByteArray): String {
    val out = StringBuilder((data.size + 2) / 3 * 4)
    var i = 0
    while (i + 2 < data.size) {
        appendBase64Group(out, readGroup(data, i, 3), 4)
        i += 3
    }
    when (data.size - i) {
        1 -> {
            appendBase64Group(out, readGroup(data, i, 1), 2)
            out.append("==")
        }
        2 -> {
            appendBase64Group(out, readGroup(data, i, 2), 3)
            out.append('=')
        }
    }
    return out.toString()
}

private fun readGroup(data: ByteArray, offset: Int, length: Int): Int {
    var group = 0
    for (b in 0 until length) {
        group = group shl 8 or (data[offset + b].toInt() and 0xFF)
    }
    return group shl (3 - length) * 8
}

private fun appendBase64Group(out: StringBuilder, group: Int, chars: Int) {
    for (c in 0 until chars) {
        out.append(BASE64_ALPHABET[group shr (18 - c * 6) and 0x3F])
    }
}
