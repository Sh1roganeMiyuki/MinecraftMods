# Impact Engine 项目说明

通用的**物理撞击与破坏结算引擎**。武器、弹射物、技能冲撞、爆炸，全都走这一条结算路径。

本模块**不含任何玩法内容**，只提供引擎 + 配置格式 + 附魔衔接。
玩法 mod 自己提供 JSON 配置，自己决定什么时候触发。

仓库级的环境事实、构建命令、已知的坑都在 **`../AGENTS.md`**，动手前先读那个。

---

## 一、让你的 mod 用上它

两步，漏一步都不行。

**1. `<你的 mod>/build.gradle`**

```gradle
// 必须在 dependencies 块外先拿 Project 对象：
// 块内的 project(...) 返回 ProjectDependency，上面没有 sourceSets 属性
def impactEngine = project(':impactengine')

dependencies {
	// 不能写 modImplementation project(':impactengine')，会死锁。
	// 原因和完整解释见 ../AGENTS.md「已知的坑」第 8 条
	implementation project(path: ':impactengine', configuration: 'namedElements')
	clientImplementation impactEngine.sourceSets.client.output
}
```

**2. `<你的 mod>/src/main/resources/fabric.mod.json`**

```json
"depends": {
	"fabricloader": ">=0.20.1",
	"minecraft": "~1.21.1",
	"java": ">=21",
	"fabric-api": "*",
	"impactengine": "*"
}
```

第 1 步只管编译期，**运行时靠第 2 步的 `depends`**。漏了它，开发环境跑得好好，
发给朋友就报缺失依赖。联机时每个人要装两个 jar。

---

## 二、核心设计：配置与上下文分离

用之前必须理解这一点，否则会把代码写歪。

- **`ImpactEffect` = 纯配置**。不可变 record，可序列化、可复用。里面**没有** Level、坐标、来源实体
- **执行时上下文 = `ImpactEngine.execute()` 的参数**。Level、中心、方向、来源、附魔等级

把 Level 和坐标塞进配置对象看着方便，但那份配置就只能用一次——
同一份 JSON 没法在不同位置、被不同武器反复触发，也就失去了数据驱动的意义。

```java
ImpactEngine.execute(ServerLevel level, ImpactEffect effect, Vec3 center,
                     @Nullable Vec3 direction, @Nullable Entity source, int enchantmentLevel)
```

| 参数 | 说明 |
|---|---|
| `level` | 必须是 `ServerLevel`。传客户端世界会直接抛 `IllegalStateException` |
| `center` | 撞击中心 |
| `direction` | 主推进方向。锥形判定和 `directional` 击退用它；传 null 时锥形退化成球形 |
| `source` | 来源实体（谁打出去的）。用于 `self_damage_multiplier` 和 `exclude_source` |
| `enchantmentLevel` | 附魔等级，所有 `LevelBasedValue` 字段按它缩放。**非附魔来源传 1** |

返回 `Result(blocksDestroyed, entitiesHit)`，可以拿来做连锁反应或判断"打空了"。

有个便捷重载省掉方向和等级：

```java
ImpactEngine.execute(serverLevel, effect, center, source);   // direction=null, level=1
```

---

## 三、三种调用方式

### A. 引用数据包里的配置（推荐）

改数值不用重编译，`/reload` 就生效。

```java
ImpactEffects.get(ResourceLocation.parse("spearmod:tnt_spear_impact"))
        .ifPresent(e -> ImpactEngine.execute(serverLevel, e, center, dir, attacker, 1));
```

`ImpactEffects.get()` 返回 `Optional`，配置不存在时是空的——**记得处理**，
否则 JSON 文件名打错就静默什么都不发生。

### B. 代码里现场构造

一次性效果、或者参数要动态算的时候用。

```java
ImpactEffect effect = ImpactEffect.builder()
        .shape(ImpactEffect.Shape.CONE)
        .radius(4.0F)
        .coneAngleDegrees(70.0F)
        .falloff(ImpactEffect.Falloff.EXPONENTIAL)
        .damage(6.0F)
        .damageType(DamageTypes.PLAYER_ATTACK.location())
        .selfDamageMultiplier(0.0F)
        .knockback(1.2F, 0.3F)
        .knockbackMode(ImpactEffect.KnockbackMode.DIRECTIONAL)
        .excludeSource(true)
        .particle(ParticleTypes.SWEEP_ATTACK, 8)
        .sound(SoundEvents.PLAYER_ATTACK_SWEEP, 1.0F, 0.9F)
        .build();
ImpactEngine.execute(serverLevel, effect, center, attacker.getLookAngle(), attacker);
```

