package dev.customsplash.client;

import dev.customsplash.CustomSplash;
import dev.customsplash.config.SplashConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * 媒体目录扫描：把 {@code config/customsplash/} 下所有能用的图片 / 动图 / 视频列出来，
 * 供游戏内的选择界面使用。
 */
public final class MediaLibrary {

    /** 支持的扩展名。 */
    public static final List<String> SUPPORTED_EXTENSIONS = List.of(
            ".png", ".jpg", ".jpeg", ".gif", ".mp4");

    private MediaLibrary() {
    }

    /** 一个媒体文件。 */
    public record MediaFile(Path path, String name, String extension, long sizeBytes) {

        /** 给玩家看的类型标签。 */
        public String typeLabel() {
            return switch (extension) {
                case ".gif" -> "GIF 动图";
                case ".mp4" -> "MP4 视频";
                default -> "静态图片";
            };
        }

        public String prettySize() {
            return humanSize(sizeBytes);
        }
    }

    public static boolean isSupported(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        for (String ext : SUPPORTED_EXTENSIONS) {
            if (lower.endsWith(ext)) {
                return true;
            }
        }
        return false;
    }

    /** 扫描媒体目录，按文件名排序返回。目录不存在时会先建出来。 */
    public static List<MediaFile> scan() {
        Path dir = SplashConfig.mediaDir();
        List<MediaFile> out = new ArrayList<>();
        try {
            Files.createDirectories(dir);
            try (Stream<Path> stream = Files.list(dir)) {
                stream.filter(Files::isRegularFile)
                        .filter(p -> isSupported(p.getFileName().toString()))
                        .forEach(p -> {
                            try {
                                out.add(new MediaFile(p, p.getFileName().toString(),
                                        extensionOf(p.getFileName().toString()),
                                        Files.size(p)));
                            } catch (IOException ignored) {
                                out.add(new MediaFile(p, p.getFileName().toString(),
                                        extensionOf(p.getFileName().toString()), -1));
                            }
                        });
            }
        } catch (IOException e) {
            CustomSplash.LOGGER.warn("[CustomSplash] 扫描媒体目录失败: {}", e.toString());
        }
        out.sort(Comparator.comparing(MediaFile::name, String.CASE_INSENSITIVE_ORDER));
        return out;
    }

    private static String extensionOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot).toLowerCase(Locale.ROOT);
    }

    public static String humanSize(long bytes) {
        if (bytes < 0) {
            return "?";
        }
        if (bytes < 1024) {
            return bytes + " B";
        }
        double kb = bytes / 1024.0;
        if (kb < 1024) {
            return String.format(Locale.ROOT, "%.0f KB", kb);
        }
        return String.format(Locale.ROOT, "%.1f MB", kb / 1024.0);
    }

    /** 在资源管理器里打开媒体目录。 */
    public static void openFolder() {
        try {
            Path dir = SplashConfig.mediaDir();
            Files.createDirectories(dir);
            // 26.3 里 Util.OS 上的 openPath / openUri 被搬到了 com.mojang.blaze3d.Blaze3D，
            // 变成一对静态方法：Blaze3D.openPath(Path) / Blaze3D.openUri(URI)。
            com.mojang.blaze3d.Blaze3D.openPath(dir);
        } catch (Exception e) {
            CustomSplash.LOGGER.warn("[CustomSplash] 打开媒体目录失败: {}", e.toString());
        }
    }
}
