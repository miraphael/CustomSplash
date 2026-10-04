package dev.customsplash.mixin;

import dev.customsplash.client.SplashMediaManager;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.TitleScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 主菜单背景替换。
 *
 * <p>26.2 里「画背景」这一步是 {@code extractBackground}（1.21.x 叫 {@code renderBackground}），
 * 它负责画原版的旋转全景图。在它开头插一脚并取消掉，换成我们自己的背景；
 * 按钮、标题文字等由后面的 {@code extractRenderState} 负责，不受影响。
 */
@Mixin(TitleScreen.class)
public class TitleScreenMixin {

    @Inject(method = "extractBackground", at = @At("HEAD"), cancellable = true)
    private void customsplash$replaceBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                                                float partialTick, CallbackInfo ci) {
        if (SplashMediaManager.get().renderTitleScreen(graphics)) {
            ci.cancel();
        }
    }
}
