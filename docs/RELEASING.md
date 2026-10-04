# 发布流程

这份文档说明 CustomSplash 怎么发新版本，以及**为什么不会覆盖旧版本**。

> **本文档对应 Minecraft 26.2 这条分支（`mc26`）。**
> 1.21.11 那条分支是 `main`，发布流程完全一样，只是工具链不同（JDK 21 + Gradle 8）。

---

## 核心原则：一个版本 = 一个 tag = 一个 Release

```
gradle.properties:
    mod_version       = 1.0.0
    minecraft_version = 26.2
            │
            ▼  拼成完整版本 1.0.0+26.2
   git tag v1.0.0+26.2
            │
            ▼
   GitHub Release "v1.0.0+26.2"
            │
            └── 附件 customsplash-1.0.0+26.2.jar
```

- **`mod_version` 只写模组自己的语义化版本，不要写 MC 版本**；
  MC 版本从 `minecraft_version` 取，两者由构建脚本自动拼起来，**只在一个地方维护**。
- **文件名和 tag 一律带目标 Minecraft 版本**，这样同时维护多个游戏版本时不会混淆，
  用户也不会下错版本。
- 发 1.0.1 时只会新建 `v1.0.1+26.2`，**完全不碰** `v1.0.0+26.2` 的
  tag、Release 和附件。
- **Release 只挂一个模组 jar，不上传 `-sources.jar`**：源码由 GitHub 在 Release 页面
  底部自动附带的 `v<tag>.zip` / `v<tag>.tar.gz` 提供，同样带版本号，功能上完全够用，
  没必要再多一个附件。因此 `build.gradle` 里**没有** `withSourcesJar()`，
  `release.sh` 也只传主 jar。
- **同一个仓库里两个版本线互不干扰**：`main` 上的 `v1.0.0+1.21.11` 和
  `mc26` 上的 `v1.0.0+26.2` 是**两个不同的 tag**，各自的 Release、各自的附件，
  谁也覆盖不了谁。这就是 tag / 文件名带 MC 版本的意义。

