# 从 Minecraft 1.21.11 移植到 26.1.1 的完整记录

> 这份文档记录的是**已经做完的事**，不是计划。
> 内容全部来自实际编译报错 + 反编译官方 jar 逐项核对，不是凭记忆写的。
> 目标版本是 **26.1.1**。

---

## 一、先说结论：为什么不能共用同一个 jar

| | 1.21.x | 26.x |
|---|---|---|
| 游戏是否混淆 | **是**（需要 Yarn / intermediary 映射） | **否**（jar 里就是 `net/minecraft/...` 官方类名） |
| Fabric 提供的映射 | 有 | `intermediary: 0.0.0`（等于没有） |
| 渲染架构 | `render(...)` 直接画 | `extractRenderState(...)` + `extractBackground(...)` 两段式 extractor |
| 图形后端 | OpenGL | OpenGL + **Vulkan**（可切换） |
| 运行时 Java | 21 | **25** |

类名和绘制 API 大面积改名，所以只能**开一个新项目、按官方类名编译**，
和 1.21.11 那份并列维护，产物用 `+<MC 版本>` 后缀区分。

---

## 二、工具链（版本卡得很死，装错直接构建失败）

| 组件 | 1.21.11 | 26.1.1 |
|---|---|---|
| JDK | 21 | **25** |
| Gradle | 8.14 | **9.7.0** |
| Fabric Loom | 1.13.6 | **1.18.2** |
| Loom 插件 id | `fabric-loom` | **`net.fabricmc.fabric-loom`** |
| `mappings` 依赖 | 必须写 `mappings "net.fabricmc:yarn:..."` | **绝对不能写** |

### 坑 1：插件 id 不是 `fabric-loom`

26.x 必须用 **no-remap 变体**：

```groovy
plugins {
    // 1.21.x 及更早
    // id 'fabric-loom' version '1.13.6'

    // 26.x：不混淆、没有映射可做，要用 no-remap 插件
    id 'net.fabricmc.fabric-loom' version '1.18.2'
}
```

两个插件 id 由**同一个 artifact** 提供，分别对应两个入口类：

- `fabric-loom` → `LoomGradlePlugin`
- `net.fabricmc.fabric-loom` → `LoomNoRemapGradlePlugin`（先 apply 前者再转派）

用错了会直接报：

```
Configuration 'mappings' has no dependencies
```

### 坑 2：依赖要用 `implementation`，不是 `modImplementation`

```groovy
minecraft "com.mojang:minecraft:26.1.1"
// 故意不写 mappings —— 不混淆，没什么可映射的
implementation "net.fabricmc:fabric-loader:0.19.5"
implementation "net.fabricmc.fabric-api:fabric-api:0.145.4+26.1.1"
```

### 坑 3：`gradle.properties` 里不要留 `yarn_mappings`

留着不报错但毫无意义，容易让人以为还需要它。

### 版本约束的来源（别再往下乱降）

- Loom **1.14** 起不再支持 Gradle 8
- Loom **1.17** 起要求 JDK 25
- 26.1.1 的版本 JSON 里写着 `javaVersion: { component: java-runtime-epsilon, majorVersion: 25 }`

> 本机没有 JDK 25 时的一个捷径：Mojang 自己下载的
> `%APPDATA%/.minecraft/runtime/java-runtime-epsilon` 就是一个**完整 JDK 25**
> （带 javac），直接复制出来当 `JAVA_HOME` 就能用。

---

## 三、类名对照表（1.21.11 Yarn → 26.1.1 官方）

### 通用

| 1.21.11 | 26.1.1 |
|---|---|
| `client.MinecraftClient` | `client.Minecraft` |
| `client.gui.screen.Screen` | `client.gui.screens.Screen` |
| `client.gui.screen.TitleScreen` | `client.gui.screens.TitleScreen` |
| `client.gui.screen.SplashOverlay` | `client.gui.screens.LoadingOverlay` |
| `client.gui.screen.world.LevelLoadingScreen` | `client.gui.screens.LevelLoadingScreen` |
| `client.gui.DrawContext` | `client.gui.GuiGraphicsExtractor` |
| `client.gui.tooltip.Tooltip` | `client.gui.components.Tooltip` |
| `client.gui.widget.ButtonWidget` | `client.gui.components.Button` |
| `client.gui.widget.CyclingButtonWidget` | `client.gui.components.CycleButton` |
| `client.gui.widget.SliderWidget` | `client.gui.components.AbstractSliderButton` |
| `client.option.KeyBinding` | `client.KeyMapping` |
| `client.util.InputUtil` | `com.mojang.blaze3d.platform.InputConstants` |
| `text.Text` | `network.chat.Component` |
| `text.OrderedText` | `util.FormattedCharSequence` |
| `util.Identifier` | `resources.Identifier` |
| `client.texture.NativeImage` | `com.mojang.blaze3d.platform.NativeImage` |
| `client.texture.NativeImageBackedTexture` | `client.renderer.texture.DynamicTexture` |
| `client.texture.TextureManager` | `client.renderer.texture.TextureManager` |

