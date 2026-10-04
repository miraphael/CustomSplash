package dev.customsplash.client.gui;

import dev.customsplash.client.MediaLibrary;
import dev.customsplash.client.SplashMediaManager;
import dev.customsplash.config.SplashConfig;
import dev.customsplash.media.MediaLoader;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.CyclingButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.text.Text;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 游戏内的 CustomSplash 设置界面。
 *
 * <p>三行分别对应三块界面（主菜单 / 世界加载 / 早期启动），每行可以：
 * 开关这一层、挑选媒体文件、切换填充方式、调整暗化程度、以及直接预览。
 *
 * <p>界面里改的是一份**配置副本**，只有点「保存并返回」才会写回磁盘并生效。
 */
public class SplashConfigScreen extends Screen {

    /** 三块界面的 key，顺序与下面的标签一一对应。 */
    private static final String[] LAYER_KEYS = {"title", "loading", "boot"};
    private static final String[] LAYER_LABELS = {"主菜单", "世界加载", "早期启动"};
    private static final String[] LAYER_TIPS = {
            "有「单人游戏 / 多人游戏」按钮的标题界面",
            "点击进入世界后显示加载进度的界面",
            "游戏刚打开时带 Mojang 标志的加载屏",
    };

    private static final List<String> FIT_VALUES = List.of("cover", "contain", "stretch");

    private final Screen parent;
    private final SplashConfig draft;

    /** 供媒体选择界面返回时重建本界面用。 */
    Screen parentScreen() {
        return parent;
    }

    /** 每行的控件，刷新文字时要用。 */
    private final List<Row> rows = new ArrayList<>();

    private int left;
    private int rowWidth;
    private int rowsTop;
    private int rowPitch = 26;
    private int titleY;
    private int hintY;

    public SplashConfigScreen(Screen parent, SplashConfig draft) {
        super(Text.literal("CustomSplash 设置"));
        this.parent = parent;
        this.draft = draft;
    }

    // ------------------------------------------------------------------
    //  布局
    // ------------------------------------------------------------------

    @Override
    protected void init() {
        this.rows.clear();

        rowWidth = Math.min(470, this.width - 16);
        left = (this.width - rowWidth) / 2;

        int headerHeight = 42;
        int footerHeight = 58;
        int total = headerHeight + LAYER_KEYS.length * rowPitch + 8 + footerHeight;
        int top = Math.max(6, (this.height - total) / 2);

        titleY = top + 8;
        rowsTop = top + headerHeight;

        for (int i = 0; i < LAYER_KEYS.length; i++) {
            rows.add(buildRow(i, rowsTop + i * rowPitch));
        }

        int footerY = rowsTop + LAYER_KEYS.length * rowPitch + 8;
        buildFooter(footerY);
        hintY = footerY + 30;
    }

    private Row buildRow(int index, int y) {
        String key = LAYER_KEYS[index];
        SplashConfig.Layer layer = draft.layer(key);
        Row row = new Row(key, layer);

        int cursor = left + 62;
        int available = rowWidth - 62;
        int gap = 4;
        int toggleW = 44;
        int fitW = 70;
        int dimW = 80;
        int previewW = 44;
        int fileW = Math.max(60, available - (toggleW + fitW + dimW + previewW + gap * 4));

        // 开关
        row.toggle = ButtonWidget.builder(toggleLabel(layer.enabled), b -> {
            layer.enabled = !layer.enabled;
            b.setMessage(toggleLabel(layer.enabled));
        }).dimensions(cursor, y, toggleW, 20).build();
        row.toggle.setTooltip(Tooltip.of(Text.literal("是否替换这一层的背景")));
        addDrawableChild(row.toggle);
        cursor += toggleW + gap;

        // 选择文件
        row.file = ButtonWidget.builder(Text.literal("未选择"), b ->
                net.minecraft.client.MinecraftClient.getInstance().setScreen(
                        new MediaSelectScreen(this, draft, key, LAYER_LABELS[index]))
        ).dimensions(cursor, y, fileW, 20).build();
        row.fileWidth = fileW;
        addDrawableChild(row.file);
        cursor += fileW + gap;

        // 填充方式
        row.fit = CyclingButtonWidget.<String>builder(
                        SplashConfigScreen::fitLabel, layer.fit == null ? "cover" : layer.fit)
                .values(FIT_VALUES)
                .omitKeyText()
                .build(cursor, y, fitW, 20, Text.literal("填充"),
                        (button, value) -> layer.fit = value);
        addDrawableChild(row.fit);
        cursor += fitW + gap;

        // 暗化滑块
        row.dim = new DimSlider(cursor, y, dimW, 20, layer);
        addDrawableChild(row.dim);
        cursor += dimW + gap;

        // 预览
        row.preview = ButtonWidget.builder(Text.literal("预览"), b -> openPreview(key))
                .dimensions(cursor, y, previewW, 20).build();
        row.preview.setTooltip(Tooltip.of(Text.literal("全屏看看效果")));
        addDrawableChild(row.preview);

        refreshRow(row);
        return row;
    }

