package com.example.funmod.fireball;

import com.example.funmod.FunModConfig;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.LargeFireball;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * 手持烈焰弹右键 → 掷出恶魂大火球。
 *
 * <p>有<b>两个</b>入口，都必须接上，缺一个就只覆盖一半场景：
 *
 * <ul>
 *   <li>右键<b>方块</b> → {@code FireChargeItemMixin} 拦 {@code FireChargeItem#useOn}</li>
 *   <li>右键<b>空气</b> → {@code FunMod} 里注册的 {@code UseItemCallback}</li>
 * </ul>
 *
 * 为什么不是一个 Mixin 搞定：1.21.1 的 {@code FireChargeItem} **只声明了 useOn，没有声明 use**，
 * 对空右键落到基类 {@code Item#use} 上，而 Mixin 不能注入目标类里不存在的方法。
 *
 * <p>为什么方块那半边用 Mixin 而不用 {@code UseBlockCallback}：事件在方块自己的
 * {@code useItemOn} **之前**触发，在那里返回 SUCCESS 会把箱子、工作台、熔炉、门全部吃掉——
 * 手里拿着烈焰弹就再也打不开箱子了。而 {@code useOn} 是原版在「方块没消费这次交互」之后
 * 才调用的，所以拦这里天然拿到正确的优先级：能开的照样开，只有本来会点火的那一下改成投掷。
 *
 * <p>火球本身完全用原版 {@link LargeFireball}，所以这些特性是白送的：
 * 爆炸点火、小范围击退、{@code explosionPower} 控制破坏力，以及
 * <b>被剑打一下能反弹并转移归属</b>（{@code AbstractHurtingProjectile#hurt} 里的逻辑，
 * owner 是谁都成立，所以自己也能把自己扔出去的火球打回去）。
 */
public final class FireballThrower {

	/** {@code EntityType.FIREBALL} 的尺寸是 1×1，半宽 0.5，用来判生成点会不会撞到自己 */
	private static final double FIREBALL_HALF_WIDTH = 0.5D;

	/** 生成点外推的次数上限。正常情况一两步就够，这个数只是兜住死循环 */
	private static final int MAX_SPAWN_PUSHES = 16;

	private FireballThrower() {
	}

	/**
	 * 尝试投掷。两个入口都调这一个实现，行为不会分叉。
	 *
	 * @return {@code PASS} 表示「这次交互不归我管」，调用方必须原样放行；
	 *         {@code FAIL} 表示冷却中、要吞掉这次交互；
	 *         其余是 {@code sidedSuccess}，客户端摆手、服务端消费
	 */
	public static InteractionResult tryThrow(Player player, Level level, InteractionHand hand) {
		if (player == null || !FunModConfig.fireballEnabled) {
			return InteractionResult.PASS;
		}
		var stack = player.getItemInHand(hand);
		if (stack.isEmpty() || !stack.is(Items.FIRE_CHARGE)) {
			return InteractionResult.PASS;
		}

		// 冷却中：吞掉这次交互但**不放行点火**。
		// 返回 FAIL 是安全的——useOn 只有在方块自己拒绝交互之后才会被调到，
		// 箱子/工作台早在上一步就消费掉了，这里 FAIL 只会挡住"点火"这一件事，
		// 不会出现拿着烈焰弹打不开箱子的情况。
		if (player.getCooldowns().isOnCooldown(Items.FIRE_CHARGE)) {
			return InteractionResult.FAIL;
		}

		// 下面三件事**两端都要各做一次**，不是只在服务端做。
		// ItemCooldowns 和玩家物品栏都是每端一份、彼此没有同步包：
		// 冷却只在服务端记的话，客户端快捷栏不会画冷却遮罩；
		// 消耗只在服务端做的话，客户端要等一个纠正包才更新，中间那一帧手里还拿着旧的。
		// 原版 SnowballItem#use 就是这么写的——playSound / shrink 在 if 外面，
		// 只有生成实体在 !isClientSide 里面。
		level.playSound(player, player.getX(), player.getY(), player.getZ(),
				SoundEvents.GHAST_SHOOT, SoundSource.PLAYERS,
				FunModConfig.fireballVolume, FunModConfig.fireballPitch);

		player.getCooldowns().addCooldown(Items.FIRE_CHARGE, FunModConfig.fireballCooldownTicks);

		// 创造模式不消耗。用 isCreative() 而不是 getAbilities().instabuild，
		// 前者语义就是"会不会消耗物品"，后者还要考虑旁观模式
		if (!player.isCreative()) {
			stack.shrink(1);
		}

		if (!level.isClientSide) {
			spawn(player, level);
		}

		// sidedSuccess：客户端拿 SUCCESS（会摆手 + 发包给服务端），服务端拿 CONSUME。
		// 摆手交给它做，不要再手动 player.swing(hand)——那样会摆两次，动画看着抽一下。
		return InteractionResult.sidedSuccess(level.isClientSide);
	}

	private static void spawn(Player player, Level level) {
		Vec3 look = player.getLookAngle();
		Vec3 origin = player.getEyePosition();
		Vec3 at = safeSpawnPoint(player, origin, look);

		// 构造器会把火球摆在 owner 的位置上，所以必须显式 setPos。
		// 漏掉这一步火球就在玩家脚下方块里生成，当 tick 撞地爆炸——就是"炸膛"。
		LargeFireball fireball = new LargeFireball(level, player, look, FunModConfig.fireballExplosionPower);
		fireball.setPos(at.x, at.y, at.z);
		level.addFreshEntity(fireball);
	}

	/**
	 * 沿视线找一个不会和投掷者自己相交的生成点。
	 *
	 * <p>平视时默认距离 1.0 格就够了：玩家碰撞箱半宽 0.3，火球半宽 0.5，
	 * 中心放在 1.0 处时它的后缘在 0.5，不重叠。但<b>低头</b>投掷时这个结论不成立——
	 * 视线朝下，"往前 1 格"落到了自己脚下，火球会在生成的那一 tick 直接命中投掷者。
	 * 所以相交就继续往外推 0.5 格，直到分开为止。
	 */
	private static Vec3 safeSpawnPoint(Player player, Vec3 origin, Vec3 look) {
		double distance = FunModConfig.fireballSpawnDistance;
		Vec3 at = origin.add(look.scale(distance));
		AABB self = player.getBoundingBox();
		for (int i = 0; i < MAX_SPAWN_PUSHES
				&& new AABB(at, at).inflate(FIREBALL_HALF_WIDTH).intersects(self); i++) {
			distance += 0.5D;
			at = origin.add(look.scale(distance));
		}
		return at;
	}
}
