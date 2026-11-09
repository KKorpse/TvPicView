# TvPicView

Android TV / Google TV 全屏图片、视频自动播放器。

## 功能

- 首页只保留按钮、目录和数量，不显示文件列表
- 选择目录后，点“播放”自动扫描并连续播放整个目录
- 图片按设定时间自动切换；视频播放完自动下一个
- 图片播放时间、顺序/随机、是否从上次位置继续，可在“设置”里修改
- 每 10 秒保存一次当前播放位置，切换文件时也会立即保存
- 图片、视频都支持暂停
- 全屏播放，隐藏系统栏，保持屏幕常亮

## 设置

点首页“设置”：

- **图片播放时间**：3 / 5 / 10 / 15 / 30 / 60 秒，默认 5 秒
- **播放顺序**：顺序播放 / 随机播放
- **继续上次播放**：开 / 关，默认开

“继续上次”影响首页“播放”按钮：会从该目录上次保存的文件开始，然后把目录内剩余文件都播放一遍；随机模式下按随机顺序播放所有文件。

播放状态保存在：

```text
/sdcard/Android/data/com.kkorpse.tvpicview/files/playback_state.txt
```

同时写入 SharedPreferences，卸载应用才会清除。设置页会显示当前状态文件路径。

## 播放控制

- 图片：OK / 播放暂停键暂停或继续当前图片，返回退出
- 视频：OK / 播放暂停键暂停或继续，左右快进/快退 10 秒，返回退出
- 播放列表结束后显示“播放结束”，返回退出

## 4K 说明

- 图片大于 4K：解码上限 3840×2160
- 图片小于 4K：按原分辨率解码，再由 ImageView 等比放大铺屏
- 比例不一致时留黑边，不裁剪
- 视频不转码，按源分辨率交给电视硬解；电视显示模式是 4K 时按 4K 显示，否则由系统/电视缩放
- 大于 4K 的视频能否播放取决于电视解码器；不支持时错误卡片会显示原因
- 播放页 Logcat 会输出当前显示模式、视频源分辨率、图片源/解码后尺寸

## 报错

- 播放出错时，屏幕中央红色错误卡片停留 5 秒
- 显示异常类型、消息、代码位置、文件名
- 视频错误包含 ExoPlayer 错误码和原因链
- HEIC/HEIF 解码失败会提示电视可能缺少解码器
- 出错后约 6 秒自动播放下一个；单个文件失败不会卡住整个列表
- 完整堆栈写入 Logcat：

```bash
adb logcat -s TvPicView
```

首页底部“诊断日志”可查看/清空内存日志。

## 安装包

```text
dist/TvPicView-v1.2-debug.apk
```

安装：

```bash
adb connect <电视IP>:5555
adb install -r dist/TvPicView-v1.2-debug.apk
adb shell am start -n com.kkorpse.tvpicview/.MainActivity
```

> TCL 电视有 Google TV / Android TV 和 Roku TV 两种系统。Roku 不能安装 Android APK。国内 TCL/雷鸟通常没有 Google Play，但可直接侧载，应用不依赖 GMS。

## MediaStore 兜底

Google TV 可能把系统文件选择器替换成空壳 Stub。点“选择目录”检测到 Stub 会自动切换到“媒体库目录”，列出已索引的图片/视频目录。U 盘上的媒体只要被系统媒体库扫描到，也能出现在这里。

## 构建

需要 JDK 17 和 Android SDK（compileSdk 35）。

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
export ANDROID_HOME="$HOME/Library/Android/sdk"
export PATH="$ANDROID_HOME/platform-tools:$PATH"

./gradlew assembleDebug
```

APK 输出自动带版本号，例如：

```text
app/build/outputs/apk/debug/TvPicView-v1.2-debug.apk
```

版本号在 `app/build.gradle` 的 `versionCode` / `versionName` 修改；每次构建文件名会自动跟随版本号。

## 工程结构

```text
MainActivity.java        # 首页、扫描、MediaStore、自动播放启动
SettingsActivity.java    # 播放设置
PlayerActivity.java      # 全屏图片/视频连续播放、暂停、状态保存
PlaybackSettings.java    # 设置和断点状态持久化
PlaybackQueue.java       # 播放列表传递
```
