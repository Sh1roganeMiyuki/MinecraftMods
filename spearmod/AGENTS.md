# Spear Mod 项目说明

## 项目目标

- Spear Mod —— Minecraft Fabric 小玩法模组。

## 环境事实

- Minecraft 1.21.1，Fabric
- Fabric Loader 0.20.1，Fabric API 0.116.17+1.21.1
- Fabric Loom 1.19.3，Gradle wrapper 已内置
- 游戏代码编译目标 Java 21；Gradle 本身跑在 JDK 25（Loom 1.19 的硬性要求）
- 本机没有系统 JDK，也没配 JAVA_HOME，JDK 在：`C:/Users/zhang/.jdks/jdk-25.0.5+8`
- 因此命令要这样跑：`JAVA_HOME="C:/Users/zhang/.jdks/jdk-25.0.5+8" ./gradlew build`
- 映射：Mojang 官方映射（`loom.officialMojangMappings()`），不是 Yarn
- 拆分源集：公共代码在 `src/main/java`，客户端专用代码在 `src/client/java`
- 模组 ID：`spearmod`；根包：`com.example.spearmod`
- 依赖不需要 `.maven-local`：Fabric 构件已在 `~/.gradle/caches/modules-2`，
  Minecraft 本体与映射在 `~/.gradle/caches/fabric-loom`，都是全局共享的

## 常用命令

- 编译打包：`JAVA_HOME="C:/Users/zhang/.jdks/jdk-25.0.5+8" ./gradlew build`
- 开发客户端：`JAVA_HOME="C:/Users/zhang/.jdks/jdk-25.0.5+8" ./gradlew runClient`
- 开发服务端：`JAVA_HOME="C:/Users/zhang/.jdks/jdk-25.0.5+8" ./gradlew runServer`
- 产物：`build/libs/spearmod-<version>.jar`

## 编码约定

- 只用 Fabric API 和 Mojang 映射下的官方类；禁止 Forge / NeoForge API
- 伤害、击退、附魔触发等玩法逻辑必须在服务端执行；客户端只负责渲染、粒子、音效
- 客户端专用类放 `src/client/java`，不要被服务端代码引用
- 注册在 `SpearMod.onInitialize` 中完成
- 资源路径必须与注册 ID 对齐：`assets/spearmod/...`、`data/spearmod/...`
- 不要修改 `build/`、`.gradle/`、`run/` 下的内容
- Mixin 清单在 `src/main/resources/spearmod.mixins.json`，类放 `com.example.spearmod.mixin`
- `build.gradle` 里的 `useLegacyMixinAp = true` **不能删**，否则不生成 refmap，
  开发环境正常但打包后 Mixin 全部失效

## 工作方式

- 一次只实现一个功能，改完立即 `./gradlew build`，编译通过再继续
- 不确定 API 时先核实，不要凭记忆猜类名或方法名。本机没有 Minecraft 源码 jar，
  用 javap 查 Loom 的 mapped jar：
  `C:/Users/zhang/.jdks/jdk-25.0.5+8/bin/javap.exe -cp <jar> <类全名>`，
  jar 在 `~/.gradle/caches/fabric-loom/minecraftMaven/net/minecraft/` 下的
  `minecraft-common-*`（客户端类在 `minecraft-clientonly-*`）
- 优先使用 Fabric API 事件；没有对应事件时才用 Mixin
- `build` 通过只代表编译打包成功，游戏内行为仍需 `runClient` / `runServer` 验证

## 多 agent 协作

- 本目录只属于 `spearmod`，不要改动其他 mod 的目录
- 不要改仓库根的 `.github/workflows/build.yml`：它会自动发现所有含
  `settings.gradle` 的一级子目录，新增 mod 无需登记
- 在自己的 worktree + 分支里工作，提交前先 `git pull --rebase origin main`