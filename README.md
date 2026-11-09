# TvPicView

Android TV demo：选择一个目录，点击“播放”后列出该目录下的图片和视频文件名。

## 功能

- 适配 Android TV / Google TV：Leanback 启动入口、横屏、D-pad 焦点
- 两种选目录方式：
  - **选择目录**：系统文件选择器（SAF），可访问任意授权目录，递归扫描
  - **媒体库目录**：基于 MediaStore 的目录列表，适合系统文件选择器被 TV 固件替换为空壳 Stub 的情况
- 点击“播放”后递归扫描目录，输出图片/视频文件名（屏幕列表 + Logcat）
- 内置诊断：
  - 屏幕底部常驻“诊断日志”面板，显示最近日志
  - 遥控器移动到日志面板按 OK，可查看/清空完整日志
  - 状态栏出错时变红，直接显示异常类型、消息和代码位置
  - 所有阶段日志写入 Logcat（TAG：`TvPicView`）

## 安装包

仓库内有一个可直接侧载的 debug APK：

```text
dist/TvPicView-debug.apk
```

## 构建

需要 JDK 17 和 Android SDK（compileSdk 35）。

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
export ANDROID_HOME="$HOME/Library/Android/sdk"
export PATH="$ANDROID_HOME/platform-tools:$PATH"

./gradlew assembleDebug
```

APK 输出：`app/build/outputs/apk/debug/app-debug.apk`

## 安装到 TCL / Android TV

电视与电脑在同一局域网；电视打开“开发者选项 → USB 调试 / 网络调试”。

```bash
adb connect <电视IP>:5555
adb devices
adb install -r dist/TvPicView-debug.apk
adb shell am start -n com.kkorpse.tvpicview/.MainActivity
```

> 注意：TCL 电视有 Google TV / Android TV 和 Roku TV 两种系统。Roku 电视不能安装 Android APK，本 demo 只适用于前者。国内 TCL/雷鸟通常没有 Google Play，但可以直接侧载 APK，应用不依赖 GMS。

## 使用

1. 遥控器选择“选择目录”或“媒体库目录”，按 OK。
2. 选中目录后，焦点会移动到“播放”，按 OK 开始扫描。
3. 结果会显示在列表中，同时写入 Logcat；状态栏显示文件总数、视频数、图片数和错误数。
4. 系统文件选择器不可用时，应用会自动提示并切换到“媒体库目录”。

## 排查问题

电视屏幕上：

- 底部“诊断日志”面板会显示最近 4 条日志
- 用方向键移动到该面板，按 OK 查看完整日志，可清空
- 扫描失败、权限不足、MediaStore 查询异常都会直接显示在状态栏（红色）

电脑上：

```bash
adb logcat -s TvPicView
```

日志包含启动环境、设备信息、权限状态、文件选择器解析结果、目录 URI、每次扫描的目录/文件、MediaStore bucket id、异常堆栈位置等。

常见问题：

| 现象 | 原因/处理 |
| --- | --- |
| 状态栏提示“系统文件选择器是空壳 Stub” | Google TV 固件移除了 DocumentsUI，使用“媒体库目录”即可 |
| 媒体库为空 | 电视媒体库还没扫描到文件；先把图片/视频放到电视，或使用“选择目录” |
| 没有媒体权限 | 按提示打开应用设置，允许访问照片和视频 |
| 列表只显示部分文件 | MediaStore 只包含已索引的媒体；改用系统文件选择器可扫描任意目录 |

## 工程结构

```text
app/src/main/java/com/kkorpse/tvpicview/MainActivity.java   # 选择器、扫描、诊断
app/src/main/res/layout/activity_main.xml                   # TV 焦点与日志面板
app/src/main/AndroidManifest.xml                            # Leanback / 权限
```