### Fabric API 侧

| 1.21.11 | 26.1.1 |
|---|---|
| `client.keybinding.v1.KeyBindingHelper` | `client.keymapping.v1.KeyMappingHelper` |
| `client.command.v2.ClientCommandManager` | `client.command.v2.ClientCommands`（模块升到 v3） |

---

## 四、API 逐项改动

### 1. 渲染入口拆成两段

26.1.1 的 `Screen` 不再是「一个 `render` 方法画完」，而是：

```java
public final void extractRenderStateWithTooltipAndSubtitles(GuiGraphicsExtractor g, int mx, int my, float a) {
    g.nextStratum();
    this.extractBackground(g, mx, my, a);   // ← 画背景（原版全景图 / 菜单底板）
    g.nextStratum();
    this.extractRenderState(g, mx, my, a);  // ← 画控件和文字
    g.extractDeferredElements(mx, my, a);
}
```

对应到本模组：

| 1.21.11 | 26.1.1 |
|---|---|
| `@Inject(method = "renderBackground", ...)` | `@Inject(method = "extractBackground", ...)` |
| `@Inject(method = "render", ...)` | `@Inject(method = "extractRenderState", ...)` |
| `SplashOverlay.render(...)` | `LoadingOverlay.extractRenderState(...)` |

> `LoadingOverlay` 继承的是 `Overlay` 而不是 `Screen`，所以它**没有**
> `extractBackground` 这一步，整个画面都在 `extractRenderState` 里画。

### 2. 绘制方法改名

| 1.21.11 | 26.1.1 |
|---|---|
| `context.getScaledWindowWidth()` | `graphics.guiWidth()` |
| `context.getScaledWindowHeight()` | `graphics.guiHeight()` |
| `context.drawTextWithShadow(font, text, x, y, color)` | `graphics.text(font, component, x, y, color)`（默认带阴影） |
| `context.drawCenteredTextWithShadow(font, t, x, y, c)` | `graphics.centeredText(font, t, x, y, c)` |
| `context.drawTexturedQuad(id, x0,y0,x1,y1, u0,u1,v0,v1)` | `graphics.blit(id, x0,y0,x1,y1, u0,u1,v0,v1)` |
| `textRenderer.wrapLines(text, width)` | `font.split(formattedText, width)` |
| `context.fill(...)` | `graphics.fill(...)`（不变） |

> **`blit` 的 9 参重载**内部直接 `innerBlit(RenderPipelines.GUI_TEXTURED, id, x0, x1, y0, y1, u0, u1, v0, v1, -1)`，
> 几何参数原样透传，**参数顺序和旧的 `drawTexturedQuad` 完全一致**，算法不用改。

### 3. `Screen` 的成员

| 1.21.11 | 26.1.1 |
|---|---|
| `this.textRenderer` | `this.font` |
| `public void render(DrawContext, int, int, float)` | `public void extractRenderState(GuiGraphicsExtractor, int, int, float)` |
| `public void renderBackground(DrawContext, int, int, float)` | `public void extractBackground(GuiGraphicsExtractor, int, int, float)` |
| `protected void init()` | `protected void init()`（不变；`public final void init(int, int)` 会先设 width/height 再调它） |
| `addDrawableChild(widget)` | `addRenderableWidget(widget)` |
| `shouldPause()` | `isPauseScreen()` |
| `close()` | `onClose()`（默认实现就是 `minecraft.setScreen(null)`） |
| `clearAndInit()` | `rebuildWidgets()` |
| `this.width` / `this.height` | 同名字段（仍是 public） |

### 4. 当前界面就在 `Minecraft` 上

```java
// 1.21.11
Screen s = client.currentScreen;
client.setScreen(new TitleScreen());

// 26.1.1
Screen s = client.screen;                // Minecraft.screen 是 public 字段
client.setScreen(new TitleScreen());
```

> `Minecraft` 自己就带着 `screen` 字段和 `setScreen(...)`，
> 取当前界面、切界面都直接走它，不用绕到 `Gui`。

