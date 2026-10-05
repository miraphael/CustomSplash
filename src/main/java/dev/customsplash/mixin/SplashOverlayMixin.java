package dev.customsplash.mixin;

import dev.customsplash.client.SplashMediaManager;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.SplashOverlay;
import net.minecraft.util.Util;
import net.minecraft.util.math.MathHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 早期启动屏（游戏刚打开、资源还在加载时那个带 Mojang 标志的界面）。
 *
 * <p><b>为什么必须画在 {@code render} 的最后（TAIL），不是开头（HEAD）：</b>
 * 原版 {@code SplashOverlay.render()} 里，底色是**无条件**铺满全屏的
 * （默认就是那片 Mojang 红，{@code ColorHelper.getArgb(255, 239, 50, 61)}），
 * 铺完还会再画一次 Mojang Studios 标志和加载进度条。
 * 以前我们在 HEAD 注入，这些东西就全都盖在我们的视频之上了 ——
 * 也就是玩家说的「会看到红色的界面」。画在最后才能整屏盖住它们。
 *
 * <p>唯一的例外是淡出阶段：原版在资源加载完成后有 1 秒把红底淡掉、
 * 露出主菜单。这段时间我们跟着一起淡（不透明度同步 1→0），
 * 这样淡出结束正好无缝接上主菜单，不会突然跳一下。
 */
@Mixin(SplashOverlay.class)
public abstract class SplashOverlayMixin implements SplashOverlayAccessor {

    @Inject(method = "render", at = @At("TAIL"))
    private void customsplash$coverOverlay(DrawContext context, int mouseX, int mouseY,
                                           float deltaTicks, CallbackInfo ci) {
        long completeTime = customsplash$getReloadCompleteTime();
        float alpha = 1.0f;
        if (completeTime > -1L) {
            // 和原版同一个算法：f = (现在 - 加载完成时刻) / 1000
            float f = (float) (Util.getMeasuringTimeMs() - completeTime) / 1000.0f;
            if (f >= 1.0f) {
                // f 在 1~2 之间就是原版那 1 秒淡出
                alpha = 1.0f - MathHelper.clamp(f - 1.0f, 0.0f, 1.0f);
            }
        }
        if (alpha <= 0.001f) {
            return;   // 已经淡完了，这一帧交给下面的主菜单画
        }
        SplashMediaManager.get().renderEarlyLoading(context, alpha);
    }
}
