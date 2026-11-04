package com.example.spearmod;

import com.example.impactengine.ImpactEffect;
import com.example.impactengine.ImpactEngine;
import com.example.impactengine.ImpactEffects;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Map;

/**
 * 调试命令。长枪还没做，但撞击引擎需要能实际触发才能调数值，
 * 所以先给一个"指哪打哪"的入口。等武器做完这些命令照样留着当测试台。
 *
 * <pre>
 * /spear list                                  列出已加载的撞击配置
 * /spear impact &lt;id&gt;                           等级 1，打视线命中的位置
 * /spear impact &lt;id&gt; &lt;level&gt;                   指定附魔等级，用来试 LevelBasedValue 的成长曲线
 * /spear impact &lt;id&gt; &lt;level&gt; &lt;distance&gt;        指定最大取点距离
 * </pre>
 *
 * <p>只对 OP 开放（权限等级 2），联机时不至于谁都能炸地图。
 */
public final class SpearCommands {

	/** 视线取不到方块时，退回到眼前这么远 */
	private static final double DEFAULT_DISTANCE = 4.0D;
	private static final double MAX_DISTANCE = 64.0D;
	private static final int MAX_LEVEL = 10;

	private SpearCommands() {
	}

	public static void register() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
				dispatcher.register(Commands.literal("spear")
						.requires(source -> source.hasPermission(2))
						.then(Commands.literal("list")
								.executes(SpearCommands::list))
						.then(Commands.literal("impact")
								.then(Commands.argument("id", ResourceLocationArgument.id())
										.executes(ctx -> impact(ctx, 1, DEFAULT_DISTANCE))
										.then(Commands.argument("level", IntegerArgumentType.integer(1, MAX_LEVEL))
												.executes(ctx -> impact(ctx,
														IntegerArgumentType.getInteger(ctx, "level"),
														DEFAULT_DISTANCE))
												.then(Commands.argument("distance",
														DoubleArgumentType.doubleArg(0.0D, MAX_DISTANCE))
														.executes(ctx -> impact(ctx,
																IntegerArgumentType.getInteger(ctx, "level"),
																DoubleArgumentType.getDouble(ctx, "distance")))))))));
	}

	private static int impact(CommandContext<CommandSourceStack> context, int level, double distance)
			throws CommandSyntaxException {
		CommandSourceStack source = context.getSource();
		ServerLevel serverLevel = source.getLevel();
		ServerPlayer player = source.getPlayerOrException();
		ResourceLocation id = ResourceLocationArgument.getId(context, "id");

		ImpactEffect effect = ImpactEffects.get(id).orElse(null);
		if (effect == null) {
			source.sendFailure(Component.literal("找不到撞击配置 " + id + "，用 /spear list 看看加载了哪些"));
			return 0;
		}

		Vec3 center = resolveCenter(player, distance, effect);
		ImpactEngine.Result result = ImpactEngine.execute(
				serverLevel, effect, center, player.getLookAngle(), player, level);

		source.sendSuccess(() -> Component.literal("触发 " + id + "（等级 " + level + "）→ 破坏 "
				+ result.blocksDestroyed() + " 个方块，命中 " + result.entitiesHit() + " 个实体"), false);
		return result.blocksDestroyed() + result.entitiesHit();
	}

	/**
	 * 中心点怎么取，得看形状：
	 *
	 * <ul>
	 *   <li>锥形：顶点必须在**玩家自己**。锥是从你这里往前扫的，
	 *       要是把顶点放到视线命中点上，整个判定区就跑到你前方远处去了，
	 *       你和身边的怪一个也打不到——只剩粒子和音效，看着像"没作用"</li>
	 *   <li>球形/圆柱：取视线真正命中的方块或实体，"看着哪炸哪"。
	 *       对着天空打空了就退回到眼前 distance 格</li>
	 * </ul>
	 */
	private static Vec3 resolveCenter(ServerPlayer player, double distance, ImpactEffect effect) {
		if (effect.area().shape() == ImpactEffect.Shape.CONE) {
			return player.getEyePosition();
		}
		HitResult hit = player.pick(distance, 0.0F, false);
		if (hit.getType() != HitResult.Type.MISS) {
			return hit.getLocation();
		}
		return player.getEyePosition().add(player.getLookAngle().scale(distance));
	}

	private static int list(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		Map<ResourceLocation, ImpactEffect> all = ImpactEffects.getAll();
		if (all.isEmpty()) {
			source.sendFailure(Component.literal(
					"没有加载到任何撞击配置。检查各 mod 的 data/<命名空间>/impact_effects/ 目录，"
							+ "或者看看日志里有没有解析失败"));
			return 0;
		}
		source.sendSuccess(() -> Component.literal("已加载 " + all.size() + " 个撞击配置："), false);
		all.keySet().stream()
				.map(ResourceLocation::toString)
				.sorted()
				.forEach(id -> source.sendSuccess(() -> Component.literal("  · " + id), false));
		return all.size();
	}
}
