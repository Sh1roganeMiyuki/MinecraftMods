package com.example.funmod.mixin;

import com.example.funmod.fireball.FireballThrower;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.FireChargeItem;
import net.minecraft.world.item.context.UseOnContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 右键<b>方块</b>那半边的入口。对空右键走 {@code UseItemCallback} 事件，见
 * {@link FireballThrower} 的类注释——两边必须都接，缺一个就只覆盖一半场景。
 *
 * <p>注入点选 {@code useOn} 的 HEAD 而不是用 {@code UseBlockCallback}，是为了拿到正确的
 * 优先级：原版先问方块（{@code BlockState#useItemOn}），方块不消费才轮到物品的 {@code useOn}。
 * 所以箱子、工作台、熔炉、门这些早在上一步就处理完了，走到这里的一定是"本来会点火"的那一下。
 * 换成事件就得自己判断哪些方块算"可交互"，那种名单永远列不全。
 *
 * <p>{@code PASS} 必须原样放行：那表示"这次交互不归我管"（手里不是烈焰弹、或功能被关了），
 * 不能吞掉原版的点火行为。
 */
@Mixin(FireChargeItem.class)
public abstract class FireChargeItemMixin {

	@Inject(method = "useOn", at = @At("HEAD"), cancellable = true)
	private void funmod$throwInsteadOfLighting(UseOnContext context,
			CallbackInfoReturnable<InteractionResult> cir) {
		InteractionResult result = FireballThrower.tryThrow(
				context.getPlayer(), context.getLevel(), context.getHand());
		if (result != InteractionResult.PASS) {
			cir.setReturnValue(result);
		}
	}
}
