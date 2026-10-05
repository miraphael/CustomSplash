package dev.customsplash.mixin;

import dev.customsplash.client.SplashMediaManager;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.GenericMessageScreen;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 进世界 / 连服务器途中的各种「过渡界面」，用世界加载层的媒体整屏盖住。
 *
 * <p>26.x 里对应的类是：
 * <ul>
 *     <li>{@link GenericMessageScreen} —— 「读取世界数据」「加载资源中」
 *         （1.21.x 叫 {@code MessageScreen}）</li>
 *     <li>{@link ConnectScreen} —— 「正在连接服务器…」</li>
 * </ul>
 *
 * <p>为什么挂在这里（{@code Screen}）而不是每个界面各写一个 mixin：
 * 这几个类大多**没有**自己声明 {@code extractBackground} / {@code extractRenderState}，
 * 用的是 {@code Screen} 里的默认实现。Mixin 注入的是方法字节码，
 * 直接对子类注入父类的方法并不可靠；挂在 {@code Screen} 上是唯一稳的写法。
 * 少数自己声明了的类（{@code GenericMessageScreen.extractBackground}、
 * {@code ConnectScreen.extractRenderState}）由各自的 mixin 单独处理。
 */
@Mixin(Screen.class)
public class TransitionScreenMixin {

    /**
     * 这些界面没有自己的 {@code extractBackground}（用的是 {@code Screen} 的默认版），
     * 所以在这里取消掉：省掉原版的全景图 + 全屏模糊 + 压暗三步，
     * 画面由 {@link #customsplash$coverForeground} 统一画。
     */
    @Inject(method = "extractBackground", at = @At("HEAD"), cancellable = true)
    private void customsplash$replaceBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                                                float partialTick, CallbackInfo ci) {
        Screen self = (Screen) (Object) this;
        if (self instanceof ConnectScreen && SplashMediaManager.get().ownsLevelLoading()) {
            ci.cancel();
        }
    }

    /**
     * 这些界面没有自己的 {@code extractRenderState}（用的是 {@code Screen} 的默认版，
     * 界面上的文字控件就是在那里面画出来的），所以在末尾把画面盖上去，
     * 连它们自己画的文字一起盖住。
     */
    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void customsplash$coverForeground(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                                              float partialTick, CallbackInfo ci) {
        Screen self = (Screen) (Object) this;
        if (self instanceof GenericMessageScreen) {
            SplashMediaManager.get().renderLevelLoading(graphics);
        }
    }
}
