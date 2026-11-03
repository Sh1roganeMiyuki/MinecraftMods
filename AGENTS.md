# MinecraftMods 仓库说明

**Gradle 多模块 monorepo**：仓库根是一个 Gradle 项目，每个 mod 是它的一个子模块。
公共构建配置只有一份，在根 `build.gradle`；各 mod 目录里只有自己的代码。

```
MinecraftMods/
├── settings.gradle      自动发现子模块 + Loom 插件版本
├── build.gradle         所有 mod 的公共配置（Loom、依赖、仓库源、打包、发布）
├── gradle.properties    版本号、JDK 路径、group 前缀
├── gradlew, gradle/     全仓库唯一一套 wrapper
├── LICENSE              全仓库共享
├── .github/workflows/build.yml   CI，一条命令构建所有 mod
├── tools/new-mod.ps1    新建 mod 的脚手架
├── funmod/              子模块：门扇挥扫冲击
└── spearmod/            子模块：（待实现）
```

---

## 新建一个 mod（最常见操作，看这里就够）

在**仓库根**执行：

```powershell
.\tools\new-mod.ps1 -ModId gunmod -ClassName GunMod -Description "某种枪"
```

然后立刻可以构建：

```powershell
$env:JAVA_HOME = "C:/Users/zhang/.jdks/jdk-25.0.5+8"
./gradlew :gunmod:build
```

就这样。**不需要任何登记动作**：

- `settings.gradle` 自动发现带 `build.gradle` 的一级子目录
- CI 一条 `./gradlew build` 构建全部，也不用改
- 不需要 wrapper、`.gitignore`、`LICENSE`、`gradle.properties`，全是仓库级共享的

脚手架只生成 8 个文件：

```
gunmod/
├── build.gradle                     空的（但必须存在，settings.gradle 靠它识别子模块）
├── README.md
├── AGENTS.md
└── src/
    ├── main/java/com/example/gunmod/GunMod.java
    ├── main/resources/fabric.mod.json
    ├── main/resources/gunmod.mixins.json
    ├── main/resources/assets/gunmod/icon.png   占位图，记得换
    └── client/java/com/example/gunmod/client/GunModClient.java
```

参数说明：

| 参数 | 必需 | 说明 |
|---|---|---|
| `-ModId` | ✅ | 小写字母开头，只能含小写字母/数字/`_`/`-` |
| `-ClassName` | | 主类名。**建议显式给**，见下面的坑 |
| `-DisplayName` | | mod 显示名，默认 `<ClassName> Mod` |
| `-Description` | | 一句话描述，不能含双引号或反斜杠 |

`group` 自动派生成 `com.example.<modid>`（前缀取自根 `gradle.properties` 的 `mod_group_base`）。

---

## 构建命令

一律在**仓库根**执行，各 mod 目录里没有 gradlew：

```powershell
$env:JAVA_HOME = "C:/Users/zhang/.jdks/jdk-25.0.5+8"

./gradlew build                    # 构建所有 mod
./gradlew :funmod:build            # 只构建某一个
./gradlew :funmod:runClient        # 跑某个 mod 的开发客户端
./gradlew projects                 # 看当前发现了哪些子模块（3 秒）
```

产物在 `<mod>/build/libs/<mod>-0.1.0.jar`，不要用带 `-sources` 的那个。

---

## 已知的坑（都踩过，别再踩）

### 1. `useLegacyMixinAp = true` 不能删

在根 `build.gradle` 的 `subprojects { loom { mixin { ... } } }` 里。Loom 1.19 默认**不挂**
Mixin 注解处理器，缺了它就不生成 refmap：开发环境跑得好好，打包后 Mixin 在生产环境
（intermediary 映射）静默失效。

验证方式：`jar tf <mod>/build/libs/<mod>-0.1.0.jar` 里应该有 `<mod>-refmap.json`。

### 2. 仓库源的排序逻辑不能删

根 `build.gradle` 里有两段看似多余的 `repositories.remove/add`：

- 把 Loom 自动注册的官方 `Fabric` 源压到**队尾**，并限制它只提供 `net.fabricmc*`
- 把 `.maven-local` 引导仓库提到**最前**

原因是本机访问 `maven.fabricmc.net` 极不稳定，几 KB 的 POM 都会 Read timed out。
删掉这两段，构建就会去联网拉本地已有的构件，然后卡十几分钟失败。

### 3. configuration cache 下不能在闭包里引用 `project`

`processResources` 和 `jar` 里必须先把值取到局部变量：

```gradle
processResources {
    def modVersion = project.version.toString()   // 先取出来
    filesMatching('fabric.mod.json') {
        expand "version": modVersion              // 闭包只捕获局部变量
    }
}
```

直接在 `filesMatching { }` 里写 `project.version` 会报
`Invocation of 'project' references a Gradle script object from a Groovy closure at execution time`。

### 4. Windows 上改 Java 类名的大小写很麻烦

`Spearmod.java` → `SpearMod.java` 这种重命名：文件系统大小写不敏感，编辑工具会认为
内容没变而拒绝写入；git 默认 `core.ignorecase=true` 看不见重命名；而 Java 要求 public
类名与文件名严格一致，对不上直接编译失败。**类名在跑脚手架时就用 `-ClassName` 定好。**

### 5. `.ps1` 必须存成 UTF-8 with BOM

PowerShell 5.1 读无 BOM 的 UTF-8 脚本时按 GBK 解析，中文全变乱码，语法检查直接炸。
但脚本**生成**的文件反过来要无 BOM（Gradle/Java 期望），所以脚本内部用
`New-Object System.Text.UTF8Encoding($false)`。

