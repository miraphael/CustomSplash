# 发布流程

这份文档说明 CustomSplash 怎么发新版本，以及**为什么不会覆盖旧版本**。

---

## 核心原则：一个版本 = 一个 tag = 一个 Release

```
gradle.properties:
    mod_version       = 1.0.0
    minecraft_version = 1.21.11
            │
            ▼  拼成完整版本 1.0.0+1.21.11
   git tag v1.0.0+1.21.11
            │
            ▼
   GitHub Release "v1.0.0+1.21.11"
            │
            └── 附件 customsplash-1.0.0+1.21.11.jar
```

- **`mod_version` 只写模组自己的语义化版本，不要写 MC 版本**；
  MC 版本从 `minecraft_version` 取，两者由构建脚本自动拼起来，**只在一个地方维护**。
- **文件名和 tag 一律带目标 Minecraft 版本**，这样同时维护多个游戏版本时不会混淆，
  用户也不会下错版本。
- 发 1.0.1 时只会新建 `v1.0.1+1.21.11`，**完全不碰** `v1.0.0+1.21.11` 的
  tag、Release 和附件。
- **Release 只挂一个模组 jar，不上传 `-sources.jar`**：源码由 GitHub 在 Release 页面
  底部自动附带的 `v<tag>.zip` / `v<tag>.tar.gz` 提供，同样带版本号，功能上完全够用，
  没必要再多一个附件。因此 `build.gradle` 里**没有** `withSourcesJar()`，
  `release.sh` 也只传主 jar。

这样做的直接好处：用户点进 [Releases 页面](https://github.com/miraphael/CustomSplash/releases)
可以拿到最新版，也可以随时回退到任何一个历史版本，旧版本的下载链接永远不会失效。

---

## 发一个新版本

### 1. 改版本号

编辑 `gradle.properties`。**只改 `mod_version`，不要往里面写 MC 版本**：

```properties
mod_version=1.0.1
minecraft_version=1.21.11     # 目标游戏版本，换版本时才动
```

### 2. 提交

```bash
git add -A
git commit -m "发布 1.0.1"
git push
```

### 3. 跑发布脚本

```bash
./scripts/release.sh
```

（脚本会自动从 `~/.workbuddy-ai/credentials/github-token.txt` 读令牌，
也可以自己 `export GITHUB_TOKEN=...`。）

脚本会依次做这些事：

1. 从 `gradle.properties` 读出 `mod_version` 和 `minecraft_version`，
   拼出完整版本 `1.0.1+1.21.11` 和 tag `v1.0.1+1.21.11`
2. 检查工作区是否干净（有未提交的改动就直接退出，避免发出一个和源码对不上的包）
3. **检查这个 tag 是否已经存在** —— 存在就拒绝执行
4. `./gradlew build` 构建
5. **校验 jar 内 `fabric.mod.json` 的版本号和文件名一致**（防错版）
6. 打 tag 并推送
7. 创建 GitHub Release，上传 `customsplash-1.0.1+1.21.11.jar`
   （源码 zip / tar.gz 由 GitHub 自动附带，脚本不上传）
8. 打印 Release 地址

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

仓库里带了两个工作流，如果你给令牌补上 `workflow` 权限（或在网页上直接粘贴这两个文件），
就可以完全不用本地脚本：

| 文件 | 触发时机 | 做什么 |
|---|---|---|
| `.github/workflows/build.yml` | 每次 push / PR | 构建一遍，确认没写坏，产物作为 artifact 上传 |
| `.github/workflows/release.yml` | 推送 `v*` 形式的 tag | 构建 + 建 Release + 上传 jar |

用 Actions 的话，发版流程简化成：

```bash
git tag v1.0.1+1.21.11
git push origin v1.0.1+1.21.11     # 剩下的交给 CI
```

CI 用的 `GITHUB_TOKEN` 是 Actions 自带的，不需要自己配密钥。
`release.yml` 里也写了「tag 与 `gradle.properties` 的版本对不上就拒绝发布」的保护。

---

## 版本号怎么定

用的是语义化版本（`主版本.次版本.修订号`）：

| 情况 | 怎么升 |
|---|---|
| 修 bug、调性能 | `1.0.0` → `1.0.1` |
| 加新功能，但老配置还能用 | `1.0.0` → `1.1.0` |
| 配置格式变了、要玩家重新设置 | `1.0.0` → `2.0.0` |

### 如果以后要支持多个 Minecraft 版本

因为 26.x 的渲染层和 1.21.x 完全不同（见 [PORTING-26x.md](PORTING-26x.md)），
不可能共用一个 jar。真要做多版本时：

- **同一个仓库、不同分支**：`main` 跟 1.21.x，`mc-26.x` 跟 26.x。
- 两边可以都用 `mod_version=1.0.0`，因为 tag 会自动带上 MC 版本
  （`v1.0.0+1.21.11` 和 `v1.0.0+26.3`），**不会撞车，各自的 Release 互不覆盖**。
- 产物名同理：`customsplash-1.0.0+1.21.11.jar` 和 `customsplash-1.0.0+26.3.jar`，
  用户一眼就能看出该下哪个。

**这就是文件名带 MC 版本的意义** —— 不做这件事的话，
两个分支的 `mod_version` 一旦相同，附件名就会重名，很容易发错包。
