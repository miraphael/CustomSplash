package dev.customsplash.mixin;

import dev.customsplash.client.SplashMediaManager;
import net.minecraft.client.MinecraftClient;
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
 * <h2>为什么必须「整段接管」，而不是画在最后盖住</h2>
 *
 * <p>以前的做法是在 {@code render} 的末尾（TAIL）把我们的画面画上去盖住原版。
 * 那样会漏 —— 而且漏得很隐蔽，因为**原版根本不是用 {@code DrawContext} 画那片红的**：
 *
 * <pre>
 *   // SplashOverlay.render()，启动时的分支（reloading == false）
 *   } else {
 *      int k = BRAND_ARGB.getAsInt();                       // 那片 Mojang 红
 *      RenderSystem.getDevice().createCommandEncoder()
 *          .clearColorTexture(client.getFramebuffer().getColorAttachment(), k);   // ← 直接清屏
 *      h = 1.0F;
 *   }
 * </pre>
 *
 * <p>它拿一个 GPU command encoder <b>直接清 framebuffer 的颜色附件</b>，
 * 而 {@code GameRenderer.render} 每帧**只清深度、不清颜色**，
 * 所以这一整屏红就是屏幕上唯一的红色来源。我们的画面走的是 {@code DrawContext}
 * 的延迟图层（要等这一帧末尾 {@code GuiRenderer.render} 才真正提交），
 * 只要有任何一帧我们没画上、或者绘制被跳过，屏幕上就是原样的红。
 *
 * <p>所以这里改成：<b>在 HEAD 拦截并 cancel，让原版那一段绘制代码一行都不执行</b>。
 * 只保留它必须的副作用，由我们自己重做。
 *
 * <h2>必须保留的副作用（少一个就出问题）</h2>
 * <ul>
 *     <li>{@code if (f >= 2.0F) client.setOverlay(null);} ——
 *         原版靠这一句把启动屏摘掉。不复制它，加载完成后会**永远停在启动屏上**。</li>
 *     <li>淡出阶段（{@code f} 在 1~2 之间，也就是加载完成后那 1 秒）要把**下面的界面**
 *         画出来，否则我们的画面淡掉之后露不出主菜单 —— 因为这一帧没人清过屏，
 *         露出来的会是上一帧的残留。</li>
 * </ul>
 *
 * <p>不需要保留的：{@code reloadStartTime}（只用于原版的红色淡入）、
 * {@code progress}（只用于那条进度条）、以及标志和进度条本身的绘制。
 */
@Mixin(SplashOverlay.class)
public abstract class SplashOverlayMixin implements SplashOverlayAccessor {

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void customsplash$replaceOverlay(DrawContext context, int mouseX, int mouseY,
                                             float deltaTicks, CallbackInfo ci) {
        // 没配置早期启动屏媒体 → 完全交回原版，别把玩家的启动屏变成一片残留画面
        if (!SplashMediaManager.get().ownsEarlyLoading()) {
            return;
        }

        MinecraftClient client = MinecraftClient.getInstance();

        // 和原版完全一样的算法：f = (现在 - 加载完成时刻) / 1000
        long completeTime = customsplash$getReloadCompleteTime();
        float f = completeTime > -1L
                ? (float) (Util.getMeasuringTimeMs() - completeTime) / 1000.0f
                : -1.0f;

        // 加载阶段（f < 1）完全不透明；f 在 1~2 之间是原版那 1 秒淡出。
        float alpha = f < 1.0f ? 1.0f : 1.0f - MathHelper.clamp(f - 1.0f, 0.0f, 1.0f);

        // ★ 原版靠这一句把启动屏摘掉，必须照做，否则加载完成后会永远停在这里。
        if (f >= 2.0f) {
            client.setOverlay(null);
        }

        // 淡出阶段要把底下的界面画出来，不然淡完露不出主菜单。
        //
        // 注意这里和原版是**一模一样的两步**：先画下面的界面，再开一个新的
        // root layer。第二步入不能省 —— 原版就是靠它把「压在界面上方」的那层
        // 单独分出来（原版在那里铺红色，我们换成铺自己的画面）。少了它，
        // 我们画的东西会落进 currentScreen 的同一个 layer 里，可能被它压住。
        if (alpha < 1.0f) {
            if (client.currentScreen != null) {
                client.currentScreen.renderWithTooltip(context, 0, 0, deltaTicks);
            } else {
                client.inGameHud.renderDeferredSubtitles();
            }
            context.createNewRootLayer();
        }

        if (alpha > 0.001f) {
            SplashMediaManager.get().renderEarlyLoading(context, alpha);
        }

        // 原版剩下的绘制（清屏成红 + 标志 + 进度条）一行都不跑。
        ci.cancel();
    }
}
