package dev.customsplash.client;

import dev.customsplash.CustomSplash;
import dev.customsplash.config.SplashConfig;
import dev.customsplash.core.LogGate;
import dev.customsplash.media.FrameSource;
import dev.customsplash.media.MediaLoader;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.GenericMessageScreen;
import net.minecraft.client.gui.screens.GenericWaitingScreen;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.client.gui.screens.ProgressScreen;
import net.minecraft.client.gui.screens.Screen;

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
 * <p>媒体在**模组初始化时就会在后台线程预加载**（见 {@link #preloadAsync()}），
 * 而不是等到界面第一次渲染时再加载。
 *
 * <p>为什么必须这样：加载一份媒体要做「读配置 → 找文件 → 探测 MP4 → 解出第一帧」
 * 这一串事，实测要几百毫秒到 1 秒。早期实现是「第一次渲染时才加载」，
 * 于是渲染线程会**在界面刚出现的那一刻卡住这么久** —— 屏幕上还是上一帧，
 * 也就是**原版界面**，玩家看到的就是「先闪一下原版背景，再切到视频」。
 * 三块界面各懒加载一次，所以三块都会闪。
 *
 * <p>改成启动阶段后台预加载之后，这段时间落在「窗口刚创建、还没开始出帧」的空窗期里，
 * 玩家完全看不到；等界面真的出现时只剩一次纹理创建 + 上传（毫秒级）。
 */
public final class SplashMediaManager {

    private static final SplashMediaManager INSTANCE = new SplashMediaManager();

    /**
     * 渲染线程最多愿意为「等预加载跑完」花多久。
     *
     * <p>正常情况下预加载在启动阶段就完成了，这里一秒都用不上。
     * 设这个上限只是为了万一预加载卡住（比如网络盘上的文件读不动），
     * 不至于把游戏卡死 —— 超时就退回「渲染线程自己加载」。
     */
    private static final long PRELOAD_WAIT_NS = 10_000_000_000L;

    private MediaPlayer titlePlayer;
    private MediaPlayer loadingPlayer;
    private MediaPlayer bootPlayer;

    /** 三块界面的媒体是否已经加载完（加载过程本身可能是后台线程在跑）。 */
    private volatile boolean initialised;
    /** 后台预加载线程正在加载。 */
    private volatile boolean preloading;

    private SplashMediaManager() {
    }

    public static SplashMediaManager get() {
        return INSTANCE;
    }

    /**
     * 在后台线程把三块界面的媒体先加载好。由模组初始化时调用一次。
     *
     * <p>这里是整个「不再闪原版背景」修复的入口。之所以能放后台线程，
     * 是因为 {@code MediaPlayer} 的构造器已经改成**不碰 OpenGL** 了
     * （纹理推迟到第一次渲染时创建）。
     *
     * <p>如果预加载还没跑完界面就出现了，渲染线程会在
     * {@link #ensureLoaded()} 里等它 —— 宁可等这一下，也不要出现原版背景。
     */
    public void preloadAsync() {
        synchronized (this) {
            if (initialised || preloading) {
                return;
            }
            // 必须在这里**同步**置位，不能等线程跑起来再置。
            // 否则存在这样一个窗口：渲染线程抢在预加载线程前面调用 ensureLoaded()，
            // 于是它自己把加载做完了 —— 那一次加载又压在「界面刚出现」的那一刻上，
            // 原版背景照样会闪。窗口很小，但赌不得。
            preloading = true;
        }

        Thread t = new Thread(() -> {
            long t0 = System.nanoTime();
            Throwable failure = null;
            try {
                doLoad();
            } catch (Throwable e) {
                failure = e;
            }
            synchronized (SplashMediaManager.this) {
                initialised = failure == null;
                preloading = false;
                SplashMediaManager.this.notifyAll();
            }
            if (failure != null) {
                CustomSplash.LOGGER.warn("[CustomSplash] 后台预加载失败，将在界面出现时重试: {}",
                        failure.toString());
                return;
            }
            int layers = 0;
            for (MediaPlayer p : new MediaPlayer[]{titlePlayer, loadingPlayer, bootPlayer}) {
                if (p != null) {
                    layers++;
                }
            }
            if (layers == 0) {
                return;   // 没配置任何媒体，不用报
            }
            long ms = (System.nanoTime() - t0) / 1_000_000L;
            CustomSplash.LOGGER.info(
                    "[CustomSplash] 已提前加载好 {} 块界面的媒体（耗时 {} ms），"
                            + "界面出现时不会再先闪一下原版背景", layers, ms);
        }, "CustomSplash-Preload");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    /**
     * 保证三块界面的媒体已经加载好（渲染线程调用）。
     *
     * <p>正常路径下预加载早就跑完了，这里只是读一个 boolean。
     * 万一预加载还在跑，就等它 —— 这一等的意义是：
     * 渲染线程**绝不会**自己去加载，也就绝不会卡在界面刚出现的那一刻。
     */
    private void ensureLoaded() {
        if (initialised) {
            return;
        }
        synchronized (this) {
            long deadline = System.nanoTime() + PRELOAD_WAIT_NS;
            while (!initialised && preloading && System.nanoTime() < deadline) {
                try {
                    wait(25);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            if (initialised) {
                return;
            }
            // 没有预加载线程（或它失败/超时了）→ 自己来，至少保证功能可用
            preloading = false;
            try {
                doLoad();
            } finally {
                initialised = true;
            }
        }
    }

    /**
     * 真正的加载。**不碰 OpenGL**，所以可以在后台线程上跑。
     *
     * <p>这就是以前压在「界面第一次渲染」那一刻上的那段工作：读配置、找文件、
     * 探测 MP4、解出第一帧。实测一份 720p 的片子要 570 ms 左右。
     */
    private void doLoad() {
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

    /**
     * 重新读取配置与媒体文件（玩家在设置界面点了「保存并返回」）。
     *
     * <p>这里是**同步**加载的，不走后台预加载：界面马上就要重画，
     * 异步的话会出现「配置生效前的那几帧没有背景」的窗口期，
     * 又变成闪一下原版背景。设置界面本来就是玩家主动点的，
     * 等这几百毫秒不会有人察觉。
     */
    public synchronized void reload() {
        dispose();
        // 共享池里可能还留着旧文件的解码器（玩家删了文件/换了文件），一并清掉，
        // 否则那份解码线程会一直挂着空转。
        dev.customsplash.media.DecoderPool.reset();
        initialised = false;
        preloading = false;
        // 清掉日志去重记录：玩家可能刚改了配置（换了文件 / 删了文件），
        // 新出现的问题应该能再报一次，而不是被上一次的记录永久静音。
        LogGate.reset();
        try {
            doLoad();
        } finally {
            initialised = true;
        }
    }

    // ------------------------------------------------------------------
    //  渲染入口（由 Mixin 调用）
    // ------------------------------------------------------------------

    /** @return true 表示已画了自定义背景，调用方应取消原版背景渲染 */
    public boolean renderTitleScreen(GuiGraphicsExtractor context) {
        ensureLoaded();
        return render(titlePlayer, context, SplashConfig.instance().titleScreen, 1f);
    }

    /**
     * 世界加载层：画在界面**所有内容之上**（不只是在背景层）。
     *
     * <p>以前只换背景，结果原版在 {@code extractRenderState} 里画的区块进度图、
     * 「正在下载地形」那行字和绿色进度条全都压在我们的画面上 —— 玩家看到的
     * 就是「进世界的动画里还有原版画面」。现在改成画在最后，整屏盖住。
     */
    public boolean renderLevelLoading(GuiGraphicsExtractor context) {
        ensureLoaded();
        return render(loadingPlayer, context, SplashConfig.instance().levelLoading, 1f);
    }

    /**
     * 早期启动屏：整屏盖住原版那片品牌底（默认就是 Mojang 红）、
     * Mojang Studios 标志和加载进度条。
     *
     * @param alpha 不透明度。原版在资源加载完成后有 1 秒淡出，这里跟着一起淡，
     *              淡完正好露出下面的主菜单。
     */
    public boolean renderEarlyLoading(GuiGraphicsExtractor context, float alpha) {
        ensureLoaded();
        return render(bootPlayer, context, SplashConfig.instance().earlyLoading, alpha);
    }

    /**
     * 世界加载这一层归不归我们管（只判断，不绘制）。
     *
     * <p>用来决定「要不要取消原版的背景渲染」。背景取消后由 {@link #renderLevelLoading}
     * 在界面末尾统一画，所以这里千万**不能**顺手画一遍 —— 那会一帧画两次。
     */
    public boolean ownsLevelLoading() {
        ensureLoaded();
        SplashConfig.Layer layer = SplashConfig.instance().levelLoading;
        return loadingPlayer != null && layer != null && layer.enabled;
    }

    /**
     * 早期启动屏这一层归不归我们管（只判断，不绘制）。
     *
     * <p>用来决定「要不要把原版那一整段绘制掐掉」。这个判断很要紧：
     * 原版那段代码里有一句把渲染目标的**清屏色设成 Mojang 红**
     * （{@code guiRenderState.clearColorOverride}），压根不走提取出来的绘制指令，
     * 我们画多少都盖不住「清屏」这一步。所以只要这一层归我们管，就<b>必须整段掐掉</b>；
     * 反过来，玩家没配置这一层时要老实交回原版。
     */
    public boolean ownsEarlyLoading() {
        ensureLoaded();
        SplashConfig.Layer layer = SplashConfig.instance().earlyLoading;
        return bootPlayer != null && layer != null && layer.enabled;
    }

    /**
     * 这个界面要不要整屏换成世界加载层的媒体。
     *
     * <p>由 {@code TransitionScreenMixin}（挂在
     * {@code Screen.extractRenderStateWithTooltipAndSubtitles} 上）每帧问一次。
     *
     * <p><b>为什么用白名单而不是黑名单：</b>漏掉一个过渡界面，代价只是「闪一帧原版」；
     * 而漏掉一个需要交互的界面（设置、选择世界……），代价是玩家看不见按钮、
     * 直接卡死在那个界面。两种失误的代价差太远，所以宁可漏掉也不误伤。
     */
    public boolean shouldCoverScreen(Screen screen) {
        return ownsLevelLoading() && isTransitionScreen(screen);
    }

    /**
     * 世界加载 / 连接途中的过渡界面。
     *
     * <p>这份名单是<b>从反编译源码里穷举出来的</b>，不是凭印象列的 ——
     * 1.21.11 那边正是因为凭印象列，把 {@code ProgressScreen} 漏了，
     * 导致点开存档时会闪一帧原版全景图。26.x 里这几种类出现在：
     * <ul>
     *     <li>{@link ProgressScreen} —— {@code Minecraft.disconnectWithProgressScreen()}
     *         （进世界前、退出世界、断开多人连接），以及世界列表里点开存档那一下</li>
     *     <li>{@link GenericMessageScreen} —— {@code gui.loadingMinecraft}、
     *         {@code Gui.SAVING_LEVEL}（退出世界时的「正在保存世界」）</li>
     *     <li>{@link GenericWaitingScreen} —— 服务端等待类界面</li>
     *     <li>{@link LevelLoadingScreen} —— 单人 / 多人真正开始进世界时</li>
     *     <li>{@link ConnectScreen} —— 「正在连接服务器…」</li>
     * </ul>
     *
     * <p>主菜单（{@code TitleScreen}）不在这里 —— 它要保留原版按钮，
     * 只换背景，由 {@code TitleScreenMixin} 单独处理。
     */
    public static boolean isTransitionScreen(Screen screen) {
        return screen instanceof ProgressScreen
                || screen instanceof GenericMessageScreen
                || screen instanceof GenericWaitingScreen
                || screen instanceof LevelLoadingScreen
                || screen instanceof ConnectScreen;
    }

    private boolean render(MediaPlayer player, GuiGraphicsExtractor context, SplashConfig.Layer layer, float alpha) {
        if (player == null || layer == null) {
            // 这一层没启用、或者没找到媒体文件 —— 让原版自己画，这是应该的。
            return false;
        }
        try {
            // 量一下「这一层第一次显示」花了多久。
            //
            // 这个数直接对应玩家看到的「原版背景闪一下」的时长 ——
            // 渲染线程卡在这里多久，屏幕上就还是原版界面多久。
            // 正常情况下媒体已经在启动阶段预加载好，这里只剩「建纹理 + 上传」，
            // 实测 16~70 ms；真的偏慢就报一次，方便排查（正常情况下不会输出）。
            boolean first = player.renderCalls() == 0;
            long t0 = System.nanoTime();

            player.render(context, layer.fit, layer.dim, alpha);

            if (first) {
                long ms = (System.nanoTime() - t0) / 1_000_000L;
                if (ms >= 150) {
                    LogGate.onceWarn("first-render-slow:" + player.label(),
                            "[CustomSplash] {} 层首次显示耗时 {} ms，偏慢 —— "
                                    + "界面出现时可能先闪一下原版背景"
                                    + "（通常是启动阶段的预加载没赶上，或者这个视频太大）",
                            player.label(), ms);
                }
            }
            // 只要这一层归我们管，就**一定**要取消原版背景。
            //
            // 以前这里返回的是「有没有真的画出内容」，画不出来就退回原版 ——
            // 结果是渲染线程卡在加载媒体上的那段时间里，屏幕上是原版界面，
            // 玩家看到的就是「先闪一下原版背景再切过来」。
            // 现在媒体是提前加载好的，正常情况下第一帧就是视频；
            // 万一真出问题，下面的 catch 会把这一层彻底停用，同样回到原版。
            return true;
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
