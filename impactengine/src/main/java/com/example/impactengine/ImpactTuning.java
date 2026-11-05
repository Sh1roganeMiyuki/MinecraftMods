package com.example.impactengine;

import eu.midnightdust.lib.config.MidnightConfig;

/**
 * 撞击引擎的**运行时调参层**，游戏里在「模组列表 → Impact Engine → 配置」打开界面调。
 *
 * <h2>为什么是倍率而不是绝对值</h2>
 *
 * 半径、伤害、硬度这些绝对值本来就在各 mod 自己的 JSON 里
 * （{@code data/<命名空间>/impact_effects/*.json}），一份配置对应一种武器。
 * 这一层如果也提供绝对值覆盖，就会出现"一个全局值压掉所有武器"的后果：
 * 长枪和爆破箭本来是两种手感，调一次滑条全都变成同一个数。
 *
 * 所以这里只做**乘法**和**开关**：JSON 决定各武器之间的相对强弱（这是它该管的），
 * 调参层决定整体强弱（这是试手感时想改的）。两者正交，不会互相踩。
 *
 * <h2>为什么这对"后续模组"自动生效</h2>
 *
 * 调参层作用在 {@link ImpactEngine#execute} 内部，而不是作用在某份具体配置上。
 * 因此任何 mod、任何 JSON、任何用 Builder 现场构造的 {@link ImpactEffect}，
 * 只要走 execute 就自动受这些滑条影响——新 mod 不需要依赖本类、不需要注册任何东西。
 *
 * <h2>不可覆盖的安全底线</h2>
 *
 * 下面这些在 {@link ImpactEngine} 里写死，调参层碰不到，避免滑条拉满把地图或服务器搞坏：
 *
 * <ul>
 *   <li>硬度为负的方块（基岩、屏障、末地传送门框架、命令方块）一律免疫</li>
 *   <li>{@code wither_immune} / {@code dragon_immune} 标签一律免疫</li>
 *   <li>方块扫描半径上限 16，再大的半径也只在这个范围内找方块</li>
 * </ul>
 *
 * <h2>字段为什么加 {@code volatile}</h2>
 *
 * 配置界面在客户端线程写字段，而结算逻辑在服务端线程读。单机下两者是同一个 JVM 的
 * 不同线程，不加 volatile 会有两个问题：可见性（服务端可能一直读到旧值）和
 * 撕裂读（JLS 不保证 double/float 的写入是原子的）。加了之后拖动滑条点完成，
 * 下一次撞击立刻用新值。
 */
public class ImpactTuning extends MidnightConfig {

	/** 三态覆盖：跟随 JSON、强制开、强制关。破坏方块和抛射方块共用这一个枚举。 */
	public enum Override {
		FROM_JSON,
		FORCE_ON,
		FORCE_OFF
	}

	// ---------------------------------------------------------------- 战斗与物理

	@Entry(category = "combat")
	public static volatile boolean enabled = true;

	@Entry(isSlider = true, min = 0.0D, max = 5.0D, precision = 2, category = "combat")
	public static volatile double damageMultiplier = 1.0D;

	/**
	 * 半径倍率。注意它同时放大实体判定范围；方块破坏另有 16 格硬上限，
	 * 所以倍率拉很高时会出现"人被炸飞了但方块没坏"，那是上限在起作用，不是 bug。
	 */
	@Entry(isSlider = true, min = 0.25D, max = 3.0D, precision = 2, category = "combat")
	public static volatile double radiusMultiplier = 1.0D;

	@Entry(isSlider = true, min = 0.0D, max = 3.0D, precision = 2, category = "combat")
	public static volatile double knockbackMultiplier = 1.0D;

	// ---------------------------------------------------------------- 环境破坏

	@Entry(category = "destruction")
	public static volatile Override destroyBlocks = Override.FROM_JSON;

	/**
	 * 硬度天花板。生效值是 {@code min(JSON 里的 max_hardness, 这个值)}，
	 * 所以默认 10.0 对绝大多数配置等于"由 JSON 决定"，往下拖才是收紧。
	 *
	 * <p>刻意只能收紧不能放宽：想炸黑曜石（硬度 50）属于某把武器的设定，
	 * 应该写进那份 JSON，而不是用全局滑条把整个服务器的方块都变成可炸的。
	 */
	@Entry(isSlider = true, min = 0.5D, max = 10.0D, precision = 1, category = "destruction")
	public static volatile double hardnessCeiling = 10.0D;

	@Entry(category = "destruction")
	public static volatile Override entitizeBlocks = Override.FROM_JSON;

	// ---------------------------------------------------------------- 视觉与特效

	/**
	 * 粒子数量倍率。JSON 里的 {@code particle_count} 乘以它，结果向下取整。
	 *
	 * <p><b>只在关闭屏幕震动时生效</b>：震动是通过原版爆炸包
	 * （{@code ClientboundExplodePacket}）实现的，那个包一次带上震动、粒子和声音，
	 * 但没有粒子数量和音量字段。要精确控制这两项就把下面的 screenShake 关掉，
	 * 引擎会改走 {@code sendParticles} + {@code playSound} 的常规通道。
	 */
	@Entry(isSlider = true, min = 0.0D, max = 4.0D, precision = 2, category = "visuals")
	public static volatile double particleDensity = 1.0D;

	/** 关掉后改走常规通道，此时 particleDensity 和 volumeMultiplier 才精确生效。 */
	@Entry(category = "visuals")
	public static volatile boolean screenShake = true;

	/** 音量倍率，同样只在关闭屏幕震动时生效，理由见 {@link #particleDensity}。 */
	@Entry(isSlider = true, min = 0.0D, max = 2.0D, precision = 2, category = "visuals")
	public static volatile double volumeMultiplier = 1.0D;

	// ---------------------------------------------------------------- 结算

	/** JSON 说要不要破坏方块，调参层可以一票否决或一票通过 */
	public static boolean shouldDestroy(boolean fromConfig) {
		return switch (destroyBlocks) {
			case FROM_JSON -> fromConfig;
			case FORCE_ON -> true;
			case FORCE_OFF -> false;
		};
	}

	/**
	 * 把 JSON 里的抛射概率换算成实际概率。
	 * FORCE_ON 给 1.0（每个方块都飞），FORCE_OFF 给 0，FROM_JSON 用原值。
	 */
	public static float entitizeChance(float fromConfig) {
		return switch (entitizeBlocks) {
			case FROM_JSON -> fromConfig;
			case FORCE_ON -> 1.0F;
			case FORCE_OFF -> 0.0F;
		};
	}

	/** 有效硬度上限：JSON 的值和天花板取小的那个 */
	public static float effectiveMaxHardness(float fromConfig) {
		return (float) Math.min(fromConfig, hardnessCeiling);
	}
}
