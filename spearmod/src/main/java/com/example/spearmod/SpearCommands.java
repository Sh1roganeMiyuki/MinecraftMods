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
 * /spear impact                                 用配置界面里的默认值（id / 等级 / 距离）
 * /spear impact &lt;id&gt;                           指定配置，等级和距离用默认值
 * /spear impact &lt;id&gt; &lt;level&gt;                   指定附魔等级，用来试 LevelBasedValue 的成长曲线
 * /spear impact &lt;id&gt; &lt;level&gt; &lt;distance&gt;        指定最大取点距离
 * </pre>
 *
 * <p>只对 OP 开放（权限等级 2），联机时不至于谁都能炸地图。
 *
 * <p>无参数的那个分支是为了调数值：反复触发同一个效果时不想每次都敲一长串
 * 命名空间，把 id 存在配置界面里改起来更快。效果本身的数值不在这里调，
 * 而是在 Impact Engine 的配置界面（全局倍率）或直接改 JSON（各武器的绝对值）。
 */
public final class SpearCommands {

	private static final double MAX_DISTANCE = 64.0D;
	private static final int MAX_LEVEL = 10;

	private SpearCommands() {
	}

	public static void register() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
			// 四层嵌套的 .then() 链式写法要在末尾数十几个右括号，错一个就是编译失败，
			// 而且错了还很难看出是哪一层。拆成自内向外的中间变量，每个分支占一行，
			// 以后加子命令也不用重新数括号。
			var distanceNode = Commands.argument("distance", DoubleArgumentType.doubleArg(0.0D, MAX_DISTANCE))
					.executes(ctx -> fire(ctx, impactId(ctx), levelOf(ctx),
							DoubleArgumentType.getDouble(ctx, "distance")));

			var levelNode = Commands.argument("level", IntegerArgumentType.integer(1, MAX_LEVEL))
					.executes(ctx -> fire(ctx, impactId(ctx), levelOf(ctx), SpearConfig.defaultDistance))
					.then(distanceNode);

			var idNode = Commands.argument("id", ResourceLocationArgument.id())
					.executes(ctx -> fire(ctx, impactId(ctx), SpearConfig.defaultLevel, SpearConfig.defaultDistance))
					.then(levelNode);

			var impactNode = Commands.literal("impact")
					.executes(SpearCommands::impactWithDefaults)
					.then(idNode);

			dispatcher.register(Commands.literal("spear")
					.requires(source -> source.hasPermission(2))
					.then(Commands.literal("list").executes(SpearCommands::list))
					.then(impactNode));
		});
	}

	private static ResourceLocation impactId(CommandContext<CommandSourceStack> ctx) {
		return ResourceLocationArgument.getId(ctx, "id");
	}

	private static int levelOf(CommandContext<CommandSourceStack> ctx) {
		return IntegerArgumentType.getInteger(ctx, "level");
	}

	/**
	 * 无参数分支：用 {@link SpearConfig} 里的默认值。
	 *
	 * <p>id 故意不去解析字符串，而是拿它去已加载的配置表里比对。
	 * 两个好处：不需要担心非法 id 抛出什么异常，而且比中的 id 一定是真的加载了的，
	 * 报错信息也就只有一种（没找到），不会分成"格式错"和"没加载"两种让人猜。
	 */
	private static int impactWithDefaults(CommandContext<CommandSourceStack> context)
			throws CommandSyntaxException {
		String wanted = SpearConfig.defaultImpactId.trim();
		ResourceLocation id = ImpactEffects.getAll().keySet().stream()
				.filter(candidate -> candidate.toString().equals(wanted))
				.findFirst()
				.orElse(null);
		if (id == null) {
			context.getSource().sendFailure(Component.literal(
					"配置里的默认撞击 id '" + wanted + "' 没加载，用 /spear list 看看有哪些"));
			return 0;
		}
		return fire(context, id, SpearConfig.defaultLevel, SpearConfig.defaultDistance);
	}

	/**
	 * 真正执行一次撞击。
	 *
	 * <p>叫 {@code fire} 而不是 {@code impact}：命令树里已经有 {@code Commands.literal("impact")}，
	 * 方法同名虽然不报错（两者在不同命名空间），但读起来很容易把“子命令”和“执行函数”搞混。
	 */
	private static int fire(CommandContext<CommandSourceStack> context, ResourceLocation id,
			int level, double distance) throws CommandSyntaxException {
		CommandSourceStack source = context.getSource();
		ServerLevel serverLevel = source.getLevel();
		ServerPlayer player = source.getPlayerOrException();

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
