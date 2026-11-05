# Fun Mod 项目说明

仓库级的环境事实、构建命令、已知的坑都在 **`../AGENTS.md`**，动手前先读那个。
这里只记 funmod 自己的东西，避免两份文档各说一套。

## 项目目标

- 受到 B 站 UP 主“老丹Daniel”的小玩法系列启发，为和朋友联机 PvP 制作小型、可独立测试的机制
- 已实现功能：门开关把实体打飞（`doorslam`）、手持烈焰弹掷出恶魂大火球（`fireball`）
- 已拆出去的功能：长枪武器及相关附魔 → 独立子模块 `../spearmod`
- 每个功能保持小范围，不做整合包规模的内容

## 本模块速查

- 模组 ID：`funmod`；根包：`com.example.funmod`
- 功能包：`doorslam`（门挥扫）、`fireball`（烈焰弹投掷）
- 构建（在**仓库根**执行）：`./gradlew :funmod:build`
- 开发客户端：`./gradlew :funmod:runClient`
- 产物：`funmod/build/libs/funmod-0.1.0.jar`
- Mixin：`DoorBlockMixin`（门）、`FireChargeItemMixin`（烈焰弹），都登记在 `funmod.mixins.json`

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
- 参数在 `FunModConfig`（MidnightConfig），游戏里「模组列表 → Fun Mod → 配置」拖滑条调，
  磁盘上落在 `config/funmod.json`。**改完立即生效**，不需 reload 也不需重启：
  界面点“完成”就把值写回静态字段，而 `DoorSlam` 每次都是直接读这些字段
- 默认水平初速度 = `horizontalStrength(1.5)` × 杠杆系数 `[0.8, 1.2]`，落在 1.2~1.8 block/tick；
  杠杆系数来自刚体旋转 `v = ω × r`，所以贴门轴几乎不动、门扇外缘打得最狠

### 击飞方向有四种算法（`knockbackMode`）

实际测试发现：**切线方向在扇形边缘的观感很怪**。以门轴在西、门从东向南扫为例，
一个站在**正南**的实体，切线算出来是**正西**——也就是朝着门轴和墙的方向飞。
这在圆周运动上完全正确（门扇转到南侧时确实正在往西走），但玩起来像 bug。

所以做成可配置的，**默认是实测手感最好的 `NORMAL`**：

| 值 | 方向 | 特点 |
|---|---|---|
| `NORMAL` ←默认 | 从门板所在的那条**边**指向方块外 | 关门推向 `facing`、开门推向 `side`（门轴那侧） |
| `TANGENTIAL` | `ω × r`，逐实体算 | 物理最准，门扇外缘打得狠；但扇形边缘会把人拍向门轴 |
| `SWEEP` | `normalize(openDir - closedDir)`，判定区内统一 | 门扇自由端的位移方向，45° 斜向，关门时自动反向 |
| `RADIAL` | 从门轴指向实体 | 永远远离门轴，最“弹开”的感觉 |

`NORMAL` 的关键在于：**门板只有 3/16 厚，它是贴在方块的一条边上的**，不是在方块中心。
所以“推”的方向应该是从那条边指向相邻方块，而不是指向当前方块内部：

```
俯视，FACING=north（门板贴北边），门轴在西

  关门：门板贴北边 → 推向 north（上）      开门：门板贴西边 → 推向 west（左）

      ↑ push                                  ┌───┐
     ━━━━━                                    │   │
     ┃门板                                    ←┃   │
     ━━━━━                                    └───┘
   （门板横在北边）                          push（门板竖在西边）
```

这里踩过一次坑：最初写成 `open ? closedDir : openDir`，而 `closedDir = -side`、
`openDir = -facing`，**恰好是正确方向的相反数**。因为 closedDir/openDir 的语义是
“从门轴指向门扇自由端”，朝的是方块**内部**，不是朝外。
当时没看懂为什么反，还加了个 `normalFlip` 开关想靠取反绕过去——那是错的做法，
开关只能把错的方向换到另一种情况上，真正要做的是把公式写对。

`knockbackMode` 现在是**枚举**而不是字符串，所以写错值根本不可能（以前用字符串时
需要 `parseMode` 兜底并在日志里警告）。配置界面上它渲染成一个循环切换按钮。

### 击退是叠加的，但有速度上限

水平分量用 `motion + push` 而**不是**直接赋值。这一点踩过坑：
写成覆盖的话，两扇邻接的门被压力板同时触发时，后一扇会把前一扇的冲力直接抹掉，
现象就是“两扇门一起踩却只朝最后一扇的方向飞”，而不是预期的飞得更远。
垂直分量本来就是叠加的（`max(motion.y, 0) + verticalStrength`），所以当时只有水平方向丢了。

叠加带来一个新风险：红石时钟接门会把实体越抽越快。所以有 `maxHorizontalSpeed`
（默认 4.0 block/tick）做封顶，超过就按比例缩回去，方向不变。

### 调手感

参数全部在配置界面里调（「模组列表 → Fun Mod → 配置」），分两个标签页：
门扇挥扫、视觉与音效。击飞方向算法就是里面的 `knockbackMode`，
点一下循环切换，关掉界面立刻开关门就能对比手感。

剩下一个命令：

```
/funmod probe             对着门用，把该门的几何量和各模式方向打出来
```

