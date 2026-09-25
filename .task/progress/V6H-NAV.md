---
task: V6H-NAV
status: done
owner: V6H-NAV subagent
done: 2026-09-25
---

## 导航层修复（GigiNavHost.kt）

1. **顶栏账户名溢出** — TextButton 加 `Modifier.widthIn(max=144.dp)` + Text 设 `maxLines=1, overflow=Ellipsis`；DropdownMenuItem 内账户名 Text 同步加 Ellipsis。
2. **底部导航无障碍** — NavigationBarItem / NavigationRailItem 各加 `Modifier.semantics { role=Role.Tab; selected=… }`，label 补 `maxLines=1, overflow=Ellipsis`。
3. **NavigationRail 与底部导航语义一致** — Rail 分支补 label（与 NavigationBar 相同）、Tab 角色、选中态同步；Rail 顶部加品牌 Icon（decorative）；Rail 与内容之间加 HorizontalDivider（outlineVariant）。
4. **Toast 层级** — 将 ToastHost 从 `Scaffold(snackbarHost=…)` 移至独立 `Popup(alignment=BottomCenter, focusable=false)` 渲染。核实结论：Compose Popup 仍为子窗口，ModalBottomSheet 使用 Dialog 窗口；Dialog > Popup 的层叠关系不确定。建议 V6F 的 onDismiss-before-toast 策略继续保留。彻底修复需 `PopupLevel.System` 或 `SYSTEM_ALERT_WINDOW` 权限，超出导航层范围。详见 V6H-AUDIT.md 末节。
5. **DropdownMenuItem 无障碍** — 账户列表项加 `Modifier.semantics { role=Role.DropdownList; selected=… }`。

Avatar.kt / GigiToast.kt 只读审查，无需改动（40dp 圆形兜底正常，ToastController 队列逻辑无误）。

## 全局质感审查

产出 .task/progress/V6H-AUDIT.md，共 28 条，最高严重度 P2（8 条），无 P1。
