package com.example.funmod.doorslam;

import com.example.funmod.FunMod;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.util.Mth;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 门扇挥扫的可调参数，落盘在 {@code config/funmod/door-slam.json}。
 *
 * <p>这里刻意用普通字段而不是 record：Gson 反序列化 record 时缺失的键会变成 0，
 * 而普通类会保留下面的默认值，玩家只改关心的项即可。非法值统一交给
 * {@link #sanitize()} 收敛，避免手改配置把实体弹到世界外。
 */
public final class DoorSlamConfig {
	/** 总开关。关掉后门恢复原版行为。 */
	public boolean enabled = true;

	/**
	 * 基础水平初速度，单位 block/tick。
	 * 实际值还要再乘以 0.8~1.2 的杠杆系数——刚体旋转时线速度正比于半径，
	 * 所以贴着门轴的实体几乎不动，门扇外缘的实体飞得最远。
	 */
	public double horizontalStrength = 1.5D;

	/** 固定上抛初速度，单位 block/tick。 */
	public double verticalStrength = 0.5D;

	/** 以门轴为圆心的水平判定半径，单位 block。 */
	public double reach = 1.6D;

	/** 扇形边界容差。越大越容易把门框两侧的实体也一起扫进去。 */
	public double angleTolerance = 0.15D;

	/** 命中音效音量。 */
	public float soundVolume = 1.0F;

	/** 是否播放打击粒子。 */
	public boolean particles = true;

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final DoorSlamConfig DEFAULTS = new DoorSlamConfig();

	public static DoorSlamConfig load() {
		Path file = FabricLoader.getInstance().getConfigDir().resolve("funmod").resolve("door-slam.json");
		DoorSlamConfig config = read(file);
		config.sanitize();
		return config;
	}

	/** 文件缺失或内容损坏时返回默认值，并把一份完整模板写回磁盘供玩家修改。 */
	private static DoorSlamConfig read(Path file) {
		try {
			if (Files.exists(file)) {
				DoorSlamConfig parsed = GSON.fromJson(Files.readString(file), DoorSlamConfig.class);
				if (parsed != null) {
					return parsed;
				}
				FunMod.LOGGER.warn("[door-slam] {} 内容为空，回退到默认参数", file);
			}
		} catch (IOException | JsonSyntaxException e) {
			FunMod.LOGGER.warn("[door-slam] 读取 {} 失败，回退到默认参数", file, e);
		}

		DoorSlamConfig fallback = new DoorSlamConfig();
		try {
			Files.createDirectories(file.getParent());
			Files.writeString(file, GSON.toJson(fallback));
		} catch (IOException e) {
			FunMod.LOGGER.warn("[door-slam] 无法写出配置模板 {}", file, e);
		}
		return fallback;
	}

	private void sanitize() {
		horizontalStrength = clampOr(horizontalStrength, 0.05D, 10.0D, DEFAULTS.horizontalStrength);
		verticalStrength = clampOr(verticalStrength, 0.0D, 5.0D, DEFAULTS.verticalStrength);
		reach = clampOr(reach, 0.5D, 8.0D, DEFAULTS.reach);
		angleTolerance = clampOr(angleTolerance, 0.0D, 1.0D, DEFAULTS.angleTolerance);
		soundVolume = clampOr(soundVolume, 0.0F, 16.0F, DEFAULTS.soundVolume);
	}

	private static double clampOr(double value, double min, double max, double fallback) {
		return Double.isFinite(value) ? Mth.clamp(value, min, max) : fallback;
	}

	private static float clampOr(float value, float min, float max, float fallback) {
		return Float.isFinite(value) ? Mth.clamp(value, min, max) : fallback;
	}
}
