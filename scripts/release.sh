#!/usr/bin/env bash
#
# CustomSplash 一键发布脚本
#
#   ./scripts/release.sh                 正常发布一个新版本
#   ./scripts/release.sh --draft         建草稿 Release（不公开）
#   ./scripts/release.sh --prerelease    标记为预发布（beta / rc）
#   ./scripts/release.sh --replace       仅替换「当前这个版本」的 Release
#
# 版本号由 gradle.properties 的两行拼出来：
#     mod_version=1.0.0  +  minecraft_version=26.2
#        → 完整版本 1.0.0+26.2
#        → tag     v1.0.0+26.2
#        → 产物    customsplash-1.0.0+26.2.jar
# 文件名和 tag 都带目标 MC 版本，这样同时维护多个游戏版本也不会混淆。
#
# Release 只上传一个主 jar 附件；源码由 GitHub 自动附带的
# v<tag>.zip / v<tag>.tar.gz 提供，不再单独打包 -sources.jar。
#
# 每次发布 = 一个新的 tag + 一个新的 Release，不会动到任何已有版本。
#
# 需要环境变量 GITHUB_TOKEN（GitHub 个人令牌，repo 权限）。
#
# 本机 github.com 主站可能被代理拦（git push 报 CONNECT tunnel failed），
# 所以 tag 的「存在性检查」和「推送」都以 api.github.com 为主、
# 把 git 命令当快路径，两者结果等价。
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

DRAFT=false
PRERELEASE=false
REPLACE=false

