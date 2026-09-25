# V6H 全局质感审查报告

审查范围：ui/screens/*、ui/dialogs/*、ui/login/*、ui/theme/*（均为只读，不修改）

---

## 界面层

[P2] ui/screens/home/HomeRoute.kt:288 — RecordItem 对手昵称 Text 设了 maxLines=1 但未配 overflow=TextOverflow.Ellipsis，长昵称被硬裁无省略号 — 加 overflow = TextOverflow.Ellipsis。
[P3] ui/screens/home/HomeRoute.kt:193 — ProfileCard Column(Modifier.weight(1f).padding(start=12.dp)) — UID 行与段位行无 maxLines，极端长 UID 可撑破行宽 — 补 maxLines=1。
[P3] ui/screens/cardstats/CardStatsRoute.kt:218 — CharTableHeader padding(top=4.dp,bottom=2.dp)，bottom=2 脱离 4dp 网格 — 改 padding(top=4.dp,bottom=4.dp)。
[P3] ui/screens/cardstats/CardStatsRoute.kt:235 — 列表头 col.label Text 有 maxLines=1 无 overflow，"出场率"在 48dp 宽下勉强显示但仍缺 Ellipsis — 补 overflow=TextOverflow.Ellipsis。
[P3] ui/screens/cardstats/CardStatsRoute.kt:273 — 统计数值 Text 有 maxLines=1 无 overflow（与行头同理）— 补 overflow。
[P3] ui/screens/cardstats/CardStatsRoute.kt:309 — ActionCardRow "出场"数值 Text 有 maxLines=1 无 overflow — 补 overflow。
[P2] ui/screens/cardstats/CardStatsRoute.kt:354 — 展开/收起箭头 Icon 无 contentDescription（纯 null），TalkBack 播报空白 — 设 contentDescription=if(open)"收起详情"else"展开详情"。
[P3] ui/screens/rank/RankRoute.kt:230 — RankRow 第二名 Avatar 积分行 Text（$scoreText　UID:…）无 maxLines/overflow，长 UID 可撑破行 — 补 maxLines=1, overflow=Ellipsis。
[P3] ui/screens/cardwiki/CardWikiRoute.kt:164 — 使用裸 TabRow，M3 规范推荐 PrimaryTabRow/SecondaryTabRow（可设 containerColor）— 替换为 PrimaryTabRow。
[P3] ui/screens/cardwiki/CardWikiRoute.kt:169 — Tab 标签 Text(category.title) 无 maxLines/overflow，长分类名溢出 — 补 maxLines=1, overflow=Ellipsis。
[P3] ui/screens/cardwiki/CardWikiRoute.kt:213 — 搜索框手拼 BasicTextField，应使用 M3 SearchBar 组件获得正确搜索语义与状态 — 换 SearchBar。
[P3] ui/screens/cardwiki/CardWikiRoute.kt:235 — 搜索 Icon contentDescription=null，BasicTextField 无 semantics label，无障碍播报为空 — 给 Icon 设 contentDescription="搜索"。
[P3] ui/screens/cardwiki/CardWikiRoute.kt:409 — 卡名 Text 的 vertical padding=6.dp，脱离 4dp 网格 — 改 8.dp。

## 弹窗层

[P2] ui/dialogs/playerdetail/PlayerDetailDialog.kt:245 — competitionName Text 在 weight(1f) Row 内无 maxLines/overflow，长赛事名换行撑破行高 — 加 maxLines=1, overflow=Ellipsis。
[P3] ui/dialogs/playerdetail/PlayerDetailDialog.kt:216 — 角色标签用裸 Surface(shape=RoundedCornerShape(16.dp), tonalElevation=1.dp) 手写，应用 M3 AssistChip — 替换 AssistChip。
[P3] ui/dialogs/playerdetail/PlayerDetailDialog.kt:217 — 角色标签 Surface padding vertical=6.dp，脱离 4dp 网格 — 改 8.dp 或 4.dp。
[P3] ui/dialogs/playerdetail/PlayerDetailDialog.kt:270 — Spacer(Modifier.width(6.dp)) 在标题与计数之间，6dp 非 4dp 网格 — 改 4.dp 或 8.dp。
[P3] ui/dialogs/playerdetail/PlayerDetailDialog.kt:250 — competitionResult Text(labelLarge) 无 maxLines/overflow/weight，长结果字符串可挤压积分区 — 加 weight(1f), maxLines=1, overflow=Ellipsis。
[P3] ui/dialogs/cardcover/CardCoverSheet.kt:148 — SegmentedButton 高度约 40dp，低于 48dp 触摸目标下限 — 加 Modifier.heightIn(min=48.dp)。
[P3] ui/dialogs/cardcover/CardCoverSheet.kt:155 — SegmentedButton 内 Text 无 maxLines/overflow，尺寸标签过长时可裁切 — 补 maxLines=1, overflow=Ellipsis。

## 登录 / 主题层

[P2] ui/login/LoginScreen.kt:186 — 二维码占位背景 Color.White 硬编码，深色主题下出现刺眼白块 — 改 MaterialTheme.colorScheme.surface。
[P2] ui/login/LoginScreen.kt:166 — 已扫码遮罩用 Color.Black.copy(alpha=0.7f)，深色模式无适配 — 改 MaterialTheme.colorScheme.scrim.copy(alpha=0.7f)。
[P3] ui/login/LoginScreen.kt:112 — FilterChip（M3 默认约 32dp 高）触摸目标 < 48dp — 加 Modifier.heightIn(min=48.dp) 或增大 modifier。
[P3] ui/login/LoginScreen.kt:173 — "已扫描"确认用 Text("✓") 字符，无障碍播报不友好且不可本地化 — 改 Icon(Icons.Outlined.Check, contentDescription="已扫描")。
[P2] ui/theme/Theme.kt:18 — lightColorScheme()/darkColorScheme() 均为 M3 裸默认（紫色系），关掉动态取色后与品牌 win/lose/gold 色无关 — 传入品牌 primary/tertiary 角色覆盖。
[P3] ui/theme/Color.kt:5 — WinColor 直接用于文字色（PlayerDetailDialog.kt:180 等），无浅色/深色变体，对比度在浅色模式下仅 4.2:1 — 补充浅色变体或改用 secondaryContainer。
[P3] ui/theme/Type.kt:5 — Typography() 全部默认，各处代码手打 fontWeight=Bold/Medium 覆写，字号层级风格不统一 — 在 Type.kt 中定义对应 FontWeight 层级。
[P3] ui/dialogs/about/AboutDialog.kt:132 — 段落 vertical padding=2.dp，脱离 4dp 网格 — 改 4.dp。

---

## Toast 层级核实结论

- 原 `ToastHost` 挂在 `Scaffold(snackbarHost=…)` 内，处于主窗口 (Activity window)。
- `ModalBottomSheet`（M3 CardCoverSheet）内部使用 `Dialog`，创建独立 Dialog 窗口——Android 窗口层级上 Dialog > 主窗口，snackbar 必然被遮住。
- 本次修复：`ToastHost` 移到 `Popup`，`Popup` 在主窗口内容之上，但 **Compose Popup 仍是子窗口，与 Dialog 窗口的层叠顺序不确定**。若 Dialog 在 Popup 之后创建，Dialog 仍在 Popup 上方。V6F 的"保存成功即 onDismiss"规避策略在 Dialog 层级问题无法通过 Popup 彻底解决前仍需保留。
- 若要彻底修复（toast 在所有 Dialog 之上），需使用 `PopupProperties(zOrderInApp=PopupLevel.System)` 或平台级 `TYPE_APPLICATION_OVERLAY`，后者需要 `SYSTEM_ALERT_WINDOW` 权限，超出本次导航层范围。
