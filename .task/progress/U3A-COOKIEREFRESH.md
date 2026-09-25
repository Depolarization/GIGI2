# U3A-COOKIEREFRESH 静默续命下沉请求层

状态: done（机制/单测/构建全达标） · 真机运行期验证 blocked：待用户扫码
开始: 2026-09-25  收工: 2026-09-25

## 目标
运行期任一请求鉴权失败（retcode -100/-101）→ 静默续命一次 → 原请求原样重放；续命/重放失败才上抛。

## 现状勘察（只读）
- ApiError.kt:18 `AUTH_FAILED_RETCODES={-100,-101}`、`isAuthFailureError()` 唯一判据（AppGate.kt:115 同款）→ 直接复用，ApiError.kt 无需改动（保持零 diff）。
- GigiRepository `get()` 是全部 11 个公开接口的唯一私有出口 → 挂载点收敛一处；TTL/磁盘/LRU 缓存层无冲突。
- `AuthManager.refreshStoredSession(account)` 需 activeAccount（CredentialStore 提供），repository 无凭据层引用 → 端口注入承接。

## 实现（commit 1a763bf，仅 GigiRepository.kt）
- 端口 `interface SessionRefresher { suspend fun refreshActive(): Boolean }`（与 GigiApiTransport/WikiDiskStore 同风格）；
  构造参数 `sessionRefresher: SessionRefresher? = null` 追加末位 → AppContainer 现有调用零改动（编译通过）。
- `get()`：请求前读 generation → ApiError 且 isAuthFailureError → 单飞续命 → 同 URL 原样重放一次（calls[0]==calls[1]）；
  重放在 try 之外（二次失败直抛）；续命失败抛原错误（上层仍可 isAuthFailureError 感知"登录已失效"）。
- 单飞（只此一处）：`refreshMutex: Mutex` + `@Volatile refreshGeneration` + `lastRefreshSucceeded`。
  同波（generation 未变）只执行一次真实续命，成败均推进 generation 并记录结果；并发等待者经锁串行醒来后复用结果。
  续命自身异常折算 false（CancellationException 原样传播）。
- 递归保护：续命走 AuthManager 自有 OkHttp 栈不经本层；重放无捕获，天然无循环。

## 测试（commit 6207c1f，CookieRefreshRetryTest.kt，6/6 通过）
- ① 首发 -100 → 续命成功 → 同 URL 重放成功
- ② 续命失败（返回 false / 抛 IOException 两分支）→ 抛原 ApiError、续命仅 1 次、零重放
- ③ 并发 6 请求同波失败 → refresh 仅调 1 次、6 个全部重放（transport 共 16 次调用）
- ④ 非鉴权（业务 retcode 12345 / 解析失败 / IO 异常）→ 零续命
- 增强：未接线（refresher=null）保持旧行为；并发+续命失败不重试风暴（refresh 1 次、零重放）

## 数字
- 基线: 19 个测试类 / 121 用例 / failures=0（改动前 test-results XML 实核）
- 改动后: 20 个测试类 / 127 用例 / failures=0 / errors=0（`:app:testDebugUnitTest --rerun-tasks` BUILD SUCCESSFUL）
- 编译: `:app:compileDebugKotlin` BUILD SUCCESSFUL；`assembleDebug` BUILD SUCCESSFUL

## ④ grep 自查（调用点收敛一处）
```
$ grep -n "refreshStoredSession\|Mutex\|single\|retry\|attempt" .../GigiRepository.kt
43:import kotlinx.coroutines.sync.Mutex
107:/** 静默续命端口：真身接线 = CredentialStore 取当前账户 → AuthManager.refreshStoredSession；
119:    private val retryDelayMs: Long = MihoyoClient.RETRY_DELAY_MS,
120:    private val retryJitterMs: Long = MihoyoClient.RETRY_JITTER_SPAN_MS,
236:    private val refreshMutex = Mutex()
258:     *  否则在锁内真正执行一次（并发调用者经 Mutex 串行等待）。续命自身异常折算 false，由调用方抛原错误。 */
259:    private suspend fun refreshSessionOnce(observedGeneration: Long): Boolean = refreshMutex.withLock {
278:            delay(retryDelayMs + if (retryJitterMs > 0) Random.nextLong(0L, retryJitterMs + 1) else 0L)

$ grep -n "refreshActive\|refreshSessionOnce" .../GigiRepository.kt
111:    suspend fun refreshActive(): Boolean
252:            if (!isAuthFailureError(e) || !refreshSessionOnce(observedGeneration)) throw e
259:    private suspend fun refreshSessionOnce(observedGeneration: Long): Boolean = refreshMutex.withLock {
262:                sessionRefresher?.refreshActive() == true
```
→ `refreshSessionOnce` 调用点 1 处（252，get 内）；`refreshActive()` 真实调用 1 处（262，单飞锁内）。

## 真机（Redmi Note 7 / ac9bcc9a / boot_id b649d7e7-9d11-4d7b-b44f-1dac7c98a995）
- `install -r` 成功 → 启动落登录页（QR），符合预期：真机无凭据
- 无 FATAL：`logcat -d | grep -c -i "FATAL EXCEPTION"` = 0；mCurrentFocus = com.gigi.tcg/MainActivity
- 证据: .task/evidence/V3A/01-启动后.png（现场实截）
- 🔴 **blocked：真机需用户扫码，未做运行期续命的真机验证**（按要求立刻停手；未清数据、未反复重装）

## 阻塞 / 待接线（未越界，交主代理决策）
1. **生产接线未完成**：AppContainer.kt 本轮只读。需后续任务把
   （CredentialStore.activeUid 取账户 → AuthManager.refreshStoredSession → Success 时 refreshAccounts()+updateSession）
   适配为 SessionRefresher 传入 GigiRepository。未接线时行为 = 现状（鉴权失败直抛原错误），零副作用。
2. 续命失败回登录页（updateSession(null) + 导航）属 UI/容器层职责，本轮未做；数据层只抛 isAuthFailureError 信号，不弹 UI。

## 附录：基线复跑撞车说明
开工后的后台基线单测撞上并行 U3B-WIKIREDESIGN 改 CardWikiRoute.kt 的中间态（6 条 unresolved import），
BUILD FAILED 与本次改动无关；U3B 完成后编译恢复且其探针记录同样 121/0，基线数字以改动前 XML 为准。
