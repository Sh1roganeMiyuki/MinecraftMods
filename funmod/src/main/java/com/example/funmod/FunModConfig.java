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
	public static boolean doorSlamEnabled = true;

	/**
	 * 击飞方向算法。用枚举而不是之前的字符串：MidnightConfig 会渲染成循环切换按钮，
	 * 而且写错值不可能——字符串那套需要 {@code parseMode} 兜底并在日志里警告。
	 */
	@Entry(category = "doorslam")
	public static DoorSlam.KnockbackMode knockbackMode = DoorSlam.KnockbackMode.NORMAL;

	@Entry(isSlider = true, min = 0.0D, max = 5.0D, precision = 2, category = "doorslam")
	public static double horizontalStrength = 1.5D;

	@Entry(isSlider = true, min = 0.0D, max = 2.0D, precision = 2, category = "doorslam")
	public static double verticalStrength = 0.5D;

	@Entry(isSlider = true, min = 0.5D, max = 4.0D, precision = 2, category = "doorslam")
	public static double reach = 1.6D;

	@Entry(isSlider = true, min = 0.0D, max = 1.0D, precision = 2, category = "doorslam")
	public static double angleTolerance = 0.15D;

	/**
	 * 水平速度上限。击退是叠加的，所以必须有这个：红石时钟接门、或者多扇门同时拍，
	 * 不设上限能把实体发射到世界外。滑块下限刻意不给到 0，0 会让门完全推不动东西，
	 * 看起来像功能坏了。
	 */
	@Entry(isSlider = true, min = 0.5D, max = 20.0D, precision = 1, category = "doorslam")
	public static double maxHorizontalSpeed = 4.0D;

	// ---------------------------------------------------------------- 视觉与音效

	@Entry(category = "visuals")
	public static boolean particles = true;

	@Entry(isSlider = true, min = 0.0F, max = 2.0F, precision = 2, category = "visuals")
	public static float soundVolume = 1.0F;
}
