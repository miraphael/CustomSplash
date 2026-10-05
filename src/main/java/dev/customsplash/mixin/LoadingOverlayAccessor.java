package dev.customsplash.mixin;

import net.minecraft.client.gui.screens.LoadingOverlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 读早期启动屏的「淡出开始时刻」。
 *
 * <p>原版 {@code LoadingOverlay} 在资源加载完成后会用 1 秒把品牌底（那片红）
 * 从全不透明淡到全透明，露出下面的主菜单。我们要知道这个时刻，
 * 才能让自己的画面跟着一起淡，而不是淡出结束后突然跳一下。
 */
@Mixin(LoadingOverlay.class)
public interface LoadingOverlayAccessor {

    /** @return 淡出开始的时刻（毫秒）；还没开始淡出时是 -1 */
    @Accessor("fadeOutStart")
    long customsplash$getFadeOutStart();
}
