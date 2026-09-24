status: done
owner: T4a
files: [data/auth/QrSession.kt, data/auth/AuthManager.kt, test data/auth/CredentialExchangeTest.kt]
evidence: [":app:assembleDebug => 0", ":app:testDebugUnitTest => 0 (95 tests = 83 + 12 new)", "commits 32bb430/c66afe5/c5bdba5"]
blockers: []
summary: 扫码登录编排完成：QrSession(Created/Polling/Scanned/Confirmed/Expired-3501/Cancelled-3505)；AuthManager.createQrLogin(直连 createQRLogin, x-rpc-device_id=UUID, x-rpc-app_id=bll8iq97cem8)/pollQrStatus(Flow+delay2500, 单次失败发 Polling 不终止)/finalize(§3.2 四步, NoRole 零副作用, 仅含 e_hk4e_token 才 save)/mergeFragments(mjs 同语义纯函数)；新增 JVM 测试 12 个
updated: 2026-09-25 01:05