### 5. 控件构造器

| 1.21.11 | 26.1.1 |
|---|---|
| `ButtonWidget.builder(text, onPress).dimensions(x,y,w,h)` | `Button.builder(text, onPress).bounds(x,y,w,h)` |
| `Tooltip.of(text)` | `Tooltip.create(text)` |
| `CyclingButtonWidget.builder(...).values(list).omitKeyText()` | `CycleButton.builder(...).withValues(list).displayOnlyValue()` |
| `....build(x,y,w,h, name, listener)` | `....create(x,y,w,h, name, listener)` |
| `SliderWidget` 子类 | `AbstractSliderButton` 子类（`value` / `updateMessage()` / `applyValue()` 签名不变） |

### 6. 按键（`KeyBinding` → `KeyMapping`）

```java
// 1.21.11
public static final KeyBinding.Category KEY_CATEGORY =
        KeyBinding.Category.create(Identifier.of(MOD_ID, "main"));
public static final KeyBinding KEY = new KeyBinding(
        "key.customsplash.open_config", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_F8, KEY_CATEGORY);
KeyBindingHelper.registerKeyBinding(KEY);
while (KEY.wasPressed()) { ... }

// 26.1.1
public static final KeyMapping.Category KEY_CATEGORY =
        KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "main"));
public static final KeyMapping KEY = new KeyMapping(
        "key.customsplash.open_config", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F8, KEY_CATEGORY);
KeyMappingHelper.registerKeyMapping(KEY);
while (KEY.consumeClick()) { ... }
```

- `KeyMapping.Category` 是 **record**，只能通过 `Category.register(Identifier)` 创建，
  同一 id 重复注册会抛 `IllegalArgumentException`。
- `wasPressed()` **不存在**了，只有 `consumeClick()`。

### 7. `Identifier` 工厂方法

`Identifier.of(...)` **已不存在**：

| 1.21.11 | 26.1.1 |
|---|---|
| `Identifier.of(ns, path)` | `Identifier.fromNamespaceAndPath(ns, path)` |
| `Identifier.of(path)` | `Identifier.withDefaultNamespace(path)` |
| `Identifier.tryParse(s)` | `Identifier.parse(s)` / `Identifier.tryParse(s)` |

### 8. 动态纹理

```java
// 1.21.11
NativeImageBackedTexture tex = new NativeImageBackedTexture(w, h, false);
NativeImage img = tex.getImage();
textureManager.registerTexture(id, tex);
textureManager.destroyTexture(id);

// 26.1.1
DynamicTexture tex = new DynamicTexture(() -> "customsplash/" + key, w, h, false);
NativeImage img = tex.getPixels();
textureManager.register(id, tex);
textureManager.release(id);
```

`NativeImage` 的内存布局**没变**（仍是 ABGR，`setPixel(x,y,argb)` 内部先 `ARGB.toABGR`），
所以本模组「用 `getPointer()` 拿 `memIntBuffer` 整块写像素」的快路径可以原样复用。

### 9. 其它小改名

| 1.21.11 | 26.1.1 |
|---|---|
| `Util.getOperatingSystem().open(path)` | `Util.getPlatform().openPath(path)` |
| `client.keyboard.setClipboard(s)` | `client.keyboardHandler.setClipboard(s)` |
| `ScreenshotRecorder.saveScreenshot(dir, name, fb, cb)` | `Screenshot.grab(dir, forceName, target, downscale, cb)` |
| `client.getMainRenderTarget()` | `client.gameRenderer.mainRenderTarget()` |
| `MinecraftClient.scheduleStop()` | `Minecraft.stop()` |

### 10. Mixin 配置

`customsplash.mixins.json` 里的 `compatibilityLevel` 要从 `JAVA_21` 升到 **`JAVA_25`**。

---

---

## 五、怎么验证的

渲染层的改动，只看编译通过是不够的 —— 画面到底对不对，只能看实际渲染结果。
这里的做法是搭一套一次性的冒烟测试：

1. **自己拼启动命令。** PCL 是图形界面启动器，没法脚本化，
   于是写了 [`scripts/run-test-client.py`](../scripts/run-test-client.py)：
   读 `versions/<版本名>/<版本名>.json`，按 rules 筛出当前平台需要的库拼 classpath，
   直接跑 `net.fabricmc.loader.impl.launch.knot.KnotClient`。
   两个容易踩的点：
   - 规则里的 `os.versionRange` 要用**注册表里的构建号**（`10.0.19045`）判断，
     不能拿 Java 的 `os.version`（Windows 上只有 `"10.0"`）—— 否则 ZGC / G1 两组参数会同时生效。
   - `features` 规则也要判。`has_quick_plays_support` 等四条 quick play 参数
     必须全部为 false，否则 `Main` 会抛 `Only one quick play option can be specified`。

