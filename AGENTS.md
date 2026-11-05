# MinecraftMods 仓库说明

**Gradle 多模块 monorepo**：仓库根是一个 Gradle 项目，每个 mod 是它的一个子模块。
公共构建配置只有一份，在根 `build.gradle`；各 mod 目录里只有自己的代码。

```
MinecraftMods/
├── settings.gradle      自动发现子模块 + Loom 插件版本
├── build.gradle         所有 mod 的公共配置（Loom、依赖、仓库源、打包、发布）
├── gradle.properties    版本号、JDK 路径、group 前缀
├── gradlew, gradle/     全仓库唯一一套 wrapper
├── gradle/config-gui.gradle   共享脚本：一行给 mod 接上配置界面
├── libs/maven/          内置的第三方 mod 库（MidnightLib、Mod Menu），详见 libs/README.md
├── LICENSE              全仓库共享
├── .github/workflows/build.yml   CI，一条命令构建所有 mod
├── tools/new-mod.ps1    新建 mod 的脚手架
├── impactengine/        **共享库**：撞击与破坏结算引擎，给其他 mod 依赖
├── funmod/              子模块：门扇挥扫冲击
└── spearmod/            子模块：长枪（骨架，依赖 impactengine）
```

---

## 共享库模块

目前只有一个：

| 模块 | 作用 | 详细文档 |
|---|---|---|
| `impactengine` | 通用的物理撞击与破坏结算引擎（伤害、击退、方块破坏、粒子音效、附魔衔接） | [`impactengine/AGENTS.md`](impactengine/AGENTS.md) |

它本身是一个**真 mod**（有自己的 `fabric.mod.json` 和 jar），不是普通 library。
这么做是因为它里面有静态状态（数据包加载的配置表），如果用 jar-in-jar 嵌到每个 mod 里，
每个 mod 的 classloader 会各持一份，同一份 JSON 被加载多遍、配置表彼此看不见。

**想用引擎的 mod 要做两件事：**

```gradle
// <你的 mod>/build.gradle
// 注意：必须在 dependencies 块外先拿 Project 对象，块内的 project(...)
// 返回的是 ProjectDependency，上面没有 sourceSets 属性
def impactEngine = project(':impactengine')

dependencies {
	// 不能写 modImplementation project(':impactengine')，会死锁，原因见下面「已知的坑」第 8 条
	implementation project(path: ':impactengine', configuration: 'namedElements')
	clientImplementation impactEngine.sourceSets.client.output
}
```

```json
// <你的 mod>/src/main/resources/fabric.mod.json
"depends": {
	"fabricloader": ">=0.20.1",
	"minecraft": "~1.21.1",
	"java": ">=21",
	"fabric-api": "*",
	"impactengine": "*"
}
```

第二件容易漏：`implementation` 只管编译期，**运行时靠 `fabric.mod.json` 的 `depends`**。
漏了它，开发环境跑得好好，发给朋友就报缺失依赖。

联机时每个人要装两个 jar（你的 mod + impactengine）。

---

## 游戏内配置界面（每个 mod 都有）

用的是 **MidnightConfig**（MidnightLib）+ **Mod Menu**。入口：游戏里「模组列表 → 某个 mod → 配置」。
拖滑条、点开关，按「完成」就存盘并**立即生效**，不需 reload、不需重启。

### 给一个 mod 接上配置界面

脚手架生成的 mod **已经接好了**。手动接也就四步：

1. `<mod>/build.gradle` 加一行：`apply from: rootProject.file('gradle/config-gui.gradle')`
2. 建一个 `public class XxxConfig extends MidnightConfig`，字段写 `@Entry public static volatile`
3. 主类 `onInitialize` 里调 `MidnightConfig.register(MOD_ID, XxxConfig.class)`
4. `assets/<modid>/lang/zh_cn.json` 补翻译键

**不需要写任何 Mod Menu 胶水代码。** MidnightLib 自己的 `fabric.mod.json` 就声明了
`modmenu` 入口点（`MidnightLib$ModMenuInit`），它会遍历 `MidnightConfig.configInstances`，
给每个调过 `register()` 的 mod 自动挂上配置界面工厂。这一点是从字节码里确认的，
不是推测——`getProvidedConfigScreenFactories()` 里直接 `configInstances.forEach(...)`。

### 三条硬规矩

**1. 一个 mod 只能有一个配置类。** `configInstances` 按 modid 索引，
同一个 modid 注册第二个会顶掉第一个。加新功能就往现有配置类里加字段，
用 `@Entry(category = "xxx")` 分新的标签页。

**2. 没代码读的字段不要加。** 界面上拖了什么都不会发生，比没这个滑条更让人困惑。
脚手架生成的模板里只放了一个 `enabled` 总开关，而那个开关主类真的会读。

**3. `register()` 必须在任何“如果配置关了就 return”之前。**
否则一旦在配置里把 mod 关掉，下次启动就再也打不开配置界面、也没法开回来。

### 翻译键格式

从 MidnightLib 字节码的常量池里挖出来的，写错不会报错，但界面会直接显示键名：

