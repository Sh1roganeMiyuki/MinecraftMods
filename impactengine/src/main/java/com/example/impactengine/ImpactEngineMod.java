package com.example.impactengine;

import net.fabricmc.api.ModInitializer;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 通用的物理撞击与破坏结算引擎。
 *
 * <p>本模块只提供引擎，不含任何玩法内容。玩法 mod 在自己的 {@code build.gradle} 里加
 * {@code modImplementation project(':impactengine')}，联机时两个 jar 都要装。
 *
 * <p>撞击配置（{@link ImpactEffect}）由各玩法 mod 自己的数据包提供，放在
 * {@code data/<自己的命名空间>/impact_effects/*.json}。本模块的加载器会扫描所有命名空间，
 * 所以引擎不需要知道有谁在用、用了几份配置。
 */
public class ImpactEngineMod implements ModInitializer {
	public static final String MOD_ID = "impactengine";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	/** 附魔 JSON 里引用本引擎的类型 id，即 {@code "type": "impactengine:impact"} */
	public static final ResourceLocation ENCHANTMENT_EFFECT_ID = id("impact");

	@Override
	public void onInitialize() {
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
