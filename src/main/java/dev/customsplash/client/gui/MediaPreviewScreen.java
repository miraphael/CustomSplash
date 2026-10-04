package dev.customsplash.client.gui;

import dev.customsplash.client.MediaPlayer;
import dev.customsplash.config.SplashConfig;
import dev.customsplash.media.FrameSource;
import dev.customsplash.media.MediaLoader;
import dev.customsplash.media.VideoReport;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 全屏预览：按该层实际的填充方式和暗化程度把媒体画出来，所见即所得。
 */
public class MediaPreviewScreen extends Screen {

    /** 每次打开用一个新的纹理 id，避免和上一张预览打架。 */
    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private final Screen parent;
    private final Path file;
    private final SplashConfig.Layer layer;

    private MediaPlayer player;
    private String error;

    /** 视频体检报告；静态图和 GIF 为 null。 */
    private VideoReport report;

    /** 「连播几秒看结论」用的计时。 */
    private long openedAt;

    /** 是否已经展示过「结论已就绪」状态，避免重复刷新界面。 */
    private boolean reportSettled;

    public MediaPreviewScreen(Screen parent, Path file, SplashConfig.Layer layer) {
        super(Component.literal("预览：" + file.getFileName()));
        this.parent = parent;
        this.file = file;
        this.layer = layer;
    }

    @Override
    protected void init() {
        openedAt = System.currentTimeMillis();

        FrameSource source = MediaLoader.loadFile(file, layer.speed, "preview");
        if (source == null) {
            error = "无法加载这个文件（详见游戏日志里的 [CustomSplash] 提示）";
        } else {
            try {
                player = new MediaPlayer("preview" + SEQUENCE.incrementAndGet(), source);
                report = player.report();
            } catch (Throwable t) {
                error = t.toString();
                source.close();
            }
        }

        buildButtons();
    }

    /**
     * 底部按钮。
     *
     * <p>「复制转码命令」只在体检结论为「不兼容」或「偏慢」时才出现 ——
     * 能正常播的视频不该给玩家推一堆转码命令，那反而像在说「你的视频有问题」。
     */
    private void buildButtons() {
        int y = this.height - 32;
        boolean needFix = report != null && report.level() != VideoReport.Level.OK;

        if (needFix) {
            int copyW = 118;
            int backW = 100;
            int gap = 8;
            int total = copyW + backW + gap;
            int x = (this.width - total) / 2;

            Button copy = Button.builder(Component.literal("复制转码命令"), b -> {
                String cmd = report.fullCommand();
                if (cmd != null) {
                    // 26.2 里这个字段叫 keyboardHandler（1.21.x 叫 keyboard）
                    Minecraft.getInstance().keyboardHandler.setClipboard(cmd);
                }
            }).bounds(x, y, copyW, 20).build();
            copy.setTooltip(Tooltip.create(Component.literal(
                    "已复制到剪贴板。\n§7装好 FFmpeg 后，在命令行里粘贴执行，\n"
                            + "会在同目录下生成一个 \u300c修复_...\u300d 文件，\n"
                            + "把它放进媒体文件夹再选它即可。")));
            addRenderableWidget(copy);
            x += copyW + gap;

            addRenderableWidget(Button.builder(Component.literal("返回"), b -> onClose())
                    .bounds(x, y, backW, 20).build());
        } else {
            addRenderableWidget(Button.builder(Component.literal("返回"), b -> onClose())
                    .bounds(this.width / 2 - 50, y, 100, 20).build());
        }
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        // 预览时不要原版背景，让媒体铺满整屏
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, this.width, this.height, 0xFF000000);

        if (player != null) {
            player.render(graphics, layer.fit, layer.dim);
        } else {
            graphics.centeredText(this.font,
                    Component.literal("§c" + (error == null ? "没有可预览的内容" : error)),
                    this.width / 2, this.height / 2, 0xFFFFFFFF);
        }