    private void buildFooter(int y) {
        int gap = 6;
        int openW = 122;
        int cancelW = 68;
        int saveW = 100;
        int total = openW + cancelW + saveW + gap * 2;
        int x = Math.max(4, (this.width - total) / 2);

        ButtonWidget open = ButtonWidget.builder(Text.literal("打开媒体文件夹"), b -> {
            MediaLibrary.openFolder();
        }).dimensions(x, y, openW, 20).build();
        open.setTooltip(Tooltip.of(Text.literal("把图片/视频丢进这个文件夹，然后回到这里选")));
        addDrawableChild(open);
        x += openW + gap;

        addDrawableChild(ButtonWidget.builder(Text.literal("取消"), b -> {
            SplashConfig.load();
            close();
        }).dimensions(x, y, cancelW, 20).build());
        x += cancelW + gap;

        addDrawableChild(ButtonWidget.builder(Text.literal("保存并返回"), b -> {
            SplashConfig.replaceWith(draft);
            SplashMediaManager.get().reload();
            close();
        }).dimensions(x, y, saveW, 20).build());
    }

    // ------------------------------------------------------------------
    //  行为
    // ------------------------------------------------------------------

    private void openPreview(String layerKey) {
        Path file = MediaLoader.resolveFile(draft.layer(layerKey), layerKey);
        if (file == null) {
            return;
        }
        net.minecraft.client.MinecraftClient.getInstance().setScreen(
                new MediaPreviewScreen(this, file, draft.layer(layerKey)));
    }

    private void refreshRow(Row row) {
        Path file = MediaLoader.resolveFile(row.layer, row.key);
        if (file == null) {
            // 按钮只有 90 来像素宽，文字长了会被裁掉，所以这里尽量短
            row.file.setMessage(Text.literal("§7未选择"));
            row.preview.active = false;
            row.file.setTooltip(null);
        } else {
            String name = file.getFileName().toString();
            row.file.setMessage(Text.literal(ellipsize(name, row.fileWidth - 10)));
            row.file.setTooltip(Tooltip.of(Text.literal(name + fileHint(file))));
            row.preview.active = true;
        }
        row.toggle.setMessage(toggleLabel(row.layer.enabled));
    }

    /** 在 tooltip 里附上文件的技术信息，正常时也让玩家心里有数。 */
    private static String fileHint(Path file) {
        String n = file.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        if (!n.endsWith(".mp4")) {
            return "";
        }
        try {
            dev.customsplash.client.Mp4Probe.Info info = dev.customsplash.client.Mp4Probe.readInfo(file);
            if (info == null) {
                return "";
            }
            return "\n§7" + info.width() + "x" + info.height() + " · " + info.codecLabel();
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static Text toggleLabel(boolean enabled) {
        return Text.literal(enabled ? "开" : "关");
    }

    private static Text fitLabel(String value) {
        return switch (value) {
            case "contain" -> Text.literal("完整");
            case "stretch" -> Text.literal("拉伸");
            default -> Text.literal("铺满");
        };
    }

    private String ellipsize(String text, int maxWidth) {
        if (this.textRenderer.getWidth(text) <= maxWidth) {
            return text;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            String candidate = sb.toString() + text.charAt(i) + "…";
            if (this.textRenderer.getWidth(candidate) > maxWidth) {
                break;
            }
            sb.append(text.charAt(i));
        }
        return sb + "…";
    }

    // ------------------------------------------------------------------
    //  绘制
    // ------------------------------------------------------------------

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
        super.render(context, mouseX, mouseY, deltaTicks);

        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, titleY, 0xFFFFFFFF);

        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            int y = rowsTop + i * rowPitch + 6;
            int color = row.layer.enabled ? 0xFFFFFFFF : 0xFF808080;
            context.drawTextWithShadow(this.textRenderer, Text.literal(LAYER_LABELS[i]), left, y, color);
        }

        context.drawCenteredTextWithShadow(this.textRenderer,
                Text.literal("§7支持 PNG / JPG / GIF / MP4　·　文件名也可以直接命名成 title.* / loading.* / boot.*"),
                this.width / 2, hintY, 0xFFFFFFFF);
    }

    @Override
    public void close() {
        net.minecraft.client.MinecraftClient.getInstance().setScreen(parent);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    // ------------------------------------------------------------------
    //  内部类型
    // ------------------------------------------------------------------

    private static final class Row {
        final String key;
        final SplashConfig.Layer layer;
        ButtonWidget toggle;
        ButtonWidget file;
        ButtonWidget preview;
        CyclingButtonWidget<String> fit;
        DimSlider dim;
        int fileWidth = 100;

        Row(String key, SplashConfig.Layer layer) {
            this.key = key;
            this.layer = layer;
        }
    }

    /** 暗化程度滑块。 */
    private static final class DimSlider extends SliderWidget {
        private final SplashConfig.Layer layer;

        DimSlider(int x, int y, int width, int height, SplashConfig.Layer layer) {
            super(x, y, width, height, Text.empty(), layer.dim);
            this.layer = layer;
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            setMessage(Text.literal("暗化 " + Math.round(this.value * 100) + "%"));
            if (this.layer != null) {
                this.layer.dim = (float) this.value;
            }
        }

        @Override
        protected void applyValue() {
            if (this.layer != null) {
                this.layer.dim = (float) this.value;
            }
        }
    }
}
