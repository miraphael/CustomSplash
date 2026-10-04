# 发布流程

这份文档说明 CustomSplash 怎么发新版本，以及**为什么不会覆盖旧版本**。

---

## 核心原则：一个版本 = 一个 tag = 一个 Release

```
gradle.properties 的 mod_version = 1.0.0
        │
        ▼
   git tag v1.0.0  ──►  GitHub Release "v1.0.0"  ──►  附件 customsplash-1.0.0.jar
```

- 版本号只从 `gradle.properties` 的 `mod_version` 读，**只在一个地方维护**。
- tag 名固定是 `v` + 版本号，Release 名和附件名都由它推导。
- 发 1.0.1 时只会新建 `v1.0.1`，**完全不碰** `v1.0.0` 的 tag、Release 和附件。

这样做的直接好处：用户点进 [Releases 页面](https://github.com/miraphael/CustomSplash/releases)
可以拿到最新版，也可以随时回退到任何一个历史版本，旧版本的下载链接永远不会失效。

---

## 发一个新版本

### 1. 改版本号

编辑 `gradle.properties`：

```properties
mod_version=1.0.1
```

### 2. 提交

```bash
git add -A
git commit -m "发布 1.0.1"
git push
```

### 3. 跑发布脚本

```bash
export GITHUB_TOKEN=<你的令牌>
./scripts/release.sh
```

脚本会依次做这些事：

1. 检查工作区是否干净（有未提交的改动就直接退出，避免发出一个和源码对不上的包）
2. 从 `gradle.properties` 读出 `mod_version`，拼出 tag `v1.0.1`
3. **检查这个 tag 是否已经存在** —— 存在就拒绝执行
4. `./gradlew build` 构建
5. 打 tag 并推送
6. 创建 GitHub Release，把 `build/libs/customsplash-1.0.1.jar` 传上去
7. 打印 Release 地址

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

发布脚本需要 GitHub 令牌，从环境变量读：

```bash
export GITHUB_TOKEN=ghp_xxxxxxxx
```

令牌需要的权限：

| 用途 | 所需权限 |
|---|---|
| 建仓库、推代码、建 Release | `repo` |
| 推送 `.github/workflows/` 下的自动构建配置 | 额外需要 `workflow` |

> 令牌**不要**写进任何文件、也不要提交到仓库。脚本只从环境变量读。
> 建议给它设一个到期时间，泄漏了随时在 GitHub 设置里撤销。

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
git tag v1.0.1
git push origin v1.0.1     # 剩下的交给 CI
```

CI 用的 `GITHUB_TOKEN` 是 Actions 自带的，不需要自己配密钥。
`release.yml` 里也写了「tag 已存在则不重复发布」的保护。

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
不可能共用一个 jar。真要做多版本时建议：

- **同一个仓库、不同分支**：`main` 跟 1.21.x，`mc-26.x` 跟 26.x。
- **tag 里带上游戏版本**：`v1.0.0+1.21.11`、`v1.0.0+26.3`。
  这样即使两个分支的 `mod_version` 一样，tag 也不会撞车，**各自的 Release 互不覆盖**。
- Release 正文里写清楚支持哪个游戏版本。
