#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
纯色覆盖验证：把三个界面都配成同一张纯品红图之后抓帧，
任何一帧里只要出现「不是品红」的像素，就说明漏出了原版画面。

为什么这么判
------------
之前用「端门蓝 / 红色 / 绿色」这类颜色判据去扫真实视频，会把动漫夜景、
金色场景误判成原版残留（55/240 全是误报）。纯色底图没有这个歧义：
原版 UI 的任意一像素都不可能是 #FF00FF，所以非品红 == 漏出。

用法
----
    python analyze-solid.py <burst/screenshots 目录> [R G B]

输出的每一帧会给出非目标色像素占比，并按区间汇总。
"""

import sys
import os
from PIL import Image

TARGET = (255, 0, 255)
TOL = 40          # （保留，仅用于打印参考）
LEAK_RATIO = 0.02  # 超过 2% 非目标色即判定漏出

# 色相判据：品红 = R、B 都高，G 明显低。
# 不能用「离 #FF00FF 的欧氏距离」当判据 —— 抓图经过缩放 + 色彩管理之后
# 品红会被压暗成 #CA03C0 之类的值，距离一下就超了，会满屏误报。
# 原版 UI（全景图天空 / 草地 / 端门 / 红底 / 绿进度条）都不满足这个色相关系。
MIN_RB = 60


def is_target(r, g, b):
    return r > MIN_RB and b > MIN_RB and g < r * 0.5 and g < b * 0.5


def main():
    d = sys.argv[1] if len(sys.argv) > 1 else None
    if not d or not os.path.isdir(d):
        print("用法: analyze-solid.py <burst/screenshots 目录>")
        return 1
    if len(sys.argv) > 4:
        global TARGET
        TARGET = tuple(int(x) for x in sys.argv[2:5])

    files = sorted(f for f in os.listdir(d) if f.endswith(".png"))
    print("目标色 #%02X%02X%02X  容差 %d  共 %d 帧" % (TARGET[0], TARGET[1], TARGET[2], TOL, len(files)))
    print("-" * 78)

    leaks = []
    for f in files:
        im = Image.open(os.path.join(d, f)).convert("RGB")
        small = im.resize((160, 90), Image.BOX)
        px = small.load()
        bad = 0
        total = 160 * 90
        # 记录漏出像素的主色，便于判断漏出的是哪一块原版 UI
        rs = gs = bs = 0
        for y in range(90):
            for x in range(160):
                r, g, b = px[x, y]
                if not is_target(r, g, b):
                    bad += 1
                    rs += r
                    gs += g
                    bs += b
        ratio = bad / total
        mark = ""
        if ratio > LEAK_RATIO:
            mark = "  ⛔ 漏出"
            leaks.append((f, ratio))
        avg = ""
        if bad:
            avg = "  非目标色均色 #%02X%02X%02X" % (rs // bad, gs // bad, bs // bad)
        print("%s  非目标色 %6.2f%%%s%s" % (f, ratio * 100, avg, mark))

    print("-" * 78)
    if leaks:
        print("⛔ 判定漏出原版画面的帧数：%d / %d" % (len(leaks), len(files)))
        for f, r in leaks[:40]:
            print("     %s  %.1f%%" % (f, r * 100))
    else:
        print("✅ 全部 %d 帧均被自定义媒体完整覆盖，无一帧漏出原版画面" % len(files))
    return 0


if __name__ == "__main__":
    sys.exit(main())
