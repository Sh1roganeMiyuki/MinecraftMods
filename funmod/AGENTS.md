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

### 撞击结算引擎（`impact`）

所有武器、弹射物、技能冲撞共用的一条结算路径。代码在
`src/main/java/com/example/funmod/impact/`，包不依赖 funmod 其他部分，
将来要抽成独立子模块给别的 mod 用，直接整个包搬走即可。

**配置与上下文分离**，这是用之前必须想清楚的一点：

- `ImpactEffect` = 纯配置（不可变 record，可序列化、可复用）
- `Level` / 中心坐标 / 方向 / 来源实体 / 附魔等级 = 执行时上下文，走 `ImpactEngine.execute()` 的参数

把 Level、center 这些塞进配置对象里看着方便，但那份配置就只能用一次，
同一份 JSON 没法在不同位置、被不同武器反复触发。

三种用法：

```java
// 1. 代码里现场构造
ImpactEffect effect = ImpactEffect.builder()
        .shape(ImpactEffect.Shape.CONE).radius(4.0F).coneAngleDegrees(70.0F)
        .damage(6.0F).damageType(DamageTypes.PLAYER_ATTACK.location())
        .knockback(1.2F, 0.3F).knockbackMode(ImpactEffect.KnockbackMode.DIRECTIONAL)
        .build();
ImpactEngine.execute(serverLevel, effect, center, lookDirection, attacker);

// 2. 引用数据包里的配置（推荐，调数值不用重编译）
ImpactEffects.get(FunMod.id("tnt_spear_impact"))
        .ifPresent(e -> ImpactEngine.execute(serverLevel, e, center, dir, source));

// 3. 挂在附魔上（见下）
```

**JSON 配置**放在 `data/<命名空间>/impact_effects/<名字>.json`，
id 由文件路径决定（`data/funmod/impact_effects/tnt_spear_impact.json` → `funmod:tnt_spear_impact`），
文件里不需要也不应该写 `id` 字段。走数据包而不是 `config/` 目录，因为：
`/reload` 能热重载、能随整合包分发、和附魔/伤害类型这些 1.21 数据驱动内容同层。

**数值字段全部是 `LevelBasedValue`**，不是裸 float。它的 CODEC 同时接受两种写法：

```json
"base_amount": 12.0                                                    // 各级一致
"base_amount": { "type": "minecraft:linear", "base": 6.0, "per_level_above_first": 3.0 }   // 随附魔等级成长
```

适用于 `area.radius`、`damage.base_amount`、`knockback.horizontal_strength`、`knockback.upward_bias`。
非附魔来源调用时传 `enchantmentLevel = 1`。

**接附魔**：`ImpactEnchantmentEffect` 已注册到
`BuiltInRegistries.ENCHANTMENT_ENTITY_EFFECT_TYPE`，类型 id 是 `funmod:impact`。
附魔 JSON 里这样引用（**这三个细节都是用字节码核实过的，别凭直觉改**）：

```json
"effects": {
  "minecraft:post_attack": [
    {
      "enchanted": "attacker",
      "affected": "victim",
      "effect": { "type": "funmod:impact", "impact": "funmod:tnt_spear_impact" }
    }
  ]
}
```

- 键名是 `minecraft:post_attack`，**不是** `entity_effect`也不是 `hit`。
  `EnchantmentEntityEffect` 能挂在 `post_attack`（攻击命中后）、`hit_block`（敲方块）、`tick` 三个键下
- `post_attack` 用的是 `TargetedConditionalEffect`，比普通 `ConditionalEffect` 多一层
  `enchanted` / `affected`，且效果体要包在 `"effect": { }` 里
- `enchanted` 只接受 `"attacker"` 或 `"victim"`（原版字节码里的校验消息就是这么写的）

**安全底线**（写在 `ImpactEngine` 里，不可配置、不可绕过）：

- 硬度为负的方块一律免疫——基岩、屏障、末地传送门框架、命令方块
- `wither_immune` / `dragon_immune` 标签的方块一律免疫，配置里的 `immune_tag` 是额外叠加的
- 流体、空气不处理
- 方块扫描半径上限 16 格（`MAX_BLOCK_SCAN_RADIUS`），防止配置写错把服务器卡死；
  超限只影响方块破坏，实体判定不受限

**两个实现细节踩过的坑**：

- `BlockPos.betweenClosed(int...)` 返回的是 `Iterable`，没有 `.stream()`；
  要接 Stream 得用 `betweenClosedStream(...)`。而且它给的是复用的
  `MutableBlockPos`，不 `.map(BlockPos::immutable)` 就会全部指向同一个对象
- `camera_shake > 0` 时走原版 `ClientboundExplodePacket`（一次拿到粒子+声音+屏幕震动），
  此时 **knockback 传 `Vec3.ZERO`、toBlow 传空表**，因为击退已经逐实体精确算过了，
  叠加两份会飞得离谱；`camera_shake == 0` 时走 `sendParticles` + `playSound`，粒子数量可精控

## 验证状态

- 2026-11-03：独立项目架构下 `./gradlew build` 通过，产物含 `funmod-refmap.json`
- 2026-11-03：迁移到 Gradle 多模块后重新构建通过，refmap 照常生成
- 2026-11-04：`impact` 包编译打包通过，jar 里含 15 个 class、两份 `impact_effects` JSON、refmap。
  所有 Minecraft API 签名都用 javap 核实过（调研输出在仓库根的 `.probe/`，已 gitignore）
- **游戏内行为尚未验证**。`build` 通过只代表编译打包成功，冲力手感、扇形范围、
  方块破坏与掉落率、JSON 能否被 `/reload` 正确加载，都仍需
  `./gradlew :funmod:runClient` 实际跑一跑。首次 `runClient` 会下载资源，耗时较长
