# V6F-COVER — 卡面下载遮挡 / 无动态卡面选择器

status: done

## 改前闸门
`git diff --quiet` 两个独占文件 → exit 0，放行。

## B 根因排查（文件:行号）
- `goldImg` 可空可空串：`data/model/ApiModels.kt:167` `@SerialName("gold_img") val goldImg: String? = null`。
- 路径 1（字段缺失/为 null）：`CardCoverViewModel.kt:44` `imageUrl` 取到 null →
  Sheet `CardCoverSheet.kt:159` `enabled = content?.imageUrl != null` 把下载按钮禁用 →
  点击**无反应**（不是报错，是按钮根本 disabled，且选择器还允许切到一个永远为空的 Gif 档）。
- 路径 2（字段为空串 ""）：`imageUrl` 返回 ""，非 null → 按钮**可点** →
  `CardImageSaver.kt:77` `Request.Builder().url("")` 抛 `IllegalArgumentException`（OkHttp 对非法 URL）→
  `CardCoverViewModel.kt:110-111` catch 后 `show(e.message)` 弹错误 toast →
  又被 ModalBottomSheet 遮挡 → 用户看到"报错/没反应"混合现象。
- 结论：`imageUrl` 未做 blank 判据 + 选择器不感知"该卡无动态卡面"是根因，非表象。

## A 实现选择
选"download 增加 onSaved 回调"方案：`download(show, onSaved = {})`，仅在 `saver.save` 成功后
`show(SAVED_TOAST)` 再 `onSaved()`；catch 分支只 show 错误、不触发 onSaved → **失败必不关闭**。
Sheet 两处调用点（权限回调 + startDownload）都传 `onDismiss`。不用 LaunchedEffect 观察信号：
回调直连、无状态字段残留、无重组时序依赖。

## B 实现
- `Content.hasDynamic = !info?.goldImg.isNullOrBlank()`。
- `imageUrl` 加 `takeIf { isNotBlank() }`（修空串路径 2 的根因）。
- `selectFormat(Gif)` 在 `!hasDynamic` 时忽略（防御）。
- load() 构建 Content 统一走 clamp：Gif 且无 goldImg → 回落 Png。
- Sheet：`content == null || !content.hasDynamic` 时整块不渲染 `SingleChoiceSegmentedButtonRow`。
  （loading/error 阶段 content 为 null 时选择器也不渲染，加载完成且有 goldImg 后再出现——
  原来的 `enabled = content != null` 占位禁用态一并移除，隐藏即无需禁用。）
- load() 的"Gif 回落 Png"：load() 每次新建 `Content(info)` 且 format 默认 Png，天然满足，未额外加代码；
  防御收口在 `selectFormat`（`format != Gif || hasDynamic`）。

## 验收
- `JAVA_HOME=... ./gradlew :app:assembleDebug --rerun-tasks` → BUILD SUCCESSFUL。
  （第一次跑失败点全在 GigiNavHost.kt——V6H 并发中间态，按 ENV-NOTES 重跑即过，与本次改动无关。）
- `git diff --stat` 仅两个独占文件；CardImageSaver.kt 未动（根因在 VM 判空缺失，不在 saver）。
- 自读确认：权限回调 `granted → download(showToast, onDismiss)` 路径完好；catch 分支不触发 onSaved、
  对话框保持打开；`CoverPreview` 的 url==null → EmptyState 空态逻辑未破坏（blank 过滤后更准）。
- C 自审：间距 8/12/16/24dp、IconButton 48dp 触摸目标、标题 ellipsis 均符合 M3，无明确问题需修。

