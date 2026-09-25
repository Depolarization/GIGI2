# V7B-ADDACCOUNT — 添加账号返回不刷新页面

status: done（构建+171/171 单测全绿；commit 0d9f958 + 228ec24）
内容：读 AppGate 全文 ✅ → 状态机改动 ✅ → AppGate 叠加层 ✅ → 构建+单测 ✅

## 现象
主页点顶栏账户名 →"添加账号"→ 返回键 → 主页被重置（跳回首页 tab、滚动丢失）。

## 根因
AppGate `when(state)` 分支切换：`Main→Login(addAccount=true)→Main`，
GigiNavHost 被移出组合再重建 → rememberNavController 重建 → 导航栈/滚动丢。
cancelAddAccount 还调 invalidateVerification() 额外触发 verify。

## 方案
Main 增加 `addAccount` 字段；addAccount/cancelAddAccount 只在 Main 内翻转；
AppGate 的 Main 分支始终渲染 GigiNavHost，addAccount=true 时同层 Box 叠加全屏 LoginScreen。

## 闸门
git diff --quiet AppGate.kt / GateSeedAndRetryTest.kt → 均 rc=0 放行（2026-09-25）

## 状态机分支对照表（改动前 → 改动后）

| 状态 | 改前渲染 | 改后渲染 |
| --- | --- | --- |
| `Login`（真·未登录/失效） | LoginScreen 全屏 | 不变（保留） |
| `Main(verified=*, addAccount=false)` | GigiNavHost | `Box{ GigiNavHost }`（同一分支，不重建） |
| `Main(verified=*, addAccount=true)` | ——（旧走 Login 分支，导致重建） | `Box{ GigiNavHost + 不透明全屏 LoginScreen 叠加层 }` |
| `Unauthenticated` | Unit | 不变 |

## 关键行为变化
- `addAccount()`（有 cookie）：不再 `invalidateVerification()` + 切 `Login(addAccount=true)`，
  改为 `Main(verified=当前值, addAccount=true)` —— 分支不切换。
- `cancelAddAccount()`：Main 内仅 `copy(addAccount=false)`；🔴 不再 `invalidateVerification()`、
  不再触碰 `verifiedForCurrentSession`（旧代码曾把它强置 true）。非 Main 兜底才回 `Login()`。
- `onLoggedIn(uid)`：语义不变（`invalidateVerification()` + `Main(verified=true)`，addAccount 回落
  默认 false ⇒ 成功登录同样只摘叠加层）。
- `LaunchedEffect(state){ if Main -> observeMain() }`：addAccount 翻转仍触发，但
  `observeMain()` 首行 `if (verifiedForCurrentSession) return` 短路，不会重跑校验。

## "返回时 GigiNavHost 不重建"论证链
1. `AppGate` 的 `when(state)` 仅在**分支类别变化**时才移动组合节点；`Main→Main` 的 copy 是同一分支。
2. `GigiNavHost()` 调用点始终存在于 `Main` 分支的 `Box` 内、位置固定（Box 首子）⇒
   组合身份（slot）稳定 ⇒ `rememberNavController()` 的 remember 槽位不失效。
3. 添加账号期间 NavHost 持续在组合中（仅被不透明覆盖层遮挡，不参与隐藏/销毁）⇒
   nav back stack、各页 `rememberScrollState`/LazyList state 全程保活。
4. 返回键：`LoginScreen`（addAccount 页）接管系统返回 → `onCancelAddAccount` →
   `cancelAddAccount()` → 步骤 2/3 成立 ⇒ 主页 tab 与滚动位置原样保留。

## 并发干扰记录
首次构建报错全部在未独占的 `ui/screens/rank/RankRoute.kt`（V7A-RANK-TAB 在途，status: doing）；
独占文件无编译错。等待 V7A 落定后重跑验收命令。
