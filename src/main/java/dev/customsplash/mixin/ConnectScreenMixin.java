package dev.customsplash.mixin;

import dev.customsplash.client.SplashMediaManager;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.ConnectScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 连接多人服务器时的「正在连接…」界面。
 *
 * <p>这个类**自己声明**了 {@code extractRenderState}，而且是在
 * {@code super.extractRenderState()} 之后才画那行状态文字的 ——
 * 挂在 {@code Screen.extractRenderState} 末尾的 {@link TransitionScreenMixin}
 * 会先于文字触发，盖不住它。所以要在这里的 {@code extractRenderState} 末尾再画一次。
 *
 * <p>背景（全景图 + 模糊 + 压暗）由 {@link TransitionScreenMixin} 取消。
 */
@Mixin(ConnectScreen.class)
public class ConnectScreenMixin {

    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void customsplash$coverForeground(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                                              float partialTick, CallbackInfo ci) {
        SplashMediaManager.get().renderLevelLoading(graphics);
    }
}
