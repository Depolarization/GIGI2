# U4A-QRRETRY 二维码创建瞬时失败自动重试

状态: done（A/B/C + 验收门槛全达标）
开始: 2026-09-25  收工: 2026-09-25

## 目标
createQrLogin() 冷启动首次瞬时失败（IOException / 限流 retcode）自动重试 ≤3 次；
失败可诊断（网络类 vs 业务类文案 + Log.w）；6 个 JVM 单测覆盖。

## 独占文件
- app/src/main/java/com/gigi/tcg/data/auth/AuthManager.kt
- app/src/main/java/com/gigi/tcg/ui/login/LoginViewModel.kt
- app/src/test/java/com/gigi/tcg/data/auth/QrCreateRetryTest.kt（新建）

## 改前闸门
`git diff --quiet -- AuthManager.kt LoginViewModel.kt` → exit=0 放行。

## 勘察（只读）
- MihoyoClient.kt:70-75 范式：`RETRYABLE_RETCODES.contains(first.first)` → `delay(RETRY_DELAY_MS + (0..RETRY_JITTER_SPAN_MS).random())` → 再请求一次；常量 L163-166。
- ApiError.kt:19 `RETRYABLE_RETCODES = setOf(-500004, -1, -110)`。
- AuthManager.createQrLogin L56-71：单次 execute，零重试；非 0 retcode 直接 `throw IOException(message)`。
- AuthManager.execute L223-232：execute 被 finalize/refreshStoredSession/queryOnce 共用 → 🔴 禁止在此加重试。
- CredentialStore 是 class(Context) 且非 interface → 测试里的构造办法待定（见 C 步）。

## A 步（AuthManager）
- 常量：`QR_CREATE_RETRY_DELAY_MS=800L` / `QR_CREATE_RETRY_JITTER_SPAN_MS=300L` / `QR_CREATE_MAX_ATTEMPTS=3`（companion，命名对齐 MihoyoClient）。
- 新增顶层 `class QrCreateException(message, isNetwork, internal retryable, cause) : IOException`（AuthManager.kt:55）。
- createQrLogin 改 while 循环：成功即 return；IOException → isNetwork=true/retryable=true（cause 原样带上）；
  retcode 非 0 → isNetwork=false 且 retryable = `RETRYABLE_RETCODES.contains(retcode)`（-100 这类立即失败）；
  `delay(800 + (0..300).random())` 与 MihoyoClient 同写法；末次不延迟直接抛末次失败（不包装文案）。
- Request 抽到 `qrCreateRequest(deviceId)`：循环内重建，deviceId 仅 UUID 一次（A.4）。
- import `com.gigi.tcg.data.api.RETRYABLE_RETCODES`。execute() 零改动（A.6）。

## B 步（LoginViewModel）
- 新增 `private const val QR_LOG_TAG = "GigiQr"`；catch 分支加
  `Log.w(QR_LOG_TAG, "createQrLogin 失败：${e.javaClass.name}: ${e.message}")`（2 参版，不打堆栈、不打 url/cookie/deviceId）。
- `qrFailureMessage(e)`：`isNetwork` → "网络不稳定，请重试"；否则透出 `e.message`（米哈游原始文案）。LoginScreen 未动。

## C 步（QrCreateRetryTest）
- 6 用例（脚本拦截器 frames=null 表示该次抛 IOException）：
  1 IOException→成功 calls=2；2 -500004→成功 calls=2；3 IO,IO→成功 calls=3 且三次 deviceId 同一个；
  4 全 IO → QrCreateException isNetwork=true、cause 是 IOException、message 含原文、calls=3；
  5 retcode=-100 → calls=1、isNetwork=false、message 含米哈游原始文案；
  6 全 IO 虚拟时钟 elapsed ∈ [2×800, 2×(800+300)] → 真退避。
- CredentialStore 是 final class + 构造需 Context（android.jar 桩抛 "Stub!"）→ 测试内用
  `sun.misc.Unsafe.allocateInstance`（纯反射，无编译期依赖）分配纯类型占位实例，createQrLogin 不触碰它。
- 类级 `@OptIn(ExperimentalCoroutinesApi::class)`：`currentTime` 是实验性 API，首轮跑出 2 条 opt-in warning，加注解后清零。
- 额外断言（防退化）：三次尝试 `x-rpc-device_id` 头同一个（A.4）；末次失败不额外 delay 但总耗时仍 ≥ 2×800ms。

## 验收（命令输出为准）
构建环境注记：`./gradlew` 在 bash 里 JAVA_HOME 未设置 → 落到 PATH 的 Java 8，AGP 8.9.1 直接
`Could not resolve com.android.tools.build:gradle:8.9.1 ... requires at least JVM runtime version 11`。
改用 `JAVA_HOME="C:/Users/oscur/.jdks/ms-21.0.12.1" ./gradlew ...` 才能跑（非本工程改动，仅执行环境）。

1. `JAVA_HOME=... ./gradlew :app:testDebugUnitTest --tests "com.gigi.tcg.data.auth.QrCreateRetryTest" --rerun-tasks`
   → `BUILD SUCCESSFUL`，XML：`tests="6" skipped="0" failures="0" errors="0"`（跑两次：加 @OptIn 前后）。
2. `grep -rn "QrCreateException" app/src/main --include=*.kt` → 6 处：定义 AuthManager.kt:55，
   使用 AuthManager.kt:86 / :93，LoginViewModel.kt:19(import) / :162(isNetwork 分流)。
3. 提交范围：`63c9398` fix 只含 AuthManager.kt + LoginViewModel.kt（2 files, +78 −14）；
   `2b47254` test 只含新建 QrCreateRetryTest.kt（+145）。
   ⚠️ 工作树同时存在别的代理未提交的 `GigiRepository.kt` / `AppContainer.kt` / `.task/任务清单-main.md` 改动，
   我按路径显式 add，未纳入（`git status` 实核）。
4. 回归（同一次 gradle 调用）：`data.auth.*` + `data.api.*` → CredentialExchangeTest 12/0、CredentialStoreTest 6/0、
   QrCreateRetryTest 6/0、MihoyoClientRetryTest 4/0、ApiErrorTest 3/0、RetcodeFlowTest 5/0、UrlBuilderTest 3/0，
   `BUILD SUCCESSFUL`；编译期我的 3 个文件 0 warning（其余 warning 均为既存）。

## 证据
- .task/progress/U4A-QRRETRY.md（本文件）
- app/build/test-results/testDebugUnitTest/TEST-com.gigi.tcg.data.auth.QrCreateRetryTest.xml
- commits: 63c9398（fix）、2b47254（test）

## 未做（越界项，交主代理）
- 未真机验证（本任务无真机指令）；LoginScreen.kt / AppContainer.kt（含 OkHttpClient readTimeout 未设）未动。
- `finalize()` 的 catch 仍用 `e.message` 兜底，未做同类文案分流（不在任务范围）。