Builder 所有字段都有可用默认值，只设你关心的即可。

### C. 挂在附魔上（不写 Java）

引擎已注册为附魔效果类型 `impactengine:impact`。附魔 JSON 直接引用配置 id：

```json
{
  "anvil_cost": 4,
  "description": { "translate": "enchantment.spearmod.spear_impact" },
  "supported_items": "#minecraft:enchantable/sword",
  "weight": 2,
  "max_level": 3,
  "min_cost": { "base": 10, "per_level_above_first": 8 },
  "max_cost": { "base": 25, "per_level_above_first": 8 },
  "slots": ["mainhand"],
  "effects": {
    "minecraft:post_attack": [
      {
        "enchanted": "attacker",
        "affected": "victim",
        "effect": {
          "type": "impactengine:impact",
          "impact": "spearmod:tnt_spear_impact"
        }
      }
    ]
  }
}
```

**这三个细节是用字节码核实过的，别凭直觉改**：

- 键名是 `minecraft:post_attack`，**不是** `entity_effect` 也不是 `hit`。
  `EnchantmentEntityEffect` 能挂在 `post_attack`（攻击命中后）、`hit_block`（敲方块）、
  `tick`（每 tick）三个键下，武器撞击用 `post_attack`
- `post_attack` 用的是 `TargetedConditionalEffect`，比普通 `ConditionalEffect` 多一层
  `enchanted` / `affected`，而且效果体要包在 `"effect": { }` 里
- `enchanted` 只接受 `"attacker"` 或 `"victim"`（原版字节码里的校验消息就是这么写的）

附魔等级会原样传给引擎，所以配置里的数值写成 `minecraft:linear` 就能随等级成长。

---

## 四、JSON 配置

放在**使用方 mod** 的 `data/<命名空间>/impact_effects/<名字>.json`。
id 由文件路径决定（`data/spearmod/impact_effects/tnt_spear_impact.json` → `spearmod:tnt_spear_impact`），
**文件里不要写 `id` 字段**，写了会被忽略（RecordCodecBuilder 默认容忍未知字段）。

引擎的加载器扫描所有命名空间，所以它不需要知道有谁在用。

走数据包而不是 `config/` 目录，因为：`/reload` 能热重载、能随整合包分发、
和附魔与伤害类型这些 1.21 数据驱动内容处于同一层级——最后这条正是能被附魔按 id 引用的前提。

### 完整字段

只有 `area.radius` 和 `damage.base_amount` 是**必填**，其余都有默认值。

**`area`** — 判定区域

| 字段 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `radius` | LevelBasedValue | **必填** | 判定半径（格） |
| `shape` | 枚举 | `spherical` | `spherical` / `cone` / `cylinder` |
| `falloff` | 枚举 | `linear` | `flat` 不衰减 / `linear` 边缘归零 / `exponential` 中心更硬 |
| `cone_angle` | 1~360 | `90` | 锥形张角，仅 `cone` 用 |
| `cylinder_height` | 0.5~64 | `2` | 圆柱总高，仅 `cylinder` 用 |

**`damage`** — 伤害

| 字段 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `base_amount` | LevelBasedValue | **必填** | 基础伤害，实际值再乘 falloff |
| `damage_type` | id | `minecraft:player_explosion` | 数据驱动伤害类型，可以是数据包里新增的 |
| `self_damage_multiplier` | 0~100 | `1.0` | 来源实体受到的倍率，`0` 是完全免伤 |

**`knockback`** — 击退

| 字段 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `horizontal_strength` | LevelBasedValue | `0` | 水平初速度 block/tick |
| `upward_bias` | LevelBasedValue | `0` | 上抛初速度 block/tick |
| `mode` | 枚举 | `radial` | `radial` 从中心向外 / `directional` 沿 direction 推 |
| `exclude_source` | bool | `false` | 击退时跳过来源实体自己 |

两个强度都是 0 时整个击退阶段跳过。击退是**叠加**在原有动量上的（`motion + push`），
垂直分量取 `max(原有, 0) + upward_bias`，不会把正在下落的实体凭空钉住。

**`blocks`** — 方块破坏

| 字段 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `destroy` | bool | `false` | 总开关 |
| `max_hardness` | -1~1000 | `3.0` | 硬度上限。黑曜石 50、附魔台 5，会被这个挡掉 |
| `immune_tag` | id | 无 | 额外的免疫方块标签，**叠加**在硬性免疫之上 |
| `drop_rate` | 0~1 | `1.0` | 掉落物概率 |
| `causes_fire` | bool | `false` | 破坏后点火，概率 1/3（对齐原版爆炸） |
| `entitize_chance` | 0~1 | `0` | 多大比例的方块变成带初速度的下落方块飞出去，纯视觉、不掉落 |

