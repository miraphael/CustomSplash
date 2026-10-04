package dev.customsplash.media;

import dev.customsplash.CustomSplash;
import dev.customsplash.config.SplashConfig;
import dev.customsplash.core.LogGate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** 根据配置找到媒体文件，并创建对应的 {@link FrameSource}。 */
public final class MediaLoader {

    private MediaLoader() {
    }

    /**
     * 按配置加载一层的媒体。
     *
     * @param layer    该层的配置
     * @param layerKey 该层的自动查找前缀（title / loading / boot）
     * @return 帧源；没有配置或文件不存在时返回 {@code null}
     */
    public static FrameSource load(SplashConfig.Layer layer, String layerKey) {
        Path file = resolveFile(layer, layerKey);
        if (file == null) {
            return null;
        }
        return loadFile(file, layer.speed, layerKey);
    }

    /**
     * 直接按文件路径加载（供游戏内预览使用）。
     *
     * @param file     媒体文件
     * @param speed    播放速度倍率
     * @param debugKey 只用于日志的标签
     */
    public static FrameSource loadFile(Path file, float speed, String debugKey) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        try {
            FrameSource source;
            if (name.endsWith(".gif")) {
                source = GifFrameSource.load(file);
            } else if (name.endsWith(".mp4")) {
                // 走共享池：同一个视频被多个界面引用时只解码一份。
                // 这是「视频一卡一卡」最关键的修复 —— 早期实现三层各建一个解码器，
                // 同一个视频被同时解三遍，CPU 被三倍争抢。
                source = DecoderPool.acquire(file, speed);
            } else if (name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg")) {
                source = ImageFrameSource.load(file);
            } else {
                CustomSplash.LOGGER.warn("[CustomSplash] 不支持的格式，已跳过: {}", file);
                return null;
            }
            // 「已加载」按文件名去重：三层引用同一个视频时只说一次，
            // 免得日志里同一个文件重复三遍，看着像出问题了。
            LogGate.onceInfo("media-loaded:" + file,
                    "[CustomSplash] 已加载媒体: {} ({}x{})，当前指派给 {} 层",
                    file.getFileName(), source.width(), source.height(), debugKey);
            return source;
        } catch (NoClassDefFoundError e) {
            LogGate.onceWarn("media-nocodec:" + file,
                    "[CustomSplash] 缺少视频解码库（jcodec），MP4 无法播放: {}", file);
            return null;
        } catch (Throwable e) {
            LogGate.onceWarn("media-load-fail:" + file,
                    "[CustomSplash] 加载媒体失败 {}: {}", file, e.toString());
            return null;
        }
    }

    /** 解析出实际要用的文件；找不到返回 {@code null}。 */
    public static Path resolveFile(SplashConfig.Layer layer, String layerKey) {
        Path dir = SplashConfig.mediaDir();

        String configured = layer.media == null ? "" : layer.media.trim();
        if (!configured.isEmpty()) {
            Path p = Path.of(configured);
            if (!p.isAbsolute()) {
                p = dir.resolve(configured);
            }
            p = p.normalize();
            if (Files.isRegularFile(p)) {
                return p;
            }
            // 配置指向的文件已经不在磁盘上了（玩家删了文件、或换了目录）。
            // 这里**必须先判「原文件是否从未存在」**再决定提示方式，
            // 而且要按「层 + 文件名」去重 —— 以前这个方法会被渲染线程每帧调用一次，
            // 再加上三个层各调一遍，一个已删除的文件能在日志里刷上百条 WARN，
            // 玩家看到满屏黄字以为模组坏了。其实只是一句「文件没了」。
            boolean lookLikeStale = !configured.contains("/") && !configured.contains("\\");
            if (lookLikeStale) {
                LogGate.onceWarn("media-missing:" + layerKey + ":" + configured,
                        "[CustomSplash] {} 层配置指向的文件已不存在，已跳过（到游戏内界面重新选一个即可）: {}",
                        layerKey, p.getFileName());
            } else {
                LogGate.onceWarn("media-missing-path:" + p,
                        "[CustomSplash] {} 层指定的文件不存在，已跳过: {}", layerKey, p);
            }
            return null;
        }

        // 没指定文件名时，按 title.* / loading.* / boot.* 自动查找
        if (!Files.isDirectory(dir)) {
            LogGate.onceWarn("media-dir-missing:" + dir,
                    "[CustomSplash] 媒体目录不存在，已跳过自动查找: {}", dir);
            return null;
        }
        for (String candidate : SplashConfig.autoNames(layerKey)) {
            Path p = dir.resolve(candidate);
            if (Files.isRegularFile(p)) {
                return p;
            }
        }
        return null;
    }
}
