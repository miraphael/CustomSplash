package dev.customsplash.client.gui;

import dev.customsplash.client.MediaLibrary;
import dev.customsplash.client.Mp4Probe;
import dev.customsplash.config.SplashConfig;
import dev.customsplash.media.MediaLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.nio.file.Path;
import java.util.List;

/**
 * 媒体文件选择界面：把 {@code config/customsplash/} 里所有能用的文件列出来，点一下就指派给某一层。
 *
 * <p>文件多的时候用底部的翻页按钮翻页。
 */
public class MediaSelectScreen extends Screen {

    private static final int COLUMNS = 2;
    private static final int ROWS = 5;
    private static final int PER_PAGE = COLUMNS * ROWS;

    private final SplashConfigScreen configScreen;
    private final SplashConfig draft;
    private final String layerKey;
    private final String layerLabel;

    private List<MediaLibrary.MediaFile> files = List.of();
    private int page;

    private int gridLeft;
    private int gridTop;
    private int cellWidth;
    private int rowPitch = 24;
    private int titleY;
    private int hintY;

    public MediaSelectScreen(SplashConfigScreen configScreen, SplashConfig draft,
                             String layerKey, String layerLabel) {
        this(configScreen, draft, layerKey, layerLabel, 0);
    }

    public MediaSelectScreen(SplashConfigScreen configScreen, SplashConfig draft,
                             String layerKey, String layerLabel, int page) {
        super(Component.literal("为「" + layerLabel + "」选择媒体文件"));
        this.configScreen = configScreen;
        this.draft = draft;
        this.layerKey = layerKey;
        this.layerLabel = layerLabel;
        this.page = page;
    }

    private int pageCount() {
        return Math.max(1, (files.size() + PER_PAGE - 1) / PER_PAGE);
    }

    @Override
    protected void init() {
        this.files = MediaLibrary.scan();
        if (page >= pageCount()) {
            page = pageCount() - 1;
        }
        if (page < 0) {
            page = 0;
        }

        int gridWidth = Math.min(430, this.width - 16);
        gridLeft = (this.width - gridWidth) / 2;
        cellWidth = (gridWidth - 6) / COLUMNS;

        int headerHeight = 46;
        int footerHeight = 46;
        int total = headerHeight + ROWS * rowPitch + 6 + footerHeight;
        int top = Math.max(6, (this.height - total) / 2);

        titleY = top + 8;
        hintY = top + 24;
        gridTop = top + headerHeight;

        buildGrid();
        buildFooter(gridTop + ROWS * rowPitch + 6);
    }

    private void buildGrid() {
        int start = page * PER_PAGE;
        for (int i = 0; i < PER_PAGE; i++) {
            int index = start + i;
            if (index >= files.size()) {
                break;
            }
            MediaLibrary.MediaFile file = files.get(index);
            int col = i % COLUMNS;
            int row = i / COLUMNS;
            int x = gridLeft + col * (cellWidth + 6);
            int y = gridTop + row * rowPitch;

            // 用「解析出来的实际文件」比较，这样没手动选过、靠 title.* 自动找到的文件也能标出来
            boolean current = file.path()
                    .equals(MediaLoader.resolveFile(draft.layer(layerKey), layerKey));
            String prefix = current ? "§a▶ " : "";

            String note = compatWarning(file);
            String info = mediaInfo(file);

            Button button = Button.builder(
                            Component.literal(prefix + (note == null ? "" : "§c⚠ ")
                                    + ellipsize(file.name(), cellWidth - (note == null ? 14 : 24))),
                            b -> pick(file))
                    .bounds(x, y, cellWidth, 20)
                    .tooltip(Tooltip.create(Component.literal(file.name() + "\n"
                            + "§7类型: " + file.typeLabel() + "\n"
                            + "§7大小: " + file.prettySize()
                            + (info == null ? "" : "\n§7" + info)
                            + (current ? "\n§a当前正在使用" : "")
                            + (note == null ? "" : "\n§c⚠ " + note))))
                    .build();
            addRenderableWidget(button);
        }
    }

