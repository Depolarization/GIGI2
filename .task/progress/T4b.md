status: done
owner: T4b
files: [app/src/main/java/com/gigi/tcg/di/AppContainer.kt, app/src/main/java/com/gigi/tcg/MainActivity.kt, app/src/main/java/com/gigi/tcg/ui/login/LoginScreen.kt, app/src/main/java/com/gigi/tcg/ui/login/LoginViewModel.kt, app/src/main/java/com/gigi/tcg/ui/login/AppGate.kt]
evidence: [gradlew :app:assembleDebug=0, gradlew :app:testDebugUnitTest=95/0fail, gradlew :app:lintDebug=0errors(5既有warnings)]
blockers: []
summary: 登录页+QR状态机+AppGate门控落地；容器新增 authManager/currentServer/sessionUid（仅内存）

## 登录状态机清单（LoginViewModel）
- Checking → createQrLogin → Qr(Waiting, ZXing BitMatrix→ImageBitmap)
- pollQrStatus collect：Scanned→Qr(Scanned 蒙层"已扫描，请在手机确认")；Polling 超 180s→自动换码
- Expired(-3501)/Cancelled(-3505)→自动 createQrLogin 换码（generation 防旧流程迟到回调）
- Confirmed→Finalizing→finalize(server)：Success→LoggedIn(uid)+容器 sessionUid；
  NoRole→零副作用（AuthManager 保证未写凭据），留登录页、notice"未绑定<服务器>原神角色请换服"、服务器高亮保持；其它 IOException→Failed+重试按钮
- 切服务器（官服/渠道服 FilterChip）：清 notice、重启 QR 流程

## 门控语义（AppGate）
- 启动同步读 credentialStore.cookieHeader()：有→Main(verified=false) 零等待渲染 GigiNavHost；无→Login
- 后台 fetchLoginInfo：成功→写 sessionUid；-100/-101(isAuthFailureError)→Login(expired 横幅)；
  🔴 会话内曾登录时 -500004/网络失败/无 game_uid 一律不踢线（auth.tsx sessionUidRef 语义）
- LoginScreen LoggedIn→Main；logout()=credentialStore.clear()+sessionUid 置空→Login，经 LocalLogout CompositionLocal 暴露（GigiNavHost 未改动）
- 🔴 差异注明：web serverStorage 持久化服务器选择，本工程服务器选择与会话均仅存 AppContainer 内存 StateFlow，不落盘（派单要求）

## 三判据
- assembleDebug: BUILD SUCCESSFUL
- testDebugUnitTest: tests=95 failures=0（总数不变）
- lintDebug: 0 errors, 5 warnings（均为既有：DataExtractionRules/GradleDependency/MissingApplicationIcon/UseKtx×2）

commits: 627c2a8 (auth: 容器) / b53bc35 (ui: 登录页+门控)
updated: 2026-09-25 01:24
