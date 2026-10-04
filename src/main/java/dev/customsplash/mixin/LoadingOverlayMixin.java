package dev.customsplash.mixin;

import dev.customsplash.client.SplashMediaManager;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.LoadingOverlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 早期启动屏（游戏刚打开、资源还在加载时那个带 Mojang 标志的界面）。
 *
 * <p>26.2 里这个类叫 {@code LoadingOverlay}（1.21.x 叫 {@code SplashOverlay}），
 * 而且它继承的是 {@code Overlay} 而不是 {@code Screen}，所以没有
 * {@code extractBackground} 这一步，整个画面都在 {@code extractRenderState} 里画。
 *
 * <p>在 {@code extractRenderState} 最开始处先把自定义背景画上去，
 * 原版的进度条和标志继续画在上面 —— 这样既换了背景，加载进度也还看得见。
 */
@Mixin(LoadingOverlay.class)
public class LoadingOverlayMixin {

    @Inject(method = "extractRenderState", at = @At("HEAD"))
    private void customsplash$drawBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                                             float partialTick, CallbackInfo ci) {
        SplashMediaManager.get().renderEarlyLoading(graphics);
    }
}
