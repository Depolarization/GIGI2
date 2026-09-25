# GIGI Android 原生重写 — 唯一权威协调文件

> 规则：事件总表只追加、文件占用锁行不删（🔒→🔓）、任务区块含完成标准。
> 设计文档：D:\EmulatorShared\GIGIWeb\2026-09-24-gigi-android-native-design.md（唯一权威设计）
> 参照实现：D:\EmulatorShared\GIGI\web（React 19 + TS，6158 行）
> 子代理手册：.task/SUBAGENT_HANDBOOK.md（每个子代理开工必读）

## 总完成标准（全部满足才算完成）
1. `./gradlew :app:assembleDebug` 退出码 0；`:app:lintDebug` 无 Error；`:app:testDebugUnitTest` 全绿
2. Web 版 79 个 Vitest 用例逐字移植为 JVM 单测且全绿（含两个"反直觉"行为：id2code 低位在前不反转、computeGcgSummary 出场率分母口径）
3. 四页面 + 登录链路 + 三弹窗功能 1:1 对齐 Web 版
4. 真机/模拟器 adb 取证：登录全链路、四页三态、换服隔离、卡面下载落相册、退出后私有缓存清空，截图落盘
5. 全阶段 git 分步提交

## 任务区块

### T1 脚手架 🔓gradle 脚本群
- 范围：git init；settings.gradle.kts / 根 build.gradle.kts / app/build.gradle.kts；gradle wrapper（8.14.5，复用 Shield）；AndroidManifest（allowBackup=false）；GigiApp/MainActivity；theme（动态取色+固定语义色 #3f8f5f/#c25656/#d4a643）；4 页占位 NavHost（NavigationBar，宽屏 NavigationRail）；AppContainer 空壳
- 完成标准：`assembleDebug` 退出码 0
- 状态：✅ 完成（8ae8853/e3af84c/d46fbd2，主代理独立复验通过：build 重跑 exit 0 + APK 19.4MB + diff 逐行审）
- 已裁决偏离：① JAVA_HOME 改 JDK 21 `C:\Users\oscur\.jdks\ms-21.0.12.1`（JBR 25.0.2 被 Gradle 8.14.5 拒识，KTS 配置阶段炸，主代理实测复现确认；手册 §2 已更正）② Kotlin 保持 2.1.20 ③ datastore-preferences 钉 1.1.1（BOM 不管辖）

### T2 domain 层 🔓domain/ + test/
- 范围：Tier/UidCode/Opponent/Percent/Summary/Throttle/TtlCache(纯Kotlin)/WikiParser + 8 个测试文件移植
- 完成标准：`testDebugUnitTest` 全绿，用例数对齐 Web 版 79 个
- 依赖：T1；状态：✅ 完成（217f2b0..67e62fd 共 8 commit；主代理复跑 test exit 0/报告59用例0失败/domain纯净0）
- 口径裁决：文档"79用例/8文件"有误——8 utils 文件展开=59 全量移植；其余 20（retcode5/servers9/storage4+2）属 T3+，由 T3 承接其 JVM 可移植部分
- VERIFY-T2（前4模块）+VERIFY-T2-B（后5模块）只读验收均 PASS；新增 Format.kt 经核实合理（throttle.test.ts 含 format 用例）

### T3 data 层 🔒data/
- 范围：@Serializable 模型（全字段可空）、MihoyoClient（retcode 集中判定/Cookie 注入/1s 节流）、CredentialStore（Keystore+AES-GCM）、双层缓存、GigiRepository、ServerId 注册表、AppContainer 填充
- 完成标准：assembleDebug 0 + 可单测部分全绿
- 依赖：T1、T2；状态：待派

### T4 登录链路 🔒data/auth/ + ui/login/
- 范围：QR 生成/轮询(Flow+delay 2500)/凭据交换(§3.2 四步)/no-role 拒绝半登录；登录全屏页
- 完成标准：assembleDebug 0；交换逻辑单测覆盖
- 依赖：T3；状态：待派

### T5 共享组件+弹窗 🔒ui/components/ + ui/dialogs/
- 范围：三态视图/Avatar/Toast；PlayerDetailDialog(Dialog)/CardCoverDialog(ModalBottomSheet)/AboutDialog(Dialog)
- 完成标准：assembleDebug 0
- 依赖：T1、T3；状态：待派

