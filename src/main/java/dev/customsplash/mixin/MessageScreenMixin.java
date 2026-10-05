package dev.customsplash.mixin;

import dev.customsplash.client.SplashMediaManager;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.MessageScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 「读取世界数据」「加载资源中」这类一次性提示界面。
 *
 * <p>这个类是少数**自己声明**了 {@code renderBackground} 的
 * （全景图 + 全屏模糊 + 压暗），必须在它自己身上取消，
 * 挂在 {@code Screen} 上的 {@link TransitionScreenMixin} 管不到。
 *
 * <p>画面本身由 {@link TransitionScreenMixin} 在 {@code Screen.render} 末尾统一画，
 * 这里只负责取消背景 —— 画两遍会一帧上传两次纹理，没必要。
 */
@Mixin(MessageScreen.class)
public class MessageScreenMixin {

    @Inject(method = "renderBackground", at = @At("HEAD"), cancellable = true)
    private void customsplash$replaceBackground(DrawContext context, int mouseX, int mouseY,
                                                float deltaTicks, CallbackInfo ci) {
        if (SplashMediaManager.get().ownsLevelLoading()) {
            ci.cancel();
        }
    }
}
