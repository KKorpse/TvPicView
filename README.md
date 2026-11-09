# TvPicView

Android TV / Google TV 全屏图片、视频自动播放器。

## 功能

- iOS 风格深色界面：大标题、磨砂圆角卡片、蓝色主按钮、绿色开关
- 首页只保留 **播放 / 选择媒体库目录 / 设置**，播放按钮在第一个
- 自动恢复上次选择的媒体库目录，下次进入可直接点播放
- 点播放先扫描目录，再弹确认窗显示视频数、图片数
- 确认后自动连续播放整个目录
- 图片按设定时间切换；视频播放完自动下一个
- 图片、视频都支持暂停
- 上键收藏、下键标记删除，标记写入目录内的状态文件
- 左右键切换媒体；随机模式下按保存的随机顺序切换
- 视频暂停后左右键快退/快进 10 秒
- 全屏播放，隐藏系统栏，保持屏幕常亮

## 使用流程

1. 首次使用点“选择媒体库目录”，选中图片/视频目录。
2. 点“播放”，应用扫描目录并弹出确认窗：
   - 显示当前目录视频数、图片数
   - **确定**：开始播放
   - **返回**：回到首页；再点一次“播放”就是重新扫描/刷新
3. 选择过的目录会保存，下次启动自动恢复，直接点“播放”即可。
4. 如果启动时没有保存目录，点“播放”会自动进入媒体库目录选择。

## 设置

点首页“设置”：

- **图片播放时间**：3 / 5 / 10 / 15 / 30 / 60 秒，默认 5 秒
- **播放顺序**：顺序播放 / 随机播放
- **继续上次播放**：开 / 关，默认开
- **媒体过滤**：可单独过滤图片或视频；过滤视频后只播放图片，过滤图片后只播放视频

设置使用 SharedPreferences 同步写入，电视重启不会归零；卸载应用才会清除。

“继续上次”影响确认后的播放起点：会从该目录上次保存的文件开始，然后把目录内剩余文件都播放一遍；随机模式下按随机顺序播放所有文件。

播放位置保存在：

```text
/sdcard/Android/data/com.kkorpse.tvpicview/files/playback_state.txt
```

同时写入 SharedPreferences，卸载应用才会清除。

## 收藏 / 删除标记

不会复制、移动或真正删除媒体文件。每个播放目录会维护一个 JSON 状态文件：

```text
tvpicview_state.json
```

内容示例：

```json
{
  "version": 1,
  "directory": "bucket:-539437383:TvPicDemo",
  "items": {
    "tv-4k.png": "FAVORITE",
    "tv-8k.png": "DELETED"
  }
}
```

- 上键写入 `FAVORITE`
- 下键写入 `DELETED`，播放时跳过被标记删除的文件
- 状态文件异步写入
- MediaStore 不允许写非媒体文件时，电视可能把文件命名为 `tvpicview_state.json.mp4`，内容仍是 JSON
- 如果 TCL 的媒体库完全禁止在该目录创建文件，应用会自动降级到私有状态文件：

```text
/sdcard/Android/data/com.kkorpse.tvpicview/files/state/
```

此时收藏/删除标记仍然生效，但不会出现在媒体目录里。

## 播放控制

- **图片**
  - OK / 播放暂停键：暂停或继续当前图片
  - 左右键：切换上一个/下一个媒体
  - 上键：标记收藏
  - 下键：标记删除并跳到下一个
  - 返回键：退出
- **视频**
  - OK / 播放暂停键：暂停或继续
  - 播放中左右键：切换上一个/下一个媒体
  - 暂停后左右键：快退/快进 10 秒
  - 上键：标记收藏
  - 下键：标记删除并跳到下一个
  - 返回键：退出
- 播放列表结束后显示“播放结束”，返回退出

## 4K 说明

- 图片大于 4K：解码上限 3840×2160
- 图片小于 4K：按原分辨率解码，再由 ImageView 等比放大铺屏
- 比例不一致时留黑边，不裁剪
- 视频不转码，按源分辨率交给电视硬解；电视显示模式是 4K 时按 4K 显示，否则由系统/电视缩放
- 大于 4K 的视频能否播放取决于电视解码器；不支持时错误卡片会显示原因

## 报错

- 播放出错时，屏幕中央红色错误卡片停留 5 秒
- 显示异常类型、消息、代码位置、文件名
- 视频错误包含 ExoPlayer 错误码和原因链
- HEIC/HEIF 解码失败会提示电视可能缺少解码器
- 出错后约 6 秒自动播放下一个
- 完整堆栈写入：

```bash
adb logcat -s TvPicView
```

首页底部“诊断日志”可查看/清空内存日志。

## 安装包

```text
dist/TvPicView-v1.6-debug.apk
```

安装：

```bash
adb connect <电视IP>:5555
adb install -r dist/TvPicView-v1.6-debug.apk
adb shell am start -n com.kkorpse.tvpicview/.MainActivity
```

> TCL 电视有 Google TV / Android TV 和 Roku TV 两种系统。Roku 不能安装 Android APK。国内 TCL/雷鸟通常没有 Google Play，但可直接侧载，应用不依赖 GMS。

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
app/build/outputs/apk/debug/TvPicView-v1.6-debug.apk
```

版本号在 `app/build.gradle` 的 `versionCode` / `versionName` 修改。

## 工程结构

```text
MainActivity.java        # 首页、目录恢复、扫描确认、MediaStore
SettingsActivity.java    # 播放设置
PlayerActivity.java      # 全屏播放、暂停、切换、收藏/删除标记
PlaybackSettings.java    # 设置、断点状态、上次目录
PlaybackQueue.java       # 播放列表和目录上下文
ActionStateStore.java    # 目录内/私有状态文件
```
