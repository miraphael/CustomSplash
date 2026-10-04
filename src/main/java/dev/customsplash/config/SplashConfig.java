package dev.customsplash.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.customsplash.CustomSplash;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 配置文件：{@code config/customsplash.json}
 *
 * <p>媒体文件默认放在 {@code config/customsplash/} 目录下。
 * 每一层（主菜单 / 世界加载 / 早期启动）都可以单独指定用哪个文件。
 */
public class SplashConfig {

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    private static SplashConfig instance;

    /** 总开关，关掉后模组完全不生效。 */
    public boolean enabled = true;

    /** 媒体文件所在目录（相对于游戏根目录，也可写绝对路径）。 */
    public String mediaFolder = "config/customsplash";

    /** 主菜单背景。 */
    public Layer titleScreen = new Layer("", "cover", 0.35f);

    /** 进入世界时的加载界面背景。 */
    public Layer levelLoading = new Layer("", "cover", 0.25f);

    /** 游戏最早期启动屏（Mojang 加载界面）背景。 */
    public Layer earlyLoading = new Layer("", "cover", 0.10f);

    /**
     * 一层界面的配置。
     */
    public static class Layer {
        /** 是否启用该层。 */
        public boolean enabled = true;

        /**
         * 媒体文件名。留空时会自动在该层的候选名里查找：
         * {@code title.*} / {@code loading.*} / {@code boot.*}
         * 支持 .png .jpg .jpeg .gif .mp4
         */
        public String media = "";

        /**
         * 填充方式：
         * <ul>
         *     <li>{@code cover}   —— 等比放大铺满，超出部分裁掉（推荐）</li>
         *     <li>{@code contain} —— 等比缩放到完整可见，两侧留黑边</li>
         *     <li>{@code stretch} —— 拉伸铺满，可能变形</li>
         * </ul>
         */
        public String fit = "cover";

        /** 背景上方叠加的黑色蒙版透明度，0 ~ 1。调高能让菜单文字更清晰。 */
        public float dim = 0.0f;

        /** 播放速度倍率（仅动图 / 视频有效）。 */
        public float speed = 1.0f;

        public Layer() {
        }

        public Layer(String media, String fit, float dim) {
            this.media = media;
            this.fit = fit;
            this.dim = dim;
        }
    }

    // ------------------------------------------------------------------
    //  读写
    // ------------------------------------------------------------------

    public static Path configPath() {
        return FabricLoader.getInstance().getConfigDir().resolve("customsplash.json");
    }

    public static Path mediaDir() {
        String folder = instance().mediaFolder;
        Path p = Path.of(folder);
        if (!p.isAbsolute()) {
            p = FabricLoader.getInstance().getGameDir().resolve(folder);
        }
        return p.normalize();
    }

    public static SplashConfig instance() {
        if (instance == null) {
            instance = new SplashConfig();
        }
        return instance;
    }

    /** 从磁盘读取配置；文件不存在或损坏时写入默认配置。 */
    public static void load() {
        Path path = configPath();
        try {
            if (Files.exists(path)) {
                try (Reader reader = Files.newBufferedReader(path)) {
                    SplashConfig loaded = GSON.fromJson(reader, SplashConfig.class);
                    if (loaded != null) {
                        instance = loaded;
                    }
                }
                // 字段补全（旧版本配置文件缺字段时）
                instance.fillDefaults();
                return;
            }
        } catch (Exception e) {
            CustomSplash.LOGGER.warn("[CustomSplash] 读取配置失败，将使用默认配置: {}", e.toString());
        }

        instance = new SplashConfig();
        save();
    }

    public static void save() {
        Path path = configPath();
        try {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(path)) {
                GSON.toJson(instance(), writer);
            }
        } catch (IOException e) {
            CustomSplash.LOGGER.warn("[CustomSplash] 保存配置失败: {}", e.toString());
        }

        // 顺手把媒体目录建出来，玩家直接丢文件进去就行
        try {
            Files.createDirectories(mediaDir());
        } catch (IOException ignored) {
        }
    }

    private void fillDefaults() {
        if (mediaFolder == null || mediaFolder.isBlank()) mediaFolder = "config/customsplash";
        if (titleScreen == null) titleScreen = new Layer("", "cover", 0.35f);
        if (levelLoading == null) levelLoading = new Layer("", "cover", 0.25f);
        if (earlyLoading == null) earlyLoading = new Layer("", "cover", 0.10f);
    }

    /** 各层的候选自动文件名。 */
    public static List<String> autoNames(String layerKey) {
        List<String> out = new ArrayList<>();
        for (String ext : new String[]{".png", ".jpg", ".jpeg", ".gif", ".mp4"}) {
            out.add(layerKey + ext);
        }
        return out;
    }

    // ------------------------------------------------------------------
    //  界面用的辅助方法
    // ------------------------------------------------------------------

    /** 按 key 取出对应的层配置：{@code title} / {@code loading} / {@code boot}。 */
    public Layer layer(String layerKey) {
        return switch (layerKey) {
            case "title" -> titleScreen;
            case "loading" -> levelLoading;
            case "boot" -> earlyLoading;
            default -> null;
        };
    }

    /** 深拷贝一份配置。界面上改的是副本，点保存时才写回。 */
    public SplashConfig copy() {
        return GSON.fromJson(GSON.toJson(this), SplashConfig.class);
    }

    /** 用一份新配置替换当前配置并落盘。 */
    public static void replaceWith(SplashConfig other) {
        instance = other;
        save();
    }
}
