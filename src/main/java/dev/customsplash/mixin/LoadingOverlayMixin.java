package dev.customsplash.mixin;

import dev.customsplash.client.SplashMediaManager;
import net.minecraft.client.Minecraft;
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
 * <h2>为什么必须「整段接管」，而不是画在最后盖住</h2>
 *
 * <p>以前是在 {@code extractRenderState} 的末尾（TAIL）把画面画上去盖住原版。
 * 那样会漏 —— 因为原版那片红**根本不是通过 {@code GuiGraphicsExtractor} 画的**：
 *
 * <pre>
 *   // LoadingOverlay.extractRenderState()，启动时的分支（fadeIn == false）
 *   } else {
 *      minecraft.gameRenderer.getGameRenderState().guiRenderState.clearColorOverride
 *          = BRAND_BACKGROUND.getAsInt();        // ← 把整个渲染目标的「清屏色」设成红
 *      logoAlpha = 1.0F;
 *   }
 * </pre>
 *
 * <p>它改的是渲染管线的**清屏色**，压根没走提取出来的绘制指令，
 * 我们画多少都盖不住「清屏」这一步。所以这里改成：
 * <b>在 HEAD 拦截并 cancel，让原版那段绘制代码一行都不执行。</b>
 *
 * <h2>必须保留的副作用（少一个就出问题）</h2>
 * <ul>
 *     <li>{@code if (fadeOutAnim >= 2.0F) minecraft.setOverlay(null);} ——
 *         原版靠这一句把启动屏摘掉。不复制它，加载完成后会**永远停在启动屏上**。</li>
 *     <li>淡出阶段（{@code fadeOutAnim} 在 1~2 之间，也就是加载完成后那 1 秒）
 *         要把**下面的界面**画出来，否则我们的画面淡掉之后露不出主菜单。</li>
 * </ul>
 *
 * <p>不需要保留的：{@code fadeInStart}（只用于原版的红色淡入）、
 * {@code currentProgress}（只用于那条进度条）、以及标志和进度条本身的绘制。
 */
@Mixin(LoadingOverlay.class)
public abstract class LoadingOverlayMixin implements LoadingOverlayAccessor {

    @Inject(method = "extractRenderState", at = @At("HEAD"), cancellable = true)
    private void customsplash$replaceOverlay(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                                             float partialTick, CallbackInfo ci) {
        // 没配置早期启动屏媒体 → 完全交回原版
        if (!SplashMediaManager.get().ownsEarlyLoading()) {
            return;
        }

        Minecraft client = Minecraft.getInstance();

        // 和原版完全一样的算法：fadeOutAnim = (现在 - 淡出开始时刻) / 1000
        long fadeOutStart = customsplash$getFadeOutStart();
        float fadeOutAnim = fadeOutStart > -1L
                ? (float) (Util.getMillis() - fadeOutStart) / 1000.0f
                : -1.0f;

        // 加载阶段（< 1）完全不透明；1~2 之间是原版那 1 秒淡出。
        float alpha = fadeOutAnim < 1.0f
                ? 1.0f
                : 1.0f - Mth.clamp(fadeOutAnim - 1.0f, 0.0f, 1.0f);

        // ★ 原版靠这一句把启动屏摘掉，必须照做，否则加载完成后会永远停在这里。
        if (fadeOutAnim >= 2.0f) {
            client.setOverlay(null);
        }

        // 淡出阶段要把底下的界面画出来，不然淡完露不出主菜单。
        //
        // 注意这里和原版是**一模一样的两步**：先画下面的界面，再开一个新的
        // stratum。第二步入不能省 —— 原版就是靠它把「压在界面上方」的那层
        // 单独分出来（原版在那里铺红色，我们换成铺自己的画面）。
        if (alpha < 1.0f) {
            if (client.screen != null) {
                client.screen.extractRenderStateWithTooltipAndSubtitles(graphics, 0, 0, partialTick);
            } else {
                client.gui.extractDeferredSubtitles();
            }
            graphics.nextStratum();
        }

        if (alpha > 0.001f) {
            SplashMediaManager.get().renderEarlyLoading(graphics, alpha);
        }

        // 原版剩下的绘制（设红色清屏色 + 标志 + 进度条）一行都不跑。
        ci.cancel();
    }
}
