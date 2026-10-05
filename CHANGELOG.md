# 更新日志

本文件记录每个版本改了什么。格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循[语义化版本](https://semver.org/lang/zh-CN/)。

下载地址：[Releases](https://github.com/miraphael/CustomSplash/releases)

> **这份 CHANGELOG 属于 Minecraft 26.3 那条线（分支 `mc263`）。**
> 1.21.11 在 `main`、26.2 在 `mc26`，各自单独维护、各自发布，
> 三边的代码不能共用，tag / Release 也互不覆盖。

---

## [1.0.0+26.3] - 2026-10-05

首个面向 **Minecraft 26.3 + Fabric** 的版本。功能与 26.2 那条线完全一致，
这一版的主要工作是跟上游把被换掉的底层接口改掉，并在 26.3 上重新实机验证一遍。

> 发布文件命名规则：`customsplash-<模组版本>+<Minecraft 版本>.jar`。
> 本版本即 `customsplash-1.0.0+26.3.jar`，tag 同样是 `v1.0.0+26.3`。
> Release 只挂这一个 jar；源码用页面底部 GitHub 自动附带的
> `v1.0.0+26.3.zip` / `.tar.gz`。

### 新增

- **三块启动界面都能替换**：早期启动屏（`LoadingOverlay`）、主菜单（`TitleScreen`）、
  世界加载界面（`LevelLoadingScreen`），三块互相独立，可只换其中一块。
- **三种媒体格式**：PNG / JPG 静态图、GIF 动图、MP4（H.264）视频。
  视频解码用的 jcodec 已作为嵌套 jar 打进模组，玩家不需要安装任何额外程序。
- **游戏内设置界面**（`F8` 或 `/customsplash config`）：
  三层各一行控件（开关 / 选文件 / 填充方式 / 暗化滑块 / 预览），
  底部可一键打开媒体文件夹。改动先落在临时副本上，点「保存并返回」才生效。
- **选文件界面**：2 列 × 5 行分页，自动标出当前正在用的文件，悬停看文件信息。
- **全屏预览界面**：所见即所得，并给出该文件的「体检结论」。
- **编码提前体检**：直接解析 MP4 的 `stsd` box 读编码四字符码，
  不是 H.264 的视频在选择列表里就挂 `⚠`，并提供一键复制的 FFmpeg 转码命令。
- **文件名自动识别**：`title.*` / `loading.*` / `boot.*` 放进媒体目录即自动生效，
  连设置界面都不用开。
- **指令**：`/customsplash config` / `reload` / `info`。

### 性能

流畅度优化与 26.2 版本同源，在 26.3 上重新实机验证有效：

- **B 帧重排**：jcodec 按解码顺序返回帧，H.264 的 B 帧会让画面「忽前忽后」。
  加了按显示时间戳排序的重排窗口后，帧序正确率从 50.4% 提到 **100%**。
- **解码与转换拆成两条流水线**：原来纯解码 29 ms + 转换 9 ms = 38 ms/帧，
  超过每帧 33.4 ms 的预算；拆成两个线程后节拍由解码决定，离线实测产出 **35.15 fps**。
- **播放速率自适应**：统计「推进了却拿不到新画面」的比例，偏高就微调帧间隔。
  实测「推进但画面没变」从 2.0 次/秒降到 **0.2 次/秒**。
- **三块界面共用解码器**：同一个视频被三块界面引用时只解码一次，
  解码池里的视频数量从 3 降到 1。
- **一步缩放**：从源图直接采样到目标尺寸 + 最近邻插值，
  缩放耗时从 19 ms/帧降到 **3.15 ms/帧**。
- **直接写显存 + 复用画布**：整块写入 `NativeImage` 底层内存，替代逐像素调用。

### 移植说明（26.3 相对 26.2 的差异）

26.3 的**界面绘制 API 和 26.2 是同一套**，所以这一版是从 `mc26` 分支直接开出来的，
只改了三处被 26.3 换掉的底层接口：

| 26.2 | 26.3 | 原因 |
|---|---|---|
| `org.lwjgl.glfw.GLFW.GLFW_KEY_F8` | `InputConstants.KEY_F8` | 26.3 把窗口库从 GLFW 换成 SDL，`org.lwjgl.glfw` 整个包不再是编译依赖 |
| `InputConstants.Type.KEYSYM` / `SCANCODE` | `InputConstants.Type.KEYBOARD` | 两个枚举合并成一个 |
| `Util.getPlatform().openPath(Path)` | `com.mojang.blaze3d.Blaze3D.openPath(Path)` | 打开文件夹 / 链接的工具方法搬到了 Blaze3D（`openUri` 同理） |

工具链（JDK 25 + Gradle 9.7 + Loom 1.18.2）、Loom 插件 id、Mixin 注入点、
`NativeImage` 内存布局**全部不变**。

### 说明

- 模组只在真的有问题时输出告警，并做了两层防误报：同一问题只说一次；
  流畅度要连续观察约 6 秒才下结论（避开启动阶段的 CPU 抢占噪声）。
- 26.x 引入的 Vulkan 图形后端与模组无关。没有 Vulkan 驱动时日志里会出现
  `[Vulkan Loader] ERROR: vkGetPhysicalDeviceProperties: Invalid physicalDevice`，
  **不影响 OpenGL 路径**，可以忽略。

[1.0.0+26.3]: https://github.com/miraphael/CustomSplash/releases/tag/v1.0.0+26.3