这样做的直接好处：用户点进 [Releases 页面](https://github.com/miraphael/CustomSplash/releases)
可以拿到最新版，也可以随时回退到任何一个历史版本，旧版本的下载链接永远不会失效。

---

## 发一个新版本

### 0. 先确认工具链

26.2 需要 **JDK 25**（1.21.x 只要 21）。没设 `JAVA_HOME` 时脚本会用 `PATH` 里的 java，
版本不够会在 Gradle 配置阶段报一堆看不懂的错，所以脚本会先检查并给一句人话：

```bash
export JAVA_HOME="C:/mcdev/tools/jdk25.0.1"     # 按本机实际路径改
```

### 1. 改版本号

编辑 `gradle.properties`。**只改 `mod_version`，不要往里面写 MC 版本**：

```properties
mod_version=1.0.1
minecraft_version=26.2     # 目标游戏版本，换版本时才动
```

### 2. 提交

```bash
git add -A
git commit -m "发布 1.0.1"
```

> 提交后记得把分支推上去。本机 `github.com` 主站通常被代理拦（`git push` 报
> `CONNECT tunnel failed`），用技能里的 `push-via-api.py` 走 Git Data API 推，
> sha 和本地完全一致。

### 3. 跑发布脚本

```bash
./scripts/release.sh
```

（脚本会自动从 `~/.workbuddy-ai/credentials/github-token.txt` 读令牌，
也可以自己 `export GITHUB_TOKEN=...`。）

脚本会依次做这些事：

1. 从 `gradle.properties` 读出 `mod_version` 和 `minecraft_version`，
   拼出完整版本 `1.0.1+26.2` 和 tag `v1.0.1+26.2`
2. 检查工作区是否干净（有未提交的改动就直接退出，避免发出一个和源码对不上的包）
3. **检查这个 tag 是否已经存在**（走 API 查，见下面「为什么要用 API」）—— 存在就拒绝执行
4. 检查 JDK 版本是否 ≥ 25
5. `./gradlew build` 构建
6. **校验 jar 内 `fabric.mod.json` 的版本号和文件名一致**（防错版）
7. 打 tag 并推送
8. 创建 GitHub Release（显式带 `target_commitish`），上传 `customsplash-1.0.1+26.2.jar`
   （源码 zip / tar.gz 由 GitHub 自动附带，脚本不上传）
9. 打印 Release 地址

### 为什么要用 API

本机 `github.com` 主站被代理拦，`git ls-remote` / `git push` 都会失败。麻烦的是
**「命令失败」和「tag 不存在」在 shell 里长得一样** —— 用 `git ls-remote` 判断
「这个版本发过没有」会得到假阴性，进而覆盖已发布的版本。所以脚本里：

- tag 存在性检查走 `api.github.com`（不通的时候才是真的不通，语义明确）；
- 推 tag 先试 `git push`，失败就退回 `POST /git/refs` 建 ref；
- 建 Release 时**显式传 `target_commitish`**，否则 GitHub 会用仓库默认分支（`main`）
  去建 tag，26.2 的 Release 就挂到 1.21.11 的提交上了。

### 可选参数

| 参数 | 作用 |
|---|---|
| `--draft` | 建草稿 Release，不公开，先在网页上检查一遍再手动发布 |
| `--prerelease` | 标记为预发布（beta / rc） |
| `--replace` | **仅当**该 tag 的 Release 已存在时，删除并重建**这一个**版本 |

> `--replace` 只影响你指定的那个版本。它不会删除、也不会修改任何其它 tag 或 Release。
> 平时不需要用它 —— 正常发版走上面三步就够了。

---

## 令牌权限

发布脚本按下面的顺序找令牌：

1. 环境变量 `GITHUB_TOKEN` 或 `GH_TOKEN`
2. 本机凭据文件 `~/.workbuddy-ai/credentials/github-token.txt`（权限 `600`）

```bash
export GITHUB_TOKEN=ghp_xxxxxxxx     # 方式一
```

令牌需要的权限：

| 用途 | 所需权限 |
|---|---|
| 建仓库、推代码、建 Release | `repo` |
| 推送 `.github/workflows/` 下的自动构建配置 | 额外需要 `workflow` |

> 令牌**不要**提交到仓库（`~/.workbuddy-ai/` 在仓库外面，不会被提交）。
> 建议给它设一个到期时间，一旦泄漏就去 GitHub 设置里撤销重发。

---

## 自动化方案（GitHub Actions）

`mc26` 分支上只带了**一个**工作流：

| 文件 | 触发时机 | 做什么 |
|---|---|---|
| `.github/workflows/build.yml` | 推送到 `mc26` / PR | 用 JDK 25 构建一遍，确认没写坏，产物作为 artifact 上传 |

> **为什么这条分支没有 `release.yml`：**
> `main`（1.21.11）上有一个「推 tag 就自动建 Release」的工作流。这条分支故意不放，
> 因为发布是本地 `release.sh` 做的 —— 如果 CI 也来建同一个 Release，
> 两个流程会**抢同一个附件**，最后挂上去的是 CI 在 Linux 上重新构建的那个 jar，
> 而不是本地已经实机验证过的那个。发布产物必须是「验证过的那一个」，
> 所以这里把发布入口收敛到唯一的脚本上。
>
> 想要 CI 自动发版的话，把 `main` 的 `release.yml` 拷过来、把 JDK 改成 25 即可，
> 但那时就别再跑本地脚本了。

---

## 版本号怎么定

用的是语义化版本（`主版本.次版本.修订号`）：

| 情况 | 怎么升 |
|---|---|
| 修 bug、调性能 | `1.0.0` → `1.0.1` |
| 加新功能，但老配置还能用 | `1.0.0` → `1.1.0` |
| 配置格式变了、要玩家重新设置 | `1.0.0` → `2.0.0` |

### 多 Minecraft 版本是怎么组织的（已实施）

26.x 的渲染层和 1.21.x 完全不同（见 [PORTING-26x.md](PORTING-26x.md)），
不可能共用一个 jar。现在的做法是：

| 分支 | 目标 | 工具链 | 产物 |
|---|---|---|---|
| `main` | Minecraft **1.21.11** | JDK 21 + Gradle 8 + Loom 1.13.6 | `customsplash-1.0.0+1.21.11.jar` |
| `mc26` | Minecraft **26.2** | JDK 25 + Gradle 9 + Loom 1.18.2 | `customsplash-1.0.0+26.2.jar` |

- 两边可以都用 `mod_version=1.0.0`，因为 tag 会自动带上 MC 版本
  （`v1.0.0+1.21.11` 和 `v1.0.0+26.2`），**不会撞车，各自的 Release 互不覆盖**。
- 产物名同理，用户一眼就能看出该下哪个。
- **两个分支的历史是独立的**（`mc26` 是从零新建的根提交），
  所以不存在「合并不上」的问题，也不需要互相 rebase。

> 以后要支持 26.3 时，从 `mc26` 开一条新分支即可，
> 只要 `minecraft_version` 和 `fabric_version` 跟着换。
