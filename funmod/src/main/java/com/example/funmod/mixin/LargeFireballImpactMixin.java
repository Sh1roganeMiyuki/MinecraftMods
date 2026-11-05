package com.example.funmod.mixin;

import com.example.funmod.fireball.FireballThrower;
import com.example.impactengine.ImpactEffect;
import com.example.impactengine.ImpactEngine;
import com.example.impactengine.ImpactEffects;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.projectile.LargeFireball;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 把玩家投掷的火球的爆炸，从原版 {@code level.explode(...)} 换成撞击引擎结算。
 *
 * <h2>为什么必须换掉，而不是在原版爆炸上再叠点什么</h2>
 *
 * 原版 {@code LargeFireball} 只有一个 {@code explosionPower}，而它<b>同时</b>驱动
 * 破坏范围、实体伤害和击退强度——三件事被绑死在一个数上。想让火球跳得更高就必须
 * 把威力拉大，于是地上炸出一个大坑；想不留坑就得压威力，于是人也飞不起来。
 * 在这个模型里"破坏强度"和"击退强度"根本拆不开。
 *
 * 撞击引擎的配置模型里这两项本来就是分开的字段（{@code blocks.*} 与 {@code knockback.*}），
 * 而且 {@code damage.self_damage_multiplier} 和 {@code knockback.exclude_source}
 * 正好就是火箭跳需要的"对自己免伤但要推开自己"。所以换过去之后，
 * 之前为此手写的两个 Mixin（自伤减免、自身弹射）都变成了多余，已删除。
 *
 * <h2>只对玩家投掷的火球生效</h2>
 *
 * 恶魂自己吐的火球走原版路径不动。两个理由：那是原版平衡的一部分；
 * 而且原版那条路径会处理 {@code mobGriefing} 游戏规则，接管过来就把这条规则绕过去了。
 * 判断依据是 owner 是不是 {@link ServerPlayer}——被剑反弹后归属会转移到反弹者身上，
 * 那种情况也算玩家的，符合直觉。
 *
 * <h2>注入在 HEAD 并取消</h2>
 *
 * 这样原版 {@code onHit} 里的 {@code super.onHit}（命中直接造成 6 点伤害）、
 * {@code level.explode} 和 {@code discard()} 全都不执行，改由引擎统一结算，
 * 不会出现"引擎伤害 + 原版伤害"叠两份。{@code discard()} 要自己补上。
 *
 * <p>配置没加载时**原样放行**，退回原版爆炸：宁可手感不对，也不能把火球变成哑弹。
 */
@Mixin(LargeFireball.class)
public abstract class LargeFireballImpactMixin {

	@Inject(method = "onHit", at = @At("HEAD"), cancellable = true)
	private void funmod$explodeThroughImpactEngine(HitResult result, CallbackInfo ci) {
		LargeFireball self = (LargeFireball) (Object) this;
		// 客户端上 level 是 ClientLevel，这个 instanceof 同时把两侧都挡掉了。
		// 破坏方块、伤害、击退全是服务端权威逻辑
		if (!(self.level() instanceof ServerLevel serverLevel)) {
			return;
		}
		if (!(self.getOwner() instanceof ServerPlayer owner)) {
			return;
		}

		ImpactEffect effect = ImpactEffects.get(FireballThrower.IMPACT_ID).orElse(null);
		if (effect == null) {
			return;
		}

		// 用命中点而不是火球中心：命中点是真正接触的位置，爆心更准。
		// 方向传火球当前的飞行方向，这样 JSON 里要是把 shape 改成 cone，
		// 锥体会顺着飞行方向展开，而不是退化成一个球
		ImpactEngine.execute(serverLevel, effect, result.getLocation(), self.getDeltaMovement(), owner, 1);
		self.discard();
		ci.cancel();
	}
}
