package dev.customsplash.mixin;

import dev.customsplash.client.SplashMediaManager;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.SplashOverlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 早期启动屏（游戏刚打开、资源还在加载时那个带 Mojang 标志的界面）。
 *
 * <p>在 {@code render} 最开始处先把自定义背景画上去，
 * 原版的进度条和标志继续画在上面 —— 这样既换了背景，加载进度也还看得见。
 */
@Mixin(SplashOverlay.class)
public class SplashOverlayMixin {

    @Inject(method = "render", at = @At("HEAD"))
    private void customsplash$drawBackground(DrawContext context, int mouseX, int mouseY,
                                             float deltaTicks, CallbackInfo ci) {
        SplashMediaManager.get().renderEarlyLoading(context);
    }
}
