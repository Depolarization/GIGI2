status: done
owner: T5a
files: [app/src/main/java/com/gigi/tcg/ui/components/StateViews.kt, app/src/main/java/com/gigi/tcg/ui/components/Avatar.kt, app/src/main/java/com/gigi/tcg/ui/components/GigiToast.kt]
evidence: [
  ":app:assembleDebug => BUILD SUCCESSFUL (首轮失败均为 T4b 在途 LoginViewModel.kt，按派单等待后通过，未改其文件)",
  ":app:lintDebug => 0 errors / 5 warnings（warning 均不在本组文件）",
  ":app:testDebugUnitTest => 95 tests, 0 failures (14 suites)",
  "commit 1977c74 (parent b53bc35 = T4b)"
]
blockers: []
summary: 三态/头像/Toast 组件按契约落地并提交
updated: 2026-09-25 01:13:38

## 组件签名清单（T5b/c/d 契约）
```kotlin
// StateViews.kt
@Composable fun LoadingView(modifier: Modifier = Modifier, label: String? = null)
@Composable fun EmptyState(modifier: Modifier = Modifier, icon: ImageVector? = null, title: String, message: String? = null) // icon=null 默认 Icons.Outlined.Inbox
@Composable fun ErrorState(modifier: Modifier = Modifier, message: String, onRetry: (() -> Unit)? = null)

// Avatar.kt（Coil 2.7 AsyncImage，圆形 clip；空 url/占位/失败=灰底 Person；不引用 DEFAULT_AVATAR_URL）
@Composable fun Avatar(url: String?, modifier: Modifier = Modifier, size: Dp = 40.dp, contentDescription: String? = null)

// GigiToast.kt（对外仅 4 符号；队列 = UNLIMITED Channel，ToastHost 内 LaunchedEffect 逐条 showSnackbar）
data class ToastMessage(val id: Long, val text: String)
class ToastController { fun show(text: String) }
val LocalToast = compositionLocalOf<(String) -> Unit> { {} } // 默认 no-op
@Composable fun ToastHost(controller: ToastController, modifier: Modifier = Modifier)
```
