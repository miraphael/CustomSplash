package dev.customsplash.core;

import dev.customsplash.CustomSplash;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 日志闸门：保证「同一件事」只打印一次。
 *
 * <h2>为什么需要它</h2>
 * 模组有三块界面（主菜单 / 世界加载 / 早期启动），它们可能引用**同一个媒体文件**。
 * 以前每块界面各自加载一遍、各自检测一遍，于是同一个问题会被打印三遍。
 * 再叠加「渲染线程每帧都会调一次 {@code ensureLoaded()}」，
 * 玩家反复重启几次之后，日志里就全是重复的黄色告警 —— 看起来像模组坏了，
 * 实际只是同一句话说了一百遍。
 *
 * <p>这里的做法是按 {@code 标签} 去重：第一次打印，之后一律静默。
 * 标签里带上文件名，所以「A 文件有问题」和「B 文件有问题」仍然会各说一次。
 *
 * <p>所有方法都是线程安全的：解码线程也会用到。
 */
public final class LogGate {

    /** 已经说过的标签。用 ConcurrentHashMap 做 Set，避免额外加锁。 */
    private static final Set<String> SAID = ConcurrentHashMap.newKeySet();

    private LogGate() {
    }

    /**
     * 说一次。已经说过的标签直接跳过。
     *
     * @param tag   去重用的标签（同标签只说一次）
     * @param warn  true 用 WARN，false 用 INFO
     * @param msg   日志内容，可含 SLF4J 的 {@code {}} 占位符
     * @param args  占位符参数
     * @return true 表示这次真的打印了
     */
    public static boolean once(String tag, boolean warn, String msg, Object... args) {
        if (!SAID.add(tag)) {
            return false;
        }
        if (warn) {
            CustomSplash.LOGGER.warn(msg, args);
        } else {
            CustomSplash.LOGGER.info(msg, args);
        }
        return true;
    }

    /** 说一次 INFO。 */
    public static void onceInfo(String tag, String msg, Object... args) {
        once(tag, false, msg, args);
    }

    /** 说一次 WARN。 */
    public static void onceWarn(String tag, String msg, Object... args) {
        once(tag, true, msg, args);
    }

    /**
     * 重置某个标签，让它下次可以重新说一次。
     *
     * <p>用在「玩家改了配置 / 换了文件」之后，这样新问题还能再报一次，
     * 而不是被上一次的记录永久静音。
     */
    public static void forget(String tag) {
        SAID.remove(tag);
    }

    /** 清空全部记录（配置 reload 时用）。 */
    public static void reset() {
        SAID.clear();
    }
}