需要 OP（权限等级 2）。`probe` 会对着你看的门输出 FACING、HINGE、门轴坐标、
closedDir/openDir，以及 NORMAL 和 SWEEP 在开门/关门两种情况下各推向哪个罗盘方位。
方向这种东西光在纸上推很容易和实际观感对不上，把算出来的向量摆到游戏现象旁边比最快。
它算的是门的**几何量**，不在任何配置里，所以配置界面代替不了它。

这里曾经还有 `reload`、`config`、`mode` 三个子命令，迁到 MidnightConfig 之后全删了：
`reload` 没有“重读”这一步了，`config` 和界面重复，`mode` 界面上的循环按钮更快。

### 烈焰弹投掷（`fireball`）

手持烈焰弹右键 → 掷出原版恶魂大火球（`LargeFireball`），而不是原版的原地摩擦点火。
代码在 `src/main/java/com/example/funmod/fireball/FireballThrower.java`。

**两个入口，缺一个就只覆盖一半场景：**

| 场景 | 拦截方式 |
|---|---|
| 右键**方块** | `FireChargeItemMixin` 注入 `FireChargeItem#useOn` 的 HEAD |
| 右键**空气** | `FunMod.onInitialize` 里注册的 `UseItemCallback` |

为什么不能只用一个 Mixin：1.21.1 的 `FireChargeItem` **只声明了 `useOn`、没有声明 `use`**
（用 javap 核过，它只实现了 `ProjectileItem` 的那几个方法），对空右键落到基类 `Item#use`，
而 Mixin 不能注入目标类里不存在的方法。

**为什么方块那半边不用 `UseBlockCallback`（重要）**：那个事件在方块自己的
`BlockState#useItemOn` **之前**触发，在那里返回 SUCCESS 会把箱子、工作台、熔炉、门
全部吃掉——手里拿着烈焰弹就再也打不开箱子了。而 `useOn` 是原版在“方块没消费这次交互”
之后才调用的，所以拦这里天然拿到正确的优先级。换成事件就得自己列“哪些方块算可交互”的
名单，那种名单永远列不全。

**冷却期间返回 `FAIL` 而不是 `PASS`**：FAIL 在这里是安全的，因为 `useOn` 只有在方块
已经拒绝交互之后才会被调到，所以 FAIL 只会挡住“点火”这一件事，不会锁死箱子。
用 PASS 的话冷却期间右键会退化成点火，投掷和点火交替出现，很怪。

**两端都要做的三件事**（这是最容易错的地方）：

```java
level.playSound(...);                    // 两端
player.getCooldowns().addCooldown(...);  // 两端
if (!player.isCreative()) stack.shrink(1);  // 两端
if (!level.isClientSide) spawn(...);     // 只有服务端
return InteractionResult.sidedSuccess(level.isClientSide);
```

`ItemCooldowns` 和玩家物品栏都是**每端一份、彼此没有同步包**：冷却只在服务端记，
客户端快捷栏就不会画冷却遮罩；消耗只在服务端做，客户端要等一个纠正包才更新。
原版 `SnowballItem#use` 就是这个写法，直接抄。

**不要手动 `player.swing(hand)`**：`sidedSuccess` 在客户端返回 SUCCESS，
框架会自己摆手（原版雪球、烈焰弹点火都是这样）。再手动摆一次就是摆两下，动画看着抽一下。

**防炸膛做了两层**，不是只把生成点往前推 1 格：

1. `LargeFireball` 的构造器会把火球摆在 **owner 的位置**上，所以必须显式 `setPos`。
   漏了这一步火球就在玩家脚下方块里生成，当 tick 撞地爆炸
2. 平视时“前方 1 格”够（玩家碰撞箱半宽 0.3，火球半宽 0.5，中心在 1.0 时后缘在 0.5，
   不重叠），但**低头投掷**时这个结论不成立——视线朝下，“往前 1 格”落到了自己脚下。
   所以 `safeSpawnPoint` 会检查火球碰撞箱是否和玩家相交，相交就沿视线再推 0.5 格

火球本体完全用原版 `LargeFireball`，所以这些是白送的：爆炸点火、小范围击退、
以及**被剑打一下能反弹并转移归属**（`AbstractHurtingProjectile#hurt` 里的逻辑，
owner 是谁都成立，所以自己也能把自己扔出去的火球打回去）。

击杀计入玩家名下，因为构造器的第二个参数就是 owner。

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
- 2026-11-05：门挥扫**已实际进游戏测过**。四种击飞方向对比后选定 NORMAL 为默认；
  过程中修了两个真 bug：击退写成覆盖导致多扇门不叠加、NORMAL 方向公式整体取反
- 2026-11-05：迁到 MidnightConfig，`./gradlew build` 三个模块全绿，
  `config/funmod.json` 正常生成，枚举序列化为 `"NORMAL"`
- 2026-11-05：烈焰弹投掷 `./gradlew :funmod:build` 通过，refmap 里
  `FireChargeItemMixin.useOn` 已映射到 `class_1778.method_7884`（打包后不会静默失效）；
  开发客户端启动无异常，三个 mod + midnightlib + modmenu 全部加载
- **烈焰弹的游戏内手感尚未验证**：投掷轨迹、低头投掷会不会炸膛、
  拿着烈焰弹能不能正常开箱子、冷却遮罩有没有画出来，这几项需要实际进游戏试