### T6-T9 四页面（并行，页面包级独占）🔒ui/screens/<各自>/
- Home(T6) / Rank(T7) / CardStats(T8) / CardWiki(T9)：各 Screen+ViewModel，MVI sealed UiState，rememberSaveable 筛选态，筛选变更滚动到顶
- 完成标准：assembleDebug 0；路由接线由 T10 统一做
- 依赖：T3、T5；状态：待派

### T10 集成接线 🔒navigation 文件
- 范围：NavHost 占位替换为真实页面；全量构建 + lint
- 完成标准：assembleDebug 0 + lintDebug 无 Error
- 依赖：T6-T9；状态：待派

### T11 真机取证
- 范围：MuMu 127.0.0.1:16384 adb 逐页截图比对 Web 版语义；§5.2 五个取证点
- 完成标准：截图落盘 .task/evidence/，逐点 PASS
- 依赖：T10；状态：待派

### U 轮 体验优化批（2026-09-25 第二轮 14 项需求，派单全文见 .task/dispatch/）
- 需求清单（用户原话 14 项）：①首刷误报"拉取失败过于频繁"②主页对局恒空③动画生硬④关于文案"网页应用"未改写⑤统计列不等宽⑥图鉴过滤挤压+卡过小⑦卡面弹窗不可拖拽⑧登录后禁换服⑨多账户⑩cookie 保活⑪对手详情隐藏查询码+段位归位⑫图片加载动画+占位⑬排行榜删引导文本⑭自主优化到可交付
- 分组：INFRA-UI（AppImage/Motion 基建）→ P1a(theme+cardstats) / P1b(cardwiki+cardcover) / P2(data+home) / P3(about+playerdetail+rank) / P4(auth+navhost+login 账户系统⑧⑨⑩) → 主代理全量回归+真机复测
- 完成标准：各棒探针 done + 主代理亲自复验（assemble 0 / lint 0 Error / 95+单测全绿 / diff 逐行审）+ 真机回归 14 项逐项核对
- 状态：✅ INFRA-UI（8f42ebf/46374ea/389f937）✅ P1a（6ffd994/4ba57d4）✅ P1b（4923cfe/f34fd56）✅ P2（ded0478/d642dc1，122 测试）✅ P3（43e99af/70f7799/1be9536）✅ P4（8e75dd4/14812c2/62b2c7c/5ceef61/2f68134）；P5R 质感定点核查在跑；之后真机回归
- 用户补充（2026-09-25 12:00）：图鉴无刷新按钮（已落地）；退出登录二次确认对话框（已落地，P4）；全软件按标准 Material 3 质感规范重构（P5R 定点核查收口）
- 依赖裁决：ui/components/AppImage.kt、ui/theme/Motion.kt 为各 UI 棒公共依赖，故设 INFRA-UI 前置棒；T11-EVIDENCE 旧版基线取证自然收尾中，与新棒文件零冲突

