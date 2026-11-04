package com.example.impactengine;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.item.enchantment.LevelBasedValue;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * 一次撞击结算的**纯配置**，不可变、可序列化、可复用。
 *
 * <p>刻意不包含 Level / 中心坐标 / 来源实体 / 方向这些执行时才知道的东西——
 * 那些是 {@link ImpactEngine#execute} 的参数。配置和上下文分离，同一份 JSON
 * 才能在不同位置、被不同武器反复触发。
 *
 * <p>数值字段一律用 {@link LevelBasedValue} 而不是裸 float：它的 CODEC 同时接受
 * {@code 12.0} 这种裸数字和 {@code {"type":"minecraft:linear",...}} 这种对象，
 * 所以 JSON 照旧好写，而接到附魔上时能自动按附魔等级缩放。
 *
 * <p>JSON 放在 {@code data/<命名空间>/impact_effects/<名字>.json}，
 * id 由文件路径决定（{@code my_mod:tnt_spear_impact}），文件里不需要写 id 字段；
 * 写了也会被忽略，因为 RecordCodecBuilder 默认容忍未知字段。
 */
public record ImpactEffect(
		Area area,
		Damage damage,
		Knockback knockback,
		Blocks blocks,
		List<StatusEffect> statusEffects,
		Visuals visuals
) {

	public static final Codec<ImpactEffect> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			Area.CODEC.fieldOf("area").forGetter(ImpactEffect::area),
			Damage.CODEC.fieldOf("damage").forGetter(ImpactEffect::damage),
			Knockback.CODEC.optionalFieldOf("knockback", Knockback.NONE).forGetter(ImpactEffect::knockback),
			Blocks.CODEC.optionalFieldOf("blocks", Blocks.NONE).forGetter(ImpactEffect::blocks),
			Codec.list(StatusEffect.CODEC).optionalFieldOf("status_effects", List.of()).forGetter(ImpactEffect::statusEffects),
			Visuals.CODEC.optionalFieldOf("visuals", Visuals.DEFAULT).forGetter(ImpactEffect::visuals)
	).apply(instance, ImpactEffect::new));

	/** 判定形状。锥形沿 execute 传入的 direction 展开。 */
	public enum Shape {
		SPHERICAL, CONE, CYLINDER
	}

	/** 距离衰减曲线，作用于伤害和击退强度。 */
	public enum Falloff {
		/** 不衰减，范围内一律满额 */
		FLAT,
		/** 线性衰减，边缘归零 */
		LINEAR,
		/** 指数衰减，中心附近更"硬" */
		EXPONENTIAL
	}

	/** 击退方向模式。 */
	public enum KnockbackMode {
		/** 径向爆发：从中心点向外推 */
		RADIAL,
		/** 方向推进：沿 execute 传入的 direction 推 */
		DIRECTIONAL
	}

	public record Area(
			LevelBasedValue radius,
			Shape shape,
			Falloff falloff,
			float coneAngleDegrees,
			float cylinderHeight
	) {
		public static final Codec<Area> CODEC = RecordCodecBuilder.create(instance -> instance.group(
				LevelBasedValue.CODEC.fieldOf("radius").forGetter(Area::radius),
				shapeCodec().optionalFieldOf("shape", Shape.SPHERICAL).forGetter(Area::shape),
				falloffCodec().optionalFieldOf("falloff", Falloff.LINEAR).forGetter(Area::falloff),
				Codec.floatRange(1.0F, 360.0F).optionalFieldOf("cone_angle", 90.0F).forGetter(Area::coneAngleDegrees),
				Codec.floatRange(0.5F, 64.0F).optionalFieldOf("cylinder_height", 2.0F).forGetter(Area::cylinderHeight)
		).apply(instance, Area::new));

		/** 按附魔等级算出的实际半径 */
		public float radiusAt(int enchantmentLevel) {
			return Math.max(0.0F, radius.calculate(enchantmentLevel));
		}

		/**
		 * 形状内某点的衰减系数，返回值在 [0, 1]。
		 *
		 * @param distance 该点到中心的距离
		 */
		public float falloffAt(double distance, float effectiveRadius) {
			if (effectiveRadius <= 0.0F) {
				return 0.0F;
			}
			double t = Math.clamp(distance / effectiveRadius, 0.0D, 1.0D);
			return switch (falloff) {
				case FLAT -> 1.0F;
				case LINEAR -> (float) (1.0D - t);
				case EXPONENTIAL -> (float) Math.exp(-3.0D * t);
			};
		}

		private static Codec<Shape> shapeCodec() {
			return enumCodec(Shape.class);
		}

		private static Codec<Falloff> falloffCodec() {
			return enumCodec(Falloff.class);
		}
	}

	public record Damage(
			LevelBasedValue baseAmount,
			ResourceLocation damageType,
			float selfDamageMultiplier
	) {
		public static final Codec<Damage> CODEC = RecordCodecBuilder.create(instance -> instance.group(
				LevelBasedValue.CODEC.fieldOf("base_amount").forGetter(Damage::baseAmount),
				ResourceLocation.CODEC.optionalFieldOf("damage_type", DamageTypes.PLAYER_EXPLOSION.location())
						.forGetter(Damage::damageType),
				Codec.floatRange(0.0F, 100.0F).optionalFieldOf("self_damage_multiplier", 1.0F)
						.forGetter(Damage::selfDamageMultiplier)
		).apply(instance, Damage::new));

		public float amountAt(int enchantmentLevel) {
			return Math.max(0.0F, baseAmount.calculate(enchantmentLevel));
		}
	}

	public record Knockback(
			LevelBasedValue horizontalStrength,
			LevelBasedValue upwardBias,
			KnockbackMode mode,
			boolean excludeSource
	) {
		/** 不击退 */
		public static final Knockback NONE = new Knockback(
				LevelBasedValue.constant(0.0F), LevelBasedValue.constant(0.0F), KnockbackMode.RADIAL, false);

		public static final Codec<Knockback> CODEC = RecordCodecBuilder.create(instance -> instance.group(
				LevelBasedValue.CODEC.optionalFieldOf("horizontal_strength", LevelBasedValue.constant(0.0F))
						.forGetter(Knockback::horizontalStrength),
				LevelBasedValue.CODEC.optionalFieldOf("upward_bias", LevelBasedValue.constant(0.0F))
						.forGetter(Knockback::upwardBias),
				knockbackModeCodec().optionalFieldOf("mode", KnockbackMode.RADIAL).forGetter(Knockback::mode),
				Codec.BOOL.optionalFieldOf("exclude_source", false).forGetter(Knockback::excludeSource)
		).apply(instance, Knockback::new));

		public float horizontalAt(int enchantmentLevel) {
			return Math.max(0.0F, horizontalStrength.calculate(enchantmentLevel));
		}

		public float upwardAt(int enchantmentLevel) {
			return Math.max(0.0F, upwardBias.calculate(enchantmentLevel));
		}

		public boolean enabled() {
			return horizontalStrength.calculate(1) > 0.0F || upwardBias.calculate(1) > 0.0F;
		}

		private static Codec<KnockbackMode> knockbackModeCodec() {
			return enumCodec(KnockbackMode.class);
		}
	}

	public record Blocks(
			boolean destroy,
			float maxHardness,
			@Nullable ResourceLocation immuneTag,
			float dropRate,
			boolean causesFire,
			float entitizeChance
	) {
		/** 完全不碰方块 */
		public static final Blocks NONE = new Blocks(false, 0.0F, null, 0.0F, false, 0.0F);

		public static final Codec<Blocks> CODEC = RecordCodecBuilder.create(instance -> instance.group(
				Codec.BOOL.optionalFieldOf("destroy", false).forGetter(Blocks::destroy),
				Codec.floatRange(-1.0F, 1000.0F).optionalFieldOf("max_hardness", 3.0F).forGetter(Blocks::maxHardness),
				ResourceLocation.CODEC.optionalFieldOf("immune_tag")
						.xmap(optional -> optional.orElse(null), Optional::ofNullable)
						.forGetter(Blocks::immuneTag),
				Codec.floatRange(0.0F, 1.0F).optionalFieldOf("drop_rate", 1.0F).forGetter(Blocks::dropRate),
				Codec.BOOL.optionalFieldOf("causes_fire", false).forGetter(Blocks::causesFire),
				Codec.floatRange(0.0F, 1.0F).optionalFieldOf("entitize_chance", 0.0F).forGetter(Blocks::entitizeChance)
		).apply(instance, Blocks::new));
	}

	public record StatusEffect(
			ResourceLocation effect,
			int durationTicks,
			int amplifier
	) {
		public static final Codec<StatusEffect> CODEC = RecordCodecBuilder.create(instance -> instance.group(
				ResourceLocation.CODEC.fieldOf("effect").forGetter(StatusEffect::effect),
				Codec.intRange(1, 100000).fieldOf("duration_ticks").forGetter(StatusEffect::durationTicks),
				Codec.intRange(0, 255).optionalFieldOf("amplifier", 0).forGetter(StatusEffect::amplifier)
		).apply(instance, StatusEffect::new));
	}

	public record Visuals(
			ParticleOptions primaryParticle,
			int particleCount,
			SoundEvent sound,
			float soundVolume,
			float soundPitch,
			float cameraShake
	) {
		public static final Visuals DEFAULT = new Visuals(
				ParticleTypes.EXPLOSION_EMITTER, 1, SoundEvents.GENERIC_EXPLODE.value(), 1.0F, 1.0F, 0.0F);

		public static final Codec<Visuals> CODEC = RecordCodecBuilder.create(instance -> instance.group(
				ParticleTypes.CODEC.optionalFieldOf("primary_particle", ParticleTypes.EXPLOSION_EMITTER)
						.forGetter(Visuals::primaryParticle),
				Codec.intRange(0, 4096).optionalFieldOf("particle_count", 1).forGetter(Visuals::particleCount),
				SoundEvent.DIRECT_CODEC.optionalFieldOf("sound", SoundEvents.GENERIC_EXPLODE.value())
						.forGetter(Visuals::sound),
				Codec.floatRange(0.0F, 16.0F).optionalFieldOf("sound_volume", 1.0F).forGetter(Visuals::soundVolume),
				Codec.floatRange(0.0F, 4.0F).optionalFieldOf("sound_pitch", 1.0F).forGetter(Visuals::soundPitch),
				Codec.floatRange(0.0F, 10.0F).optionalFieldOf("camera_shake", 0.0F).forGetter(Visuals::cameraShake)
		).apply(instance, Visuals::new));

		public boolean silent() {
			return soundVolume <= 0.0F;
		}
	}

	/**
	 * 枚举的 codec 统一走字符串，避免依赖 StringRepresentable 在各版本间的签名变动。
	 * JSON 里大小写不敏感，写 {@code "spherical"} 或 {@code "SPHERICAL"} 都行。
	 */
	private static <E extends Enum<E>> Codec<E> enumCodec(Class<E> type) {
		return Codec.STRING.comapFlatMap(name -> {
			try {
				return DataResult.success(Enum.valueOf(type, name.trim().toUpperCase(Locale.ROOT)));
			} catch (IllegalArgumentException e) {
				return DataResult.error(() -> "未知取值 '" + name + "'，可选: "
						+ Arrays.toString(type.getEnumConstants()));
			}
		}, value -> value.name().toLowerCase(Locale.ROOT));
	}

	// ------------------------------------------------------------ Builder

	public static Builder builder() {
		return new Builder();
	}

	/** 代码侧构建配置用。所有字段都有可用默认值，只设你关心的即可。 */
	public static final class Builder {
		private Shape shape = Shape.SPHERICAL;
		private LevelBasedValue radius = LevelBasedValue.constant(3.0F);
		private Falloff falloff = Falloff.LINEAR;
		private float coneAngleDegrees = 90.0F;
		private float cylinderHeight = 2.0F;

		private LevelBasedValue damageAmount = LevelBasedValue.constant(6.0F);
		private ResourceLocation damageType = DamageTypes.PLAYER_EXPLOSION.location();
		private float selfDamageMultiplier = 1.0F;

		private LevelBasedValue horizontalStrength = LevelBasedValue.constant(0.0F);
		private LevelBasedValue upwardBias = LevelBasedValue.constant(0.0F);
		private KnockbackMode knockbackMode = KnockbackMode.RADIAL;
		private boolean excludeSource = false;

		private boolean destroyBlocks = false;
		private float maxHardness = 3.0F;
		@Nullable
		private ResourceLocation immuneTag = null;
		private float dropRate = 1.0F;
		private boolean causesFire = false;
		private float entitizeChance = 0.0F;

		private List<StatusEffect> statusEffects = List.of();

		private ParticleOptions particle = ParticleTypes.EXPLOSION_EMITTER;
		private int particleCount = 1;
		private SoundEvent sound = SoundEvents.GENERIC_EXPLODE.value();
		private float soundVolume = 1.0F;
		private float soundPitch = 1.0F;
		private float cameraShake = 0.0F;

		private Builder() {
		}

		public Builder shape(Shape value) {
			this.shape = value;
			return this;
		}

		public Builder radius(float value) {
			return radius(LevelBasedValue.constant(value));
		}

		/** 用 LevelBasedValue 可以让半径随附魔等级变化 */
		public Builder radius(LevelBasedValue value) {
			this.radius = value;
			return this;
		}

		public Builder falloff(Falloff value) {
			this.falloff = value;
			return this;
		}

		public Builder coneAngleDegrees(float value) {
			this.coneAngleDegrees = value;
			return this;
		}

		public Builder cylinderHeight(float value) {
			this.cylinderHeight = value;
			return this;
		}

		public Builder damage(float amount) {
			return damage(LevelBasedValue.constant(amount));
		}

		public Builder damage(LevelBasedValue amount) {
			this.damageAmount = amount;
			return this;
		}

		public Builder damageType(ResourceLocation value) {
			this.damageType = value;
			return this;
		}

		/** 0 表示来源实体完全免伤，1 表示和别的实体一样 */
		public Builder selfDamageMultiplier(float value) {
			this.selfDamageMultiplier = value;
			return this;
		}

		public Builder knockback(float horizontal, float upward) {
			this.horizontalStrength = LevelBasedValue.constant(horizontal);
			this.upwardBias = LevelBasedValue.constant(upward);
			return this;
		}

		public Builder knockbackMode(KnockbackMode value) {
			this.knockbackMode = value;
			return this;
		}

		/** 击退时跳过来源实体自己 */
		public Builder excludeSource(boolean value) {
			this.excludeSource = value;
			return this;
		}

		public Builder destroyBlocks(boolean value) {
			this.destroyBlocks = value;
			return this;
		}

		/** 硬度上限。基岩那种 -1 由引擎硬性免疫，跟这个值无关 */
		public Builder maxHardness(float value) {
			this.maxHardness = value;
			return this;
		}

		public Builder immuneTag(ResourceLocation value) {
			this.immuneTag = value;
			return this;
		}

		public Builder dropRate(float value) {
			this.dropRate = value;
			return this;
		}

		public Builder causesFire(boolean value) {
			this.causesFire = value;
			return this;
		}

		/** 被破坏的方块有多大比例变成带初速度的下落方块飞出去 */
		public Builder entitizeChance(float value) {
			this.entitizeChance = value;
			return this;
		}

		public Builder statusEffects(List<StatusEffect> value) {
			this.statusEffects = List.copyOf(value);
			return this;
		}

		public Builder addStatusEffect(ResourceLocation effect, int durationTicks, int amplifier) {
			this.statusEffects = Stream.concat(this.statusEffects.stream(),
					Stream.of(new StatusEffect(effect, durationTicks, amplifier))).toList();
			return this;
		}

		public Builder particle(ParticleOptions value, int count) {
			this.particle = value;
			this.particleCount = count;
			return this;
		}

		public Builder sound(SoundEvent value, float volume, float pitch) {
			this.sound = value;
			this.soundVolume = volume;
			this.soundPitch = pitch;
			return this;
		}

		/** 大于 0 时视觉音效走原版爆炸包，顺带产生屏幕震动 */
		public Builder cameraShake(float value) {
			this.cameraShake = value;
			return this;
		}

		public ImpactEffect build() {
			return new ImpactEffect(
					new Area(radius, shape, falloff, coneAngleDegrees, cylinderHeight),
					new Damage(damageAmount, damageType, selfDamageMultiplier),
					new Knockback(horizontalStrength, upwardBias, knockbackMode, excludeSource),
					new Blocks(destroyBlocks, maxHardness, immuneTag, dropRate, causesFire, entitizeChance),
					statusEffects,
					new Visuals(particle, particleCount, sound, soundVolume, soundPitch, cameraShake)
			);
		}
	}
}
