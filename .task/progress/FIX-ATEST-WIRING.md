# FIX-ATEST-WIRING

status: done

## 改前闸门
`git status --porcelain` 对 app/build.gradle.kts / DebugCredentialInjector.kt → 空（仅 CredentialStoreInstrumentedTest.kt untracked，符合预期）。

## 依赖
本地 Gradle cache 只有 androidx.test:runner 1.5.2 / ext.junit 1.1.5 → 按派单允许联网拉取 1.6.2 / 1.2.1 成功（BUILD SUCCESSFUL，非离线）。

## 门槛① connectedDebugAndroidTest（ANDROID_SERIAL=127.0.0.1:7555, JDK ms-21.0.12.1）
`BUILD SUCCESSFUL in 23s` — `Starting 1 tests on PGEM10 - 12 / Finished 1 tests on PGEM10 - 12`
app/build/reports/androidTests/connected/debug/index.html：tests=1, failures=0, errors=0

## 门槛② 注入路径实测（同一 runner 类，双路径）
cookie 串 `e_hk4e_token=smoke; cookie_token=x` → Base64 `ZV9oazRlX3Rva2VuPXNtb2tlOyBjb29raWVfdG9rZW49eA==`
- `am instrument -w -e cookie_b64 <b64> com.gigi.tcg.test/com.gigi.tcg.debug.DebugCredentialInjector`
  → `INSTRUMENTATION_RESULT: cookie_length=34` / `status=saved`（只回报长度，未打印凭据内容）
- `am instrument -w -e clear 1 ...` → `INSTRUMENTATION_RESULT: status=cleared`

## 门槛③
`:app:assembleDebug :app:lintDebug :app:testDebugUnitTest` → BUILD SUCCESSFUL
lint: 0 errors / 8 warnings（DataExtractionRules、IconLauncherShape、ModifierParameter、
MonochromeLauncherIcon、ScopedStorage、UseKtx — 均为既有 app 级告警，与本次改动无关）
testDebugUnitTest: 14 suites, tests=95 failures=0 errors=0 skipped=0

## 实现要点
DebugCredentialInjector 改为 `AndroidJUnitRunner` 子类；onCreate 先 super，
检测到 cookie_b64 / clear 时置 injectMode 并在 HandlerThread 上执行注入 + finish
（MonitoringInstrumentation 禁止主线程调用 finish，实测首版在主线程 finish 触发
"Cannot be called from main thread!" 崩溃，已改为工作线程 + CountDownLatch 等待）；
onStart 在 injectMode 下不调 super，否则正常跑 JUnit。
runner 值 com.gigi.tcg.debug.DebugCredentialInjector 未改动。