usage() {
    # 打印文件开头的注释块（第 2 行到 set -euo 之前），不依赖具体行号
    sed -n '2,/^set -euo/p' "$0" | sed '$d' | sed 's/^# \{0,1\}//'
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
read_prop() {
    grep -E "^[[:space:]]*$1[[:space:]]*=" gradle.properties \
        | head -1 | cut -d= -f2 | tr -d ' \r\n'
}

MOD_VERSION="$(read_prop mod_version)"
MC_VERSION="$(read_prop minecraft_version)"
[[ -n "$MOD_VERSION" ]] || die "读不到 gradle.properties 里的 mod_version"
[[ -n "$MC_VERSION" ]]  || die "读不到 gradle.properties 里的 minecraft_version"

FULL_VERSION="$MOD_VERSION+$MC_VERSION"
TAG="v$FULL_VERSION"
JAR="build/libs/customsplash-$FULL_VERSION.jar"

say "模组版本：$MOD_VERSION    目标 Minecraft：$MC_VERSION"
say "完整版本：$FULL_VERSION"
say "tag：$TAG"

# ---------------------------------------------------------------- 令牌
TOKEN="${GITHUB_TOKEN:-${GH_TOKEN:-}}"
if [[ -z "$TOKEN" ]]; then
    CRED="$HOME/.workbuddy-ai/credentials/github-token.txt"
    [[ -f "$CRED" ]] && TOKEN="$(tr -d ' \r\n' < "$CRED")"
fi
[[ -n "$TOKEN" ]] || die "请先设置令牌：export GITHUB_TOKEN=<你的 GitHub 令牌>"

# ---------------------------------------------------------------- 仓库
REMOTE="$(git remote get-url origin 2>/dev/null || true)"
[[ -n "$REMOTE" ]] || die "没有 origin 远端，先 git remote add origin <仓库地址>"
SLUG="$(printf '%s' "$REMOTE" \
        | sed -E 's#.*github\.com[:/]([^/]+/[^/]+?)(\.git)?/?$#\1#')"
[[ "$SLUG" == */* ]] || die "识别不出仓库路径，origin = $REMOTE"
say "仓库：$SLUG"

# ---------------------------------------------------------------- 工作区
# 只看「已跟踪文件」有没有被改过 —— 未跟踪的新文件（比如刚写好的笔记）
# 不影响发布的包和源码是否一致，所以不拦。
if [[ -n "$(git status --porcelain --untracked-files=no)" ]]; then
    warn "工作区有未提交的改动，先提交再发布（否则发出的包和源码对不上）："
    git status --short --untracked-files=no
    exit 1
fi

api() {
    curl -sS -H "Authorization: Bearer $TOKEN" \
             -H "Accept: application/vnd.github+json" \
             -H "X-GitHub-Api-Version: 2022-11-28" "$@"
}

# 远端是否已有这个 tag。
# 用 API 查而不是 git ls-remote：本机 github.com 不通时 git ls-remote 会**失败**，
# 而失败和「tag 不存在」在 shell 里长得一样，会被误判成「可以发布」。
tag_exists() {
    local code
    code="$(api -o /dev/null -w '%{http_code}' \
            "https://api.github.com/repos/$SLUG/git/ref/tags/$TAG" 2>/dev/null || true)"
    [[ "$code" == "200" ]]
}

# 把本地 tag 推到远端。github.com 不通时退回 Git Data API 建 ref。
#
# 快路径上跑 git push 有三个必须加的限制，否则会**卡死**（实测卡了 5 分钟以上）：
#   - GIT_TERMINAL_PROMPT=0 / GIT_ASKPASS=echo / credential.helper=
#     关掉凭据交互 —— 远端要认证时 Git Credential Manager 会弹窗等人点，
#     在脚本里就是永久挂起（进程列表里能看到 git-credential-helper-selector.exe）。
#   - timeout 25 兜底，连不上时不要无限等。
#
# 两个注意点：
#   1. 建 ref 前**必须**先有远端对象；这里推的是轻量 tag（ref 直接指向 commit），
#      所以 `git rev-parse` 拿到的就是 commit sha，不需要先建 tag 对象。
#   2. 绝不能依赖 `POST /releases` 自动建 tag —— 它会用 target_commitish
#      （默认是仓库默认分支 main）去建，那样 26.2 的 Release 会指到 1.21.11 的提交上。
push_tag() {
    if timeout 25 env GIT_TERMINAL_PROMPT=0 GIT_ASKPASS=echo \
            git -c credential.helper= push origin "$TAG" 2>/dev/null; then
        return 0
    fi
    local sha resp
    warn "git push 走不通（本机 github.com 通常被代理拦），改用 Git Data API 建 tag"
    sha="$(git rev-parse "$TAG^{commit}")"
    resp="$(api -X POST "https://api.github.com/repos/$SLUG/git/refs" \
            -d "{\"ref\":\"refs/tags/$TAG\",\"sha\":\"$sha\"}")"
    if ! printf '%s' "$resp" | grep -q '"ref"'; then
        die "通过 API 建 tag 失败，GitHub 返回：$resp"
    fi
    say "已通过 API 建 tag $TAG -> $sha"
}

# ---------------------------------------------------------------- tag 冲突检查
# 这是「不覆盖别的版本」的第一道闸门。
if tag_exists; then
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

# ---------------------------------------------------------------- JDK 检查
# 26.2 要 JDK 25（1.21.x 只要 21）。版本不够时 Gradle 会抛一堆看不懂的错，
# 所以这里先给一句人话。
if [[ -n "${JAVA_HOME:-}" && -x "${JAVA_HOME}/bin/java" ]]; then
    JAVA_MAJOR="$("${JAVA_HOME}/bin/java" -version 2>&1 \
        | sed -n 's/.*version "\([0-9]*\).*/\1/p' | head -1)"
    if [[ -n "$JAVA_MAJOR" && "$JAVA_MAJOR" -lt 25 ]]; then
        die "26.2 需要 JDK 25 或更高，当前 JAVA_HOME 是 JDK $JAVA_MAJOR（$JAVA_HOME）"
    fi
    say "JDK：$JAVA_MAJOR（$JAVA_HOME）"
else
    warn "没有设置 JAVA_HOME，将用 PATH 里的 java；26.2 需要 JDK 25 或更高"
fi

# ---------------------------------------------------------------- 构建
say "开始构建……"
if [[ -x ./gradlew ]]; then
    ./gradlew build
else
    ./gradlew.bat build
fi

[[ -f "$JAR" ]] || die "构建完成但找不到产物 $JAR"
say "产物：$JAR（$(du -h "$JAR" | cut -f1)）"

# 防止「文件名写着 +26.2、包里却是别的版本」这种错版
if command -v unzip >/dev/null 2>&1; then
    BUILT_VERSION="$(unzip -p "$JAR" fabric.mod.json \
        | grep -o '"version"[[:space:]]*:[[:space:]]*"[^"]*"' | head -1 \
        | sed -E 's/.*"([^"]*)"$/\1/')"
    if [[ -n "$BUILT_VERSION" && "$BUILT_VERSION" != "$FULL_VERSION" ]]; then
        die "jar 内 fabric.mod.json 的版本是 $BUILT_VERSION，与期望的 $FULL_VERSION 不一致"
    fi
    say "jar 内版本号校验通过：$BUILT_VERSION"
fi

# ---------------------------------------------------------------- --replace 清理
if [[ "$REPLACE" == true ]]; then
    REL_ID="$(api "https://api.github.com/repos/$SLUG/releases/tags/$TAG" \
              | sed -n 's/.*"id": *\([0-9]*\).*/\1/p' | head -1)"
    if [[ -n "${REL_ID:-}" ]]; then
        say "删除旧的 Release（id=$REL_ID）"
        api -X DELETE "https://api.github.com/repos/$SLUG/releases/$REL_ID" >/dev/null
    fi
    if tag_exists; then
        say "删除旧的远端 tag"
        api -X DELETE "https://api.github.com/repos/$SLUG/git/refs/tags/$TAG" >/dev/null
    fi
    git tag -d "$TAG" >/dev/null 2>&1 || true
fi

# ---------------------------------------------------------------- 打 tag
if ! git rev-parse -q --verify "refs/tags/$TAG" >/dev/null; then
    say "创建 tag $TAG"
    # 轻量 tag：ref 直接指向 commit。这样 API 建 ref 时 sha 和本地完全一致，
    # 不用去凑注释 tag 对象的 sha（消息末尾换行、tagger 时间差一点就对不上）。
    git tag "$TAG"
fi
say "推送 tag"
push_tag

# ---------------------------------------------------------------- 建 Release
# 正文里刻意不出现双引号，避免手工拼 JSON 时转义出错。
BODY="## CustomSplash $FULL_VERSION\\n\\n**Minecraft $MC_VERSION · Fabric**\\n\\n下载下面的 \`customsplash-$FULL_VERSION.jar\`，放进 \`.minecraft/mods/\` 即可。\\n\\n- 支持 PNG / JPG / GIF / MP4（H.264）\\n- 游戏内按 \`F8\` 打开设置界面\\n- 安装与使用说明见仓库首页 README\\n"

# target_commitish 显式写当前分支：多版本分支共用一个仓库时，
# 不写就会用默认分支（main），Release 会挂到另一个版本的提交上。
BRANCH="$(git rev-parse --abbrev-ref HEAD)"

say "创建 GitHub Release"
RESP="$(api -X POST "https://api.github.com/repos/$SLUG/releases" \
        -d "{\"tag_name\":\"$TAG\",\"name\":\"$TAG\",\"body\":\"$BODY\",\"target_commitish\":\"$BRANCH\",\"draft\":$DRAFT,\"prerelease\":$PRERELEASE}")"

