# 更新日志

本文件记录每个版本改了什么。格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循[语义化版本](https://semver.org/lang/zh-CN/)。

下载地址：[Releases](https://github.com/miraphael/CustomSplash/releases)

> **这份 CHANGELOG 属于 Minecraft 26.1.1 那条线。** 1.21.x 那条线在仓库的
> `main` 分支上单独维护，两边的代码不能共用，发布也是各自独立的 tag / Release。

---

## [1.0.4+26.1.1] - 2026-10-05

> 本版本即 `customsplash-1.0.4+26.1.1.jar`，tag 是 `v1.0.4+26.1.1`。

用图片 / GIF / 视频替换 Minecraft 26.1.1 的启动界面。

### 内容

- **三块界面可以分别替换**，互不影响：

  | 界面 | 配置项 | 出现时机 |
  |---|---|---|
  | 早期启动屏 | `earlyLoading` | 刚打开游戏、资源还在加载时 |
  | 主菜单 | `titleScreen` | 标题界面的背景 |
  | 世界加载界面 | `levelLoading` | 进世界 / 退世界 / 联机的过渡界面 |

- **媒体格式**：PNG / JPG 静态图、GIF 动图、MP4（H.264）。
  视频解码库（jcodec）已经打包在模组里，玩家不用装 FFmpeg，也不用转码。

- **游戏内设置界面**：按 `F8` 或输入 `/customsplash config` 打开，
  在里面挑文件、选填充方式、调暗化蒙版、看全屏预览。保存后立刻生效，不用重启游戏。

- **早期启动屏整段接管，一帧原版画面都不会出现。**
  在 `LoadingOverlay.extractRenderState()` 的 HEAD 拦截并 `cancel()`，
  原版那段绘制代码一行都不跑，只保留两个必须的副作用：

  - `if (fadeOutAnim >= 2.0F) minecraft.setOverlay(null);` ——
    不复制它，加载完会永远停在启动屏上；
  - 淡出那 1 秒把下面的界面画出来
    （`screen.extractRenderStateWithTooltipAndSubtitles(...)` + `graphics.nextStratum()`），
    否则淡完露不出主菜单。

  原版那片红**不是用提取出来的绘制指令画的**，而是直接改渲染管线的清屏色
  （`guiRenderState.clearColorOverride`），所以「画在末尾盖住」怎么都盖不严；
  改成整段接管之后就没有这个问题了。

- **进世界 / 退世界 / 联机的过渡界面全部接管**，包括 `ProgressScreen`
  这类容易漏掉的。做法是挂在所有界面唯一的共用入口
  `Screen.extractRenderStateWithTooltipAndSubtitles(...)` 上
  （它是 `final` 方法，任何子类都绕不过），而不是逐个界面写补丁。

- **未配置某一层时完全交回原版**，不会留下残留画面。

- **视频编码提前体检**：选文件时就把播不了的视频（HEVC / AV1 / 隔行扫描等）标出来，
  并给出一条可以直接复制的 FFmpeg 转码命令。

- **流畅度自适应**：播放速率按机器实际解码能力自动微调，宁可慢一点也不卡。

### 说明

- 支持 Minecraft **26.1.1**（Java 25 + Fabric）。
- 从游戏画出的第一帧一直到主菜单，反复核对过，原版那片红一帧都没有出现。