**`status_effects`** — 状态效果数组，可为空

| 字段 | 类型 | 说明 |
|---|---|---|
| `effect` | id | 如 `minecraft:slowness` |
| `duration_ticks` | 1~100000 | **必填**。实际时长会乘 falloff，至少 1 tick |
| `amplifier` | 0~255 | 默认 0 |

**`visuals`** — 表现

| 字段 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `primary_particle` | 粒子 | `minecraft:explosion_emitter` | 支持带参数的写法，如 `{"type":"minecraft:dust",...}` |
| `particle_count` | 0~4096 | `1` | **仅 `camera_shake == 0` 时生效**，见下 |
| `sound` | id | `minecraft:entity.generic.explode` | |
| `sound_volume` | 0~16 | `1.0` | `0` 表示静音 |
| `sound_pitch` | 0~4 | `1.0` | |
| `camera_shake` | 0~10 | `0.0` | 大于 0 时走原版爆炸包，顺带屏幕震动 |

`camera_shake` 有个权衡要知道：大于 0 时视觉和音效走原版 `ClientboundExplodePacket`
（一次拿到粒子+声音+屏幕震动），此时粒子数量由客户端决定，`particle_count` 不生效；
等于 0 时走 `sendParticles` + `playSound`，数量精确可控但没有震动。

### LevelBasedValue：数值字段的两种写法

所有标了 LevelBasedValue 的字段（`radius`、`base_amount`、`horizontal_strength`、`upward_bias`）
都同时接受两种写法：

```json
"base_amount": 12.0                                                          // 各级一致
"base_amount": { "type": "minecraft:linear", "base": 6.0, "per_level_above_first": 3.0 }
```

第二种随附魔等级成长：1 级是 6.0，2 级 9.0，3 级 12.0。
非附魔来源调用时传 `enchantmentLevel = 1`，取的就是 `base`。

原版还提供 `minecraft:constant`、`minecraft:lookup`、`minecraft:levels_squared`、`minecraft:fraction`。

---

## 五、全局调参层（ImpactTuning）

游戏内配置界面，入口「模组列表 → Impact Engine → 配置」，磁盘上落在 `config/impactengine.json`。
拖滑条点完成就**立即生效**，不用 `/reload`、不用重启。

这一层和第四节的 JSON 是**两个不同的东西**，不要搞混：

| | JSON 配置（`ImpactEffect`） | 调参层（`ImpactTuning`） |
|---|---|---|
| 作用范围 | 一份对应一种武器/效果 | 全局，乘在所有效果上 |
| 给的数 | 绝对值（半径 3.5、伤害 12） | 倍率和开关（伤害 ×1.0） |
| 改完生效 | `/reload` | 立即 |
| 谁提供 | 各玩法 mod 自己的数据包 | 本模块内置 |

**为什么只做倍率不做绝对值：** 一份 JSON 对应一种武器。全局滑条要是给绝对值，
调一次就把长枪和爆破箭压成同一个数，两者本来该有的手感差异全没了。
各武器之间的**相对**强弱属于 JSON，整体**绝对**强弱属于调参层，两者正交。

### 对后续模组自动生效

调参层作用在 `ImpactEngine.execute()` **内部**，而不是作用在某份具体配置上。
所以任何 mod、任何 JSON、任何用 Builder 现场构造的 `ImpactEffect`，只要走 execute
就自动受这些滑条影响——**新 mod 不需要依赖 `ImpactTuning`、不需要注册任何东西**。

### 字段

| 分类 | 字段 | 语义 |
|---|---|---|
| 战斗与物理 | `enabled` | 总开关，关掉后 execute 直接返回空结果 |
| | `damageMultiplier` | 乘在 `base_amount` 上，0 = 只击退不掉血 |
| | `radiusMultiplier` | 乘在 `radius` 上，同时放大实体判定范围 |
| | `knockbackMultiplier` | 同时乘在水平推力和上抛分量上 |
| 环境破坏 | `destroyBlocks` | 三态：跟随配置 / 强制开 / 强制关 |
| | `hardnessCeiling` | 生效值 = `min(JSON 的 max_hardness, 这个值)` |
| | `entitizeBlocks` | 三态，同上；强制开 = 每个方块都飞 |
| 视觉与特效 | `particleDensity` | 乘在 `particle_count` 上 |
| | `screenShake` | 关掉后改走常规粒子/声音通道 |
| | `volumeMultiplier` | 乘在 `sound_volume` 上 |