| 用途 | 键 |
|---|---|
| 界面标题 | `<modid>.midnightconfig.title` |
| 条目名 | `<modid>.midnightconfig.<字段名>` |
| 悬浮说明 | `<modid>.midnightconfig.<字段名>.tooltip` |
| 分类标签页 | `<modid>.midnightconfig.category.<分类名>` |
| 枚举取值 | `<modid>.midnightconfig.enum.<枚举类简名>.<常量名>` |

最后一条容易踩：第二段是 `dataType.getSimpleName()`，也就是**枚举类名**而不是字段名。
枚举渲染成循环切换按钮，比用字符串安全（写错值不可能），所以优先用枚举。

只提供 `zh_cn.json`。要用英文界面就再补一份 `en_us.json`，键名完全一样。

### 引擎的全局调参层（ImpactTuning）

`impactengine` 里有一份特殊配置：它不是某把武器的参数，而是**乘在所有撞击效果上的全局倍率**。
因为它作用在 `ImpactEngine.execute()` 内部，所以任何 mod、任何 JSON、任何用 Builder
现场构造的 `ImpactEffect` 都自动受它影响——**新 mod 不需要做任何接入工作就能被调参**。

刻意只提供倍率和开关、不提供绝对值：一份 JSON 对应一种武器，全局滑条要是给绝对值，
调一次就把长枪和爆破箭压成同一个数了。各武器之间的相对强弱属于 JSON，整体强弱属于调参层。

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

### 8. 跨子模块依赖另一个 mod 必须用 `namedElements`

`modImplementation project(':impactengine')` 会**死锁**：Loom 在配置阶段就要读对方的
 remapped jar 来解析 `fabric.mod.json`，可那时 jar 还没构建出来，报
`Failed to read metadata from .../impactengine-0.1.0.jar`。

而且没得逃：单独跑 `./gradlew :impactengine:build` 也一样失败，因为根 `build.gradle`
的 `subprojects {}` 会配置所有子项目，`--configure-on-demand` 对此无效。

正确写法（Loom 官方文档 "Depending on Remapped Subprojects"）：

```gradle
def impactEngine = project(':impactengine')   // 必须在 dependencies 块外

dependencies {
	implementation project(path: ':impactengine', configuration: 'namedElements')
	clientImplementation impactEngine.sourceSets.client.output
}
```

`namedElements` 暴露的是未 remap 的输出，配置阶段不需要 jar 存在，死锁就绕开了。
第二行是因为开了 `splitEnvironmentSourceSets()`，不接对方的 client 源集就引用不到它的客户端类。

还有个容易漏的：`implementation` 只管编译期，**运行时要靠 `fabric.mod.json` 里的
`"impactengine": "*"` 声明**。漏了它开发环境跑得好好，发给朋友就报缺失依赖。

---

### 9. Modrinth 的 maven 对本项目完全不可用，第三方 mod 库要内置

想拉 MidnightLib 时会发现 `api.modrinth.com/maven` 对所有坐标都返回 404：

```
ERR 404  /maven/mavenmods/midnightlib/maven-metadata.xml
ERR 404  /maven/mavenmods/midnightlib/1.10.0/midnightlib-1.10.0.jar
ERR 404  /maven/mavenmods/fabric-api/maven-metadata.xml   ← 对照组，同样 404
```

连 `fabric-api` 这种主流项目都 404，说明不是坐标写错，是这个端点本身不通。
但 Modrinth 的 **API 和 CDN 是通的**，所以做法是：用 API 查版本 → 从 CDN 下 jar →
按 Maven 目录布局放进 `libs/maven/`，手写一份最小 POM。具体步骤和坐标表在 `libs/README.md`。

两个注意项：

- POM 里**不要声明依赖**。MidnightLib 原本依赖 `fabric-resource-loader-v0`，
  写进去会让 Gradle 试图从这个本地仓库解析 Fabric 构件而失败（fabric-api 已经提供了它）
- 版本号里**不要带 `+`**（别写 `1.10.0+1.21.1`）。`+` 在 URL 里要转义成 `%2B`，
  Gradle 解析本地文件仓库时容易对不上路径

`maven.terraformersmc.com`（Mod Menu）是通的，但同样内置了一份，让构建和 CI 都不依赖
任何第三方 maven 在线。`maven.fabricmc.net` 在这台机器上本来就不稳定，再挂两个不稳定源
只会让“能不能编译”变成看运气。

### 10. 静态字段初始化顺序（已经踩过三次）

`static final` 字段按**声明顺序**初始化，引用到还没初始化的字段会拿到 `null`。
这个错编译期、CI 全都报不出来，只会在运行时炸。本项目已经因此崩过三次：

1. `INSTANCE` 声明在 `GSON` 前面，`super(GSON, ...)` 拿到 null，进世界就 NPE
2. Codec 里用 `xmap` 把 `Optional.empty()` 转成 `null`，而 DFU 的 `DataResult.Success`
   内部用 `Optional.of()` 包结果，字段为 null 直接 NPE（可空字段要用 `Optional` 当 record 组件）
3. `PARTICLE_CODEC` 声明在 `CODEC` 后面，而 `CODEC` 会间接读到它，`ExceptionInInitializerError`

写带静态字段的类时，把被依赖的字段（尤其是各种 Codec）**声明在前面**，
或者直接在构造器/`super()` 里现场构造，从根上消除顺序依赖。

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
