package dev.customsplash;

import dev.customsplash.client.SplashMediaManager;
import dev.customsplash.client.gui.MediaPreviewScreen;
import dev.customsplash.client.gui.MediaSelectScreen;
import dev.customsplash.client.gui.SplashConfigScreen;
import dev.customsplash.config.SplashConfig;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * CustomSplash —— 用图片 / GIF / 视频替换 Minecraft 的启动界面。
 *
 * <p>替换范围：
 * <ul>
 *     <li>早期启动屏（资源加载时的 Mojang 加载界面，{@code SplashOverlay}）</li>
 *     <li>主菜单（{@code TitleScreen}）背景</li>
 *     <li>进入世界时的世界加载界面（{@code LevelLoadingScreen}）背景</li>
 * </ul>
 *
 * <p>所有媒体资源都从 {@code config/customsplash/} 目录读取，
 * 具体用哪个文件由 {@code config/customsplash.json} 决定。
 *
 * <p>游戏里按 <b>F8</b>（可在「按键设置 → CustomSplash」里改）或输入
 * {@code /customsplash config} 就能打开设置界面，在里面挑图片 / 视频。
 */
public class CustomSplash implements ClientModInitializer {

    public static final String MOD_ID = "customsplash";
    public static final Logger LOGGER = LoggerFactory.getLogger("CustomSplash");

    /** 按键分类。1.21.9 起分类名走数据包语言文件：{@code key.category.customsplash.main}。 */
    public static final KeyBinding.Category KEY_CATEGORY =
            KeyBinding.Category.create(Identifier.of(MOD_ID, "main"));

    /** 打开设置界面的快捷键，默认 F8。 */
    public static final KeyBinding OPEN_CONFIG_KEY = new KeyBinding(
            "key.customsplash.open_config",
            InputUtil.Type.KEYSYM,
            GLFW.GLFW_KEY_F8,
            KEY_CATEGORY);

    public static Identifier id(String path) {
        return Identifier.of(MOD_ID, path);
    }

    @Override
    public void onInitializeClient() {
        // 读取配置（配置文件不存在时会自动生成一份默认的）
        SplashConfig.load();

        // 快捷键：F8 打开设置界面
        KeyBindingHelper.registerKeyBinding(OPEN_CONFIG_KEY);
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            // while 循环是为了吞掉一次 tick 里积累的多次按下
            while (OPEN_CONFIG_KEY.wasPressed()) {
                openConfigScreen(client);
            }
        });

        // 注册客户端指令：/customsplash reload | info | config
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommandManager.literal("customsplash")
                        .then(ClientCommandManager.literal("reload")
                                .executes(ctx -> {
                                    SplashConfig.load();
                                    SplashMediaManager.get().reload();
                                    ctx.getSource().sendFeedback(
                                            Text.literal("§a[CustomSplash] 配置与媒体已重新加载"));
                                    return 1;
                                }))
                        .then(ClientCommandManager.literal("info")
                                .executes(ctx -> {
                                    for (String line : SplashMediaManager.get().describe()) {
                                        ctx.getSource().sendFeedback(Text.literal(line));
                                    }
                                    return 1;
                                }))
                        .then(ClientCommandManager.literal("config")
                                .executes(ctx -> {
                                    openConfigScreen(MinecraftClient.getInstance());
                                    return 1;
                                }))
                ));

        LOGGER.info("[CustomSplash] 已加载，媒体目录: {}", SplashConfig.mediaDir());
    }

    /**
     * 打开设置界面。
     *
     * <p>界面里操作的是一份配置副本，只有点「保存并返回」才会真正写回并生效；
     * 已经在设置界面（或它的子界面）里时不再重复打开。
     */
    private static void openConfigScreen(MinecraftClient client) {
        if (client == null) {
            return;
        }
        Screen current = client.currentScreen;
        if (current instanceof SplashConfigScreen
                || current instanceof MediaSelectScreen
                || current instanceof MediaPreviewScreen) {
            return;
        }
        client.setScreen(new SplashConfigScreen(current, SplashConfig.instance().copy()));
    }
}
