package com.example.funmod;

import eu.midnightdust.lib.config.MidnightConfig;
import net.fabricmc.api.ModInitializer;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FunMod implements ModInitializer {
	public static final String MOD_ID = "funmod";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		// 一行做两件事：从 config/funmod.json 读盘填好 FunModConfig 的静态字段，
		// 并把自己登记进 MidnightConfig.configInstances。
		//
		// 第二步顺带解决了 Mod Menu 集成——MidnightLib 自带的 modmenu 入口点会遍历
		// configInstances，给每个登记过的 mod 注册配置界面工厂，所以这里不需要
		// 写任何 ModMenuApi 实现，也不需要在 fabric.mod.json 里声明 modmenu 入口点。
		//
		// 用 register 而不是 init：后者在 MidnightLib 1.10 已标 @Deprecated，只是转调前者。
		MidnightConfig.register(MOD_ID, FunModConfig.class);
		FunModCommands.register();
		LOGGER.info("Fun Mod loaded");
	}

	public static ResourceLocation id(String path) {
		return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
	}
}