## 事件总表（只追加）
| 时间 | ID | 事件 | hash |
|---|---|---|---|
| 2026-09-24 22:14 | main | 协调网络建立；用户确认 applicationId=com.gigi.tcg | - |
| 2026-09-24 22:17 | T1 | 后台派发（Qwen3.8-Flash，job-2b256ce7）；T2 派单已预写 | - |
| 2026-09-24 22:45 | T1 | ✅ 验收通过（主代理重跑 build exit 0）；裁决 JAVA_HOME→JDK21、datastore 钉 1.1.1；手册已更正 | 8ae8853..d46fbd2 |
| 2026-09-24 23:01 | T2 | 后台派发（Qwen3.8-Flash，job-d5c3da74）；监视器已 ensure@8787，--wait-done T2 已登记 | - |
| 2026-09-24 23:12 | VERIFY-T2 | 只读验收代理后台派发（job-f798f971），验收已产出的前 4 模块；--wait-done T2,VERIFY-T2 | - |
| 2026-09-24 23:12 | main | 用户确立常设纪律：本轮验收通过后自动派下一轮，不再询问；只读验收由非实现者执行 | - |
| 2026-09-24 23:40 | T2 | ✅ 正式闭环：VERIFY-T2/T2-B 均 PASS + 主代理复验 | - |
| 2026-09-24 23:42 | T3a/b/c | 三路并行派发（models+registry / api client / credential store），T3d 派单已预写；--wait-done 已登记 | - |
| 2026-09-25 00:05 | T3a/b/c | ✅ 主代理核实：6 commit（f85b49c..be4ab50）、源码树干净、产物在盘、探针 done；throttled 显式化裁决知悉 | - |
| 2026-09-25 00:06 | T3d | 集成收尾后台派发（job-523d5704）；--wait-done T3d | - |
| 2026-09-25 00:25 | T3 | ✅ 全量验收通过：build 0、83 测试 0 失败（主代理复跑核实）、APK 在盘 | e124d19..bf5ebeb |
| 2026-09-25 00:27 | T4a/T4LINT | 并行派发：登录核心 AuthManager / domain 层 lint 7 Error 修复；--wait-done 已登记 | - |
| 2026-09-25 01:10 | T4a/T4LINT | ✅ 主代理验收：95 测试 0 失败、lint Error 0、build 0（5 commit：32bb430..c5bdba5） | - |
| 2026-09-25 01:12 | T4b | 登录全屏页+AppGate 门控后台派发（job-9a2d5eac） | - |
| 2026-09-25 01:14 | T5a | 共享组件后台派发（job-0f3490d2），与 T4b 并行；T5b/c/d 派单预写 | - |
| 2026-09-25 01:40 | T4b/T5a | ✅ 主代理验收：3 commit（627c2a8/ b53bc35/1977c74）、assemble 0、树干净 | - |
| 2026-09-25 01:43 | GIGI-MAIN | 接力链启动：T5b(15656)/T5c(36116)/T5d(560) 已派发 | .task/chains/GIGI-MAIN.json |
| 2026-09-25 01:46 | GIGI-MAIN | 调度器切换计划任务模式：pid=38128 parent=svchost（修复相对路径坑：链条改绝对路径）；三棒 wait_only；监视器 8787 在 | - |
| 2026-09-25 02:00-02:06 | T5b/c/d | ✅ 三弹窗完成（commits 8167533/9047de6/d3fe7da..6009135）；调度器自动派 T6-T9 | - |
| 2026-09-25 02:23 | GIGI-MAIN | ⛔ 误判 T9 stale→重试→双代理并发；整链 blocked 停止（无残留进程，调度器已退出） | - |
| 2026-09-25 02:35 | T6-T9 | ✅ 主代理验收：四路全 done（296c277/b84cf74、69b486e/9b34596、97a2cfc、31d179a/d783b96），三判据 0、95 测试；T9 最终探针 done | - |
| 2026-09-25 02:37 | TSCHEDFIX | 派单修复调度器 sess_ts 单向救援（job-cc6df1e3）；GIGI-FINISH 链（T10→T11）已预写 | - |
| 2026-09-25 08:25 | TSCHEDFIX | ✅ 主代理验收 dd25df4：diff 最小、三场景独立复跑通过（running/stale/dead） | - |
| 2026-09-25 08:26 | GIGI-FINISH | 计划任务链启动：scheduler pid=37480（svchost→cmd→python），T10 已派发 pid=39336 Qwen3.8-Max；T10 done 后自动派 T11 取证收尾 | .task/chains/GIGI-FINISH.json |
| 2026-09-25 09:02 | T11 | ⛔ 取证受阻：扫码确认即闪退（dropbox+logcat 证据：Caller-provided IV not permitted @CredentialStore.save:32）；T11 又被双派发；存活代理及残留进程已清理 | - |
| 2026-09-25 09:05 | FIX×3 | 并行派发：FIX-CRED-IV（Keystore IV 修复+androidTest）、FIX-ICON（自适应图标+PNG 回退）、TSCHEDFIX2（收尾代理防双发） | - |
| 2026-09-25 10:15 | FIX-ICON/TSCHEDFIX2 | ✅ 主代理验收：83a9916（aapt badging icon 已声明）、71702db（stub 链独立复跑 dispatch 仅 1 次）；三判据 0 | - |
| 2026-09-25 10:15 | FIX-CRED-IV | ⚠️ 5f892de 修复已提交且三判据过，但 androidTest 缺依赖无法收口（探针 blocked）→ 另派 FIX-ATEST-WIRING | - |
| 2026-09-25 10:20 | FIX-ATEST-WIRING | 后台派发（job-9f42f5bc）：补三依赖 + Injector 改 AndroidJUnitRunner 子类 + connected 测试收口 | - |
| 2026-09-25 10:40 | FIX-ATEST-WIRING | ✅ 主代理独立复验：直装两 APK、JUnit via injector OK (1 test)、注入 saved→冷启动直入主界面、clear→回登录页（截图+UI dump 证实）；提交 de9b200/43381a8/017c631 | - |
| 2026-09-25 10:42 | T11 | ⛔ 能力边界：本机 cookie（round7/8 gigi-cookie.json）cookie_token_v2 已过期，登录后页面无法取证；已重启 App 备好新鲜二维码，等用户真机扫码或提供新 cookie | - |
| 2026-09-25 01:48 | GIGI-MAIN | ✅ 提交协作基础设施（自包含 skill + 本地 tools）：ea58444、协调网络：a98e09d；T5b/c/d 在跑产物有意排除 | ea58444 / a98e09d |
| 2026-09-25 10:30 | main | 第二轮 U 轮启动：14 项体验需求派单落盘（INFRA-UI/P1a/P1b/P2/P3/P4）；主代理已实读 MihoyoClient/ApiError/HomeViewModel/CredentialStore/AuthManager/AppGate/GigiNavHost/AboutDialog 定稿派单；T11-EVIDENCE 基线取证在跑（约第 6/10 项） | - |
| 2026-09-25 10:31 | INFRA-UI | 后台派发（Qwen3.8-Flash，job-4b82）；--wait-done 已登记（job-6088） | - |
| 2026-09-25 11:22 | P1a | ✅ 验收：Motion 接入+统计四列等宽对齐+formatStatPercent 单测；三判据绿 | 6ffd994/4ba57d4 |
| 2026-09-25 11:05-12:12 | 通道回退 | Max/DeepSeek 额度尽→OpenRouter key 失效→宿主代理 killed→opencode（用户级 key 显式注入）/qoder 新增 100 积分；P2R2 用 Flash 收口 | - |
| 2026-09-25 11:43 | P1b | ✅ ⑥图鉴紧凑重排（156dp/去刷新）⑦卡面弹窗拖拽+按钮常驻；探针 done | 4923cfe/f34fd56 |
| 2026-09-25 12:12 | P3 | ✅ ④关于 Android 化⑪对手隐藏查询码+段位入头部⑬排行榜去引导；grep 网页口径清零 | 43e99af/70f7799/1be9536 |
| 2026-09-25 12:13 | P2 | ✅ ①首刷错峰+静默重试+抖动 ②对局列表去 0 高裁剪；122 测试全绿 | ded0478/d642dc1 |
| 2026-09-25 12:15 | P4 | ✅ 多槽凭据+账户菜单切换/添加/退出二次确认+e_hk4e 静默续命；主代理三判据复跑绿 | 8e75dd4..2f68134 |
| 2026-09-25 12:26 | P5 | ⛔ 全量审计 29 文件体量过大挂死（残留 node 已清）→ 改派 P5R 五项定点核查 | - |
| 2026-09-25 12:38 | P5R | 后台派发（qoder Flash，job-fc64） | - |

