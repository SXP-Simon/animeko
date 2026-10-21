# 暂停画面与全屏尺寸同步

## 症状与范围

Android 播放器暂停后进入或退出全屏，Compose 控件已经使用新布局，但视频缓冲仍按旧边界呈现，
可能表现为偏移、裁剪或画面只占屏幕一角。继续播放产生新帧后恢复。
用户报告环境为 iQOO V2352A、Android 15、Animeko 6.4 pre beta，画质增强关闭。

这类症状与 SurfaceView 的合成事务未随 AndroidView 布局同步相符，但不能仅凭截图确认设备上的根因。
需要在报告设备上复测，模拟器通过不能替代厂商真机验证。

## Android 修复路径

- `video-player/src/androidMain/kotlin/ui/VideoPlayer.android.kt`：为 PlayerView 启用
  `setEnableComposeSurfaceSyncWorkaround(true)`，覆盖 Media3 内部针对 API 34 的兼容处理。
- API 35 及以上，`PausedSurfaceResizeSync` 监听实际 Surface 尺寸变化；暂停时将窗口的下一次绘制纳入
  SurfaceSyncGroup，并提交一个绘制事务，使静止的视频层与 Compose 界面一起更新。
  多次中间尺寸变更合并到主线程的一个请求；销毁、释放时取消请求并结束未完成的同步组。
- 不通过恢复播放、seek 或重建播放器获取新帧，避免改变播放状态、进度或引发重新缓冲。

Media3 的开关只在 API 34 执行，不能把它当作 Android 15 的修复。
维护者说明 Android 15 理应已包含系统层修复，并在 Pixel 真机上验证过原问题不再复现；
这不构成所有厂商 Android 15 系统均已验证的结论。

参考：

- [Media3 原始问题与显式启用开关](https://github.com/androidx/media/issues/1237)
- [维护者对 Android 15 系统修复的说明](https://github.com/androidx/media/issues/2158#issuecomment-2663607055)
- [Android 系统修复：确保提交待处理的图层事务](https://github.com/aosp-mirror/platform_frameworks_base/commit/54526e9b29556ede6c22a07fad83cc6cc8163915)

## 其他平台的代码检查

| 平台 | 输出路径 | 与暂停尺寸变化有关的处理 |
| --- | --- | --- |
| Android / Android TV | ExoPlayer → PlayerView → SurfaceView | 共享此 Android 输出路径；TV 通常没有手机的旋转切换，但暂停时的视口变化也适用 |
| Windows / macOS / Linux | mediamp-mpv 的 Compose Canvas | Canvas 每次绘制按当前 `size` 缩放并居中已有纹理；尺寸变化通过 `onSizeChanged` 请求新 Surface，过渡时仍绘制旧纹理 |
| iOS | EnhancedPlayerView 的 AVPlayerLayer；增强模式使用 Metal | 原生图层跟随 UIView bounds；Metal 子视图在 `layoutSubviews` 更新 frame，渲染器在 `drawableSizeWillChange` 标记 `needsRedraw`，可重绘保留的暂停帧 |

桌面与 iOS 没有共用 Android 的 SurfaceView 事务路径，代码中未发现相同缺口。
这只是代码检查，不能表述为已在这些平台完成全屏实测。

## 回归验证

`PausedVideoResizeTest` 使用本地四色 H.264 视频和真实 ExoPlayer。合成点击反复切换视口尺寸，
检查系统截图中的四色位置，而不是只断言 Compose 节点或 SurfaceView 的 bounds。
同时检查播放器仍处于暂停、位置不变、没有替换 Surface。
分别覆盖 prepare 后未开始播放，以及实际播放后暂停的情况。

测试截图写入测试目标包 external files 下的 `paused-resize-*.png`，便于失败后检查。
在 API 34 运行可覆盖 Media3 自带修复，在 API 35+ 运行可覆盖额外同步路径。
仍需在报告问题的 iQOO 上确认竖屏 → 全屏 → 竖屏两方向、连续切换及恢复播放后的效果。

```shell
./gradlew :app:shared:video-player:connectedAndroidDeviceTest \
  -Pandroid.testInstrumentationRunnerArguments.class=me.him188.ani.app.videoplayer.ui.PausedVideoResizeTest
```
