# V7D-TOAST-POS — Toast 位置回归修复（回退 Popup → Scaffold snackbarHost）

status: done

## 背景
- V6H-NAV (799c36b) 把 ToastHost 从 `Scaffold(snackbarHost=...)` 挪到 `Popup(BottomCenter)`，
  Popup 对齐的是屏幕底，丢失了 Scaffold 对 bottomBar 的避让 ⇒ snackbar 贴屏幕底、被手势条压着。
- 当初用 Popup 是为解决"下载提示被 ModalBottomSheet 遮挡"，但该问题已由 V6F 的
  "保存成功即 onDismiss"（CardCoverViewModel.download 的 onSaved 只在成功路径触发，先关 sheet 再显示 toast）规避。
  sheet 关闭后不再存在遮挡场景 ⇒ Popup 引入位置回归，代价大于收益 ⇒ 安全回退。

## 改动计划（独占文件：ui/navigation/GigiNavHost.kt）
1. `snackbarHost = { }` → `snackbarHost = { ToastHost(toastController) }`
2. 删除 NavHost 后的 Popup(...) { ToastHost(...) } 块
3. 清理 import：`androidx.compose.ui.window.Popup` / `PopupProperties`；
   `Alignment` 保留（Rail 顶部品牌 Icon 用 `contentAlignment = Alignment.Center`）
4. 保留 V6H 其它改动：顶栏 widthIn(144dp)+Ellipsis、DropdownMenuItem Ellipsis+semantics、
   NavigationBar/RailItem label Ellipsis+alwaysShowLabel+semantics Tab、Rail 顶部 Icon+HorizontalDivider

## 改前闸门
`git diff --quiet -- .../GigiNavHost.kt` → exit 0，放行。

## 构建
`JAVA_HOME="C:/Users/oscur/.jdks/ms-21.0.12.1" ./gradlew :app:assembleDebug --rerun-tasks` → **BUILD SUCCESSFUL in 10s**（35 tasks executed）。

## git diff 全文（GigiNavHost.kt）
```diff
diff --git a/app/src/main/java/com/gigi/tcg/ui/navigation/GigiNavHost.kt b/app/src/main/java/com/gigi/tcg/ui/navigation/GigiNavHost.kt
index c4cf221..6d77fc8 100644
--- a/app/src/main/java/com/gigi/tcg/ui/navigation/GigiNavHost.kt
+++ b/app/src/main/java/com/gigi/tcg/ui/navigation/GigiNavHost.kt
@@ -58,8 +58,6 @@ import androidx.compose.ui.semantics.selected
 import androidx.compose.ui.semantics.semantics
 import androidx.compose.ui.text.style.TextOverflow
 import androidx.compose.ui.unit.dp
-import androidx.compose.ui.window.Popup
-import androidx.compose.ui.window.PopupProperties
 import androidx.lifecycle.compose.collectAsStateWithLifecycle
 import androidx.navigation.NavGraph.Companion.findStartDestination
 import androidx.navigation.NavHostController
@@ -250,7 +248,7 @@ fun GigiNavHost() {
                             }
                         }
                     },
-                    snackbarHost = { },
+                    snackbarHost = { ToastHost(toastController) },
                 ) { innerPadding ->
                     Row(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
                         if (useRail) {
@@ -327,17 +325,6 @@ fun GigiNavHost() {
             }
         }
 
-        // Toast 用 Popup 独立窗口渲染，避免被 Scaffold 内的 ModalBottomSheet / Dialog 遮挡。
-        Popup(
-            alignment = Alignment.BottomCenter,
-            properties = PopupProperties(focusable = false, dismissOnClickOutside = false),
-        ) {
-            ToastHost(
-                controller = toastController,
-                modifier = Modifier.padding(horizontal = 16.dp, vertical = 24.dp),
-            )
-        }
-
         if (queryOpen) {
             PlayerQueryDialog(
                 onSubmit = { uid ->
```

## 要求 4 逐条确认（grep 复核）
- 顶栏账户名 `widthIn(max = 144.dp)` + Ellipsis → L158/L163 ✅ 仍在
- `DropdownMenuItem` Ellipsis + `semantics { role = Role.DropdownList; selected }` → L176/L185 ✅ 仍在
- `NavigationBarItem`/`NavigationRailItem` label Ellipsis、`alwaysShowLabel`、`semantics { role = Role.Tab }` → L238/L241/L243/L277/L281 ✅ 仍在
- Rail 顶部 `Icon(Icons.Outlined.Style)` + `HorizontalDivider` → L263/L287 ✅ 仍在
- `Popup`/`PopupProperties` import 与调用块 → grep 无残留 ✅ 已清除；`Alignment` 因 L260 `contentAlignment = Alignment.Center` 仍在使用，import 保留 ✅

## 归属说明
`git diff --stat` 中其余文件（AppGate.kt / CardStatsRoute.kt / RankRoute.kt / 任务清单 / task_scheduler.py 等）
为并发棒遗留的工作区改动，非本任务产物，未裹挟进 commit；本任务只 add GigiNavHost.kt 与探针。

status: done

