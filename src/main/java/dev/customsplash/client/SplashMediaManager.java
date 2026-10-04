package dev.customsplash.client;

import dev.customsplash.CustomSplash;
import dev.customsplash.config.SplashConfig;
import dev.customsplash.core.LogGate;
import dev.customsplash.media.FrameSource;
import dev.customsplash.media.MediaLoader;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 全局的启动界面媒体管理器。
 *
 * <p>三块界面各自持有一个 {@link MediaPlayer}：
 * <ul>
 *     <li>{@code title}   —— 主菜单</li>
 *     <li>{@code loading} —— 进入世界时的加载界面</li>
 *     <li>{@code boot}    —— 游戏最早期启动屏</li>
 * </ul>
 *
 * <p>媒体是「第一次真正需要渲染时才加载」的，这样既不会拖慢启动，
 * 也能保证早期启动屏能用上（那时很多东西还没初始化完）。
 */
public final class SplashMediaManager {

    private static final SplashMediaManager INSTANCE = new SplashMediaManager();

    private MediaPlayer titlePlayer;
    private MediaPlayer loadingPlayer;
    private MediaPlayer bootPlayer;

    private boolean initialised;

    private SplashMediaManager() {
    }

    public static SplashMediaManager get() {
        return INSTANCE;
    }

    private synchronized void ensureLoaded() {
        if (initialised) {
            return;
        }
        initialised = true;

        SplashConfig cfg = SplashConfig.instance();
        if (!cfg.enabled) {
            CustomSplash.LOGGER.info("[CustomSplash] 总开关已关闭，不替换任何界面");
            return;
        }

        titlePlayer = create("title", cfg.titleScreen, "title");
        loadingPlayer = create("loading", cfg.levelLoading, "loading");
        bootPlayer = create("boot", cfg.earlyLoading, "boot");
    }

    private MediaPlayer create(String key, SplashConfig.Layer layer, String autoKey) {
        if (layer == null || !layer.enabled) {
            return null;
        }
        try {
            FrameSource source = MediaLoader.load(layer, autoKey);
            if (source == null) {
                return null;
            }
            return new MediaPlayer(key, source);
        } catch (Throwable e) {
            CustomSplash.LOGGER.error("[CustomSplash] 初始化 {} 层失败: {}", key, e.toString());
            return null;
        }
    }

    /** 重新读取配置与媒体文件。 */
    public synchronized void reload() {
        dispose();
        // 共享池里可能还留着旧文件的解码器（玩家删了文件/换了文件），一并清掉，
        // 否则那份解码线程会一直挂着空转。
        dev.customsplash.media.DecoderPool.reset();
        initialised = false;
        // 清掉日志去重记录：玩家可能刚改了配置（换了文件 / 删了文件），
        // 新出现的问题应该能再报一次，而不是被上一次的记录永久静音。
        LogGate.reset();
        ensureLoaded();
    }

    // ------------------------------------------------------------------
    //  渲染入口（由 Mixin 调用）
    // ------------------------------------------------------------------

    /** @return true 表示已画了自定义背景，调用方应取消原版背景渲染 */
    public boolean renderTitleScreen(GuiGraphicsExtractor context) {
        ensureLoaded();
        return render(titlePlayer, context, SplashConfig.instance().titleScreen);
    }

    public boolean renderLevelLoading(GuiGraphicsExtractor context) {
        ensureLoaded();
        return render(loadingPlayer, context, SplashConfig.instance().levelLoading);
    }

    public boolean renderEarlyLoading(GuiGraphicsExtractor context) {
        ensureLoaded();
        return render(bootPlayer, context, SplashConfig.instance().earlyLoading);
    }

