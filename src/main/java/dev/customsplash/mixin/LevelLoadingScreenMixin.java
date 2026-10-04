package dev.customsplash.mixin;

import dev.customsplash.client.SplashMediaManager;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.world.LevelLoadingScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 进入世界时的加载界面背景替换。
 *
 * <p>只换背景，中间那块区块加载进度图和进度条保持原样。
 */
@Mixin(LevelLoadingScreen.class)
public class LevelLoadingScreenMixin {

    @Inject(method = "renderBackground", at = @At("HEAD"), cancellable = true)
    private void customsplash$replaceBackground(DrawContext context, int mouseX, int mouseY,
                                                float deltaTicks, CallbackInfo ci) {
        if (SplashMediaManager.get().renderLevelLoading(context)) {
            ci.cancel();
        }
    }
}