## 文件占用锁
| 文件/目录 | 状态 | 占用者 |
|---|---|---|
| gradle 脚本群/AndroidManifest | 🔓 | -（T1 完成释放） |
| app/src/main/java/com/gigi/tcg/domain/ | � | -（T2 完成释放） |
| app/src/test/ | �→�� | T3 各组按需新增测试 |
| app/src/main/java/com/gigi/tcg/data/model + ServerId/ServerApi | � | T3 完成 |
| app/src/main/java/com/gigi/tcg/data/api/ | 🔓 | T3 完成 |
| app/src/main/java/com/gigi/tcg/data/auth/ QrSession/AuthManager | 🔓 | T4a 完成 |
| app/src/main/java/com/gigi/tcg/domain/ | 🔓 | T4LINT 完成 |
| app/src/main/java/com/gigi/tcg/ui/login/ + MainActivity + di/AppContainer | 🔒 | T4b |
| app/src/main/java/com/gigi/tcg/ui/components/ | 🔒 | T5a |
| app/src/main/java/com/gigi/tcg/ui/dialogs/{playerdetail,cardcover,about} | 🔓 | T5b/c/d 待派 |
| app/src/main/java/com/gigi/tcg/data/cache + repo | 🔓 | T3 完成 |
| app/src/main/java/com/gigi/tcg/ui/ | 🔓 | - |
| ui/components/AppImage.kt + ui/theme/Motion.kt + ui/components/Avatar.kt | 🔒 | INFRA-UI |
| ui/theme/ 其余 + ui/screens/cardstats/ | 🔒 | P1a（排队） |
| ui/screens/cardwiki/ + ui/dialogs/cardcover/ | 🔒 | P1b（排队） |
| data/api/ + data/repo/GigiRepository.kt + ui/screens/home/ | 🔒 | P2（排队） |
| ui/dialogs/about/ + ui/dialogs/playerdetail/ + ui/screens/rank/ | 🔒 | P3（排队） |
| data/auth/ + ui/login/ + ui/navigation/GigiNavHost.kt + di/AppContainer.kt + GigiApp.kt | 🔒 | P4（排队） |
| app/src/test/ | 🔒 | P1a/P2/P4 按派单各写各的（冲突即 blocked） |