    /**
     * 展示用的技术信息（分辨率 / 编码 / 帧率）。只是给人看的，不参与任何判断。
     *
     * <p>以前这里展示的东西被拿去做「兼容性判断」，结果全是误报。
     * 现在它只负责让玩家知道自己选的是个什么文件 —— 判断交给实测。
     */
    private String mediaInfo(MediaLibrary.MediaFile file) {
        if (!".mp4".equals(file.extension())) {
            return null;
        }
        try {
            Mp4Probe.Info info = Mp4Probe.readInfo(file.path());
            if (info == null) {
                return null;
            }
            return info.width() + "x" + info.height() + " · " + info.codecLabel();
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * 挑文件时先把「确定放不出来」的视频标出来，省得玩家选完才发现黑屏。
     *
     * <p>只报**确实会失败**的情况：编码不是 H.264（H.265 / AV1 / VP9 等）。
     * 这些 jcodec 会直接报 {@code Not a video track}，是 100% 放不出来的。
     *
     * <p><b>注意</b>：这里**不再检查分辨率**。早期版本曾经因为
     * 「宽度不是 16 的整数倍」和「分辨率偏高」就报警，后来实测证明那两条判据都是错的
     * （{@code 1336x612} 能正常解码，实测 30 ms/帧）。
     * 那种误报只会让玩家白折腾转码，所以整条逻辑已经删掉。
     * 是否真的卡顿由解码线程实测决定，见 {@code VideoFrameSource#report()}。
     *
     * @return 有问题时返回原因，正常返回 {@code null}
     */
    private String compatWarning(MediaLibrary.MediaFile file) {
        if (!".mp4".equals(file.extension())) {
            return null;
        }
        try {
            Mp4Probe.Info info = Mp4Probe.readInfo(file.path());
            if (info == null) {
                return null;   // 读不出来就不乱报
            }
            if (!info.isH264()) {
                return "这个视频的编码是 " + info.codecLabel() + "，本模组用的 jcodec 只能解 H.264。\n"
                        + "请用 FFmpeg 转成 H.264 后重试：-c:v libx264 -pix_fmt yuv420p";
            }
        } catch (Throwable ignored) {
            // 探测失败就当没问题，不要因为探测本身的问题误导玩家
        }
        return null;
    }

    /**
     * 底部按钮排成两行。
     *
     * <p>一开始把它们挤在一行里，结果在 1280×720（GUI 缩放 3，缩放后只有 426×240）
     * 这种很常规的分辨率下总宽度会超出屏幕，「刷新」直接被推到画面外面去。
     */
    private void buildFooter(int y) {
        int gap = 6;

        // 第一行：翻页 + 刷新
        int navW = 30;
        int pageW = 70;
        int refreshW = 46;
        int row1 = navW * 2 + pageW + refreshW + gap * 3;
        int x = Math.max(4, (this.width - row1) / 2);

        Button prev = Button.builder(Component.literal("◀"), b -> {
            page = Math.max(0, page - 1);
            rebuild();
        }).bounds(x, y, navW, 20).build();
        prev.active = page > 0;
        prev.setTooltip(Tooltip.create(Component.literal("上一页")));
        addRenderableWidget(prev);
        x += navW + gap;

        addRenderableWidget(Button.builder(
                Component.literal((page + 1) + " / " + pageCount()), b -> {
        }).bounds(x, y, pageW, 20).build());
        x += pageW + gap;

        Button next = Button.builder(Component.literal("▶"), b -> {
            page = Math.min(pageCount() - 1, page + 1);
            rebuild();
        }).bounds(x, y, navW, 20).build();
        next.active = page < pageCount() - 1;
        next.setTooltip(Tooltip.create(Component.literal("下一页")));
        addRenderableWidget(next);
        x += navW + gap;

        Button refresh = Button.builder(Component.literal("刷新"), b -> {
            files = MediaLibrary.scan();
            page = 0;
            rebuild();
        }).bounds(x, y, refreshW, 20).build();
        refresh.setTooltip(Tooltip.create(Component.literal("重新扫描媒体文件夹，看看有没有新放进来的文件")));
        addRenderableWidget(refresh);

        // 第二行：动作
        int y2 = y + 24;
        int clearW = 82;
        int openW = 118;
        int backW = 66;
        int row2 = clearW + openW + backW + gap * 2;
        int x2 = Math.max(4, (this.width - row2) / 2);

        Button clear = Button.builder(Component.literal("不使用"), b -> {
            draft.layer(layerKey).media = "";
            back();
        }).bounds(x2, y2, clearW, 20).build();
        clear.setTooltip(Tooltip.create(Component.literal("清空选择，恢复成按 title.* / loading.* / boot.* 自动查找")));
        addRenderableWidget(clear);
        x2 += clearW + gap;

        Button open = Button.builder(Component.literal("打开文件夹"), b ->
                MediaLibrary.openFolder()).bounds(x2, y2, openW, 20).build();
        open.setTooltip(Tooltip.create(Component.literal("放入新文件后点「刷新」重新扫描")));
        addRenderableWidget(open);
        x2 += openW + gap;

        addRenderableWidget(Button.builder(Component.literal("返回"), b -> back())
                .bounds(x2, y2, backW, 20).build());
    }

    /** 重建整个界面（翻页 / 刷新后），保留当前页码。 */
    private void rebuild() {
        Minecraft.getInstance().setScreen(
                new MediaSelectScreen(configScreen, draft, layerKey, layerLabel, page));
    }

    private void pick(MediaLibrary.MediaFile file) {
        draft.layer(layerKey).media = file.name();
        back();
    }

    private void back() {
        Minecraft.getInstance().setScreen(new SplashConfigScreen(configScreen.parentScreen(), draft));
    }

    private String ellipsize(String text, int maxWidth) {
        if (this.font.width(text) <= maxWidth) {
            return text;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            if (this.font.width(sb.toString() + text.charAt(i) + "…") > maxWidth) {
                break;
            }
            sb.append(text.charAt(i));
        }
        return sb + "…";
    }

    /**
     * 完整路径太长会顶到屏幕外面去，所以只留末尾两级。
     * 反正「打开文件夹」按钮能直接把目录弹出来。
     */
    private static String shortDir() {
        Path dir = SplashConfig.mediaDir();
        int count = dir.getNameCount();
        if (count >= 2) {
            return "…/" + dir.getName(count - 2) + "/" + dir.getName(count - 1);
        }
        return dir.toString();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);

        graphics.centeredText(this.font, this.title, this.width / 2, titleY, 0xFFFFFFFF);
        graphics.centeredText(this.font,
                Component.literal("§7媒体目录: " + shortDir()), this.width / 2, hintY, 0xFFFFFFFF);

        if (files.isEmpty()) {
            graphics.centeredText(this.font,
                    Component.literal("§e这个文件夹里还没有可用的媒体文件"),
                    this.width / 2, gridTop + 30, 0xFFFFFFFF);
            graphics.centeredText(this.font,
                    Component.literal("§7点「打开文件夹」把图片 / 视频放进去，再点「刷新」"),
                    this.width / 2, gridTop + 46, 0xFFFFFFFF);
        }
    }

    @Override
    public void onClose() {
        back();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
