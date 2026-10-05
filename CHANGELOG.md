# 更新日志

本文件记录每个版本改了什么。格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循[语义化版本](https://semver.org/lang/zh-CN/)。

下载地址：[Releases](https://github.com/miraphael/CustomSplash/releases)

> **这份 CHANGELOG 属于 Minecraft 26.2 那条线。** 1.21.11 那条线在仓库的
> `main` 分支上单独维护，两边的代码不能共用，发布也是各自独立的 tag / Release。

---

## [1.0.3+26.2] - 2026-10-05

> 本版本即 `customsplash-1.0.3+26.2.jar`，tag 是 `v1.0.3+26.2`。

解决「**进世界还是会有一帧原版画面**」，并顺手接管**退出世界**的界面。

### 修复

- **架构：从「一个界面一个补丁」改成「挂在所有界面唯一的共用入口上」。**
  这是本版真正的改动。以前是逐个界面写 mixin，靠人工列举 ——
  1.21.11 那边就是这么把 `ProgressScreen` 漏掉的（26.x 同样有这个类、
  同样在进世界和退出世界时出现）。现在改成注入
  `Screen.extractRenderStateWithTooltipAndSubtitles(...)`
  （26.x 里所有界面共用的唯一入口，而且是 `final` 方法，任何子类都绕不过）：

  1. 让原版的 `extractRenderState()` 照常跑一遍 —— 必须保留它内部的状态切换
     （`ProgressScreen` 靠它 `setScreen(null)` 进世界，跳过会永远卡在加载界面）；
  2. 再把我们的画面整屏画上去，盖住这一帧里原版画过的所有东西；
  3. 最后 `cancel()` 掉原版的背景层（`extractBackground`，也就是全景图 + 模糊 + 压暗）。

  目前接管的界面：`ProgressScreen`、`GenericMessageScreen`、
  `GenericWaitingScreen`、`LevelLoadingScreen`、`ConnectScreen`。
  主菜单仍然保留原版按钮，只换背景。

- **退出世界的界面。** 以前完全没处理过，现在一并接管：
  退出本地世界 → `GenericMessageScreen(正在保存世界)`；
  退出多人服务器 → `ProgressScreen`。

### 顺带

- 删掉了已被统一入口取代的 `LevelLoadingScreenMixin` / `GenericMessageScreenMixin` /
  `ConnectScreenMixin` —— 留着会一帧画两遍。

---

## [1.0.2+26.2] - 2026-10-05

> 本版本即 `customsplash-1.0.2+26.2.jar`，tag 是 `v1.0.2+26.2`。

这一版专治「**还是有几帧原版画面露出来**」。三个地方都有，根因都是同一个：
**以前只接管了背景层，而原版是在背景之上再画一遍前景的。**

26.x 的类名和 1.21.x 不一样，下面括号里是对应关系。

### 修复

- **早期启动屏（那片 Mojang 红）彻底盖住。**
  原版 `LoadingOverlay.extractRenderState()` 里，底色是**无条件**铺满全屏的，
  铺完还会再画 Mojang Studios 标志和加载进度条。以前我们的注入点在方法**开头**
  （`HEAD`），画完之后原版接着往上面画红底和标志 —— 等于我们白画了。
  现在改成注入到**末尾**（`TAIL`），整屏盖住。

  淡出阶段（原版用 1 秒把红底淡掉，字段是 `fadeOutStart`）我们跟着一起淡，
  不透明度同步 1→0，淡完正好无缝接上主菜单。

- **进入世界时那几帧原版画面彻底盖住。**
  以前只取消了 `LevelLoadingScreen.extractBackground()`（背景层），
  可原版在 `extractRenderState()` 里还画了三样东西：**中间的彩色区块进度图**、
  **「正在下载地形」那行字**、**绿色进度条**。它们画在背景**之上**，
  所以我们取消了背景也没用。现在改成：背景只取消不画，
  真正的画面画在 `extractRenderState()` 的**末尾**，把这三样一起盖住。

- **多人服务器的进入界面以前完全没管。**
  联机进世界最后也会走 `LevelLoadingScreen`，但前面还有两三个只有联机
  才会出现的过渡界面，以前一个都没处理。现在补齐：
  - `ConnectScreen`（「正在连接服务器…」）—— 它在 `super.extractRenderState()`
    之后才画状态文字，所以要注入到它自己的 `extractRenderState()` 末尾；
  - `GenericMessageScreen`（「读取世界数据」「加载资源中」，1.21.x 叫 `MessageScreen`）
    —— 它自己声明了 `extractBackground()`，必须在它自己身上取消；
  - `ReconfiguringScreen`、以及所有用 `Screen` 默认实现的过渡界面 ——
    挂在 `Screen` 上统一处理。

- **主菜单**：`TitleScreen` 同样改成「背景只取消不画 + 前景末尾统一覆盖」。

### 验证方式（这一版是怎么确认「一帧都没有」的）

肉眼看不出一帧，所以改成程序化验证：

1. 把三个界面全部指向一张 **纯品红（#FF00FF）** 的图片 —— 原版 UI 里
   不可能出现这个颜色，所以**任何非品红像素 == 漏出原版**；
2. 跑一个调试构建，**每隔一帧抓一张截图**，一直抓 240 张
   （26.x 的截图 API 是 `Screenshot.grab`，主渲染目标是
   `gameRenderer.mainRenderTarget()`，整帧入口是 `Minecraft.renderFrame`）；
3. 用脚本逐帧统计非品红像素占比。

（早期验证时曾用「端门蓝 / 红色 / 绿色」这类颜色判据去扫真实视频，
结果 240 帧里误报 55 帧 —— 那其实是动漫夜景里的深蓝画面。
纯色底图没有这个歧义，所以最终以纯色验证为准。
另外，截图是**异步回读**的，切换界面的那一两帧偶尔会出现「世界画面从右边缘渗进来」
的撕裂条纹 —— 那是抓图工具的假象，不是模组漏画。）

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
