package com.gigi.tcg.i18n

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.gigi.tcg.R
import com.gigi.tcg.data.ServerId

/**
 * 服务器显示名的三语通道（放 i18n 包而非数据层：数据层零 Compose / 零 R 依赖）。
 * id → 资源映射的唯一事实源在此，F1 的 strings.xml 键为 server_{id}[_short]。
 */
@StringRes
private fun ServerId.nameRes(): Int = when (this) {
    ServerId.Official -> R.string.server_cn_gf01
    ServerId.Channel -> R.string.server_cn_qd01
}

@StringRes
private fun ServerId.shortNameRes(): Int = when (this) {
    ServerId.Official -> R.string.server_cn_gf01_short
    ServerId.Channel -> R.string.server_cn_qd01_short
}

/** 服务器显示名（跟随系统语言）；非 Compose 上下文用 LocaleStrings.get(...) */
@Composable
fun ServerId.displayName(): String = stringResource(nameRes())

/** 服务器简称（跟随系统语言） */
@Composable
fun ServerId.displayShortName(): String = stringResource(shortNameRes())

/** 非 Compose 通道：按当前系统语言取显示名，未注册桥时回落简中 */
fun ServerId.displayNameSync(): String =
    LocaleStrings.getOrDefault(nameRes(), nameOrDefault())

/** 非 Compose 通道：简称 */
fun ServerId.displayShortNameSync(): String =
    LocaleStrings.getOrDefault(shortNameRes(), shortNameOrDefault())

/**
 * 任意**注册表标识符字符串** → 显示名（跟随系统语言；未注册回落简中）。
 *
 * 与 [ServerId.displayNameSync] 的区别：入参是裸字符串而非 [ServerId]，用于服务端回传的
 * region 标识符（no-role 诊断要展示"角色实际绑在哪台服"，拿到的是 region 字符串）。
 * 🔴 未知标识符原样返回而非丢弃：宁可多显示一个原始值，也不要让诊断信息凭空少一块。
 */
fun serverDisplayNameSync(id: String): String =
    ServerId.from(id)?.displayNameSync() ?: id


