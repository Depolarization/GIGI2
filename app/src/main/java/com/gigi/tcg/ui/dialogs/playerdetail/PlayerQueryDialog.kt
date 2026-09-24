// 玩家查询弹窗：移植 web/src/components/PlayerQueryDialog.tsx。
// 校验对齐原版——仅接受 9 位纯数字，不符合提示"UID不符合格式"且不发请求。
// 本任务不提供全局 controller：合法提交仅回调 onSubmit(uid)，由调用页负责关闭并 openPlayerDetail。

package com.gigi.tcg.ui.dialogs.playerdetail

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.KeyboardType

@Composable
fun PlayerQueryDialog(onSubmit: (String) -> Unit, onClose: () -> Unit) {
    var text by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }

    fun submit() {
        val trimmed = text.trim()
        if (trimmed.length != 9 || trimmed.any { !it.isDigit() }) {
            error = "UID不符合格式"
            return
        }
        error = ""
        onSubmit(trimmed)
    }

    AlertDialog(
        onDismissRequest = {
            error = ""
            onClose()
        },
        title = { Text("玩家查询") },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = {
                        text = it.filter(Char::isDigit).take(9)
                        error = ""
                    },
                    label = { Text("输入玩家UID") },
                    singleLine = true,
                    isError = error.isNotEmpty(),
                    supportingText = { if (error.isNotEmpty()) Text(error) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { submit() }) { Text("查询") }
        },
        dismissButton = {
            TextButton(onClick = {
                error = ""
                onClose()
            }) { Text("取消") }
        },
    )
}
