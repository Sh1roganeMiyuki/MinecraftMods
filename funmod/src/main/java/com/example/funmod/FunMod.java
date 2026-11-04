package com.example.funmod;

import com.example.funmod.doorslam.DoorSlamConfig;
import net.fabricmc.api.ModInitializer;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FunMod implements ModInitializer {
	public static final String MOD_ID = "funmod";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	/**
	 * 门扇挥扫参数。先给一份内存默认值兜底，onInitialize 再换成磁盘上的配置，
	 * 这样即使有代码在初始化之前触发也不会拿到 null。
	 */
	public static DoorSlamConfig DOOR_SLAM = new DoorSlamConfig();

	@Override
	public void onInitialize() {
		DOOR_SLAM = DoorSlamConfig.load();
		LOGGER.info("Fun Mod loaded");
	}

	public static ResourceLocation id(String path) {
		return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
	}
}