### 6. CI 上要覆盖 `org.gradle.java.home`

根 `gradle.properties` 里写的是本机 JDK 路径，runner 上不存在。
workflow 已经统一加了 `-Dorg.gradle.java.home="$JAVA_HOME"`。

### 7. 本机 git 访问 github.com 需要走代理

已配全局：`http.https://github.com.proxy = http://127.0.0.1:7900`。
代理软件关掉后 push 会失败，届时 `git config --global --unset http.https://github.com.proxy`。

---

## 环境事实

- Minecraft **1.21.1**，Fabric，Mojang 官方映射（不是 Yarn）
- Fabric Loader 0.20.1，Fabric API 0.116.17+1.21.1，Loom 1.19.3，Gradle 9.8.2
- 游戏代码编译目标 **Java 21**；Gradle 本身跑在 **JDK 25**（Loom 1.19 的硬性要求）
- 本机没有系统 JDK，也没配 `JAVA_HOME`，JDK 在 `C:/Users/zhang/.jdks/jdk-25.0.5+8`
- shell 是 PowerShell 5.1，**不支持 `&&`**，用 `;`
- 拆分源集：公共代码在 `src/main/java`，客户端专用代码在 `src/client/java`

### 升级版本改哪里

| 要改什么 | 改哪个文件 |
|---|---|
| Minecraft / Loader / Fabric API 版本 | `gradle.properties` |
| Loom 插件版本 | `settings.gradle` 的 `pluginManagement.plugins` |
| 所有 mod 的默认版本号 | `gradle.properties` 的 `version` |
| group 前缀 | `gradle.properties` 的 `mod_group_base` |
| JDK 路径 | `gradle.properties` 的 `org.gradle.java.home` |

都是一处改、全仓库生效。`plugins {}` 块不允许变量插值，所以 Loom 版本只能硬编码在
`settings.gradle`，这是 Gradle 的限制，不是遗漏。

### 全局共享的缓存

新 mod 的边际成本很低，重资产都在用户目录下全局共享：

| 位置 | 内容 | 大小 |
|---|---|---|
| `~/.gradle/caches/fabric-loom` | Minecraft 本体 + Mojang 映射 | ~234 MB |
| `~/.gradle/caches/modules-2` | 依赖构件，**已含 fabric-api** | ~77 MB |
| `./.gradle` | 本仓库的配置缓存 | 仓库级共享一份 |

所以同版本的新 mod **不用重新下载 Minecraft**，也不用重新映射。
`funmod/.maven-local`（约 20MB）是当初绕开网络问题时预取的引导仓库，
现在只是兜底，**不要删**，但新 mod 不需要它。

### 核实 Minecraft API

本机没有源码 jar，用 javap 查 Loom 的 mapped jar：

```powershell
& "C:/Users/zhang/.jdks/jdk-25.0.5+8/bin/javap.exe" -cp <jar> <类全名>
```

jar 在 `~/.gradle/caches/fabric-loom/minecraftMaven/net/minecraft/` 下的
`minecraft-common-*`（客户端类在 `minecraft-clientonly-*`）。

**不确定 API 时先查，不要凭记忆猜类名或方法名。** 加 `-c -p` 能看字节码，
用来确认方法之间的调用关系（比如某个方法到底会不会被另一条路径调用）。

---

## 编码约定（所有 mod 通用）

- 只用 Fabric API 和 Mojang 映射下的官方类；禁止 Forge / NeoForge API
- 伤害、击退、附魔触发等玩法逻辑必须在**服务端**执行；客户端只负责渲染、粒子、音效
- 客户端专用类放 `src/client/java`，不要被服务端代码引用
- 注册在各 mod 的 `<MainClass>.onInitialize` 中完成
- 资源路径必须与注册 ID 对齐：`assets/<modid>/...`、`data/<modid>/...`
- 不要修改 `build/`、`.gradle/`、`run/` 下的内容
- 优先使用 Fabric API 事件；没有对应事件时才用 Mixin
- Mixin 类放 `<rootpackage>.mixin`，清单在 `src/main/resources/<modid>.mixins.json`
- 需要某个 mod 独有的依赖或仓库源，写进**该 mod 自己的** `build.gradle`，不要改根配置
- `./gradlew build` 通过只代表编译打包成功，游戏内行为仍需 `runClient` / `runServer` 验证

---

## 工作方式

- 一次只实现一个功能，改完立即 `./gradlew :<modid>:build`，编译通过再继续
- 做完一个功能就提交，commit message 说清"为什么这么做"，不只是"改了什么"
- 临时诊断文件（查 API 的输出、构建日志）用 `.` 开头命名，根 `.gitignore` 已经忽略它们

---

## 附：如果以后要多个 agent 并行

现在是一个人顺序做，不需要下面这些。真要并行时再看：

- `.git/index.lock` 是仓库级独占的，两个 agent 同时 `commit` 必有一个失败，
  跟改的是不是同一个文件无关。要用 `git worktree add ..\wt-xxx -b feat/xxx` 各开一个工作区
- 各 mod 只改自己的目录；**不要改根 `build.gradle` / `settings.gradle` / CI**，
  那是所有分支的公共冲突点
- 多模块共享一个 Gradle daemon（`-Xmx3G`），多个 worktree 同时构建会各自起 daemon，注意内存
- 多 agent 共用一个终端会话时 PowerShell 输出会滞后串台，结果重定向到文件再读更可靠
