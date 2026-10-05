package dev.customsplash.mixin;

import dev.customsplash.client.SplashMediaManager;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 连接多人服务器时的「正在连接…」界面。
 *
 * <p>这个类**自己声明**了 {@code render}，而且是在 {@code super.render()} 之后
 * 才画那行状态文字的 —— 挂在 {@code Screen.render} 末尾的
 * {@link TransitionScreenMixin} 会先于文字触发，盖不住它。
 * 所以要在这里的 {@code render} 末尾再画一次。
 *
 * <p>背景（全景图 + 模糊 + 压暗）由 {@link TransitionScreenMixin} 取消。
 */
@Mixin(ConnectScreen.class)
public class ConnectScreenMixin {

    @Inject(method = "render", at = @At("TAIL"))
    private void customsplash$coverForeground(DrawContext context, int mouseX, int mouseY,
                                              float deltaTicks, CallbackInfo ci) {
        SplashMediaManager.get().renderLevelLoading(context);
    }
}
