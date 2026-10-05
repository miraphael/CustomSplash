package dev.customsplash;

import dev.customsplash.client.SplashMediaManager;
import dev.customsplash.client.gui.MediaPreviewScreen;
import dev.customsplash.client.gui.MediaSelectScreen;
import dev.customsplash.client.gui.SplashConfigScreen;
import dev.customsplash.config.SplashConfig;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
// 注意：Fabric API 的指令模块在 26.x 升到了 v3，
// 原来的 ClientCommandManager 已改名为 ClientCommands（方法 literal / argument 不变）。
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * CustomSplash —— 用图片 / GIF / 视频替换 Minecraft 的启动界面。
 *
 * <p>替换范围：
 * <ul>
 *     <li>早期启动屏（资源加载时的 Mojang 加载界面，{@code LoadingOverlay}）</li>
 *     <li>主菜单（{@code TitleScreen}）背景</li>
 *     <li>进入世界时的世界加载界面（{@code LevelLoadingScreen}）背景</li>
 * </ul>
 *
 * <p>所有媒体资源都从 {@code config/customsplash/} 目录读取，
 * 具体用哪个文件由 {@code config/customsplash.json} 决定。
 *
 * <p>游戏里按 <b>F8</b>（可在「按键设置 → CustomSplash」里改）或输入
 * {@code /customsplash config} 就能打开设置界面，在里面挑图片 / 视频。
 *
 * <p><b>这是给 Minecraft 26.2 的版本。</b>26.x 起游戏不再混淆，类名就是官方名
 * （{@code Minecraft} 而不是 {@code MinecraftClient}，{@code KeyMapping} 而不是
 * {@code KeyBinding}，渲染方法也从 {@code render} 改成了 {@code extractRenderState}），
 * 所以这份代码和 1.21.11 那份不能共用，需要分开维护。
 */
public class CustomSplash implements ClientModInitializer {

    public static final String MOD_ID = "customsplash";
    public static final Logger LOGGER = LoggerFactory.getLogger("CustomSplash");

    /**
     * 按键分类。
     *
     * <p>26.2 里 {@code KeyMapping.Category} 是个 record，只能通过
     * {@code Category.register(Identifier)} 创建；同一 id 重复注册会抛异常。
     * 分类显示名走语言文件 {@code key.category.customsplash.main}。
     */
    public static final KeyMapping.Category KEY_CATEGORY =
            KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "main"));

    /** 打开设置界面的快捷键，默认 F8。 */
    public static final KeyMapping OPEN_CONFIG_KEY = new KeyMapping(
            "key.customsplash.open_config",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_F8,
            KEY_CATEGORY);

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }

    @Override
    public void onInitializeClient() {
        // 读取配置（配置文件不存在时会自动生成一份默认的）
        SplashConfig.load();

        // 快捷键：F8 打开设置界面
        KeyMappingHelper.registerKeyMapping(OPEN_CONFIG_KEY);
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            // while 循环是为了吞掉一次 tick 里积累的多次按下
            while (OPEN_CONFIG_KEY.consumeClick()) {
                openConfigScreen(client);
            }
        });

        // 注册客户端指令：/customsplash reload | info | config
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommands.literal("customsplash")
                        .then(ClientCommands.literal("reload")
                                .executes(ctx -> {
                                    SplashConfig.load();
                                    SplashMediaManager.get().reload();
                                    ctx.getSource().sendFeedback(
                                            Component.literal("§a[CustomSplash] 配置与媒体已重新加载"));
                                    return 1;
                                }))
                        .then(ClientCommands.literal("info")
                                .executes(ctx -> {
                                    for (String line : SplashMediaManager.get().describe()) {
                                        ctx.getSource().sendFeedback(Component.literal(line));
                                    }
                                    return 1;
                                }))
                        .then(ClientCommands.literal("config")
                                .executes(ctx -> {
                                    openConfigScreen(Minecraft.getInstance());
                                    return 1;
                                }))
                ));

        LOGGER.info("[CustomSplash] 已加载，媒体目录: {}", SplashConfig.mediaDir());

        // 立刻在后台把三块界面的媒体加载好。
        //
        // 这一步必须在这里做，不能等界面第一次渲染时再做：读文件、探测 MP4、
        // 解第一帧加起来要几百毫秒到 1 秒，如果压在「界面刚出现」那一刻，
        // 渲染线程会卡住，屏幕上还是原版界面 —— 玩家看到的就是
        // 「先闪一下原版背景，再切到视频」。放到这里则落在窗口刚创建、
        // 还没开始出帧的空窗期里，完全看不见。
        SplashMediaManager.get().preloadAsync();
    }

    /**
     * 打开设置界面。
     *
     * <p>界面里操作的是一份配置副本，只有点「保存并返回」才会真正写回并生效；
     * 已经在设置界面（或它的子界面）里时不再重复打开。
     *
     * <p>26.1 里 {@code Minecraft} 自带 {@code screen} 字段与 {@code setScreen(...)}，
     * 直接用它切换界面。
     */
    private static void openConfigScreen(Minecraft client) {
        if (client == null) {
            return;
        }
        Screen current = client.screen;
        if (current instanceof SplashConfigScreen
                || current instanceof MediaSelectScreen
                || current instanceof MediaPreviewScreen) {
            return;
        }
        client.setScreen(new SplashConfigScreen(current, SplashConfig.instance().copy()));
    }
}
