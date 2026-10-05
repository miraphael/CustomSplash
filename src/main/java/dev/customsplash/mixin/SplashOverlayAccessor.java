package dev.customsplash.mixin;

import net.minecraft.client.gui.screen.SplashOverlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 读早期启动屏的「资源加载完成时刻」。
 *
 * <p>原版 {@code SplashOverlay} 在资源加载完成后会用 1 秒把品牌底（那片红）
 * 从全不透明淡到全透明，露出下面的主菜单。我们要知道这个时刻，
 * 才能让自己的画面跟着一起淡，而不是淡出结束后突然跳一下。
 */
@Mixin(SplashOverlay.class)
public interface SplashOverlayAccessor {

    /** @return 资源加载完成的时刻（毫秒）；还没加载完时是 -1 */
    @Accessor("reloadCompleteTime")
    long customsplash$getReloadCompleteTime();
}
