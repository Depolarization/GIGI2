FIX-ICON: done

## 新增/修改资源清单
- app/src/main/AndroidManifest.xml  `<application>` 仅新增 `android:icon="@mipmap/ic_launcher"` + `android:roundIcon="@mipmap/ic_launcher_round"`（其余属性/顺序未动）
- app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml        adaptive-icon（background/foreground）
- app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml  adaptive-icon（同内容）
- app/src/main/res/drawable/ic_launcher_background.xml      108dp 矢量：#12352b 深绿底 + #1c4a3a 对角几何带 + 金/绿角点
- app/src/main/res/drawable/ic_launcher_foreground.xml      108dp 矢量：白色描边卡牌 + 绿/金七圣星 + 金色 pip，全部元素落在 32..76dp（距边 ≥18dp，安全区内）
- app/src/main/res/mipmap-xxxhdpi/ic_launcher.png           192x192 RGBA（API24/25 回退）
- app/src/main/res/mipmap-xxxhdpi/ic_launcher_round.png     192x192 RGBA + 圆形 alpha mask

## PNG 生成方式
PIL 12.3.0 按矢量同源几何 4x 超采样绘制后 LANCZOS 缩到 192（未使用外部图床/HTTP 素材：
ImageGen 产物带 “Qoder AI 生成” 水印，不适合做应用图标，故弃用）。

## 验收门槛
1. `.\gradlew :app:assembleDebug` → BUILD SUCCESSFUL（AAPT 解析全部资源通过）
2. `.\gradlew :app:lintDebug`     → BUILD SUCCESSFUL，Error 0 / Warning 8
   （warning: DataExtractionRules, IconLauncherShape, ModifierParameter,
     MonochromeLauncherIcon x2, ScopedStorage, UseKtx x2 —— 均为提示级，非本单范围）
3. `.\gradlew :app:testDebugUnitTest` → tests 95 / failures 0 / errors 0 / skipped 0
4. 装包验证
   - `adb -s 127.0.0.1:7555 install -r app/build/outputs/apk/debug/app-debug.apk` → Success
   - 设备为 Android 12，`dumpsys package com.gigi.tcg` 该版本不再打印 `icon=` 行
     （grep -i icon 无匹配），故改用等价证据：从设备实际安装的
     `/data/app/.../com.gigi.tcg-.../base.apk` 反解二进制 Manifest：
       `android:icon(0x01010002)=@0x7f080000`
       `android:roundIcon(0x0101052c)=@0x7f080001`   ← 非 0 资源 id，达标
   - `aapt dump badging app-debug.apk` → `application: label='GIGI' icon='res/mipmap-anydpi-v26/ic_launcher.xml'`

## 环境备注
- PATH 上 `adb` 不可用，实际路径 `D:\AndroidSDK\platform-tools\adb.exe`
- 默认 JVM 为 Java 8，构建需 `JAVA_HOME=C:/Users/oscur/.jdks/ms-21.0.12.1`