2. **加一个临时的自检钩子。** 只在 `-Dcustomsplash.selftest=true` 时生效，
   按固定 tick 时间线依次切到「早期启动屏 → 主菜单 → 设置界面 → 选文件界面 → 全屏预览 → 进入世界」，
   每站用 `Screenshot.grab(...)` 截图，最后 `mc.stop()` 自己退出。
   **验证完必须把这段代码删掉**（本仓库的最终产物里没有它）。

3. **进世界那一站要自动点确认框。** 1.21.11 的存档在 26.1.1 打开会弹
   「要不要先备份再升级」，`BackupConfirmScreen` 的回调要反射拿。
   注意：**方法要从字段声明的类型（接口）上取**，
   因为实例通常是 JVM 生成的隐藏 lambda 类，对它调 `getClass().getMethod(...)`
   会抛 `IllegalAccessException: cannot access a member of class ...$$Lambda`。

### 验证结果

三块界面 + 三个 GUI 界面全部实机通过，截图在 `docs/screenshots/`：

| 界面 | 截图 | 结果 |
|---|---|---|
| 早期启动屏（`LoadingOverlay`） | `01-boot-early-loading.png` | ✅ 视频背景 + Mojang 标志 + 进度条 |
| 主菜单（`TitleScreen`） | `02-title-screen.png` | ✅ 视频背景 + 标题 + 按钮 |
| 世界加载界面（`LevelLoadingScreen`） | `03-level-loading.png` | ✅ 视频背景 + "Loading terrain..." + 进度条 |
| 设置界面 | `04-config-screen.png` | ✅ 文字清晰、控件齐全 |
| 选文件界面 | `05-media-select.png` | ✅ 文件列表、当前项高亮、翻页按钮 |
| 全屏预览 | `06-preview.png` | ✅ 视频正常播放、帧在推进 |
| 进入世界后 | `07-in-game.png` | ✅ 原版画面不受影响 |

视频相关日志（26.1.1 实机）：

```
[CustomSplash] 已加载媒体: 口袋觉醒_基拉祈_无水印_无黑屏版.mp4 (1280x586)，当前指派给 title 层
[CustomSplash] 同一个视频被多个界面共用，已共享同一个解码器（避免重复解码拖慢帧率）
[CustomSplash] 这个视频用了 B 帧（解码顺序与显示顺序不一致），已自动按显示时间重排
[CustomSplash] 这台机器解码这个视频略慢，已把播放速率微调慢一点（约 30.3 → 28.6 帧/秒）
```

也就是说，1.21.11 上做的那些流畅度优化（B 帧重排、双线程流水线、速率自适应、
共享解码器、整块写显存）**在 26.1.1 上全部原样生效**，
只有「拿渲染上下文画背景」那一层需要改。

---

## 六、移植时的工作量分布

真正需要改 MC API 的只有 9 个文件，其余全是纯 Java、可以原样复制：

| 文件 | 是否要改 |
|---|---|
| `media/*`（8 个）、`config/*`、`core/*` | ❌ 零 MC 依赖，直接复制 |
| `client/MediaLibrary.java`、`client/Mp4Probe.java` | ⚠️ 只有 1~2 处（`Util.getOperatingSystem`） |
| `client/MediaPlayer.java` | ✅ 纹理类 + 绘制上下文 |
| `client/SplashMediaManager.java` | ⚠️ 只换 import |
| `client/gui/SplashConfigScreen.java` | ✅ 控件类 + 渲染方法 + 屏幕切换 |
| `client/gui/MediaSelectScreen.java` | ✅ 同上 |
| `client/gui/MediaPreviewScreen.java` | ✅ 同上 + `wrapLines` → `split` |
| `mixin/*`（3 个） | ✅ 注入点方法名 + 参数类型 |
| `CustomSplash.java` | ✅ 按键 + 指令 + 屏幕切换 |

---

## 七、回到 1.21.11 那份

1.21.11 的代码在同一个仓库的 `main` 分支（`CustomSplash/` 目录），
26.1.1 的在 26.x 分支（`CustomSplash-mc26/` 目录）。
两边**各自独立构建、独立发布**，产物名带 `+<MC 版本>` 后缀，tag 也不会撞车。
