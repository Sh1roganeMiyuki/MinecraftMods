# Fun Mod 项目说明

仓库级的环境事实、构建命令、已知的坑都在 **`../AGENTS.md`**，动手前先读那个。
这里只记 funmod 自己的东西，避免两份文档各说一套。

## 项目目标

- 受到 B 站 UP 主“老丹Daniel”的小玩法系列启发，为和朋友联机 PvP 制作小型、可独立测试的机制
- 已计划功能：门开关把实体打飞（**已完成**）、长枪武器及相关附魔（已拆成独立子模块 `../spearmod`）
- 每个功能保持小范围，不做整合包规模的内容

## 本模块速查

- 模组 ID：`funmod`；根包：`com.example.funmod`
- 构建（在**仓库根**执行）：`./gradlew :funmod:build`
- 开发客户端：`./gradlew :funmod:runClient`
- 产物：`funmod/build/libs/funmod-0.1.0.jar`

## funmod 独有的东西

### `.maven-local/` 引导仓库（约 20MB，**不要删**）

已被 `.gitignore` 忽略，里面预取了 Fabric Loader 0.20.1、Intermediary 1.21.1（含 `-v2`
分类器）、Fabric API 0.116.17+1.21.1 本体及全部模块、Loom 需要的 `mercury-0.4.3`。

根 `build.gradle` 会检测每个子模块目录下有没有 `.maven-local`，有就把它提到仓库列表最前。
本机访问 `maven.fabricmc.net` 极不稳定（几 KB 的 POM 都会 Read timed out），
这个目录是构建不联网的关键。

其他 mod 没有这个目录也不需要——它们的构件能从全局 `~/.gradle/caches/modules-2` 命中。

## 已实现功能

### 门扇挥扫冲击（`doorslam`）

门开/关时，把门当成绕门轴转 90° 的刚体，对扫过象限内的实体施加切线冲力 + 固定上抛。
代码在 `src/main/java/com/example/funmod/doorslam/`，Mixin 在 `.../mixin/DoorBlockMixin.java`。

**注入点是 `DoorBlock.playSound`（private），不是看起来更直观的 `setOpen`。**
这是用 javap 读字节码确认的，别凭直觉改回去：

- 1.21.1 里 `setOpen` **只**被生物开门（村民、铁傀儡）那条路径调用
- 玩家右键走 `useWithoutItem`、红石走 `neighborChanged`，两者都直接 `setBlock`，
  压根不经过 `setOpen`。只注入 `setOpen` 会漏掉 PvP 里 99% 的场景
- `playSound(Entity, Level, BlockPos, boolean)` 是三条路径唯一的汇聚点，
  且第 4 个参数直接给出变更后的开关状态，不用再读方块

其他要点：

- 红石会让门上下两半各触发一次 `playSound`（`neighborChanged` 综合了上下两半的信号），
  `DoorSlam.claimOnce` 按 `(维度, 下半坐标, gameTime)` 去重，否则冲力翻倍。
  注意不能简单丢弃 UPPER 那次——玩家右键点上半截时就只有那一次调用
- `neighborChanged` 里 `playSound` **先于** `setBlock`，另两条路径相反，
  所以注入点读到的方块状态新旧不定。好在 `FACING`/`HINGE`/`HALF` 都不受 `OPEN` 影响，
  读它们安全，开关状态一律用参数 `open`
- 客户端为了交互预测也会跑 `useWithoutItem`，所以入口第一件事是 `level.isClientSide` 直接返回
- 对玩家施加冲力后必须 `hurtMarked = true` + 发 `ClientboundSetEntityMotionPacket`，
  否则客户端会按自己的预测把玩家拽回去
- 参数在 `config/funmod/door-slam.json`，缺文件会自动写一份完整模板
- 默认水平初速度 = `horizontalStrength(1.5)` × 杠杆系数 `[0.8, 1.2]`，落在 1.2~1.8 block/tick；
  杠杆系数来自刚体旋转 `v = ω × r`，所以贴门轴几乎不动、门扇外缘打得最狠

### 关于撞击结算引擎

通用引擎已经抽成独立子模块 **`../impactengine`**，完整文档在 `../impactengine/AGENTS.md`。

**funmod 目前不依赖它**，这是有意的：doorslam 是绕门轴的**切线**击飞，
力度正比于到门轴的距离；引擎做的是**径向/定向**爆发。两套物理模型不一样，
硬把 doorslam 接到引擎上会丢掉门轴杠杆这个最有意思的手感。

哪天门挥扫要加方块破坏、爆炸表现或者状态效果，按 `../impactengine/AGENTS.md`
第一节的两步接上就行，doorslam 自己的击飞逻辑保持不动。

## 验证状态

- 2026-11-03：独立项目架构下 `./gradlew build` 通过，产物含 `funmod-refmap.json`
- 2026-11-03：迁移到 Gradle 多模块后重新构建通过，refmap 照常生成
- 2026-11-04：`impact` 包抽离到 `../impactengine` 后重新构建通过，
  jar 里只剩 doorslam + mixin + refmap，无残留
- **游戏内行为尚未验证**。`build` 通过只代表编译打包成功，冲力手感和扇形范围
  仍需 `./gradlew :funmod:runClient` 实际开门拍几下。首次 `runClient` 会下载资源，耗时较长
