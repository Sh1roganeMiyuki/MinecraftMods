package com.example.impactengine;

import eu.midnightdust.lib.config.MidnightConfig;
import net.fabricmc.api.ModInitializer;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 通用的物理撞击与破坏结算引擎。
 *
 * <p>本模块只提供引擎，不含任何玩法内容。玩法 mod 在自己的 {@code build.gradle} 里按
 * {@code ../AGENTS.md}「已知的坑」第 8 条的写法依赖本模块（必须走 {@code namedElements}，
 * 写 {@code modImplementation project(...)} 会死锁），联机时两个 jar 都要装。
 *
 * <p>撞击配置（{@link ImpactEffect}）由各玩法 mod 自己的数据包提供，放在
 * {@code data/<自己的命名空间>/impact_effects/*.json}。本模块的加载器会扫描所有命名空间，
 * 所以引擎不需要知道有谁在用、用了几份配置。
 *
 * <p>{@link ImpactTuning} 是引擎自带的全局调参层，作用在 {@link ImpactEngine#execute} 内部，
 * 因此上面那些 JSON 全都自动可调，提供 JSON 的 mod 不需要做任何事。
 */
public class ImpactEngineMod implements ModInitializer {
	public static final String MOD_ID = "impactengine";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	/** 附魔 JSON 里引用本引擎的类型 id，即 {@code "type": "impactengine:impact"} */
	public static final ResourceLocation ENCHANTMENT_EFFECT_ID = id("impact");

	@Override
	public void onInitialize() {
		// 游戏内配置界面。登记之后 MidnightLib 自带的 modmenu 入口点会自动
		// 给 impactengine 挂上"配置"按钮，不需要自己写 ModMenuApi 实现。
		// 放在最前面：后面的逻辑一旦跑起来就会读 ImpactTuning 的静态字段。
		MidnightConfig.register(MOD_ID, ImpactTuning.class);

		// 数据包加载器，注册后 /reload 可以热重载配置
		ImpactEffects.register();

		// 注册成附魔可用的效果类型。1.21 的附魔本身是数据包 JSON，
		// 效果按 type 字段分发，注册进来之后附魔就能直接引用撞击配置
		Registry.register(BuiltInRegistries.ENCHANTMENT_ENTITY_EFFECT_TYPE,
				ENCHANTMENT_EFFECT_ID, ImpactEnchantmentEffect.CODEC);

		LOGGER.info("Impact Engine loaded");
	}

	public static ResourceLocation id(String path) {
		return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
	}
}
