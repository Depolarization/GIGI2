// V10-B：结果弹窗只在「检查到终态」时弹——isCheckFinished 是唯一的判定口径，
// Idle / Checking 若被误判为终态，关于对话框会在用户没点过按钮时凭空消失。

package com.gigi.tcg.ui.about

import com.gigi.tcg.data.github.UpdateCheckResult
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateStateTest {

    @Test
    fun `Idle 与 Checking 不算终态`() {
        assertFalse(UpdateState.Idle.isCheckFinished)
        assertFalse(UpdateState.Checking.isCheckFinished)
    }

    @Test
    fun `UpToDate Available Failed 三种终态都要弹结果`() {
        assertTrue(UpdateState.UpToDate(source = "mirror").isCheckFinished)
        assertTrue(
            UpdateState.Available(
                UpdateCheckResult(
                    hasUpdate = true,
                    latestVersionName = "99.0.0",
                    latestVersionCode = 9900,
                    releaseNotes = null,
                    downloadUrl = null,
                    source = "github-api",
                ),
            ).isCheckFinished,
        )
        assertTrue(UpdateState.Failed(message = "timeout").isCheckFinished)
    }
}
