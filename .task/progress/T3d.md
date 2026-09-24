status: done
owner: T3d
files: [
  app/src/main/java/com/gigi/tcg/data/cache/WikiDiskCache.kt,
  app/src/main/java/com/gigi/tcg/data/repo/GigiRepository.kt,
  app/src/main/java/com/gigi/tcg/di/AppContainer.kt,
  app/src/test/java/com/gigi/tcg/data/repo/GigiRepositoryTest.kt,
  app/src/test/java/com/gigi/tcg/data/api/RetcodeFlowTest.kt,
  app/src/main/java/com/gigi/tcg/data/ServerId.kt,
  app/src/main/java/com/gigi/tcg/data/api/MihoyoClient.kt,
  app/src/test/java/com/gigi/tcg/data/ServerRegistryTest.kt
]
evidence: [
  "gradlew :app:assembleDebug => BUILD SUCCESSFUL, exit 0 (JDK ms-21.0.12.1)",
  "gradlew :app:testDebugUnitTest => BUILD SUCCESSFUL, 83 tests 全绿 (app/build/test-results/testDebugUnitTest: domain(T2)=59 + data 新增 24)",
  "APK: app/build/outputs/apk/debug/app-debug.apk 19,572,063 B (00:16 重新打包)",
  "commits: e124d19 cache / b531464 repo+tests / ab2b56a di / bf5ebeb fix(兄弟文件)"
]
blockers: []
summary: >
  T3d 完成：WikiDiskCache(DataStore wiki_cache, 1h TTL, 键含服务器, 仅公开图鉴落盘) +
  GigiRepository(mihoyo.ts 全方法 + pageCache.ts 双层缓存策略, 私有键
  gigi:private:<server>:<uid>:<suffix>, LruCache200 详情+TAG_DETAIL 节流点, force 绕缓存) +
  AppContainer 装配(json/credentialStore/wikiDiskCache/client/repository, GigiApp 未动)。
  测试 12+5=17 新增（无新依赖：本地 stub GigiApiTransport/WikiDiskStore/DetailCacheStore + 注入时钟）。

## 编译错误修复登记（兄弟文件最小修改, bf5ebeb）
1. data/ServerId.kt — T3a 潜在 bug：伴生对象 `ALL = listOf(Official, Channel)` 急切求值，
   嵌套 object 类初始化晚于伴生对象 → ALL=[null,null]，JVM 单测 5+1 用例 NPE。
   改为 `by lazy` + `DEFAULT get() = Official`。
2. data/api/MihoyoClient.kt L49 — public inline get() 访问 private detailThrottle 编译错
   → @PublishedApi internal（行为不变）。
3. data/api/MihoyoClient.kt L104 — `element.isNull`  unresolved（1.7.3 该扩展不可见）
   → `content == "null" && isString.not()`（与原语义等价：JSON null 判定）。
4. test/ServerRegistryTest.kt L73 — 反引号测试名含 `.`（api.lua）JVM 非法方法名
   → 改 `api-lua`（仅改名，断言未动）。
另：android.util.LruCache 在 JVM 单测为 not-mocked 桩 → repo 内加 DetailCacheStore 薄抽象
（生产默认实现仍是 LruCache(200)），非修复兄弟文件。

## 备案
- lintDebug 报 7 Error 全部位于 T2 既有文件（Format.kt 等 java.time NewApi/minSdk24），
  不在本次派单门槛（①②③）与独占范围，未处理，建议另派 T2 收尾单。
- 传输经 GigiApiTransport 最小接口（真身 MihoyoClientEnvelopeTransport 复用
  requestEnvelope），派单允许"将 client 抽象为接口（如需）"；Cookie 注入/节流/网络归因仍在 client。
updated: 2026-09-25 00:20:14
