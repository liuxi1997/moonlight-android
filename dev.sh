#!/usr/bin/env bash
# dev.sh —— remote_desk Android 一键开发流水线（zcode/人共用）
# 用法：
#   ./dev.sh build            构建 APK（Gradle daemon 热，增量 ~3s）
#   ./dev.sh install [serial] 装到模拟器（默认 emulator-5556）
#   ./dev.sh all    [serial]  build + install
#   ./dev.sh shot   [serial]  [tag]  截图到 /tmp/<tag>.png
#   ./dev.sh log    [serial]  rd_ui 埋点日志（最近 30 条）
#   ./dev.sh ui     [serial]  dump UI 层级（含 Flutter 应用的原生控件边界）
#   ./dev.sh clean-stream     确保串流干净退出（防 force-stop 僵死 RTSP）
set -euo pipefail
cd "$(dirname "$0")"

export JAVA_HOME="${JAVA_HOME:-D:\tools\jdk\jdk-17.0.21+9}"
export GRADLE_HOME="${GRADLE_HOME:-D:\tools\gradle-9.7.1}"
export PATH="$GRADLE_HOME/bin:/d/android-sdk/platform-tools:$PATH"
ADB=adb
[ -x /d/android-sdk/platform-tools/adb.exe ] && ADB=/d/android-sdk/platform-tools/adb.exe
SERIAL="${2:-emulator-5556}"
APK=app/build/outputs/apk/nonRoot/debug/app-nonRoot-debug.apk

case "${1:-help}" in
  build)   gradle assembleDebug ;;
  install) $ADB -s "$SERIAL" install -r "$APK" ;;
  all)     gradle assembleDebug && $ADB -s "$SERIAL" install -r "$APK" ;;
  shot)    $ADB -s "$SERIAL" exec-out screencap -p > "/tmp/${3:-shot}.png" && echo "/tmp/${3:-shot}.png" ;;
  log)     $ADB -s "$SERIAL" logcat -d -s rd_ui:* | tail -30 ;;
  ui)      $ADB -s "$SERIAL" shell "uiautomator dump //sdcard//ui.xml && cat //sdcard//ui.xml" | tr '<' '\n<' ;;
  clean-stream)
    $ADB -s "$SERIAL" shell am force-stop com.limelight.debug 2>/dev/null || true
    $ADB -s "$SERIAL" shell input keyevent 3 2>/dev/null || true
    echo "注：force-stop 会令 Sunshine RTSP 僵死数分钟；串流退出请优先用返回键（keyevent 4）" ;;
  *) grep -E '^#   ' "$0" | sed 's/^#   //' ;;
esac
