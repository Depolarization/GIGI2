status: done
owner: P5-MATERIAL
files: []
evidence:
  - "改前闸门：git status --porcelain -- app/src/main/java/com/gigi/tcg/ui => 空"
  - "HEAD=ed31a3d"
blockers: []
summary: 开工：全量审计 ui/ 的 Material 3 / Material You 一致性，审计完成后逐项重构。
updated: 2026-09-25 12:22:23 +08:00

## P5R 定点核查（P5R 代理，2026-09-25）

前案全量审计挂死；本棒只做 5 项高频核查，发现实际不符才改。

### 1. 触摸目标 ≥48dp
- 检查：ui 全树 IconButton / clickable / FilterChip / Tab。
  - 合规：HomeRoute.kt:92、GigiNavHost.kt:144/196、CardCoverSheet.kt:130（M3 IconButton 默认 48dp）；
    HomeRoute.kt:159/209（Card 整卡 ≥80dp）、RankRoute.kt:139（行含 44dp 头像 ≈60dp）、
    CardWikiRoute.kt:313（卡牌 Cell 远超 48dp）；LoginScreen/GigiNavHost 的 TextButton 自带 minimumInteractiveComponentSize。
- 实际不符 1 处：CardWikiRoute.kt:231 清除按钮 `IconButton(Modifier.size(36.dp))` 把触摸目标压到 36dp。
- 改：去掉 size(36.dp)，恢复 M3 自带 minimumInteractiveComponentSize（≥48dp 触摸目标）。

### 2. Dialog 规范
- AboutDialog.kt:99 仅 confirmButton"关闭"；PlayerDetailDialog.kt:85-95 dismiss"关闭"左 / confirm"复制UID"右；
  PlayerQueryDialog.kt:59-66 dismiss"取消"左 / confirm"查询"右；GigiNavHost.kt:286-296 退出确认 dismiss"取消"左 / confirm"退出"右。
- 四个 AlertDialog 均 M3 默认容器（28dp 圆角、24dp 内边距），未覆写 shape/containerColor。
- 结论：符合 M3，未改。

### 3. 三态统一（StateViews）
- Loading/Empty/Error 三组件本身风格一致（48dp 图标、top 12dp 标题间距、共享 TextButton 重试）。
- 实际不符 1 处：CardStatsRoute.kt:96-107 三态用 fillMaxSize 直接拉伸（LoadingView 内部 fillMaxWidth 使 align 失效，spinner 被拉到整屏高、顶对齐），
  与 Home/Rank 顶对齐、Wiki Box 居中的表现不一致。
- 改：CardStats 三态统一为 `Box(fillMaxSize, Center)` 承载，与 CardWiki 同构。

### 4. 8dp 网格抽查
- Home（16/12/8/4）、CardStats（16/12/8/4/2）合规，未改。
- 明显非 4 倍数共 4 处，收口：
  - CardWikiRoute.kt GRID_SPACING 10→8dp、搜索图标 Spacer 10→8dp、FilterBar 纵向 6→4dp（padding 与 spacedBy）；
  - RankRoute.kt:140 行纵向 padding 10→8dp（行仍 ≈56dp ≥48dp）。

### 5. Icon contentDescription
- 功能性均有描述：刷新/玩家查询/关于（GigiNavHost:145/197、HomeRoute:93）、清空搜索（CardWiki:234）、关闭（CardCoverSheet:133）、导航 label、Avatar/卡面图用昵称/标题。
- 装饰性均 null：StateViews 状态图标、ExpandMore/Less（CardStats:336）、Check/Add/Logout 菜单前导图标（文字已表意）、Search 输入框图标、BrokenImage 兜底。
- 结论：无缺失，未改。

待修清单（报主代理，非本棒范围）：无功能缺陷发现。

## 审计清单

- [ ] colorScheme / typography / shape 使用一致性
- [ ] pressed / hover / ripple / disabled 状态
- [ ] 触摸目标 >= 48dp 与 Icon 内容描述
- [ ] 列表 / 网格分割线
- [ ] Dialog / BottomSheet 圆角、边距、按钮顺序
- [ ] 页面切换与组件动效统一使用 Motion
- [ ] 字体层级使用
- [ ] 四页、登录页、三弹窗的 8dp 间距与标题栏
- [ ] StateViews 加载 / 空态 / 错误态收口
- [ ] 三语义固定色、数据流与 retcode 语义未变
