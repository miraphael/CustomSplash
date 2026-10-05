# 更新日志

本文件记录每个版本改了什么。格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循[语义化版本](https://semver.org/lang/zh-CN/)。

下载地址：[Releases](https://github.com/miraphael/CustomSplash/releases)

> **这份 CHANGELOG 属于 Minecraft 26.3 那条线（分支 `mc263`）。**
> 1.21.11 在 `main`、26.2 在 `mc26`，各自单独维护、各自发布，
> 三边的代码不能共用，tag / Release 也互不覆盖。

---

## [1.0.4+26.3] - 2026-10-05

> 本版本即 `customsplash-1.0.4+26.3.jar`，tag 是 `v1.0.4+26.3`。

解决「**游戏刚启动时还是会冒出几帧原版的红色界面（带进度条）**」。
原版这边走的是 `LoadingOverlay`。

### 修复

- **早期启动屏整段接管，一帧原版画面都不再出现。**
  在 `LoadingOverlay.extractRenderState()` 的 HEAD 拦截并 `cancel()`，
  原版那段绘制代码一行都不跑。只保留两个必须的副作用：

  - `if (fadeOutAnim >= 2.0F) minecraft.gui.setOverlay(null);` —— 不复制它，
    加载完会永远停在启动屏上；
  - 淡出那 1 秒把下面的界面画出来（`gui.screen().extractRenderStateWithTooltipAndSubtitles(...)`
    + `graphics.nextStratum()`），否则淡完露不出主菜单。

- **未配置这一层时完全交回原版**（`ownsEarlyLoading()`）。

### 为什么盖不住

```java
// LoadingOverlay.extractRenderState()，启动时的分支（fadeIn == false）
} else {
   ARGB.setVector4fFromARGB32(
       minecraft.gameRenderer.gameRenderState().guiRenderState.clearColorOverride,
       BRAND_BACKGROUND.getAsInt());          // ← 把整个渲染目标的「清屏色」设成红
   logoAlpha = 1.0F;
}
```

它改的是渲染管线的**清屏色**，压根没走提取出来的绘制指令 —— 我们画多少都盖不住
「清屏」这一步。以前那版是画在最后盖住，所以淡出阶段会泛红。

---

## [1.0.3+26.3] - 2026-10-05

> 本版本即 `customsplash-1.0.3+26.3.jar`，tag 是 `v1.0.3+26.3`。

解决「**进世界还是会有一帧原版画面**」，并顺手接管**退出世界**的界面。

### 修复

- **架构：从「一个界面一个补丁」改成「挂在所有界面唯一的共用入口上」。**
  这是本版真正的改动。以前是逐个界面写 mixin，靠人工列举 ——
  `ProgressScreen` 就是这么漏掉的（它在进世界和退出世界时都会出现）。现在改成注入
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

## [1.0.2+26.3] - 2026-10-05

> 本版本即 `customsplash-1.0.2+26.3.jar`，tag 是 `v1.0.2+26.3`。

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

- 顺带一提：连接失败时的 `DisconnectedScreen`（「连接已断开」那个报错页）
  **故意不覆盖** —— 那一页上的报错文字和「返回」按钮是玩家必须看到的，
  盖上视频反而会挡住信息。

### 验证方式

一帧的画面肉眼根本抓不住，所以把三处界面都换成一整张纯色的图片
（原版 UI 里不会出现这个颜色，画面上只要混进别的颜色就说明原版漏出来了），
然后连本地 26.3 服务端把「连接 → 加载地形 → 进世界」整段跑一遍，逐帧截图核对：
整段画面全是模组自己的内容。

调试日志同时记下了这一路经过的界面，确认覆盖范围：
`LoadingOverlay` → `GenericMessageScreen` → `ProgressScreen` →
`ConnectScreen` → `LevelLoadingScreen` → 进入世界。

（一开始拿真实视频去扫颜色，误报不少 —— 动漫夜景里的深蓝画面被当成了异常。
换成纯色底图就没有这个歧义，所以最终以纯色底图为准。
另外，截图是**异步回读**的，切换界面的那一两帧偶尔会出现「世界画面从右边缘渗进来」
的撕裂条纹 —— 那是截图工具本身的假象，不是模组漏画。）

## [1.0.1+26.3] - 2026-10-05

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

以下流畅度优化都在 26.3 上实机验证有效：

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

[1.0.1+26.3]: https://github.com/miraphael/CustomSplash/releases/tag/v1.0.1+26.3
[1.0.0+26.3]: https://github.com/miraphael/CustomSplash/releases/tag/v1.0.0+26.3
