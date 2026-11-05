package com.example.spearmod;

import eu.midnightdust.lib.config.MidnightConfig;

/**
 * spearmod 的可调参数，游戏里在「模组列表 → Spear Mod → 配置」打开界面调。
 *
 * <p>目前只有调试命令的默认值——长枪本体还没做，所以这里刻意不放"冲刺初速度""充能冷却"
 * 这类参数：没有代码读的配置项是死配置，界面上拖了什么都不会发生，比没有更糟。
 * 等长枪做出来，那些字段加到这里、归到新的 {@code category} 就行。
 *
 * <p>撞击效果本身的数值（半径、伤害、击退、破坏）不在这里，分两处：
 *
 * <ul>
 *   <li>各武器的<b>相对</b>强弱在 {@code data/spearmod/impact_effects/*.json}，
 *       改完在游戏里 {@code /reload}</li>
 *   <li>全局<b>倍率</b>在 Impact Engine 自己的配置界面（ImpactTuning），
 *       拖滑条立刻生效，不用 reload</li>
 * </ul>
 *
 * 这么分是因为一份 JSON 对应一种武器：全局滑条要是给绝对值，调一次就把长枪和爆破箭
 * 压成同一个数了。
 */
public class SpearConfig extends MidnightConfig {

	/**
	 * {@code /spear impact} 不带参数时用哪份配置。
	 * 调数值时最常反复触发同一个效果，把 id 存这里就能只打 {@code /spear impact}，
	 * 不用每次都敲一长串命名空间。填错或配置没加载会明确报错，不会静默失效。
	 */
	@Entry(category = "debug")
	public static volatile String defaultImpactId = "spearmod:tnt_spear_impact";

	@Entry(isSlider = true, min = 1, max = 10, category = "debug")
	public static volatile int defaultLevel = 1;

	/** 视线没打中方块时，退回到眼前这么远。球形/圆柱判定才用得上，锥形固定在玩家眼睛。 */
	@Entry(isSlider = true, min = 1.0D, max = 64.0D, precision = 1, category = "debug")
	public static volatile double defaultDistance = 4.0D;
}