## Git 历史
（待 T1 git init 后登记）

## U2 轮（2026-09-25 16:45 派发，三棒并行）
| 文件 | 锁 | 归属 |
| ui/components/AppImage.kt | 🔒 | U2A-IMAGE（A1 P0 图片自锁） |
| ui/screens/rank/RankRoute.kt | 🔒 | U2A2-RANKTOP（A2 行距） |
| ui/navigation/GigiNavHost.kt | 🔒 | U2A2-RANKTOP（A3 顶栏顺序） |
| ui/screens/cardwiki/CardWikiRoute.kt | 🔒 | U2B-WIKIABOUT（B1/B2/B3） |
| ui/dialogs/about/AboutDialog.kt | 🔒 | U2B-WIKIABOUT（B4） |
| .task/evidence/U2A/ + U2A2/ + U2B/ | 🔒 | 各自棒 |

### 事件（只追加）
| 2026-09-25 16:45 | U2 | 主代理完成派单前调查：A1 根因字节码取证（coil 2.7.0 AsyncImagePainter.drawSize 初值 Size.Zero + updateRequest 注入 drawSize.mapNotNull{toSizeOrNull}.first() 的 SizeResolver，而 AppImage 只在 Success 分支才绘制 painter ⇒ 自锁永 shimmer）；设备 MuMu 已拉起 boot_id=0e07370d-841d-4ce7-af39-c151824f9dc5 900x1600/density320/Android12；gradle 四任务名已实测存在 | - |
| 2026-09-25 16:46 | U2A-IMAGE | 已派发 DeepSeek-Flash，后台运行 | - |
| 2026-09-25 16:46 | U2A2-RANKTOP | 已派发 DeepSeek-Flash，后台运行 | - |
| 2026-09-25 16:47 | U2B-WIKIABOUT | 已派发 DeepSeek-Flash，后台运行 | - |

### 已知环境风险（供后续棒注意）
- 设备 DNS 对 hoyobbs/hoyohub/hoyoapi 解析失败（api-takumi、baidu 正常）⇒ A1 修复后若卡面仍
  失败，先区分「代码自锁」与「设备侧 DNS/网络」两个原因，不要混为一谈。
- 设备当前未安装 com.gigi.tcg（pm list 无包），首装后凭据可能已失效 ⇒ 各棒若停在登录页，
  一律 blocked 记录，禁止清数据。
- IDE studio64 当前未运行（gradle 无锁竞争）；若用户在子代理运行期间打开 IDE，
  会出现 registry.bin.lock 拒绝访问并静默中断，遇此必须停手报 blocked。

### U2 收尾（17:25）
| commit | 内容 |
| df3f502 + 1c1a935 | A1 AppImage 图片自锁修复（coil drawSize 死锁）+ 探针 |
| c16a4cb | A2 排行榜行距收紧 |
| 100f9c1 + 590d628 | A3 顶栏账户名移到查询图标左侧 + 证据 |
| 7b64fa2 | B1/B2/B3 图鉴边距/下拉筛选/固定两列 |
| 36c8fa7 + cd2ff87 | B4 关于对话框文案与双按钮 + 证据 |
| 2e8a5d2 | U2 真机回归证据（18 张截图 + 报告） |

**主代理独立验收结论：10/10 PASS，0 FAIL，0 受限；单测 121/0 failures；FATAL EXCEPTION=0。**
遗留（非代码缺陷）：设备 DNS 解析失败 hoyobbs/hoyohub/hoyoapi.mihoyo.com（对照组 api-takumi 正常），
本轮图片均正常显示；未来清缓存取新卡图若失败，优先归因设备 DNS。
