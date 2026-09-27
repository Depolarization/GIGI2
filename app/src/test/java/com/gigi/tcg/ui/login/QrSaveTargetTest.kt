// 二维码保存目标 + 已保存二维码删除决策的纯 JVM 单测（V27-E 契约钉死）：
// ① 落盘目标 = Pictures/GIGI/<export_dir_qr>/（不带 UID 一级）、文件名前缀 = login_qr_title
//    本地化文案（实测产物 `扫码登录_2026-09-27.png`）、格式恒 PNG（JPEG 压黑白码出噪点）；
// ② 只有「登录成功」这一刻删已保存的二维码，换码/取消/无角色一律保留。
// 渲染/落盘本身依赖 android.graphics 与 MediaStore，工程无 Robolectric，不在此覆盖。

package com.gigi.tcg.ui.login

import android.graphics.Bitmap
import com.gigi.tcg.R
import com.gigi.tcg.ui.dialogs.cardcover.EXPORT_DIR_QR
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QrSaveTargetTest {

    @Test
    fun `二维码落盘子目录按 V27 契约指向 export_dir_qr 资源`() {
        val target = qrSaveTarget()
        assertEquals(EXPORT_DIR_QR, target.dirRes)
        // EXPORT_DIR_QR 与字符串资源 id 同源（G 冻结契约：目录名走本地化，不再是硬编码常量）
        assertEquals(R.string.export_dir_qr, target.dirRes)
    }

    @Test
    fun `二维码文件名前缀沿用扫码登录文案口径`() {
        val target = qrSaveTarget()
        // 前缀 = R.string.login_qr_title（「扫码登录」），添加账户分支也不改口径，
        // 保证历史文件名 `扫码登录_2026-09-27.png` 不漂移
        assertEquals(R.string.login_qr_title, target.fileNamePrefixRes)
        assertEquals("扫码登录_2026-09-27", qrSaveBaseName("扫码登录", "2026-09-27"))
    }

    @Test
    fun `二维码恒为 PNG 格式`() {
        // 黑白码走 JPEG 会压出噪点、影响扫码（历史决策，不得改回 JPEG）
        assertEquals(Bitmap.CompressFormat.PNG, qrSaveTarget().format)
    }

    @Test
    fun `仅登录成功时删除已保存的二维码`() {
        assertTrue(shouldDeleteSavedQr(QrLifecycleEvent.LoginSucceeded))
    }

    @Test
    fun `换码取消与无角色均保留已保存的二维码`() {
        assertFalse(shouldDeleteSavedQr(QrLifecycleEvent.Refreshed))
        assertFalse(shouldDeleteSavedQr(QrLifecycleEvent.Cancelled))
        assertFalse(shouldDeleteSavedQr(QrLifecycleEvent.NoRole))
    }
}
