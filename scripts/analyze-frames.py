#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
逐帧验证「有没有一帧漏出原版画面」，并把每一帧和**当时显示的界面**对上号。

为什么需要它
------------
上一版只用「非品红像素占比」判断，结果：
  * 抓图是每隔一帧抓一张（EVERY=2），有一半的帧根本没被检查；
  * 看到异常颜色时不知道那一帧显示的是哪个界面，只能靠猜。
这个脚本把调试日志里的 `[CustomSplash-DBG] fNNNN <screen> | overlay=<overlay>`
解析出来，和抓图按帧号一一对应，于是能直接指出「f0123 显示的是 ProgressScreen，
它有 38% 不是品红」。

判据
----
界面全部指向纯品红 #FF00FF 底图 → 原版 UI 不可能出现这个颜色 →
**任何非品红像素 == 漏出原版**。

注意：**不能用欧氏距离**。抓图经过缩放 + 色彩管理后品红会被压暗成 #CA03C0 之类，
距离一下就超了，会满屏误报。用色相关系：r>60 且 b>60 且 g<r/2 且 g<b/2。

用法
----
    python analyze-frames.py <screenshots 目录> [日志文件]
"""

import os
import re
import sys
from PIL import Image

LEAK_RATIO = 0.02   # 超过 2% 非品红即判定漏出
MIN_RB = 60


def is_target(r, g, b):
    return r > MIN_RB and b > MIN_RB and g < r * 0.5 and g < b * 0.5


def parse_log(path):
    """把日志解析成 {帧号: '界面 | overlay=...'}"""
    out = {}
    pat = re.compile(r"\[CustomSplash-DBG\] f(\d+)\s+(.*)$")
    if not path or not os.path.isfile(path):
        return out
    with open(path, "r", encoding="utf-8", errors="replace") as f:
        for line in f:
            m = pat.search(line)
            if m:
                out[int(m.group(1))] = m.group(2).strip()
    return out


def short(s):
    """net.minecraft.client.gui.screen.world.LevelLoadingScreen -> LevelLoadingScreen"""
    s = s.strip()
    if s.startswith("<null>"):
        return s
    if " | overlay=" in s:
        scr, ov = s.split(" | overlay=", 1)
        return short(scr) + " | overlay=" + short(ov)
    return s.rsplit(".", 1)[-1]


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return 1
    d = sys.argv[1]
    log = sys.argv[2] if len(sys.argv) > 2 else None
    screens = parse_log(log)

    files = sorted(f for f in os.listdir(d) if f.endswith(".png"))
    print("共 %d 帧；日志里有 %d 条界面记录" % (len(files), len(screens)))
    print("-" * 100)

    leaks = []
    for f in files:
        n = int(f[1:5])
        im = Image.open(os.path.join(d, f)).convert("RGB")
        small = im.resize((160, 90), Image.BOX)
        px = small.load()
        bad = 0
        rs = gs = bs = 0
        for y in range(90):
            for x in range(160):
                r, g, b = px[x, y]
                if not is_target(r, g, b):
                    bad += 1
                    rs += r
                    gs += g
                    bs += b
        ratio = bad / (160 * 90)
        scr = short(screens.get(n, "(日志无记录)"))
        if ratio > LEAK_RATIO:
            leaks.append((n, ratio, scr, (rs // bad, gs // bad, bs // bad) if bad else None))
        # 只打印「不是纯品红」或「界面变化」的帧，避免几百行刷屏
        if ratio > 0.001:
            avg = " 均色 #%02X%02X%02X" % (rs // bad, gs // bad, bs // bad) if bad else ""
            print("f%04d 非品红 %6.2f%%%s   [%s]" % (n, ratio * 100, avg, scr))

    print("-" * 100)
    print("界面变化时间线：")
    for n in sorted(screens):
        print("   f%04d  %s" % (n, short(screens[n])))
    print("-" * 100)
    if leaks:
        print("⛔ 非纯品红的帧：%d / %d" % (len(leaks), len(files)))
        for n, r, scr, avg in leaks[:60]:
            a = " 均色 #%02X%02X%02X" % avg if avg else ""
            print("     f%04d  %.2f%%%s   [%s]" % (n, r * 100, a, scr))
    else:
        print("✅ 全部 %d 帧都是纯品红" % len(files))
    return 0


if __name__ == "__main__":
    sys.exit(main())
