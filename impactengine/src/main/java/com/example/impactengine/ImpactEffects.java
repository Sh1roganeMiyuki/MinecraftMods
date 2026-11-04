package com.example.impactengine;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import net.fabricmc.fabric.api.resource.IdentifiableResourceReloadListener;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 从数据包加载 {@link ImpactEffect} 配置。
 *
 * <p>JSON 放在 {@code data/<命名空间>/impact_effects/<名字>.json}，
 * id 由文件路径决定：{@code data/my_pack/impact_effects/tnt_spear.json} → {@code my_pack:tnt_spear}。
 *
 * <p>走数据包而不是 config 目录，好处是：
 * <ul>
 *   <li>{@code /reload} 热重载，调数值不用重启</li>
 *   <li>能放进整合包/服务器数据包分发，玩家不用改本地配置</li>
 *   <li>和附魔、伤害类型这些 1.21 的数据驱动内容处于同一层级，附魔 JSON 能直接按 id 引用</li>
 * </ul>
 *
 * <p>字段缺失会用 {@link ImpactEffect} 里各 record 的默认值；解析失败只跳过该文件并记 error，
 * 不会拖垮整个 reload。
 */
public final class ImpactEffects extends SimpleJsonResourceReloadListener
		implements IdentifiableResourceReloadListener {

	public static final String DIRECTORY = "impact_effects";

	/**
	 * 单例。注意 Gson 必须在 {@code super()} 里直接构造，**不能**用静态字段：
	 * Java 静态字段按声明顺序初始化，如果 INSTANCE 排在 GSON 前面，
	 * 构造时 GSON 还是 null，super 就拿到一个 null 的 Gson。
	 * 这个错编译期、CI 全都报不出来，只会在进世界触发资源加载时炸 NPE。
	 */
	public static final ImpactEffects INSTANCE = new ImpactEffects();

	/** reload 期间用 volatile 整体替换，读取方永远看到一致的快照 */
	private volatile Map<ResourceLocation, ImpactEffect> effects = Map.of();

	private ImpactEffects() {
		super(new GsonBuilder().create(), DIRECTORY);
	}

	/** 已由 {@code ImpactEngineMod.onInitialize} 调用，依赖本模块的 mod 不需要再管 */
	public static void register() {
		ResourceManagerHelper.get(PackType.SERVER_DATA).registerReloadListener(INSTANCE);
	}

	@Override
	public ResourceLocation getFabricId() {
		return ImpactEngineMod.id(DIRECTORY);
	}

	@Override
	protected void apply(Map<ResourceLocation, JsonElement> prepared, ResourceManager manager,
			ProfilerFiller profiler) {
		Map<ResourceLocation, ImpactEffect> loaded = new HashMap<>();
		prepared.forEach((id, json) -> {
			DataResult<ImpactEffect> result = ImpactEffect.CODEC.parse(JsonOps.INSTANCE, json);
			result.resultOrPartial(error -> ImpactEngineMod.LOGGER.error("[impact] {} 解析失败: {}", id, error))
					.ifPresent(effect -> loaded.put(id, effect));
		});
		this.effects = Map.copyOf(loaded);
		ImpactEngineMod.LOGGER.info("[impact] 已加载 {} 个撞击效果配置", loaded.size());
	}

	/** 按 id 取配置，不存在时返回空 */
	public static Optional<ImpactEffect> get(ResourceLocation id) {
		return Optional.ofNullable(INSTANCE.effects.get(id));
	}

	/** 只读快照，供命令或调试用 */
	public static Map<ResourceLocation, ImpactEffect> getAll() {
		return INSTANCE.effects;
	}
}
