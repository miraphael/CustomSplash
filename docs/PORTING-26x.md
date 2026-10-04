# 把 CustomSplash 移植到 Minecraft 26.x

> 这份文档说明为什么 26.x 需要单独一份构建，以及具体要改哪些地方。
> 结论来自对 `1.21.11` 与 `26.3` 官方客户端 jar 的实际字节码比对。

---

## 一、先搞清楚 26.x 到底变了什么

### 1. 版本号规则变了

从 2026 年起，Minecraft Java 版不再用 `1.21.x`，改成「年份 + 该年内第几个版本」：

| 版本 | 名称 | 发布时间 |
|---|---|---|
| 1.21.x | Tricky Trials | 2024 ~ 2025 |
| 26.1 | Tiny Takeover | 2026 年 3 月 |
| 26.2 | Chaos Cubed | 2026 年 6 月 |
| **26.3** | Wilderness Bound | 2026 年 9 月 15 日 |

### 2. 游戏不再混淆了

这是最关键的一条。1.21.11 的客户端 jar 里类名是 `grr.class`、`gsd.class` 这种；
而 26.3 的客户端 jar 里直接就是 `net/minecraft/client/gui/screens/TitleScreen.class`。

对应地，Fabric 官方对 26.3 返回的映射是：

```
intermediary: 0.0.0     ← 表示「不需要 intermediary」
yarn:         []        ← 没有 Yarn 映射
```

**所以 26.x 不需要 Yarn，直接用官方类名编译。**

### 3. 渲染底层被重写了

26.3 里出现了全新的 `com.mojang.renderpearl.*` 渲染后端（含 Vulkan 支持），
GUI 绘制从 `DrawContext` 换成了 `GuiGraphicsExtractor`。这一条决定了**不能靠改几个类名就搞定**。

---

## 二、类名 / 包名对照表

| 用途 | 1.21.11（Yarn） | 26.3（官方） |
|---|---|---|
| 早期启动屏 | `client.gui.screen.SplashOverlay` | `client.gui.screens.LoadingOverlay` |
| 主菜单 | `client.gui.screen.TitleScreen` | `client.gui.screens.TitleScreen` |
| 世界加载界面 | `client.gui.screen.world.LevelLoadingScreen` | `client.gui.screens.LevelLoadingScreen` |
| 覆盖层接口 | `client.gui.screen.Overlay` | `client.gui.screens.Overlay` |
| 绘制上下文 | `client.gui.DrawContext` | `client.gui.GuiGraphicsExtractor` |
| 图片数据 | `client.texture.NativeImage` | `com.mojang.blaze3d.platform.NativeImage` |
| 纹理管理 | `client.texture.TextureManager` | `client.renderer.texture.TextureManager` |
| 资源 ID | `util.Identifier` | `resources.Identifier` |
| 渲染管线 | `com.mojang.blaze3d.pipeline.RenderPipeline` | `com.mojang.renderpearl.api.pipeline.RenderPipeline` |

注意包名复数变化：`gui/screen/` → `gui/screens/`。

---

## 三、移植步骤

### 第 1 步：新建一个独立项目

不要试图和 1.21.11 共用同一个 `build.gradle`。建议：

```
CustomSplash/
├── (当前 1.21.11 项目)
└── mc26/                 ← 新项目
    ├── build.gradle
    └── src/main/java/...
```

`mc26/build.gradle` 的关键差异：

```groovy
plugins {
    id 'fabric-loom' version '<支持 26.x 的 Loom 版本>'
}

dependencies {
    minecraft "com.mojang:minecraft:26.3"
    // 26.x 不再需要 yarn：
    // mappings "net.fabricmc:yarn:..."

    modImplementation "net.fabricmc:fabric-loader:${loader_version}"
    modImplementation "net.fabricmc.fabric-api:fabric-api:0.161.2+26.3"
}
```

Fabric API 的版本号后缀要跟着游戏版本走（例如 `+26.3`）。
可用版本查：<https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/maven-metadata.xml>

### 第 2 步：可以直接复用的部分

这几个包**完全不依赖 Minecraft**，原样复制即可：

- `media/FrameSource.java`
- `media/ImageFrameSource.java`
- `media/GifFrameSource.java`
- `media/VideoFrameSource.java`
- `media/Images.java`
- `config/SplashConfig.java`（只用到 Fabric Loader 的 `getGameDir()` / `getConfigDir()`，26.x 没变）

### 第 3 步：需要改写的部分

**（1）Mixin 目标类与注入方法名**

```java
// 1.21.11
@Mixin(net.minecraft.client.gui.screen.SplashOverlay.class)
public class SplashOverlayMixin {
    @Inject(method = "render", at = @At("HEAD"))
    private void draw(DrawContext ctx, int mx, int my, float delta, CallbackInfo ci) { ... }
}

// 26.3（示意，方法名需按 26.3 实际代码确认）
@Mixin(net.minecraft.client.gui.screens.LoadingOverlay.class)
public class LoadingOverlayMixin {
    @Inject(method = "<26.3 里的渲染方法名>", at = @At("HEAD"))
    private void draw(GuiGraphicsExtractor ctx, ..., CallbackInfo ci) { ... }
}
```

**（2）`MediaPlayer` 里的纹理上传与绘制**

- `NativeImage` 换包名到 `com.mojang.blaze3d.platform.NativeImage`
- `Identifier.of(...)` 换成 `net.minecraft.resources.Identifier` 的等价构造
- `DrawContext.drawTexturedQuad(...)` 换成 `GuiGraphicsExtractor` 上的对应方法
- `context.getScaledWindowWidth()/Height()` 的取法要重新确认

**（3）`fabric.mod.json` 的 `depends`**

```json
"depends": {
  "minecraft": "~26.3"
}
```

**（4）`customsplash.mixins.json`**

`compatibilityLevel` 跟着 26.x 要求的 Java 版本走。

### 第 4 步：怎么快速查到 26.3 的真实方法签名

因为 26.x 不混淆，最简单的办法是直接看客户端 jar：

```bash
# 1. 从版本清单里找到 26.3 的 client.jar 地址
curl -s https://piston-meta.mojang.com/mc/game/version_manifest_v2.json

# 2. 下载后用 javap 看类结构（JDK 21 自带 javap）
javap -classpath client.jar net.minecraft.client.gui.screens.LoadingOverlay
javap -classpath client.jar net.minecraft.client.gui.GuiGraphicsExtractor
```

因为是官方类名，`javap` 的输出直接就是可读的，不需要任何映射工具。

---

## 四、要不要同时维护两份？

建议先只维护 1.21.11 这份 —— 它对应的 Fabric 工具链最成熟，玩家基数也最大。
等 26.x 的 Yarn/Loom 生态稳定下来（或者确认官方映射方案完全定型）再迁。

如果确实要双版本，推荐把 `media/` 和 `config/` 抽成一个不含 Minecraft 依赖的公共模块，
两个版本各自只维护 `mixin/` + `client/MediaPlayer` 这两处差异代码。
