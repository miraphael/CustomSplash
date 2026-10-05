package dev.customsplash.mixin;

import dev.customsplash.client.SplashMediaManager;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 进世界 / 退出世界途中的各种「过渡界面」，整屏换成世界加载层的媒体。
 *
 * <h2>为什么改成挂在 {@code Screen.extractRenderStateWithTooltipAndSubtitles} 上</h2>
 *
 * <p>以前是「一个界面写一个 mixin」：{@code GenericMessageScreen} 一个、
 * {@code ConnectScreen} 一个、{@code LevelLoadingScreen} 一个。
 * 这套做法有个致命的毛病 —— <b>它依赖「我记得有哪些界面」</b>。
 * 1.21.11 那边 {@code ProgressScreen} 就是这么被漏掉的（26.x 同样有这个类、
 * 同样在进世界和退出世界时出现），于是点开存档的一瞬间会闪一帧原版全景图。
 *
 * <p>26.x 里 {@code Screen.extractRenderStateWithTooltipAndSubtitles} 是所有界面
 * 共用的唯一入口（而且它是 {@code final} 的，任何子类都绕不过），
 * 结构上和 1.21.11 的 {@code renderWithTooltip} 一一对应：
 * <pre>
 *   nextStratum(); extractBackground(...);   // ← 原版全景图 / 模糊 / 压暗
 *   nextStratum(); extractRenderState(...);  // ← 界面上的文字与控件
 *   extractDeferredElements(...);
 * </pre>
 * 挂在这里一次就能覆盖全部界面，不用再关心某个界面到底覆写了哪几个方法。
 * Mixin 注入 {@code final} 方法本身没有任何限制（只是不能覆写它）。
 *
 * <h2>为什么要先跑一遍原版的 {@code extractRenderState()}</h2>
 *
 * <p>不能直接把整个方法掐掉。{@code ProgressScreen.extractRenderState} 里有一句
 * 「加载完成后 {@code setScreen(null)}」的状态切换 —— 跳过它游戏会卡在加载界面上。
 * 所以这里让原版的 {@code extractRenderState()} 照常执行（状态切换照做，
 * 它画出来的文字随后被整屏画面盖住），只跳过原版背景那一层。
 */
@Mixin(Screen.class)
public class TransitionScreenMixin {

    @Inject(method = "extractRenderStateWithTooltipAndSubtitles", at = @At("HEAD"), cancellable = true)
    private void customsplash$replaceWholeScreen(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                                                 float partialTick, CallbackInfo ci) {
        Screen self = (Screen) (Object) this;
        if (!SplashMediaManager.get().shouldCoverScreen(self)) {
            return;
        }

        // 1) 原版的 extractRenderState() 照常跑：保留它内部的状态切换
        //    （ProgressScreen 靠它进世界），它画出来的文字稍后会被整屏画面盖掉。
        //    背景那一层（extractBackground）故意不跑 —— 那正是原版全景图的来源。
        graphics.nextStratum();
        self.extractRenderState(graphics, mouseX, mouseY, partialTick);

        // 2) 我们的画面画在最后，盖住这一帧里原版画过的所有东西。
        graphics.nextStratum();
        SplashMediaManager.get().renderLevelLoading(graphics);
        graphics.extractDeferredElements(mouseX, mouseY, partialTick);

        // 3) 取消原版剩余的 extractBackground + extractRenderState，避免又画一遍。
        ci.cancel();
    }
}
