# 更新日志

本文件记录每个版本改了什么。格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循[语义化版本](https://semver.org/lang/zh-CN/)。

下载地址：[Releases](https://github.com/miraphael/CustomSplash/releases)

> **这份 CHANGELOG 属于 Minecraft 26.2 那条线。** 1.21.11 那条线在仓库的
> `main` 分支上单独维护，两边的代码不能共用，发布也是各自独立的 tag / Release。

---

## [1.0.1+26.2] - 2026-10-05

### 修复

- **界面出现时不再先闪一下原版背景。** 以前三块界面（早期启动屏 / 主菜单 /
  世界加载界面）都是「第一次渲染时才加载媒体」，而加载一份媒体要做
  「读配置 → 找文件 → 探测 MP4 → 解出第一帧」，实测要 **0.6 ~ 1.4 秒**。
  这段时间渲染线程是卡住的，屏幕上还是上一帧 —— 也就是**原版界面**，
  于是玩家看到「先闪一下原版，再切到视频」。三块界面各懒加载一次，所以三块都会闪。

  现在改成**模组初始化时就在后台线程预加载**：这段耗时落在「窗口刚创建、
  还没开始出帧」的空窗期里，玩家完全看不到；等界面真的出现时只剩一次
  纹理创建 + 上传。实测「首次显示耗时」从 **578 ~ 1369 ms** 降到 **16 ~ 69 ms**。
- 只要某一层启用了媒体，就**一定**接管背景，不再有「画不出来就退回原版」的中间态。

### 变更

- 渲染前先铺一层不透明黑底，保证我们的画面是一整块不透明区域，
  不会漏出下面那一帧原版背景（`contain` 模式补黑边的行为不变）。
- 新增日志：启动时输出一行「已提前加载好 N 块界面的媒体（耗时 X ms）」；
  万一预加载没赶上、某层首次显示超过 150 ms，会输出一条告警方便排查。

## [1.0.0+26.2] - 2026-10-04

首个面向 **Minecraft 26.2 + Fabric** 的版本。

> 发布文件命名规则：`customsplash-<模组版本>+<Minecraft 版本>.jar`。
> 本版本即 `customsplash-1.0.0+26.2.jar`，tag 同样是 `v1.0.0+26.2`。
> Release 只挂这一个 jar；源码用页面底部 GitHub 自动附带的
> `v1.0.0+26.2.zip` / `.tar.gz`。

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

以下优化与 1.21.11 版本同源，在 26.2 上重新实机验证有效：

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

### 移植说明（26.2 与 1.21.11 的差异）

26.x 是一次渲染底层的大重构，**和 1.21.x 不能共用同一个 jar**。主要差异：

- 游戏**不再混淆**，客户端 jar 里直接是 `net.minecraft.*` 官方类名，
  Fabric 对 26.2 不提供 Yarn 映射，因此直接按官方类名编译。
- 构建必须用 **`net.fabricmc.fabric-loom`**（no-remap 变体），
  用 `fabric-loom` 会报 `Configuration 'mappings' has no dependencies`。
- 工具链升到 **JDK 25 + Gradle 9.7 + Loom 1.18.2**。
- 界面绘制从 `render(...)` 改成两段式
  `extractRenderStateWithTooltipAndSubtitles` → `extractBackground(...)` + `extractRenderState(...)`；
  绘制上下文 `DrawContext` 改名 `GuiGraphicsExtractor`。
- 纹理类 `NativeImageBackedTexture` → `DynamicTexture`，
  但 `NativeImage` 的内存布局（ABGR）没变，整块写显存的做法可以原样复用。

完整的逐项对照表、踩坑记录与验证方法见
[docs/PORTING-26x.md](docs/PORTING-26x.md)。

### 说明

- 模组只在真的有问题时输出告警，并做了两层防误报：同一问题只说一次；
  流畅度要连续观察约 6 秒才下结论（避开启动阶段的 CPU 抢占噪声）。
- 26.2 引入的 Vulkan 图形后端与模组无关。没有 Vulkan 驱动时日志里会出现
  `[Vulkan Loader] ERROR: vkGetPhysicalDeviceProperties: Invalid physicalDevice`，
  **不影响 OpenGL 路径**，可以忽略。

[1.0.1+26.2]: https://github.com/miraphael/CustomSplash/releases/tag/v1.0.1+26.2
[1.0.0+26.2]: https://github.com/miraphael/CustomSplash/releases/tag/v1.0.0+26.2
