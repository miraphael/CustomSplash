package dev.customsplash.mixin;

import dev.customsplash.client.SplashMediaManager;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.LoadingOverlay;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 早期启动屏（游戏刚打开、资源还在加载时那个带 Mojang 标志的界面）。
 *
 * <p>26.x 里这个类叫 {@code LoadingOverlay}（1.21.x 叫 {@code SplashOverlay}），
 * 它继承 {@code Overlay} 而不是 {@code Screen}，没有 {@code extractBackground} 这一步，
 * 整个画面都在 {@code extractRenderState} 里画。
 *
 * <p><b>为什么必须画在 {@code extractRenderState} 的最后（TAIL），不是开头（HEAD）：</b>
 * 原版这个方法里，底色是**无条件**铺满全屏的（默认就是那片 Mojang 红，
 * {@code ARGB.color(255, 239, 50, 61)}），铺完还会再画一次 Mojang Studios 标志和
 * 加载进度条。以前我们在 HEAD 注入，这些东西就全都盖在我们的视频之上了 ——
 * 也就是玩家说的「会看到红色的界面」。画在最后才能整屏盖住它们。
 *
 * <p>唯一的例外是淡出阶段：原版在资源加载完成后有 1 秒把红底淡掉、露出主菜单。
 * 这段时间我们跟着一起淡（不透明度同步 1→0），淡完正好无缝接上主菜单。
 */
@Mixin(LoadingOverlay.class)
public abstract class LoadingOverlayMixin implements LoadingOverlayAccessor {

    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void customsplash$coverOverlay(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                                           float partialTick, CallbackInfo ci) {
        long fadeOutStart = customsplash$getFadeOutStart();
        float alpha = 1.0f;
        if (fadeOutStart > -1L) {
            // 和原版同一个算法：fadeOutAnim = (现在 - 淡出开始时刻) / 1000
            float fadeOutAnim = (float) (Util.getMillis() - fadeOutStart) / 1000.0f;
            if (fadeOutAnim >= 1.0f) {
                // fadeOutAnim 在 1~2 之间就是原版那 1 秒淡出
                alpha = 1.0f - Mth.clamp(fadeOutAnim - 1.0f, 0.0f, 1.0f);
            }
        }
        if (alpha <= 0.001f) {
            return;   // 已经淡完了，这一帧交给下面的主菜单画
        }
        SplashMediaManager.get().renderEarlyLoading(graphics, alpha);
    }
}
