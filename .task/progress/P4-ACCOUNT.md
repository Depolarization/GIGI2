status: done
owner: P4-RESUME
files: [data/auth/AuthManager.kt, data/auth/CredentialStore.kt, di/AppContainer.kt, ui/login/AppGate.kt, ui/login/LoginScreen.kt, ui/login/LoginViewModel.kt, ui/navigation/GigiNavHost.kt, test/data/auth/CredentialStoreTest.kt]
evidence: [":app:assembleDebug => 0", ":app:testDebugUnitTest => 0", ":app:lintDebug => 0"]
blockers: []
summary: >-
  设计（⑧⑨⑩）：CredentialStore 多槽——Keystore 别名 gigi_credentials_key_<uid>（isSafeUid 白名单防注入），
  密文 prefs 键 ciphertext_<uid>；账户索引（uid/nickname/serverId/lastActiveEpochMs）序列化为 JSON 存同 prefs 的
  index 键，头=最近使用；activeUid 独立键。cookieHeader()=激活槽，无激活回退旧版单槽（兼容 androidTest 与升级用户，
  observeMain 验证成功时 adoptActiveCookie 收养为账户）。⑧：顶栏删服务器菜单，服务器与账户登录时绑定。
  ⑨：顶栏账户菜单（当前昵称入口，行=昵称（服务器短名）+当前√）→ 切 sessionUid/currentServer（key(server) 重建即重载）、
  添加账户（Login(addAccount) 扫码不覆盖既有）、退出当前账户；退出/移除前 AlertDialog 二次确认。
  ⑩：Gate 后台校验仅 -100/-101 时静默重跑 finalize 第①②步（getGameRecordCard 发现 uid + badge login/account 重取
  e_hk4e_token）→ 合并落盘续命留主界面；失败踢登录页。如实注明：能静默续的是 e_hk4e 会话，cookie_token_v2 有期
  （数天~数周），彻底过期仍需重扫。纯函数（索引序列化/键派生）配 JVM 单测。
  注：app/src/test/java/com/gigi/tcg/ui/ 为他代理未跟踪文件（StatsFormatTest），本棒不触碰、不 add。
updated: 2026-09-25 12:15

## RESUME
- 接续修复 CredentialStore 列表序列化，并完成多账户存储/切换/添加/退出确认接线。
- AuthManager 保存正式账户索引；Gate 对 -100/-101 仅用当前账户重新交换新 e_hk4e_token，失败回登录。
- JVM 覆盖 uid 白名单、键派生、索引编码/解析及新 token 判定。
- 验证：assembleDebug=0；lintDebug=0；全量 testDebugUnitTest=0（含 P4 CredentialStoreTest）。
- 提交：14812c2、62b2c7c、5ceef61、2f68134。

