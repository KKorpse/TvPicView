#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"

export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
export PATH="$ANDROID_HOME/platform-tools:$PATH"

APP_ID="com.kkorpse.tvpicview/.MainActivity"
APK="${1:-}"

if [[ -z "$APK" ]]; then
  APK=$(ls -t dist/TvPicView-*.apk 2>/dev/null | head -1 || true)
fi

if [[ -z "$APK" || ! -f "$APK" ]]; then
  echo "找不到 APK。请先运行 ./gradlew assembleDebug，或把 APK 路径作为参数传入。"
  exit 1
fi

if ! command -v adb >/dev/null 2>&1; then
  echo "找不到 adb，请先安装 Android SDK Platform-Tools。"
  exit 1
fi

devices=()
while IFS= read -r device; do
  devices+=("$device")
done < <(adb devices | awk 'NR > 1 && $2 == "device" { print $1 }')

if (( ${#devices[@]} == 0 )); then
  echo "没有已连接的设备。请先在电视上开启 ADB 调试，并运行 adb connect <电视IP>:5555。"
  exit 1
fi

if (( ${#devices[@]} > 1 )); then
  echo "检测到多个设备，请只保留电视连接后重试："
  printf '  %s\n' "${devices[@]}"
  exit 1
fi

device="${devices[0]}"
echo "更新安装：$APK"
echo "目标设备：$device"

set +e
output=$(adb -s "$device" install -r "$APK" 2>&1)
status=$?
set -e

echo "$output"

if (( status == 0 )); then
  adb -s "$device" shell am start -n "$APP_ID" >/dev/null 2>&1 || true
  echo "更新完成，不需要卸载旧版本。"
  exit 0
fi

case "$output" in
  *VERSION_DOWNGRADE*)
    echo "电视上的版本更新。请先提升 app/build.gradle 里的 versionCode；临时回退可加 -d。"
    ;;
  *UPDATE_INCOMPATIBLE*|*"signatures do not match"*)
    echo "签名不一致。这一次需要卸载旧版；之后固定使用同一个签名即可直接覆盖安装。"
    ;;
  *ALREADY_EXISTS*)
    echo "旧版本已存在。请使用 -r 覆盖安装（本脚本已经带上 -r）。"
    ;;
  *INSUFFICIENT_STORAGE*)
    echo "电视存储空间不足，清理空间后重试。"
    ;;
  *USER_RESTRICTED*)
    echo "电视限制了 ADB 安装，请在开发者选项和未知来源设置中允许。"
    ;;
  *)
    echo "安装失败，请根据上面的 adb 错误信息处理。"
    ;;
esac

exit "$status"
