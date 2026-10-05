package dev.customsplash.mixin;

import dev.customsplash.client.SplashMediaManager;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 进入世界时的加载界面 —— 单人进存档和进多人服务器走的是**同一个类**，
 * 所以改一处两边都生效。
 *
 * <p><b>以前为什么没处理干净：</b>只取消了 {@code extractBackground}，
 * 可原版在 {@code extractRenderState} 里还画了三样东西：中间那块彩色区块进度图、
 * 「正在下载地形」那行字、还有绿色进度条。它们画在背景**之上**，
 * 于是玩家还是能看到原版画面。
 *
 * <p>现在改成：背景只取消不画（省掉原版的全景图 / 模糊 / 压暗三步），
 * 真正的画面画在 {@code extractRenderState} 的最末尾，把这三样一起盖住。
 */
@Mixin(LevelLoadingScreen.class)
public class LevelLoadingScreenMixin {

    /**
     * 只取消背景，**不在这里画**。
     *
     * <p>画两遍没意义（一帧要上传两次纹理），而且画在这里的话
     * 后面 {@code extractRenderState} 里的原版元素又会盖上来。
     */
    @Inject(method = "extractBackground", at = @At("HEAD"), cancellable = true)
    private void customsplash$replaceBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                                                float partialTick, CallbackInfo ci) {
        if (SplashMediaManager.get().ownsLevelLoading()) {
            ci.cancel();
        }
    }

    /** 画在最后，盖住区块进度图 /「正在下载地形」/ 绿色进度条。 */
    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void customsplash$coverForeground(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                                              float partialTick, CallbackInfo ci) {
        SplashMediaManager.get().renderLevelLoading(graphics);
    }
}
