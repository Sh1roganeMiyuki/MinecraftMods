package com.example.funmod;

import com.example.funmod.doorslam.DoorSlam;
import com.example.funmod.doorslam.DoorSlamConfig;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/**
 * 调手感用的命令。
 *
 * <pre>
 * /funmod reload        重新读 config/funmod/door-slam.json，改了数值不用重启游戏
 * /funmod mode &lt;名字&gt;    直接切击飞方向算法，连文件都不用改
 * /funmod config        把当前生效的参数打出来
 * </pre>
 *
 * <p>{@code mode} 子命令存在的意义：三种击飞方向哪种手感好，光看代码想不出来，
 * 得站在门前反复开关门对比。能即时切换比"改文件 → reload → 再试"快得多。
 * 注意它改的是内存里的值，下次 {@code reload} 会被文件覆盖回去。
 */
public final class FunModCommands {

	private FunModCommands() {
	}

	public static void register() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
				dispatcher.register(Commands.literal("funmod")
						.requires(source -> source.hasPermission(2))
						.then(Commands.literal("reload")
								.executes(ctx -> {
									FunMod.DOOR_SLAM = DoorSlamConfig.load();
									ctx.getSource().sendSuccess(() -> Component.literal(
											"已重新加载门挥扫配置：" + describe()), true);
									return 1;
								}))
						.then(Commands.literal("config")
								.executes(ctx -> {
									ctx.getSource().sendSuccess(() -> Component.literal(describe()), true);
									return 1;
								}))
						.then(Commands.literal("mode")
								.then(Commands.argument("mode", StringArgumentType.word())
										.executes(ctx -> {
											String name = StringArgumentType.getString(ctx, "mode");
											DoorSlam.KnockbackMode mode = DoorSlam.parseMode(name);
											if (mode == null) {
												ctx.getSource().sendFailure(Component.literal(
														"未知模式 '" + name + "'，可选：TANGENTIAL 切线、"
																+ "SWEEP 横扫、RADIAL 径向"));
												return 0;
											}
											FunMod.DOOR_SLAM.knockbackMode = mode.name();
											ctx.getSource().sendSuccess(() -> Component.literal(
													"击飞方向已切到 " + mode + "，去开关门试试"), true);
											return 1;
										})))));
	}

	private static String describe() {
		DoorSlamConfig c = FunMod.DOOR_SLAM;
		return "力度 " + c.horizontalStrength
				+ "，上抛 " + c.verticalStrength
				+ "，半径 " + c.reach
				+ "，容差 " + c.angleTolerance
				+ "，方向模式 " + c.knockbackMode
				+ (c.enabled ? "" : "（已禁用）");
	}
}
