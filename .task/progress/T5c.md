status: done
owner: T5c
files: [
  app/src/main/java/com/gigi/tcg/ui/dialogs/cardcover/CardCoverViewModel.kt,
  app/src/main/java/com/gigi/tcg/ui/dialogs/cardcover/CardCoverSheet.kt,
  app/src/main/java/com/gigi/tcg/ui/dialogs/cardcover/CardImageSaver.kt
]
evidence: [
  "gate: git status --porcelain -- app/src/main/java/com/gigi/tcg/ui/dialogs/cardcover => empty（开工时 HEAD=1977c74 >= c5bdba5；收工时 HEAD=45fceaa）",
  "T5a.md => status done（复用 LoadingView / ErrorState / EmptyState / LocalToast，未改 ui/components）",
  ":app:assembleDebug => BUILD SUCCESSFUL (exit 0)",
  ":app:testDebugUnitTest => tests 95 / failures 0（未新增用例，保持派单要求的 95）",
  ":app:lintDebug => Fatal 0 / Error 0 / Warning 5（warning 全在既有文件，lint-results-debug.xml 内 cardcover 关键字命中 0）",
  "commits: d3fe7da（VM + Saver）→ 45fceaa（Sheet）"
]
blockers: [
  "🔴 API24-28 下载路径不可用：AndroidManifest 缺 `WRITE_EXTERNAL_STORAGE`（maxSdkVersion=29）声明，改清单超出本派单独占文件范围，故未加。
   现状：Sheet 在 <Q 会 launch RequestPermission(WRITE_EXTERNAL_STORAGE)，清单未声明 → 系统直接返回 denied（永久拒绝，且不再弹窗），
   CardImageSaver.savePublicDirectory 的 hasWriteExternalPermission 检查随即抛「需要存储权限才能保存卡面」，经 toast 呈现；Q+ 主路径不受影响。
   需后续任务在清单补一行：`<uses-permission android:name=\"android.permission.WRITE_EXTERNAL_STORAGE\" android:maxSdkVersion=\"29\" />`"
]
summary: 卡面 ModalBottomSheet（PNG/GIF 切换 + 详情缓存）与 MediaStore 无回退下载已落地，Q+ 主路径全绿

## 状态机（对照 CardCoverDialog.tsx）
```kotlin
sealed interface CoverUiState { Loading; Content(info: CardBasicInfo?, format: CoverFormat); Error(message) }
enum class CoverFormat { Png(commonImg, "png", "image/png"), Gif(goldImg, "gif", "image/gif") }  // Content.imageUrl 按 format 取
```
- `setContentId(id)`：id 变化 → generation++ / cancel 旧 job；null（关闭）→ 复位 Loading；非 null → `load()`。
- `load()`：本层 `parsedCache`（accessOrder LinkedHashMap，LRU 200，对应 web 模块级 detailCache）命中 → 直接 Content、**不进 Loading**；
  未命中才 Loading → `repository.fetchCardDetail`（内层已有 DetailCacheStore LRU200 + MihoyoClient TAG_DETAIL 1s 节流）
  → 解析 `page.modules["基础信息"].components[0].data`（二次 JSON，失败=「获取数据失败」）。
- 每次构建 Content 都用默认 `format = Png` ⇒ 重开即复位 PNG（对齐 web setFormat('png')）。
- `retry()` 供 ErrorState；`selectFormat()` 只在 Content 内 copy(format)。
- 图片自身加载态交 Coil AsyncImage（web 的 imgLoading 遮罩/ref 兜底整段不移植）。
- 错误文案统一走 `describeApiError`（retcode 归因留在数据层，设计红线 2），未再自行判 kind。

## 保存路径（🔴 无 fallback：设计 §4.4 删 window.open 分支）
- `CardImageSaver.save(url, name, format)`：`withContext(IO)` + 自建 OkHttpClient（10s/30s 超时；不复用带 Cookie 的 MihoyoClient 链路）
  → 非 2xx 抛 `IOException("下载失败 HTTP …")` → 按 SDK 分叉写相册，失败只 toast 不回退。
- **Q+（主路径）**：`MediaStore.Images` insert `DISPLAY_NAME` / `MIME_TYPE` / `RELATIVE_PATH="Pictures/GIGI"` / `IS_PENDING=1`
  → `openOutputStream` 写字节 → `IS_PENDING=0`；中途异常 `resolver.delete(uri)` 回滚，不留半张图。同名由 MediaStore 自动改名 `name (1).png`。无需运行时权限。
- **API24-28**：`getExternalStoragePublicDirectory(PICTURES)/GIGI/<file>` + `MediaScannerConnection.scanFile`；权限受阻见 blockers。
- 文件名 `coverFileName()`：`{清洗后的 name}.{png|gif}`，非法字符 `[\\/:*?"<>|控制符]` → `_`，trim 后截断 60 字符，空则「卡面」。
- 节流：`Throttle(1000)`（domain）由 VM 持有，生命周期同 Sheet；被节流时静默返回（与 web 一致）。
- toast：成功「已保存到相册」，失败用异常 message（兜底「保存失败，请重试」）。Sheet 取 `LocalToast.current` 传入 `vm.download(show)`。

## 待接（非本派单范围）
- Sheet 契约：`CardCoverSheet(contentId: Int?, onDismiss: () -> Unit, modifier: Modifier = Modifier)`，contentId=null 时不渲染弹层（对齐 `open={contentId!==null}`）；VM 经 `CardCoverViewModel.factory(app)` 取。
- 未做真机验证（本派单验收门槛只有 build/test/lint）；GIF 动图播放依赖 Coil `ImageDecoderDecoder`（API28+ 动图、24-27 仅首帧），如需全版本动图要另引 `coil-gif`。
- 全局 `LocalToast` 目前尚无宿主 provide（T5a 只给组件），宿主侧接入前 toast 静默。
