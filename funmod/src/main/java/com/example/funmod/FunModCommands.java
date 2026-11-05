package com.example.funmod;

import com.example.funmod.doorslam.DoorSlam;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * 诊断命令。
 *
 * <pre>
 * /funmod probe         对着门用，把该门的几何量和各模式方向打出来
 * </pre>
 *
 * <p>这里曾经还有 {@code reload}、{@code config}、{@code mode} 三个子命令，
 * 迁移到 MidnightConfig 之后全部删掉了：
 *
 * <ul>
 *   <li>{@code reload} —— MidnightConfig 点「完成」就直接写回静态字段并存盘，没有"重读"这一步</li>
 *   <li>{@code config} —— 配置界面本身就是当前值的权威视图，再打印一遍是重复</li>
 *   <li>{@code mode} —— 界面上 {@code knockbackMode} 是个循环切换按钮，比记命令参数快</li>
 * </ul>
 *
 * 留下来的 {@code probe} 不一样：它算的是**门的几何量**（门轴在哪、closedDir/openDir
 * 指向哪、各种模式会往哪个罗盘方向推），这些信息不在任何配置里，界面也给不出来。
 * 调方向手感时对着一扇门打一次，比在纸上推导可靠得多。
 */
public final class FunModCommands {

	private FunModCommands() {
	}

	public static void register() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
				dispatcher.register(Commands.literal("funmod")
						.requires(source -> source.hasPermission(2))
						.then(Commands.literal("probe")
								.executes(ctx -> {
									ServerPlayer player = ctx.getSource().getPlayerOrException();
									HitResult hit = player.pick(6.0D, 0.0F, false);
									if (!(hit instanceof BlockHitResult blockHit)) {
										ctx.getSource().sendFailure(Component.literal("对着门再用这个命令"));
										return 0;
									}
									String report = DoorSlam.describe(player.level(), blockHit.getBlockPos());
									ctx.getSource().sendSuccess(() -> Component.literal(report), false);
									return 1;
								}))));
	}
}
