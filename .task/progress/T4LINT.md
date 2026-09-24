status: done
owner: T4LINT
files: [app/src/main/java/com/gigi/tcg/domain/Format.kt, app/src/main/java/com/gigi/tcg/domain/UidCode.kt]
evidence: [
  "gate: `git status --porcelain -- domain(main+test)` = 空 @ bf5ebeb",
  "commit 1f88a74 fix: replace java.time with minSdk24-compatible date formatting in domain",
  "commit 85d8f41 fix: replace java.util.Base64 with minSdk24-compatible encoder in domain",
  "worktree@85d8f41: `:app:lintDebug` BUILD SUCCESSFUL, grep -c 'Error:' lint-results-debug.txt = 0（Warning 5 条，未处理）",
  "worktree@85d8f41: `:app:testDebugUnitTest` BUILD SUCCESSFUL，tests=83 failures=0 errors=0 skipped=0（总数不变）",
  "worktree@85d8f41: `:app:assembleDebug` BUILD SUCCESSFUL（与上条同一条命令，exit 0）",
  "app/build/reports/lint-results-debug.html / app/build/intermediates/lint_intermediate_text_report/debug/lintReportDebug/lint-results-debug.txt",
]
blockers: []
summary: 7 个 NewApi Error 清零（Format.kt java.time→SimpleDateFormat；UidCode.kt java.util.Base64→自实现），测试断言零改动；主工作树复验受 T4a 在途文件阻塞
updated: 2026-09-25 00:43:42

## 原 Error 清单（:app:lintDebug @ bf5ebeb，7 Error / 5 Warning）
1. domain/Format.kt:12 NewApi — java.time.ZoneId#systemDefault
2. domain/Format.kt:12 NewApi — java.time.format.DateTimeFormatter#ofPattern
3. domain/Format.kt:12 NewApi — java.time.format.DateTimeFormatter#withZone
4. domain/Format.kt:25 NewApi — java.time.Instant#ofEpochMilli
5. domain/Format.kt:25 NewApi — java.time.format.DateTimeFormatter#format
6. domain/UidCode.kt:30 NewApi — java.util.Base64#getEncoder
7. domain/UidCode.kt:30 NewApi — java.util.Base64.Encoder#encodeToString

T3d 备案只提 java.time / 预计 Format.kt，实测第 6、7 条落在 domain/UidCode.kt（java.util.Base64 同样需 API 26）。派单独占文件范围写作"domain 下 lint 实际命中的文件"，故一并修；未越界到 data/ ui/ 构建脚本。

## 修复方式
- Format.kt：`DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault())` + `Instant.ofEpochMilli(...)` → `SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(floor(sec*1000).toLong()))`（API 1 可用）。SimpleDateFormat 默认即本地时区、补零一致、非法值仍走 `return ""` 与 try/catch；因 SimpleDateFormat 非线程安全，改为每次调用新建实例（原共享 DateTimeFormatter 是线程安全的，不引入新隐患）。Locale.US 固定拉丁数字，避免阿拉伯语等 locale 下输出十进制数字变形。
- UidCode.kt：`Base64.getEncoder().encodeToString()` → 文件内私有 `base64Encode`（RFC 4648 标准表、`=` 补位、不换行），与 JDK 编码器逐字节等价（既有 UidCodeTest 三条 base64 硬编码期望值全通过）。未选 android.util.Base64：纯 JVM 单测下会 not-mocked 导致测试失败。未开 coreLibraryDesugaring、未加依赖。
- 测试改动：0。ThrottleTest 仍用 java.time 构造期望 epoch（测试源码跑在 JVM 上，lint 不对 test 源报 NewApi），断言未动，无迁就。

## 遗留（非本任务范围）
主工作树当前 `:app:compileDebugKotlin` 失败于并行子代理 T4a 的在途未跟踪文件 `app/src/main/java/com/gigi/tcg/data/auth/AuthManager.kt`（4 个语法/类型错误，与我的改动无关；改它违反独占约定）。因此三条验收闸门在 main@85d8f41 的隔离 worktree（不含该未跟踪文件、其余内容与主树逐字相同）中跑通；T4a 收尾后请在主树复跑一次 `:app:lintDebug`（预期仍 Error=0）。验证用 worktree 与临时分支已清理，未留残留。