`hardnessCeiling` 刻意**只能收紧不能放宽**：默认 10.0 对绝大多数配置等于“由 JSON 决定”，
往下拖才是限制。想炸黑曜石（硬度 50）属于某把武器的设定，应该写进那份 JSON，
而不是用全局滑条把整个地图都变成可炸的。

### 两个已知的不精确之处

**1. `particleDensity` 和 `volumeMultiplier` 只在关掉 `screenShake` 时生效。**
屏幕震动是通过原版爆炸包（`ClientboundExplodePacket`）实现的，那个包把震动、粒子、声音
打包在一起发，**没有粒子数量和音量字段**。想要精确控制这两项就把震动关掉，
引擎会改走 `sendParticles` + `playSound`。这一点写进了字段的 tooltip。

**2. `radiusMultiplier` 拉很高时会出现“人被炸飞了但方块没坏”。**
那是第六节的 16 格扫描硬上限在起作用，不是 bug。实体判定不受那个上限影响。

### 字段为什么都加了 `volatile`

配置界面在**客户端线程**写字段，而结算逻辑在**服务端线程**读。单机下两者是同一个 JVM
的不同线程，不加 `volatile` 会有两个问题：可见性（服务端可能一直读到旧值），以及
撕裂读（JLS 不保证 `double`/`float` 的写入是原子的）。

---

## 六、安全底线（不可配置、不可绕过）

写在 `ImpactEngine` 里，JSON 改不动：

- **硬度为负的方块一律免疫**——基岩、屏障、末地传送门框架、命令方块
- **`wither_immune` / `dragon_immune` 标签一律免疫**，配置里的 `immune_tag` 是额外叠加而非替换
- 流体、空气不处理
- **方块扫描半径硬上限 16 格**（`MAX_BLOCK_SCAN_RADIUS`）。半径 16 意味着最多遍历
  33³ ≈ 3.6 万个位置；万一 JSON 写成 `radius: 1000`，服务器会直接卡死。
  超限只截断方块破坏并打 warn，实体判定不受影响
- 观察者模式玩家不参与；骑乘中的实体不参与（乘客位置由载具接管，单独打飞会把两者撕裂）
- 破坏顺序**从远到近**，避免先炸掉支撑结构导致上方方块判定异常

---

## 七、实现细节踩过的坑

- `BlockPos.betweenClosed(int...)` 返回 `Iterable`，**没有** `.stream()`；要接 Stream 得用
  `betweenClosedStream(...)`。而且它给的是复用的 `MutableBlockPos`，
  不 `.map(BlockPos::immutable)` 就会全部指向同一个对象
- 跨模块依赖的 `namedElements` 写法，见 `../AGENTS.md`「已知的坑」第 8 条
- 对玩家施加击退后必须 `hurtMarked = true` **并**发 `ClientboundSetEntityMotionPacket`，
  否则客户端会按自己预测的位置把玩家拽回去
- `neighborChanged` 之类的注入点里，方块状态新旧不定，别依赖它；需要的信息尽量走参数

---

## 八、文件

```
src/main/java/com/example/impactengine/
├── ImpactEffect.java              配置 record + CODEC + Builder + 6 个嵌套类型
├── ImpactEngine.java              execute() 结算：方块 / 实体 / 表现
├── ImpactEffects.java             数据包加载器，/reload 热重载
├── ImpactEnchantmentEffect.java   附魔衔接层
├── ImpactTuning.java              全局调参层（游戏内配置界面）
└── ImpactEngineMod.java           入口，注册配置、加载器和附魔效果类型
```

---

## 九、验证状态

- 2026-11-04：从 funmod 抽成独立子模块，跨模块依赖跑通，`./gradlew build` 三个模块全绿
- jar 内容核对过：引擎 17 个 class 在 `impactengine`，两份 JSON 在 `spearmod`，funmod 无残留
- 所有 Minecraft API 签名都用 javap 核实过；附魔 JSON 格式是从原版字节码和
  `data/minecraft/enchantment/*.json` 里对照出来的，不是凭记忆写的
- **游戏内行为尚未验证**。JSON 能否被 `/reload` 正确加载、锥形判定的手感、
  `entitize_chance` 的碎块抛射好不好看、`drop_rate` 的实际观感，都只能进游戏才知道。
  能保证的是 Codec 结构和 API 签名正确，不能保证数值合理