    private boolean render(MediaPlayer player, GuiGraphicsExtractor context, SplashConfig.Layer layer) {
        if (player == null || layer == null) {
            return false;
        }
        try {
            return player.render(context, layer.fit, layer.dim);
        } catch (Throwable e) {
            CustomSplash.LOGGER.error("[CustomSplash] 渲染失败，已停用该层: {}", e.toString());
            player.close();
            if (player == titlePlayer) titlePlayer = null;
            if (player == loadingPlayer) loadingPlayer = null;
            if (player == bootPlayer) bootPlayer = null;
            return false;
        }
    }

    // ------------------------------------------------------------------
    //  信息 / 释放
    // ------------------------------------------------------------------

    public List<String> describe() {
        ensureLoaded();
        SplashConfig cfg = SplashConfig.instance();
        List<String> lines = new ArrayList<>();
        lines.add("§b[CustomSplash]§r 媒体目录: §f" + SplashConfig.mediaDir());
        lines.add(describeOne("主菜单", "title", cfg.titleScreen, titlePlayer));
        lines.add(describeOne("世界加载", "loading", cfg.levelLoading, loadingPlayer));
        lines.add(describeOne("早期启动", "boot", cfg.earlyLoading, bootPlayer));
        return lines;
    }

    private String describeOne(String label, String key, SplashConfig.Layer layer, MediaPlayer player) {
        Path file = layer == null ? null : MediaLoader.resolveFile(layer, key);
        String state;
        if (player != null) {
            state = "§a已启用§r " + player.width() + "x" + player.height()
                    + (player.source().frameCount() == 1 ? " (静态)" : " (动画)");
            // 体检结论：正常就报「兼容性正常」，有问题才说原因。
            // 注意这里只报**实测**出来的问题（解码跟不上），不再按分辨率猜。
            dev.customsplash.media.VideoReport report = player.report();
            if (report != null) {
                state += "\n      " + switch (report.level()) {
                    case OK -> "§a✔ " + report.headline();
                    case SLOW -> "§e⚠ " + report.headline()
                            + (report.detail() == null ? "" : "（" + report.detail() + "）");
                    case BROKEN -> "§c⚠ " + report.headline()
                            + (report.detail() == null ? "" : "（" + report.detail() + "）");
                };
                // 画面更新率：这是「一卡一卡」最直接、最诚实的量化指标。
                //
                // 做法是统计「渲染侧推进了多少次」和「其中多少次真的换上了新画面」。
                // 推进了但拿到的还是上一帧 —— 玩家看到的就是画面停了一下。
                // 这个比例比「解码器解了多少帧」更贴近实际观感，因为解码帧数
                // 受缓冲与背压影响，不能直接换算成流畅度。
                int shown = player.renderedFrames();
                int fresh = player.freshAdvances();
                if (shown >= 30) {
                    double update = fresh / (double) shown;
                    state += "\n      §7画面更新率 §f" + Math.round(update * 100) + "%§7"
                            + "（推进 " + shown + " 次，其中 " + fresh + " 次换上了新画面"
                            + "，解码 " + report.decoded() + " 帧）";
                    if (update >= 0.98) {
                        state += " §a很流畅";
                    } else if (update >= 0.93) {
                        state += " §a流畅";
                    } else if (update >= 0.85) {
                        state += " §e偶尔顿一下";
                    } else {
                        state += " §c明显卡顿";
                    }
                }
                if (report.shared()) {
                    state += "\n      §7这个视频被多个界面共用，已合并为同一份解码（省 CPU）";
                }
            }
        } else if (file != null) {
            state = "§e文件存在但未加载§r";
        } else {
            state = "§7未配置§r";
        }
        String name = file == null ? "-" : file.getFileName().toString();
        return "  " + label + ": §f" + name + "§r -> " + state;
    }

    public synchronized void dispose() {
        closeQuietly(titlePlayer);
        closeQuietly(loadingPlayer);
        closeQuietly(bootPlayer);
        titlePlayer = null;
        loadingPlayer = null;
        bootPlayer = null;
    }

    private void closeQuietly(MediaPlayer player) {
        if (player != null) {
            try {
                player.close();
            } catch (Exception ignored) {
            }
        }
    }
}
