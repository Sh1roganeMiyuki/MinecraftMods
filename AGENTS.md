# MinecraftMods 仓库说明

这是一个 **monorepo**：每个 mod 是一个**完全独立的 Gradle 项目**，占据一个一级子目录。

```
MinecraftMods/
├── .github/workflows/build.yml   CI，自动发现所有 mod，新增 mod 不需要改
├── tools/new-mod.ps1             一键生成新 mod 的脚手架
├── funmod/                       独立项目：门扇挥扫等小玩法
└── <其他 mod>/                    独立项目
```

## 新建一个 mod

```powershell
.\tools\new-mod.ps1 -ModId spearmod -Description "长枪武器与相关附魔"
```

脚手架会从 `funmod` 继承版本号、JDK 路径和 Gradle wrapper，生成可直接 `./gradlew build` 的项目，
并带上 `useLegacyMixinAp = true`（Loom 1.19 的坑，见下）。详见脚本头部的注释。

主类名默认从 ModId 帕斯卡化推导，`spearmod` 会得到 `Spearmod`。想要 `SpearMod` 就显式传：

```powershell
.\tools\new-mod.ps1 -ModId spearmod -ClassName SpearMod
```

**类名要在生成时就定好，不要事后再改大小写。** Windows 文件系统大小写不敏感，
`Spearmod.java` → `SpearMod.java` 这种重命名会撞上一连串麻烦：编辑工具认为内容没变而拒绝写入，
git 默认 `core.ignorecase=true` 看不见重命名，而 Java 要求 public 类名与文件名严格一致，
对不上就直接编译失败。真要改，得先删文件再按新大小写重建。

新 mod **不需要**任何登记动作：CI 靠"一级子目录里有没有 `settings.gradle`"来发现项目。

## 全局共享的缓存（重要）

新增 mod 的边际成本很低，因为重资产都在用户目录下全局共享：

| 位置 | 内容 | 大小 |
|---|---|---|
| `~/.gradle/caches/fabric-loom` | Minecraft 本体 + Mojang 映射 | ~234 MB |
| `~/.gradle/caches/modules-2` | 依赖构件，**已含 fabric-api** | ~77 MB |
| `<mod>/.gradle` | 项目级配置缓存 | ~64 MB，各自独立 |

所以：

- 同版本（1.21.1 + Mojang 映射）的新 mod **不用重新下载 Minecraft**，也不用重新映射
- 新 mod **不需要** `.maven-local`。`funmod/.maven-local` 是当初绕开 `maven.fabricmc.net`
  不稳定时做的引导仓库，它的构件现在已经进了全局 `modules-2`
- 真要离线引导，脚手架加 `-WithBootstrap` 会复制一份（约 20MB）
- 多个 mod 同时编译时，Gradle 会用文件锁保护全局缓存，**会互相等待但不会损坏**

## 多 agent 并行开发

### 用 worktree，不要多个 agent 挤同一个目录

`.git/index.lock` 是仓库级独占的，两个 agent 同时 `git add`/`commit` 必然有一个失败，
跟改的是不是同一个文件无关。正确做法是每个 agent 一个 worktree + 一个分支：

```powershell
# 在主仓库里为某个 mod 开一个工作区
git worktree add ..\wt-spear -b feat/spear
cd ..\wt-spear
```

各 worktree 有独立的 index 和物理目录，互不干扰，最后合回 `main`。

管理：

```powershell
git worktree list          # 看当前有哪些
git worktree remove ..\wt-spear
```

### 硬性规则

- **只改自己 mod 的目录**。跨 mod 的公共改动要单独开分支，不要混在功能分支里
- **不要改 `.github/workflows/build.yml`**。它是自动发现的，改了就是给所有分支制造冲突
- **不要改 `tools/new-mod.ps1`**，除非这次任务就是改脚手架
- 提交前先 `git pull --rebase origin main`
- 各 mod 的 `AGENTS.md` 是该 mod 自己的约定，进某个 mod 目录前先读它

### 并行的真实成本

- 每个 Gradle daemon 吃 `-Xmx2G`，三个 mod 同时编译就是 6GB+，还没算 Loom 的映射开销
- 多个 agent 共享同一个终端会话时，PowerShell 的输出会滞后、串台。
  需要看命令结果时，**重定向到文件再读**比直接看 stdout 可靠得多
- 首次为新 mod 跑 Loom 时如果多个 agent 同时触发，会争用 `fabric-loom` 缓存锁，很慢。
  错开第一次构建，之后的增量构建就不冲突了

## 各 mod 通用的坑

- **`useLegacyMixinAp = true` 不能删**。Loom 1.19 默认不挂 Mixin 注解处理器，
  缺了它不生成 refmap：开发环境跑得好好，打包后 Mixin 在生产环境（intermediary）静默失效。
  验证方式：`jar tf build/libs/<mod>-0.1.0.jar` 里应该有 `<mod>-refmap.json`
- **CI 上要覆盖 `org.gradle.java.home`**。各 mod 的 `gradle.properties` 里写的是本机 JDK 路径，
  runner 上不存在。workflow 已经统一加了 `-Dorg.gradle.java.home="$JAVA_HOME"`
- **本机 git 访问 github.com 需要走代理**，已配全局：
  `http.https://github.com.proxy = http://127.0.0.1:7900`。
  代理软件关掉后 push 会失败，届时 `git config --global --unset http.https://github.com.proxy`

## 环境与命令

- Windows，shell 是 PowerShell 5.1（不支持 `&&`，用 `;`）
- 本机没有系统 JDK，也没配 `JAVA_HOME`，JDK 25 在 `C:/Users/zhang/.jdks/jdk-25.0.5+8`
- 构建某个 mod：
  ```powershell
  cd <mod>
  $env:JAVA_HOME = "C:/Users/zhang/.jdks/jdk-25.0.5+8"; ./gradlew build
  ```
- PowerShell 5.1 读 `.ps1` 时，**UTF-8 无 BOM 的中文会按 GBK 解析成乱码**。
  仓库里的 `.ps1` 一律存成 UTF-8 with BOM
- 核实 Minecraft API 用 javap 查 Loom 的 mapped jar（本机没有源码 jar）：
  ```powershell
  & "C:/Users/zhang/.jdks/jdk-25.0.5+8/bin/javap.exe" -cp <jar> <类全名>
  ```
  jar 在 `~/.gradle/caches/fabric-loom/minecraftMaven/net/minecraft/` 下的
  `minecraft-common-*`，客户端类在 `minecraft-clientonly-*`
