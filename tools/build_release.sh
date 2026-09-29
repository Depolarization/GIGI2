#!/usr/bin/env bash
# GIGI release 一键出包 + 验签 + 覆盖桌面（Git Bash / Windows）。
#
# 用法：  bash tools/build_release.sh
#
# 铁律（踩过的坑，别改）：
#  1. JDK 必须是 21：C:/Users/oscur/.jdks/ms-21.0.12.1
#     D:\Java 是 JDK8 太旧；Android Studio 自带 jbr=25 会被 AGP 8.9.1 + Kotlin 2.1.20 拒绝。
#  2. 签名凭据只走环境变量（GIGI_STORE_PASSWORD / GIGI_KEY_PASSWORD / GIGI_KEY_ALIAS），
#     build.gradle.kts 读的就是这三个。V38-C 起**缺失即构建失败**（GradleException 带 setx 指引），
#     不再有"未配置就出未签名包"的静默回退。本脚本因此也**不再注入任何默认口令**——
#     注入 `temp_password` 这种兜底会在真值缺失时把构建打成"keystore 口令错误"的混淆失败，
#     盖掉 build.gradle 那条清晰指引。setx 见 docs/RELEASE-SIGNING.md。
#  3. apksigner 在 **Android SDK 的 build-tools** 里，不在 JDK 里：
#     D:/AndroidSDK/build-tools/36.0.0/apksigner.bat
#     （去 JDK 的 bin 找会 "No such file or directory"）
#  4. 证书必须是 CN=GIGI（与已发布包同签名），否则装机报 INSTALL_FAILED_UPDATE_INCOMPATIBLE。
#     注意：换 keystore **口令**不换密钥，证书指纹不变 ⇒ install -r 照常覆盖；
#     换 keystore **文件/密钥对**才会导致指纹变化、必须卸载重装。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

export JAVA_HOME="C:/Users/oscur/.jdks/ms-21.0.12.1"
# 🔴 不设任何口令兜底（V38-C）：缺失时让 build.gradle.kts 抛带 setx 指引的 GradleException。
export GIGI_KEY_ALIAS="${GIGI_KEY_ALIAS:-gigi_key}"

APKSIGNER="D:/AndroidSDK/build-tools/36.0.0/apksigner.bat"
OUT="app/build/outputs/apk/release/app-release.apk"

echo "==> 单元测试"
./gradlew :app:testDebugUnitTest --console=plain

echo "==> 构建签名 release"
./gradlew :app:assembleRelease --console=plain

[ -f "$OUT" ] || { echo "✗ 没产出 $OUT（若上方报的是缺少 GIGI_STORE_PASSWORD/GIGI_KEY_PASSWORD，见 docs/RELEASE-SIGNING.md）" >&2; exit 1; }

echo "==> 验签"
"$APKSIGNER" verify --print-certs "$OUT" | head -3

if [ -f /c/Users/oscur/Desktop/GIGI.apk ]; then
  cp "$OUT" /c/Users/oscur/Desktop/GIGI.apk
  echo "==> 已覆盖桌面 GIGI.apk"
fi

ls -la "$OUT"
