package com.gigi.tcg.data

/**
 * 服务器注册表，移植 web/src/config/servers.ts：展示名称/标识符的唯一数据源。
 * 全应用只允许从这里读取服务器信息，禁止硬编码标识符或名称。
 */
sealed class ServerId(val id: String, val name: String, val shortName: String) {
    /** 官服（天空岛）cn_gf01 */
    data object Official : ServerId("cn_gf01", "官服（天空岛）", "官服")

    /** 渠道服（世界树）cn_qd01 */
    data object Channel : ServerId("cn_qd01", "渠道服（世界树）", "渠道服")

    companion object {
        /** 全部可选服务器（切换控件渲染顺序即此列表顺序） */
        val ALL: List<ServerId> = listOf(Official, Channel)

        /** 默认服务器：官服——未选择或标识符非法时的兜底目标 */
        val DEFAULT: ServerId = Official

        /** 按标识符精确查找（未命中返回 null，不做兜底；非注册表值一律不合法） */
        fun from(id: String?): ServerId? = ALL.firstOrNull { it.id == id }

        /** servers.ts isServerId 语义 */
        fun isValid(id: String?): Boolean = from(id) != null
    }
}

/** servers.ts resolveServer 返回结构：fallback 为 true 时调用方应给出明确提示 */
data class ServerResolution(val server: ServerId, val fallback: Boolean)

/** 标识符 → 服务器，未选择（空白/空值）或非法时兜底到默认服务器并标记 fallback */
fun resolveServerWithFallback(id: String?): ServerResolution {
    val hit = ServerId.from(id)
    return if (hit != null) ServerResolution(hit, fallback = false)
    else ServerResolution(ServerId.DEFAULT, fallback = true)
}

/** 另一个服务器（二选一切换控件用） */
fun otherServer(id: ServerId): ServerId = ServerId.ALL.first { it != id }