        graphics.centeredText(this.font, this.title, this.width / 2, 12, 0xFFFFFFFF);

        refreshReportIfNeeded(graphics);

        if (report != null && report.level() != VideoReport.Level.OK) {
            drawReportBox(graphics);
        }

        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    /**
     * 播放几秒后刷新体检结论。
     *
     * <p>「解码跟不跟得上」必须靠实测 —— jcodec 是软解，快慢取决于具体视频内容。
     * 所以要让它真的播一会儿，拿到足够样本后才能下结论。
     * 在这之前不显示任何「可能有问题」的提示，避免刚打开就吓人一跳。
     *
     * <p>等待时间取 7 秒：解码端要连续 3 个采样窗口（每窗 60 帧）都超标才判定偏慢，
     * 30fps 的视频大约需要 6 秒。多留 1 秒余量，免得提示还在转结论就已经出来了。
     */
    private void refreshReportIfNeeded(GuiGraphicsExtractor graphics) {
        if (player == null || report == null) {
            return;
        }
        if (report.level() == VideoReport.Level.OK && !reportSettled) {
            // 还没出结论时给一句进度提示
            long elapsed = System.currentTimeMillis() - openedAt;
            if (elapsed < 7000) {
                graphics.centeredText(this.font,
                        Component.literal("§7正在试播，稍等看兼容性结论…"),
                        this.width / 2, 26, 0xFFFFFFFF);
                return;
            }
            VideoReport fresh = player.report();
            if (fresh != null) {
                report = fresh;
            }
            reportSettled = true;
            if (report.level() != VideoReport.Level.OK) {
                // 结论从「正常」变成了「有问题」，重排按钮（这时要多出一个复制按钮）
                rebuildWidgets();
            }
        }
    }

    /**
     * 画体检结论条。
     *
     * <p>文字可能比较长（例如带上 FFmpeg 命令），所以按可用宽度自动折行，
     * 背景高度也跟着行数走 —— 否则长文字会直接溢出到屏幕外面看不见。
     *
     * <p>标题按严重程度区分：真的放不出来才写「放不出来」，
     * 只是偏慢就写「可能掉帧」，别把能看的视频说成坏的。
     */
    private void drawReportBox(GuiGraphicsExtractor graphics) {
        int margin = 8;
        int maxTextWidth = this.width - margin * 2 - 12;

        boolean broken = report.isBroken();
        String head = broken ? "§c⚠ 这个视频放不出来" : "§e⚠ 播放可能不够流畅";

        StringBuilder body = new StringBuilder("§7" + report.headline());
        if (report.detail() != null) {
            body.append("。").append(report.detail());
        }
        if (report.advice() != null) {
            body.append(" ").append(report.advice());
        }
        // 26.2 里 Font 的折行方法叫 split(FormattedText, maxWidth)，返回 FormattedCharSequence 列表
        List<FormattedCharSequence> lines = this.font.split(Component.literal(body.toString()), maxTextWidth);

        int lineCount = 1 + lines.size();
        int boxHeight = lineCount * 11 + 12;
        // 底部按钮可能有两行位置，这里统一往上让开 44
        int top = this.height - 44 - boxHeight;

        graphics.fill(margin, top, this.width - margin, top + boxHeight,
                broken ? 0xC0200000 : 0xC0302800);
        graphics.centeredText(this.font,
                Component.literal(head), this.width / 2, top + 5, 0xFFFFFFFF);

        int y = top + 16;
        for (FormattedCharSequence line : lines) {
            graphics.centeredText(this.font, line, this.width / 2, y, 0xFFFFFFFF);
            y += 11;
        }
    }

    @Override
    public void removed() {
        dispose();
    }

    @Override
    public void onClose() {
        dispose();
        Minecraft.getInstance().gui.setScreen(parent);
    }

    private void dispose() {
        if (player != null) {
            try {
                player.close();
            } catch (Exception ignored) {
            }
            player = null;
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
