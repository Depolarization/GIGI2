status: doing
owner: P4-ACCOUNT
files: []
evidence: []
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
updated: 2026-09-25 11:18
