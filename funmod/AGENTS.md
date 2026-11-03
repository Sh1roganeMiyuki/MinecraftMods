# Fun Mod 项目说明

## 项目目标

- 受到 B 站 UP 主“老丹Daniel”的小玩法系列启发，为和朋友联机 PvP 制作小型、可独立测试的机制
- 已计划功能：门开关把实体打飞、长枪武器及相关附魔
- 每个功能保持小范围，不做整合包规模的内容

## 环境事实

- 工程位置：`D:/MinecraftMods/funmod`（2026-11-03 从 Qoder 工作区迁出）

- Minecraft 1.21.1，Fabric
- Fabric Loader 0.20.1，Fabric API 0.116.17+1.21.1
- Fabric Loom 1.19.3，Gradle 9.8.2（wrapper 已内置）
- 游戏代码编译目标 Java 21；Gradle 本身运行在 Temurin JDK 25（Loom 1.19 的硬性要求）
- 本机没有系统 JDK，也没有配置 JAVA_HOME。JDK 25 位于：
  `C:/Users/zhang/.jdks/jdk-25.0.5+8`
- Gradle 通过 `org.gradle.java.home` 使用它，但 `gradlew` 启动器仍需要 JAVA_HOME，因此命令要这样跑：
  `JAVA_HOME="C:/Users/zhang/.jdks/jdk-25.0.5+8" ./gradlew build`
- 映射：Mojang 官方映射（`loom.officialMojangMappings()`），不是 Yarn
- 拆分源集：公共代码在 `src/main/java`，客户端专用代码在 `src/client/java`
- 模组 ID：`funmod`；根包：`com.example.funmod`

## 常用命令

在项目根目录执行（注意 JAVA_HOME 前缀）：

- 编译并打包：`JAVA_HOME="C:/Users/zhang/.jdks/jdk-25.0.5+8" ./gradlew build`
- 启动开发客户端：`JAVA_HOME="C:/Users/zhang/.jdks/jdk-25.0.5+8" ./gradlew runClient`
- 启动开发服务端：`JAVA_HOME="C:/Users/zhang/.jdks/jdk-25.0.5+8" ./gradlew runServer`
- 产物：`build/libs/funmod-<version>.jar`（不要用带 `-sources` 的那个）

## 网络

- 本机访问 `maven.fabricmc.net` 极不稳定，几 KB 的 POM 都会卡住或 DNS 解析失败
- `settings.gradle` 和 `build.gradle` 中已加入第三方代理 `https://repository.hanbings.io/proxy`，Maven Central 依赖走阿里云
- `.maven-local/` 是本地引导仓库（被 `.gitignore` 忽略，约 21 MB），已预取 Fabric Loader 0.20.1、Intermediary 1.21.1（含 `-v2` 分类器）、Fabric API 0.116.17+1.21.1 本体及全部模块、Loom 的 `mercury-0.4.3`。**不要删除**
- `build.gradle` 末尾会把 `.maven-local/` 移到仓库列表最前，并把官方 Fabric 源移到队尾、限制为只查 `net.fabricmc*`。这是构建不访问官方 Fabric 源的关键，不要删
- 该代理仅用于拉取依赖；如果它失效，可改用 VPN，或手动把缺失构件补进 `.maven-local`
- 首次构建需要下载 Minecraft 依赖，耗时较长；超时后直接重试即可
- 2026-11-03 已验证 `./gradlew build` 成功，产物为 `build/libs/funmod-0.1.0.jar`

## 编码约定

- 只使用 Fabric API 和 Mojang 映射下的官方类；禁止 Forge / NeoForge API
- 伤害、击退、附魔触发等玩法逻辑必须在服务端执行；客户端只负责渲染、粒子、音效
- 客户端专用类放 `src/client/java`，不要被服务端代码引用
- 物品、方块、附魔等注册在 `FunMod.onInitialize` 中完成
- 资源路径必须与注册 ID 对齐：`assets/funmod/...`、`data/funmod/...`
- 不要修改 `build/`、`.gradle/`、`run/` 下的内容
- Mixin 已启用：清单在 `src/main/resources/funmod.mixins.json`，类放 `com.example.funmod.mixin`
- `build.gradle` 里的 `loom { mixin { useLegacyMixinAp = true } }` **不能删**。Loom 1.19 默认不挂 Mixin 注解处理器，缺了它就不生成 refmap，开发环境跑得好好、打包后 Mixin 全部失效

## 已实现功能

### 门扇挥扫冲击（`doorslam`）

门开/关时，把门当成绕门轴转 90° 的刚体，对扫过象限内的实体施加切线冲力 + 固定上抛。

- **注入点是 `DoorBlock.playSound`（private），不是 `setOpen`**
  - 1.21.1 里 `setOpen` 只被生物开门（村民、铁傀儡）调用
  - 玩家右键走 `useWithoutItem`、红石走 `neighborChanged`，两者都直接 `setBlock`，压根不经过 `setOpen`
  - `playSound(Entity, Level, BlockPos, boolean)` 是三条路径唯一的汇聚点，第 4 个参数就是变更后的开关状态
- 红石会让门上下两半各触发一次 `playSound`，`DoorSlam.claimOnce` 按 `(维度, 下半坐标, gameTime)` 去重，避免冲力翻倍
- 客户端为了交互预测也会跑 `useWithoutItem`，所以入口第一件事是 `level.isClientSide` 直接返回
- 参数在 `config/funmod/door-slam.json`，缺文件会自动写一份完整模板

## 工作方式

- 一次只实现一个功能，改完立即执行 `./gradlew build`，编译通过再继续
- 不确定 API 时先查项目依赖中的 Minecraft 源码，不要凭记忆猜类名或方法名
- 本机没有 Minecraft 源码 jar，核实签名用 `javap` 查 Loom 的 mapped jar：
  `C:/Users/zhang/.jdks/jdk-25.0.5+8/bin/javap.exe -cp <jar> <类全名>`，
  jar 在 `C:/Users/zhang/.gradle/caches/fabric-loom/minecraftMaven/net/minecraft/` 下的
  `minecraft-common-*` 和 `minecraft-clientonly-*`（客户端类在后者）
- 优先使用 Fabric API 事件；没有对应事件时才用 Mixin
- `./gradlew build` 通过只代表编译和打包成功，游戏内行为仍需 `runClient` 或 `runServer` 验证
- 游戏客户端尚未启动验证过；首次 `runClient` 会下载资源，耗时较长
- 打包后可用 `jar tf build/libs/funmod-0.1.0.jar` 确认 `funmod-refmap.json` 在包里