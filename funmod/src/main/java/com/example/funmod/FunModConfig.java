package com.example.funmod;

import com.example.funmod.doorslam.DoorSlam;
import eu.midnightdust.lib.config.MidnightConfig;

/**
 * funmod 的全部可调参数，游戏里在「模组列表 → Fun Mod → 配置」打开界面调。
 *
 * <h2>为什么字段全是 {@code public static}</h2>
 *
 * 这是 MidnightConfig 的约定，也是"改完立即生效"的原因：界面点「完成」时它把新值
 * 直接写回这些静态字段并存盘，游戏逻辑每次读的都是当前值，不需要 reload、不需要重启。
 * 之前用 Gson + 实例字段那套（{@code FunMod.DOOR_SLAM}）必须靠 {@code /funmod reload}
 * 手动重读文件，现在整条链路都不需要了。
 *
 * <h2>一个 mod 只能有一个配置类</h2>
 *
 * {@code MidnightConfig.configInstances} 按 modid 索引，同一个 modid 注册第二个类会顶掉第一个。
 * 所以以后 funmod 加新功能，参数往这里加、用 {@code category} 分成新的标签页，
 * <b>不要</b>再建第二个配置类。
 *
 * <h2>字段为什么都加 {@code volatile}</h2>
 *
 * 配置界面在**客户端线程**写字段，而门挥扫、烈焰弹这些都是**服务端线程**读。
 * 单机下两者是同一个 JVM 的不同线程，不加会有可见性问题（服务端一直读到旧值），
 * 而且 JLS 不保证 {@code double}/{@code float} 的写入是原子的。
 *
 * <h2>翻译键</h2>
 *
 * 缺了对应的键，界面会直接把键名显示出来（不会崩，但很难看）。格式是从 MidnightLib
 * 的字节码里核实过的：
 *
 * <ul>
 *   <li>界面标题 {@code funmod.midnightconfig.title}</li>
 *   <li>条目名 {@code funmod.midnightconfig.<字段名>}</li>
 *   <li>悬浮说明 {@code funmod.midnightconfig.<字段名>.tooltip}</li>
 *   <li>分类标签页 {@code funmod.midnightconfig.category.<分类名>}</li>
 *   <li>枚举取值 {@code funmod.midnightconfig.enum.<枚举类简名>.<常量名>}
 *       —— 第二段是 {@code dataType.getSimpleName()}，也就是枚举类名而不是字段名</li>
 * </ul>
 *
 * 中英文两份都在 {@code assets/funmod/lang/} 下，加字段时两边都要补。
 */
public class FunModConfig extends MidnightConfig {

	// ---------------------------------------------------------------- 门扇挥扫

	@Entry(category = "doorslam")
	public static volatile boolean doorSlamEnabled = true;

	/**
	 * 击飞方向算法。用枚举而不是之前的字符串：MidnightConfig 会渲染成循环切换按钮，
	 * 而且写错值不可能——字符串那套需要 {@code parseMode} 兜底并在日志里警告。
	 */
	@Entry(category = "doorslam")
	public static volatile DoorSlam.KnockbackMode knockbackMode = DoorSlam.KnockbackMode.NORMAL;

	@Entry(isSlider = true, min = 0.0D, max = 5.0D, precision = 2, category = "doorslam")
	public static volatile double horizontalStrength = 1.5D;

	@Entry(isSlider = true, min = 0.0D, max = 2.0D, precision = 2, category = "doorslam")
	public static volatile double verticalStrength = 0.5D;

	@Entry(isSlider = true, min = 0.5D, max = 4.0D, precision = 2, category = "doorslam")
	public static volatile double reach = 1.6D;

	@Entry(isSlider = true, min = 0.0D, max = 1.0D, precision = 2, category = "doorslam")
	public static volatile double angleTolerance = 0.15D;

	/**
	 * 水平速度上限。击退是叠加的，所以必须有这个：红石时钟接门、或者多扇门同时拍，
	 * 不设上限能把实体发射到世界外。滑块下限刻意不给到 0，0 会让门完全推不动东西，
	 * 看起来像功能坏了。
	 */
	@Entry(isSlider = true, min = 0.5D, max = 20.0D, precision = 1, category = "doorslam")
	public static volatile double maxHorizontalSpeed = 4.0D;

	// ---------------------------------------------------------------- 烈焰弹投掷

	@Entry(category = "fireball")
	public static volatile boolean fireballEnabled = true;

	/**
	 * 火球在视线前方多远生成。代码里还会再校验一次：如果这个位置上
	 * 火球的碰撞箱和玩家自己相交（低头投掷时就会），会沿视线继续往外推。
	 * 所以这个值只是"起码多远"，不是硬保证。
	 */
	@Entry(isSlider = true, min = 0.5D, max = 3.0D, precision = 2, category = "fireball")
	public static volatile double fireballSpawnDistance = 1.0D;

	/**
	 * 发射冷却，单位 tick（20 = 1 秒）。防连点器刷屏轰炸。设 0 就是无冷却。
	 *
	 * <p><b>爆炸相关的数值不在这里。</b>破坏强度、击退强度、伤害、自伤减免、点火、
	 * 掉落率、粒子音效全在 {@code data/funmod/impact_effects/fireball.json}（改完 {@code /reload}），
	 * 整体倍率在 Impact Engine 的配置界面（拖滑条立即生效）。
	 *
	 * <p>为什么不放这里：原版那一个 {@code explosionPower} 把破坏、伤害、击退
	 * 绑在一个数上，想跳得高就得炸出大坑，拆不开。而引擎的配置模型里
	 * {@code blocks.*} 和 {@code knockback.*} 本来就是两组独立字段，
	 * {@code damage.self_damage_multiplier} 和 {@code knockback.exclude_source}
	 * 则正好就是火箭跳需要的“对自己免伤但要推开自己”。
	 */
	@Entry(isSlider = true, min = 0, max = 100, category = "fireball")
	public static volatile int fireballCooldownTicks = 20;

	/** 投掷那一瞬的恶魂吐球声音量。爆炸声在 JSON 的 {@code visuals.sound_volume} 里。 */
	@Entry(isSlider = true, min = 0.0F, max = 2.0F, precision = 2, category = "fireball")
	public static volatile float fireballVolume = 1.0F;

	/** 原版恶魂吐球是 1.0，压低一点听着更像"玩家扔的"而不是"旁边有只恶魂"。 */
	@Entry(isSlider = true, min = 0.5F, max = 2.0F, precision = 2, category = "fireball")
	public static volatile float fireballPitch = 0.8F;

	// ---------------------------------------------------------------- 视觉与音效

	@Entry(category = "visuals")
	public static volatile boolean particles = true;

	@Entry(isSlider = true, min = 0.0F, max = 2.0F, precision = 2, category = "visuals")
	public static volatile float soundVolume = 1.0F;
}
