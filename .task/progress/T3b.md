# T3b 探针

status: done
owner: T3b
files: [
  app/src/main/java/com/gigi/tcg/data/api/CredentialSource.kt,
  app/src/main/java/com/gigi/tcg/data/api/ApiError.kt,
  app/src/main/java/com/gigi/tcg/data/api/UrlBuilder.kt,
  app/src/main/java/com/gigi/tcg/data/api/MihoyoClient.kt,
  app/src/test/java/com/gigi/tcg/data/api/ApiErrorTest.kt,
  app/src/test/java/com/gigi/tcg/data/api/UrlBuilderTest.kt
]
evidence: [
  "git status --porcelain -- app/.../data/api=空(改前闸门, HEAD=67e62fd)",
  "commit 172b587 CredentialSource(1 file)",
  "commit 05c4712 ApiError+UrlBuilder+2 tests(4 files, 207 ins)",
  "commit 263f839 MihoyoClient(1 file, 164 ins)",
  "commit be4ab50 UrlBuilder 改引 T3a data.ServerApi 常量(去重, 兄弟提交 7485049 后)"
]
blockers: []
summary: api 层完成：CredentialSource 契约 + retcode 集中判定/文案 + urls.ts 全量构造 + MihoyoClient 三策略，4 commits；未跑 gradle（T3d 集成）。
updated: 2026-09-24 23:50:38

## 验收细节

### 1. CredentialSource（签名逐字符照派单，未改动）
`fun cookieHeader(): String?`；T3c 的 CredentialStore（f85b49c）已 `override fun cookieHeader()` 实现，吻合。

### 2. ApiError.kt ← client.ts
- `ApiError(kind: String, message, retcode: Int? = null) : Exception`（L20-31）
- `AUTH_FAILED_RETCODES = {-100,-101}` / `RETRYABLE_RETCODES = {-500004,-1,-110}`（L40-41）
- `isAuthFailureError`（L44-46）：kind==retcode 且 retcode∈AUTH，`?: 0` 兜底与 web 一致
- `describeApiError`（L49-55）逐字：`请求过于频繁，请稍后重试` / `message ?: 接口返回异常，请稍后重试` / `请检查网络重试`
- kind 常量化：`retcode`/`network`/`throttled`（throttled 为本层新增，见 §4）

### 3. UrlBuilder.kt ← urls.ts 全量逐字
LOGIN_URL(L11)、DEFAULT_AVATAR_URL(L14)、CARD_INFO_URL(L18)、cardDetailUrl(L22)、
userInfoUrl(L26)、eventEndpointUrl 私有(L29)、gameRecordsUrl(L33)、myHomePageUrl(L37)、
otherHomePageUrl(L41)、peakRankUrl(L45)、competitionRankUrl(L49)、cardListUrl(L53)。
参数用 `data.ServerId`（只读），域名恒取 `data.ServerApi` 常量（T3a 7485049 提供，be4ab50 完成切换，无本地重复定义）。

### 4. MihoyoClient.kt 三策略（设计文档 §2.2）
- ① Cookie 注入：构造期 `http.newBuilder().addInterceptor`，`credentials.cookieHeader()` → `Cookie` 头，空/ null 不加；业务层不可见明文。
- ② retcode 集中判定：`suspend inline fun <reified T> get(url, serializer=serializer(), tag="")`；
  retcode∈RETRYABLE → `delay(700)` 重发**一次**（mihoyo.ts L34-43 语义）；
  重试后 `retcode!=0 || data==null` → `ApiError(retcode, message ?: "接口返回 retcode=N", retcode)`；
  `0 && data!=null` → 返回 data。data 解码失败记 null 走同一失败分支（不误报 network）。
- ③ 详情节流：**策略选择 = 拒绝时抛 `ApiError(kind="throttled")`**。
  依据：Web 版 detailThrottle（playerDetail.tsx L13/L99-102）在 UI 层**静默丢弃**连点；
  client 层无法"丢弃"，设计文档 §2.2 又把节流定在拦截器层，故拒绝显式化为错误；
  `describeApiError` 对 throttled 返回与限流同样的文案「请求过于频繁，请稍后重试」。
  节流只拦首次请求，700ms 自动重试不重复计时。tag==`TAG_DETAIL("detail")` 才过 `domain.Throttle(1000)`。
- IO/非2xx/JSON 解析失败 → `ApiError(network, 网络请求失败 / 网络请求失败（HTTP N） / 响应不是有效的 JSON)`（client.ts L100-112 逐字）；`withContext(Dispatchers.IO)` + OkHttp enqueue 挂起化。

### 5. 测试用例清单
ApiErrorTest ← retcode.test.ts（依赖 fetchMyHomePage 的"限流自动重试"3 it 归 T3d）：
- it1 ← L8-17（-100/-101 真；-500004/-1/-110/network/其它异常 假）
- it2 ← L19-24（4 组文案逐字）
- it3 ← L78 内联断言扩展（两集合全等 + -100∉RETRYABLE）
UrlBuilderTest ← servers.test.ts 后 3 it（web 侧 toContain 处升级为完整 URL 串断言）：
- it1 ← L64-70：gameRecordsUrl 全串 + badge_region 随服
- it2 ← L72-79：cardListUrl（cn_gf01 contains / cn_qd01 全串含域名前缀）
- it3 ← L81-88：userInfoUrl 全串；peakRankUrl/otherHomePageUrl 全串

### 6. 未做（按派单）
未跑 gradle（T3d 集成）；无 git add -A（逐文件 add）；未碰 model/auth/cache/repo。
编译风险自查：kotlinc 不可用，人工核对全部 import/常量引用/跨包符号（data.ServerApi 大写常量、Throttle、serializer()）一致。
