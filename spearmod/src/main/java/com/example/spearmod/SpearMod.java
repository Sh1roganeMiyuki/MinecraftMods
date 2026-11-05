package com.example.spearmod;

import eu.midnightdust.lib.config.MidnightConfig;
import net.fabricmc.api.ModInitializer;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SpearMod implements ModInitializer {
	public static final String MOD_ID = "spearmod";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		// 游戏内配置界面。登记之后 MidnightLib 自带的 modmenu 入口点会自动
		// 给 spearmod 挂上"配置"按钮，不需要写 ModMenuApi 实现。
		// 放在命令注册之前：命令的 executes 会读 SpearConfig 的静态字段。
		MidnightConfig.register(MOD_ID, SpearConfig.class);

		// 调试命令，长枪本体做好之前用它触发撞击引擎调数值
		SpearCommands.register();
		LOGGER.info("Spear Mod loaded");
	}

	public static ResourceLocation id(String path) {
		return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
	}
}