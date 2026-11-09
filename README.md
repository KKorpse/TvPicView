# TvPicView

Android TV / Google TV demo：选择媒体目录，扫描图片和视频，点击列表即可全屏播放。

## 功能

- 适配 Android TV / Google TV：Leanback 启动入口、横屏、D-pad 焦点
- 两种选目录方式：
  - **选择目录**：系统文件选择器（SAF），可访问任意授权目录，递归扫描
  - **媒体库目录**：基于 MediaStore 的目录列表，适合系统文件选择器被 TV 固件替换为空壳 Stub 的情况
- 扫描后点击列表项播放：
  - 图片：全屏显示，`fitCenter` 等比铺满，不裁剪，比例不一致时自动留黑边
  - 视频：Media3/ExoPlayer 播放，自动播放，支持播放/暂停、前后 10 秒
- 图片按 4K 上限解码：
  - 源图大于 4K：解码到 3840×2160 以内
  - 源图小于 4K：按原分辨率解码，再由 ImageView 放大到屏幕
- 视频不转码：按源分辨率硬解，由电视显示管线缩放到 4K 屏幕；大于 4K 的视频取决于电视解码器是否支持
- 播放页诊断：
  - 出错时屏幕中央显示红色错误卡片，停留 5 秒
  - 错误包含异常类型、消息、代码位置、文件名；HEIC/HEIF 会额外提示解码器问题
  - 视频错误包含 ExoPlayer 错误码、原因链
  - 全部错误写入 Logcat（TAG：`TvPicView`）
- 底部常驻诊断日志面板，按 OK 可查看/清空完整日志

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
2. 选中目录后，焦点移动到“播放”，按 OK 开始扫描。
3. 扫描结果列表中，方向键选择文件，按 OK 播放。
4. 视频播放中：
   - OK：播放/暂停
   - 左/右：快退/快进 10 秒
   - 返回：退出播放
5. 图片播放中：返回退出。

## 4K 说明

- 图片按 `3840×2160` 上限解码，8K 图片会先缩到 4K 再显示；小图不会在解码阶段放大，由 ImageView 铺满屏幕。
- 视频不做转码。4K 视频交给电视硬解；电视当前显示模式是 4K 时就按 4K 显示，是 1080p 时由系统/电视缩放。
- 播放页 Logcat 会输出当前显示模式、支持的模式、视频源分辨率、图片源/解码后尺寸。
- HEIC/HEIF 是否可播取决于电视系统解码器；不支持时错误卡片会提示。
- Live Photo 会被识别为两个独立文件：HEIC 显示静态图，MOV 单独作为视频播放。

## 排查问题

电视屏幕上：

- 播放错误：中央红色卡片显示 5 秒，包含异常、文件和代码位置
- 扫描/权限错误：状态栏变红，底部诊断日志显示最近记录
- 日志面板：方向键移动过去按 OK，可查看/清空完整日志

电脑上：

```bash
adb logcat -s TvPicView
```

日志包含启动环境、设备信息、权限状态、文件选择器解析结果、目录 URI、扫描到的文件 URI、MediaStore bucket id、图片解码尺寸、视频分辨率、ExoPlayer 错误码和异常堆栈。

## 工程结构

```text
app/src/main/java/com/kkorpse/tvpicview/MainActivity.java    # 选择目录、扫描、诊断
app/src/main/java/com/kkorpse/tvpicview/PlayerActivity.java  # 图片/视频全屏播放
app/src/main/res/layout/activity_main.xml                    # TV 焦点与日志面板
app/src/main/res/layout/activity_player.xml                  # 播放页、错误卡片、进度
app/src/main/AndroidManifest.xml                             # Leanback / 权限 / 播放页
```
