package com.example.impactengine;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.protocol.game.ClientboundExplodePacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 物理撞击与破坏结算引擎。所有武器、弹射物、技能冲撞共用这一条结算路径。
 *
 * <p>配置来自 {@link ImpactEffect}（可由 JSON 数据包提供，也可用 Builder 在代码里构造），
 * 执行时上下文（Level、中心、方向、来源实体、附魔等级）由参数传入。
 *
 * <p><b>只能在服务端调用。</b>伤害、击退、方块破坏都是服务端权威逻辑，
 * 客户端表现由这里的 {@code sendParticles} / {@code playSound} / 爆炸包广播出去。
 *
 * <p>安全底线（不可配置、不可绕过）：
 * <ul>
 *   <li>硬度为负的方块一律免疫——基岩、屏障、末地传送门框架、命令方块等</li>
 *   <li>{@code wither_immune} / {@code dragon_immune} 标签的方块一律免疫</li>
 *   <li>流体、空气不处理</li>
 *   <li>方块扫描半径上限 {@value #MAX_BLOCK_SCAN_RADIUS}，防止配置写错把服务器卡死</li>
 * </ul>
 */
public final class ImpactEngine {

	/** 结算结果，方便调用方记日志或做连锁反应 */
	public record Result(int blocksDestroyed, int entitiesHit) {
		public static final Result EMPTY = new Result(0, 0);

		public boolean didAnything() {
			return blocksDestroyed > 0 || entitiesHit > 0;
		}
	}

	/** 视觉/音效广播距离，与原版爆炸的可听范围同量级 */
	private static final double BROADCAST_RANGE = 64.0D;
	private static final double BROADCAST_RANGE_SQR = BROADCAST_RANGE * BROADCAST_RANGE;

	/** 方块扫描半径上限。半径 16 意味着最多遍历 33^3 ≈ 3.6 万个位置 */
	private static final float MAX_BLOCK_SCAN_RADIUS = 16.0F;

	/** 点火概率，对齐原版爆炸的 1/3 */
	private static final float FIRE_CHANCE = 1.0F / 3.0F;

	private ImpactEngine() {
	}

	/** 便捷入口：不指定方向（锥形会退化成球形），附魔等级按 1 算。 */
	public static Result execute(ServerLevel level, ImpactEffect effect, Vec3 center, @Nullable Entity source) {
		return execute(level, effect, center, null, source, 1);
	}

	/**
	 * 执行一次撞击结算。
	 *
	 * @param level            必须是服务端世界
	 * @param effect           配置
	 * @param center           撞击中心
	 * @param direction        主推进方向，锥形判定和 DIRECTIONAL 击退用它；可为 null
	 * @param source           来源实体（谁打出去的），可为 null；用于 selfDamageMultiplier 和 excludeSource
	 * @param enchantmentLevel 附魔等级，所有 {@link net.minecraft.world.item.enchantment.LevelBasedValue}
	 *                         字段按它缩放；非附魔来源传 1
	 */
	public static Result execute(ServerLevel level, ImpactEffect effect, Vec3 center,
			@Nullable Vec3 direction, @Nullable Entity source, int enchantmentLevel) {
		if (level.isClientSide) {
			throw new IllegalStateException("ImpactEngine 只能在服务端调用");
		}
		if (effect == null) {
			return Result.EMPTY;
		}

		int level_ = Math.max(1, enchantmentLevel);
		float radius = effect.area().radiusAt(level_);
		if (radius <= 0.0F) {
			return Result.EMPTY;
		}

		Vec3 dir = normalizeOrNull(direction);
		int destroyed = effect.blocks().destroy()
				? destroyBlocks(level, effect, center, dir, source, radius, level_)
				: 0;
		int hit = affectEntities(level, effect, center, dir, source, radius, level_);
		broadcast(level, effect, center);
		return new Result(destroyed, hit);
	}

	// ------------------------------------------------------------------ 方块

	private static int destroyBlocks(ServerLevel level, ImpactEffect effect, Vec3 center,
			@Nullable Vec3 direction, @Nullable Entity source, float radius, int enchantmentLevel) {
		ImpactEffect.Blocks config = effect.blocks();
		float scanRadius = Math.min(radius, MAX_BLOCK_SCAN_RADIUS);
		if (scanRadius < radius) {
			ImpactEngineMod.LOGGER.warn("[impact] 半径 {} 超过方块扫描上限 {}，方块破坏按上限处理（实体判定不受影响）",
					radius, MAX_BLOCK_SCAN_RADIUS);
		}

		BlockPos origin = BlockPos.containing(center);
		int r = Mth.ceil(scanRadius);
		@Nullable TagKey<net.minecraft.world.level.block.Block> immuneTag = config.immuneTag() != null
				? TagKey.create(Registries.BLOCK, config.immuneTag())
				: null;
		RandomSource random = level.getRandom();
		ImpactEffect.Area area = effect.area();

		// 离中心远的方块先破坏，避免支撑结构先塌导致上面的方块判定异常。
		// 注意 betweenClosed 返回 Iterable，要用 betweenClosedStream 才能接 Stream；
		// 而且它给的是复用的 MutableBlockPos，不 immutable() 就会全部指向同一个对象。
		List<BlockPos> candidates = BlockPos.betweenClosedStream(
						origin.getX() - r, origin.getY() - r, origin.getZ() - r,
						origin.getX() + r, origin.getY() + r, origin.getZ() + r)
				.filter(pos -> inShape(area, Vec3.atCenterOf(pos), center, direction, scanRadius))
				.filter(pos -> !isImmune(level, pos, config, immuneTag))
				.map(BlockPos::immutable)
				.sorted((a, b) -> Double.compare(
						Vec3.atCenterOf(b).distanceToSqr(center),
						Vec3.atCenterOf(a).distanceToSqr(center)))
				.toList();

		int destroyed = 0;
		for (BlockPos pos : candidates) {
			BlockState state = level.getBlockState(pos);
			// 排序和破坏之间可能有方块已经变了（比如上面的沙子塌下来），重新校验一次
			if (state.isAir() || isImmuneState(level, pos, state, config, immuneTag)) {
				continue;
			}

			if (config.entitizeChance() > 0.0F && random.nextFloat() < config.entitizeChance()) {
				launchAsFallingBlock(level, pos, state, center, random);
			} else {
				boolean drop = random.nextFloat() < config.dropRate();
				level.destroyBlock(pos, drop, source, 0);
			}
			destroyed++;

			if (config.causesFire() && random.nextFloat() < FIRE_CHANCE
					&& level.isEmptyBlock(pos) && level.getBlockState(pos.below()).isSolidRender(level, pos.below())) {
				level.setBlockAndUpdate(pos, Blocks.FIRE.defaultBlockState());
			}
		}
		return destroyed;
	}

	/** 硬性免疫 + 配置免疫，两层一起判 */
	private static boolean isImmune(ServerLevel level, BlockPos pos, ImpactEffect.Blocks config,
			@Nullable TagKey<net.minecraft.world.level.block.Block> immuneTag) {
		return isImmuneState(level, pos, level.getBlockState(pos), config, immuneTag);
	}

	private static boolean isImmuneState(ServerLevel level, BlockPos pos, BlockState state,
			ImpactEffect.Blocks config, @Nullable TagKey<net.minecraft.world.level.block.Block> immuneTag) {
		if (state.isAir() || !state.getFluidState().isEmpty()) {
			return true;
		}
		// 基岩、屏障、末地传送门框架、命令方块等硬度为 -1，无条件免疫
		float hardness = state.getDestroySpeed(level, pos);
		if (hardness < 0.0F || hardness > config.maxHardness()) {
			return true;
		}
		if (state.is(BlockTags.WITHER_IMMUNE) || state.is(BlockTags.DRAGON_IMMUNE)) {
			return true;
		}
		return immuneTag != null && state.is(immuneTag);
	}

	/** 把方块变成带初速度的下落方块飞出去，纯视觉，不产生掉落物 */
	private static void launchAsFallingBlock(ServerLevel level, BlockPos pos, BlockState state,
			Vec3 center, RandomSource random) {
		Vec3 blockCenter = Vec3.atCenterOf(pos);
		Vec3 away = blockCenter.subtract(center);
		double horizontal = Math.sqrt(away.x * away.x + away.z * away.z);
		Vec3 push = horizontal > 1.0E-4
				? new Vec3(away.x / horizontal, 0.0D, away.z / horizontal)
				: new Vec3(random.nextFloat() - 0.5F, 0.0D, random.nextFloat() - 0.5F).normalize();

		// fall() 内部已经把原位置换成空气，所以不要再 destroyBlock
		FallingBlockEntity entity = FallingBlockEntity.fall(level, pos, state);
		entity.dropItem = false;
		double speed = 0.3D + random.nextDouble() * 0.5D;
		entity.setDeltaMovement(push.x * speed, 0.2D + random.nextDouble() * 0.55D, push.z * speed);
		level.addFreshEntity(entity);
	}

	// ------------------------------------------------------------------ 实体

	private static int affectEntities(ServerLevel level, ImpactEffect effect, Vec3 center,
			@Nullable Vec3 direction, @Nullable Entity source, float radius, int enchantmentLevel) {
		AABB box = new AABB(center, center).inflate(radius);
		List<Entity> candidates = level.getEntities((Entity) null, box, ImpactEngine::isEligible);

		DamageSource damageSource = buildDamageSource(level, effect, source, center);
		ImpactEffect.Area area = effect.area();
		float baseDamage = effect.damage().amountAt(enchantmentLevel);
		int hit = 0;

		for (Entity target : candidates) {
			if (!inShape(area, target.position(), center, direction, radius)) {
				continue;
			}

			double distance = target.position().distanceTo(center);
			float falloff = area.falloffAt(distance, radius);
			boolean isSource = target == source;

			if (damageSource != null && baseDamage > 0.0F) {
				float amount = baseDamage * falloff;
				if (isSource) {
					amount *= effect.damage().selfDamageMultiplier();
				}
				if (amount > 0.0F && target.hurt(damageSource, amount)) {
					hit++;
				}
			}

			if (effect.knockback().enabled() && !(isSource && effect.knockback().excludeSource())) {
				applyKnockback(target, effect, center, direction, falloff, enchantmentLevel);
				if (baseDamage <= 0.0F) {
					hit++;
				}
			}

			if (!effect.statusEffects().isEmpty() && target instanceof LivingEntity living) {
				applyStatusEffects(living, effect, falloff);
			}
		}
		return hit;
	}

	/** 观察者模式不参与；乘客位置由载具接管，单独打飞会把两者撕裂 */
	private static boolean isEligible(Entity entity) {
		return entity.isAlive() && !entity.isSpectator() && !entity.isPassenger();
	}

	private static void applyKnockback(Entity target, ImpactEffect effect, Vec3 center,
			@Nullable Vec3 direction, float falloff, int enchantmentLevel) {
		ImpactEffect.Knockback config = effect.knockback();
		double horizontal = config.horizontalAt(enchantmentLevel) * falloff;
		double upward = config.upwardAt(enchantmentLevel);

		Vec3 push;
		if (config.mode() == ImpactEffect.KnockbackMode.DIRECTIONAL && direction != null) {
			push = new Vec3(direction.x, 0.0D, direction.z);
			if (push.lengthSqr() < 1.0E-6) {
				push = Vec3.ZERO;
			} else {
				push = push.normalize();
			}
		} else {
			Vec3 offset = target.position().subtract(center);
			double flat = Math.sqrt(offset.x * offset.x + offset.z * offset.z);
			push = flat > 1.0E-4 ? new Vec3(offset.x / flat, 0.0D, offset.z / flat) : Vec3.ZERO;
		}

		Vec3 motion = target.getDeltaMovement();
		target.setDeltaMovement(
				motion.x + push.x * horizontal,
				Math.max(motion.y, 0.0D) + upward,
				motion.z + push.z * horizontal);

		// 只改速度不同步的话，客户端会按自己预测的位置把玩家拽回去
		target.hurtMarked = true;
		if (target instanceof ServerPlayer player) {
			player.connection.send(new ClientboundSetEntityMotionPacket(player));
		}
	}

	private static void applyStatusEffects(LivingEntity target, ImpactEffect effect, float falloff) {
		for (ImpactEffect.StatusEffect configured : effect.statusEffects()) {
			@Nullable MobEffect mobEffect = BuiltInRegistries.MOB_EFFECT.get(configured.effect());
			if (mobEffect == null) {
				ImpactEngineMod.LOGGER.warn("[impact] 未知的药水效果 {}，已跳过", configured.effect());
				continue;
			}
			// 时长随衰减缩短，至少给 1 tick，否则边缘命中会完全没效果
			int duration = Math.max(1, Mth.ceil(configured.durationTicks() * falloff));
			Holder<MobEffect> holder = BuiltInRegistries.MOB_EFFECT.wrapAsHolder(mobEffect);
			target.addEffect(new MobEffectInstance(holder, duration, configured.amplifier()));
		}
	}

	@Nullable
	private static DamageSource buildDamageSource(ServerLevel level, ImpactEffect effect,
			@Nullable Entity source, Vec3 center) {
		Registry<DamageType> registry = level.registryAccess().registryOrThrow(Registries.DAMAGE_TYPE);
		ResourceKey<DamageType> key = ResourceKey.create(Registries.DAMAGE_TYPE, effect.damage().damageType());
		@Nullable DamageType type = registry.get(key);
		if (type == null) {
			ImpactEngineMod.LOGGER.error("[impact] damage_type {} 不存在，回退到 minecraft:generic。检查 JSON 或数据包",
					effect.damage().damageType());
			key = DamageTypes.GENERIC;
			type = registry.get(key);
			if (type == null) {
				return null;
			}
		}
		DamageSource damageSource = new DamageSource(registry.wrapAsHolder(type), source);
		return damageSource;
	}

	// ------------------------------------------------------------------ 表现

	private static void broadcast(ServerLevel level, ImpactEffect effect, Vec3 center) {
		ImpactEffect.Visuals visuals = effect.visuals();
		if (visuals.cameraShake() > 0.0F) {
			// 走原版爆炸包：一次拿到粒子、声音和屏幕震动。
			// knockback 传 ZERO，因为击退由 applyKnockback 逐实体精确计算，不能叠加两份；
			// toBlow 传空表，避免客户端以为方块被破坏而闪一下。
			Holder<SoundEvent> sound = BuiltInRegistries.SOUND_EVENT.wrapAsHolder(visuals.sound());
			ParticleOptions particle = visuals.primaryParticle();
			for (ServerPlayer player : level.players()) {
				if (player.distanceToSqr(center.x, center.y, center.z) <= BROADCAST_RANGE_SQR) {
					player.connection.send(new ClientboundExplodePacket(
							center.x, center.y, center.z,
							visuals.cameraShake(),
							List.of(),
							Vec3.ZERO,
							Explosion.BlockInteraction.TRIGGER_BLOCK,
							particle, particle, sound));
				}
			}
			return;
		}

		// 不要震动时走常规通道，粒子数量可以精确控制
		if (visuals.particleCount() > 0) {
			level.sendParticles(visuals.primaryParticle(),
					center.x, center.y, center.z, visuals.particleCount(),
					0.5D, 0.5D, 0.5D, 0.0D);
		}
		if (!visuals.silent()) {
			level.playSound(null, BlockPos.containing(center), visuals.sound(), SoundSource.BLOCKS,
					visuals.soundVolume(), visuals.soundPitch());
		}
	}

	// ------------------------------------------------------------------ 形状

	/**
	 * 判定一个点是否落在配置的判定区域内。
	 *
	 * <p>锥形沿 {@code direction} 的水平分量展开；没给方向时退化成球形，
	 * 这样"锥形配置 + 无方向调用"不会静默地什么都打不到。
	 */
	private static boolean inShape(ImpactEffect.Area area, Vec3 point, Vec3 center,
			@Nullable Vec3 direction, float radius) {
		Vec3 offset = point.subtract(center);
		if (offset.lengthSqr() > (double) radius * radius) {
			return false;
		}
		return switch (area.shape()) {
			case SPHERICAL -> true;
			case CYLINDER -> Math.abs(offset.y) <= area.cylinderHeight() * 0.5D
					&& horizontalLength(offset) <= radius;
			case CONE -> insideCone(area, offset, direction);
		};
	}

	private static boolean insideCone(ImpactEffect.Area area, Vec3 offset, @Nullable Vec3 direction) {
		if (direction == null) {
			return true;
		}
		double flat = horizontalLength(offset);
		if (flat < 1.0E-4) {
			// 正好在锥轴上，算命中
			return true;
		}
		Vec3 axis = new Vec3(direction.x, 0.0D, direction.z);
		if (axis.lengthSqr() < 1.0E-6) {
			return true;
		}
		double cosine = (offset.x * axis.x + offset.z * axis.z) / (flat * axis.length());
		return cosine >= Math.cos(Math.toRadians(area.coneAngleDegrees() * 0.5D));
	}

	private static double horizontalLength(Vec3 vec) {
		return Math.sqrt(vec.x * vec.x + vec.z * vec.z);
	}

	@Nullable
	private static Vec3 normalizeOrNull(@Nullable Vec3 direction) {
		if (direction == null || direction.lengthSqr() < 1.0E-6) {
			return null;
		}
		return direction.normalize();
	}
}
