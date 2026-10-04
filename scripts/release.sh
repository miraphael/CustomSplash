#!/usr/bin/env bash
#
# CustomSplash 一键发布脚本
#
#   ./scripts/release.sh                 正常发布一个新版本
#   ./scripts/release.sh --draft         建草稿 Release（不公开）
#   ./scripts/release.sh --prerelease    标记为预发布（beta / rc）
#   ./scripts/release.sh --replace       仅替换「当前这个版本」的 Release
#
# 版本号只从 gradle.properties 的 mod_version 读。
# 每次发布 = 一个新的 tag + 一个新的 Release，不会动到任何已有版本。
#
# 需要环境变量 GITHUB_TOKEN（GitHub 个人令牌，repo 权限）。
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

DRAFT=false
PRERELEASE=false
REPLACE=false

usage() {
    sed -n '2,12p' "$0" | sed 's/^# \{0,1\}//'
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --draft)      DRAFT=true ;;
        --prerelease) PRERELEASE=true ;;
        --replace)    REPLACE=true ;;
        -h|--help)    usage; exit 0 ;;
        *)            echo "未知参数：$1"; echo; usage; exit 1 ;;
    esac
    shift
done

say()  { printf '\033[36m[release]\033[0m %s\n' "$*"; }
warn() { printf '\033[33m[release]\033[0m %s\n' "$*"; }
die()  { printf '\033[31m[release] 错误：%s\033[0m\n' "$*" >&2; exit 1; }

# ---------------------------------------------------------------- 版本号
VERSION="$(grep -E '^[[:space:]]*mod_version[[:space:]]*=' gradle.properties \
           | head -1 | cut -d= -f2 | tr -d ' \r\n')"
[[ -n "$VERSION" ]] || die "读不到 gradle.properties 里的 mod_version"
TAG="v$VERSION"
JAR="build/libs/customsplash-$VERSION.jar"

say "版本号：$VERSION    tag：$TAG"

# ---------------------------------------------------------------- 令牌
TOKEN="${GITHUB_TOKEN:-${GH_TOKEN:-}}"
[[ -n "$TOKEN" ]] || die "请先设置令牌：export GITHUB_TOKEN=<你的 GitHub 令牌>"

# ---------------------------------------------------------------- 仓库
REMOTE="$(git remote get-url origin 2>/dev/null || true)"
[[ -n "$REMOTE" ]] || die "没有 origin 远端，先 git remote add origin <仓库地址>"
SLUG="$(printf '%s' "$REMOTE" \
        | sed -E 's#.*github\.com[:/]([^/]+/[^/]+?)(\.git)?/?$#\1#')"
[[ "$SLUG" == */* ]] || die "识别不出仓库路径，origin = $REMOTE"
say "仓库：$SLUG"

# ---------------------------------------------------------------- 工作区
if [[ -n "$(git status --porcelain)" ]]; then
    warn "工作区有未提交的改动，先提交再发布（否则发出的包和源码对不上）："
    git status --short
    exit 1
fi

api() {
    curl -sS -H "Authorization: Bearer $TOKEN" \
             -H "Accept: application/vnd.github+json" \
             -H "X-GitHub-Api-Version: 2022-11-28" "$@"
}

# ---------------------------------------------------------------- tag 冲突检查
# 这是「不覆盖别的版本」的第一道闸门。
if git ls-remote --exit-code --tags origin "refs/tags/$TAG" >/dev/null 2>&1; then
    if [[ "$REPLACE" != true ]]; then
        echo
        warn "远端已经存在 tag $TAG —— 说明这个版本已经发布过了。"
        warn "已发布的版本默认不动。如果你确实要重发这一个版本，加 --replace："
        echo "    ./scripts/release.sh --replace"
        echo
        exit 1
    fi
    warn "--replace：将只删除并重建 $TAG 这一个 Release，其它版本不受影响。"
fi

# ---------------------------------------------------------------- 构建
say "开始构建……"
if [[ -x ./gradlew ]]; then
    ./gradlew build
else
    ./gradlew.bat build
fi

[[ -f "$JAR" ]] || die "构建完成但找不到产物 $JAR"

SIZE="$(du -h "$JAR" | cut -f1)"
say "产物：$JAR（$SIZE）"

# ---------------------------------------------------------------- --replace 清理
if [[ "$REPLACE" == true ]]; then
    REL_ID="$(api "https://api.github.com/repos/$SLUG/releases/tags/$TAG" \
              | sed -n 's/.*"id": *\([0-9]*\).*/\1/p' | head -1)"
    if [[ -n "${REL_ID:-}" ]]; then
        say "删除旧的 Release（id=$REL_ID）"
        api -X DELETE "https://api.github.com/repos/$SLUG/releases/$REL_ID" >/dev/null
    fi
    if git ls-remote --exit-code --tags origin "refs/tags/$TAG" >/dev/null 2>&1; then
        say "删除旧的远端 tag"
        api -X DELETE "https://api.github.com/repos/$SLUG/git/refs/tags/$TAG" >/dev/null
    fi
    git tag -d "$TAG" >/dev/null 2>&1 || true
fi

# ---------------------------------------------------------------- 打 tag
if ! git rev-parse -q --verify "refs/tags/$TAG" >/dev/null; then
    say "创建 tag $TAG"
    git tag -a "$TAG" -m "CustomSplash $TAG"
fi
say "推送 tag"
git push origin "$TAG"

# ---------------------------------------------------------------- 建 Release
# 正文里刻意不出现双引号，避免手工拼 JSON 时转义出错。
BODY="## CustomSplash $TAG\\n\\n**Minecraft 1.21.11 · Fabric**\\n\\n下载下面的 \`customsplash-$VERSION.jar\`，放进 \`.minecraft/mods/\` 即可。\\n\\n- 支持 PNG / JPG / GIF / MP4（H.264）\\n- 游戏内按 \`F8\` 打开设置界面\\n- 安装与使用说明见仓库首页 README\\n"

say "创建 GitHub Release"
RESP="$(api -X POST "https://api.github.com/repos/$SLUG/releases" \
        -d "{\"tag_name\":\"$TAG\",\"name\":\"$TAG\",\"body\":\"$BODY\",\"draft\":$DRAFT,\"prerelease\":$PRERELEASE}")"

REL_ID="$(printf '%s' "$RESP" | sed -n 's/.*"id": *\([0-9]*\).*/\1/p' | head -1)"
URL="$(printf '%s' "$RESP" | sed -n 's/.*"html_url": *"\([^"]*\)".*/\1/p' | head -1)"

if [[ -z "${REL_ID:-}" ]]; then
    die "创建 Release 失败，GitHub 返回：$RESP"
fi

# ---------------------------------------------------------------- 传附件
say "上传 $JAR"
api -X POST -H "Content-Type: application/octet-stream" \
    --data-binary "@$JAR" \
    "https://api.github.com/repos/$SLUG/releases/$REL_ID/assets?name=$(basename "$JAR")" \
    >/dev/null

echo
say "完成：$URL"
say "旧版本的 tag / Release / 附件都没有被改动。"
