package dev.customsplash.mixin;

import dev.customsplash.client.SplashMediaManager;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.MessageScreen;
import net.minecraft.client.gui.screen.ReconfiguringScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 进世界 / 连服务器途中的各种「过渡界面」，用世界加载层的媒体整屏盖住。
 *
 * <p>覆盖的界面（这些都是玩家在点完存档或服务器之后、真正进世界之前会一闪而过的）：
 * <ul>
 *     <li>{@link MessageScreen} —— 「读取世界数据」「加载资源中」</li>
 *     <li>{@link ConnectScreen} —— 「正在连接服务器…」</li>
 *     <li>{@link ReconfiguringScreen} —— 服务器重配置</li>
 * </ul>
 *
 * <p>为什么挂在这里（{@code Screen}）而不是每个界面各写一个 mixin：
 * 这几个类大多**没有**自己声明 {@code renderBackground} / {@code render}，
 * 用的是 {@code Screen} 里的默认实现。Mixin 注入的是方法字节码，
 * 直接对子类注入父类的方法并不可靠；挂在 {@code Screen} 上是唯一稳的写法。
 * 少数自己声明了的类（{@code MessageScreen.renderBackground}、
 * {@code ConnectScreen.render}）由各自的 mixin 单独处理。
 */
@Mixin(Screen.class)
public class TransitionScreenMixin {

    /**
     * 这些界面没有自己的 {@code renderBackground}（用的是 {@code Screen} 的默认版），
     * 所以在这里取消掉：省掉原版的全景图 + 全屏模糊 + 压暗三步，
     * 画面由 {@link #customsplash$coverForeground} 统一画。
     */
    @Inject(method = "renderBackground", at = @At("HEAD"), cancellable = true)
    private void customsplash$replaceBackground(DrawContext context, int mouseX, int mouseY,
                                                float deltaTicks, CallbackInfo ci) {
        Screen self = (Screen) (Object) this;
        if (needsBackgroundReplaced(self) && SplashMediaManager.get().ownsLevelLoading()) {
            ci.cancel();
        }
    }

    /**
     * 这些界面没有自己的 {@code render}（用的是 {@code Screen} 的默认版，
     * 界面上的文字控件就是在那里面画出来的），所以在末尾把画面盖上去，
     * 连它们自己画的文字一起盖住。
     */
    @Inject(method = "render", at = @At("TAIL"))
    private void customsplash$coverForeground(DrawContext context, int mouseX, int mouseY,
                                              float deltaTicks, CallbackInfo ci) {
        Screen self = (Screen) (Object) this;
        if (needsForegroundCovered(self)) {
            SplashMediaManager.get().renderLevelLoading(context);
        }
    }

    private static boolean needsBackgroundReplaced(Screen screen) {
        return screen instanceof ConnectScreen || screen instanceof ReconfiguringScreen;
    }

    private static boolean needsForegroundCovered(Screen screen) {
        return screen instanceof MessageScreen || screen instanceof ReconfiguringScreen;
    }
}
