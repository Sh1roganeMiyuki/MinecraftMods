package com.example.funmod.mixin;

import com.example.funmod.doorslam.DoorSlam;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.DoorBlock;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 拦截门的开关。
 *
 * <p>注入点选的是 {@code DoorBlock#playSound} 而不是看起来更直观的 {@code setOpen}：
 * 在 1.21.1 里 {@code setOpen} <b>只</b>被生物开门（村民、铁傀儡）那条路径调用，
 * 玩家右键走 {@code useWithoutItem}、红石走 {@code neighborChanged}，两者都直接
 * {@code setBlock}，压根不经过 {@code setOpen}。
 *
 * <p>而这三条路径最终都会调用 private 的 {@code playSound(Entity, Level, BlockPos, boolean)}，
 * 它是唯一的汇聚点，且第 4 个参数直接给出变更后的开关状态，不需要再去读方块。
 */
@Mixin(DoorBlock.class)
public abstract class DoorBlockMixin {

	@Inject(method = "playSound", at = @At("HEAD"))
	private void funmod$onDoorToggled(@Nullable Entity entity, Level level, BlockPos pos, boolean open,
			CallbackInfo ci) {
		DoorSlam.onDoorToggled(entity, level, pos, open);
	}
}
