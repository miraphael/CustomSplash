package dev.customsplash.mixin;

import dev.customsplash.client.SplashMediaManager;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.TitleScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 主菜单背景替换。
 *
 * <p>{@code renderBackground} 负责画原版的旋转全景图，在它开头插一脚并取消掉，
 * 换成我们自己的背景；按钮、标题文字等不受影响。
 */
@Mixin(TitleScreen.class)
public class TitleScreenMixin {

    @Inject(method = "renderBackground", at = @At("HEAD"), cancellable = true)
    private void customsplash$replaceBackground(DrawContext context, int mouseX, int mouseY,
                                                float deltaTicks, CallbackInfo ci) {
        if (SplashMediaManager.get().renderTitleScreen(context)) {
            ci.cancel();
        }
    }
}