REL_ID="$(printf '%s' "$RESP" | sed -n 's/.*"id": *\([0-9]*\).*/\1/p' | head -1)"
URL="$(printf '%s' "$RESP" | sed -n 's/.*"html_url": *"\([^"]*\)".*/\1/p' | head -1)"

if [[ -z "${REL_ID:-}" ]]; then
    die "创建 Release 失败，GitHub 返回：$RESP"
fi

# ---------------------------------------------------------------- 传附件
# 注意：附件必须传到 uploads.github.com。
# 传 api.github.com 会返回 302，而 curl 遇到 302 会把 POST 降级成 GET，
# 结果就是「命令没报错、附件却没传上去」。
upload_asset() {
    local file="$1" resp
    say "上传 $(basename "$file")"
    resp="$(api -X POST -H "Content-Type: application/octet-stream" \
            --data-binary "@$file" \
            "https://uploads.github.com/repos/$SLUG/releases/$REL_ID/assets?name=$(basename "$file")")"
    if ! printf '%s' "$resp" | grep -q '"browser_download_url"'; then
        die "上传 $(basename "$file") 失败，GitHub 返回：$resp"
    fi
}

upload_asset "$JAR"

echo
say "完成：$URL"
say "旧版本的 tag / Release / 附件都没有被改动。"
say "源码 zip / tar.gz 由 GitHub 在 Release 页面自动附带，无需上传。"
