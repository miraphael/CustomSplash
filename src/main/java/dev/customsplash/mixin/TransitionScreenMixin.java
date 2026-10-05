package dev.customsplash.mixin;

import dev.customsplash.client.SplashMediaManager;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 进世界 / 退出世界途中的各种「过渡界面」，整屏换成世界加载层的媒体。
 *
 * <h2>为什么改成挂在 {@code Screen.renderWithTooltip} 上</h2>
 *
 * <p>以前是「一个界面写一个 mixin」：{@code MessageScreen} 一个、
 * {@code ConnectScreen} 一个、{@code LevelLoadingScreen} 一个。
 * 这套做法有个致命的毛病 —— <b>它依赖「我记得有哪些界面」</b>。
 * 1.21.11 里 {@code ProgressScreen} 就是被这么漏掉的：
 * 它<b>没有</b>覆写 {@code renderBackground}（用的是 {@code Screen} 的默认实现，
 * 也就是旋转全景图 + 全屏模糊 + 压暗），同时又<b>有</b>自己的 {@code render}
 * （所以挂在 {@code Screen.render} 上的那版也盖不住它画的字）。
 * 两头都刚好躲开，于是点开存档的一瞬间，屏幕上就是原版那张全景图。
 *
 * <p>{@code Screen.renderWithTooltip} 是 {@code GameRenderer.render} 对
 * <b>每一个</b>界面调用的唯一入口（而且它是 {@code final} 的，任何子类都绕不过），
 * 挂在这里一次就能覆盖全部界面，不用再关心某个界面到底覆写了哪几个方法。
 * Mixin 注入 {@code final} 方法本身没有任何限制（只是不能覆写它）。
 *
 * <h2>为什么要先跑一遍原版的 {@code render()}</h2>
 *
 * <p>不能直接把整个 {@code renderWithTooltip} 掐掉。{@code ProgressScreen.render}
 * 里有一句「加载完成后 {@code setScreen(null)}」的状态切换 ——
 * 跳过它游戏就会永远卡在加载界面上。所以这里让原版的 {@code render()} 照常执行
 * （状态切换照做，它画的文字随后被整屏画面盖住），只取消掉后面的原版背景渲染。
 */
@Mixin(Screen.class)
public class TransitionScreenMixin {

    @Inject(method = "renderWithTooltip", at = @At("HEAD"), cancellable = true)
    private void customsplash$replaceWholeScreen(DrawContext context, int mouseX, int mouseY,
                                                 float deltaTicks, CallbackInfo ci) {
        Screen self = (Screen) (Object) this;
        if (!SplashMediaManager.get().shouldCoverScreen(self)) {
            return;
        }

        // 1) 原版的 render() 照常跑：保留它内部的状态切换（ProgressScreen 靠它进世界），
        //    它画出来的文字 / 进度条稍后会被整屏画面盖掉。
        //    背景（renderBackground）故意不跑 —— 那正是原版全景图的来源。
        context.createNewRootLayer();
        self.render(context, mouseX, mouseY, deltaTicks);

        // 2) 我们的画面画在最后，盖住这一帧里原版画过的所有东西。
        context.createNewRootLayer();
        SplashMediaManager.get().renderLevelLoading(context);
        context.drawDeferredElements();

        // 3) 取消原版剩余的 renderBackground + render，避免又画一遍。
        ci.cancel();
    }
}
