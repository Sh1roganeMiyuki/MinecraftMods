package com.example.impactengine;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.enchantment.EnchantedItemInUse;
import net.minecraft.world.item.enchantment.effects.EnchantmentEntityEffect;
import net.minecraft.world.phys.Vec3;

/**
 * 把 {@link ImpactEffect} 接到 1.21 的数据驱动附魔上。
 *
 * <p>1.21 的附魔本身就是数据包里的 JSON，效果按 {@code effects} 下的键名分发。
 * {@link net.minecraft.world.item.enchantment.effects.EnchantmentEntityEffect} 能挂在三个键下：
 * {@code minecraft:post_attack}（攻击命中后）、{@code minecraft:hit_block}（敲方块时）、
 * {@code minecraft:tick}（每 tick）。武器撞击用 {@code post_attack}。
 *
 * <p>注意 {@code post_attack} 用的是 {@code TargetedConditionalEffect}，比普通
 * {@code ConditionalEffect} 多出 {@code enchanted} / {@code affected} 两个目标字段，
 * 且 {@code effect} 要再包一层：
 *
 * <pre>{@code
 * {
 *   "anvil_cost": 4,
 *   "description": { "translate": "enchantment.spearmod.spear_impact" },
 *   "supported_items": "#minecraft:enchantable/sword",
 *   "weight": 2,
 *   "max_level": 3,
 *   "min_cost": { "base": 10, "per_level_above_first": 8 },
 *   "max_cost": { "base": 25, "per_level_above_first": 8 },
 *   "slots": ["mainhand"],
 *   "effects": {
 *     "minecraft:post_attack": [
 *       {
 *         "enchanted": "attacker",
 *         "affected": "victim",
 *         "effect": {
 *           "type": "impactengine:impact",
 *           "impact": "spearmod:tnt_spear_impact"
 *         }
 *       }
 *     ]
 *   }
 * }
 * }</pre>
 *
 * <p>{@code enchanted} 只能写 {@code "attacker"} 或 {@code "victim"}（原版字节码里就是这么校验的）。
 *
 * <p>附魔等级会原样传给 {@link ImpactEngine}，所以配置里的 {@code radius}、
 * {@code base_amount}、{@code horizontal_strength} 写成
 * {@code {"type":"minecraft:linear","base":6.0,"per_level_above_first":2.0}}
 * 就能随等级成长；写裸数字则各级一致。
 *
 * <p>刻意只支持按 id 引用、不支持内联整份配置：附魔 JSON 会因此保持可读，
 * 而且同一份撞击配置能被多个附魔、武器、技能共用。真要在代码里临时构造，
 * 用 {@code ImpactEffect.builder()} 配 {@code ImpactEngine.execute()} 即可，不必绕附魔。
 */
public record ImpactEnchantmentEffect(ResourceLocation impact) implements EnchantmentEntityEffect {

	public static final MapCodec<ImpactEnchantmentEffect> CODEC = RecordCodecBuilder.mapCodec(
			instance -> instance.group(
					ResourceLocation.CODEC.fieldOf("impact").forGetter(ImpactEnchantmentEffect::impact)
			).apply(instance, ImpactEnchantmentEffect::new));

	@Override
	public void apply(ServerLevel level, int enchantmentLevel, EnchantedItemInUse item,
			Entity target, Vec3 origin) {
		ImpactEffect effect = ImpactEffects.get(impact).orElse(null);
		if (effect == null) {
			ImpactEngineMod.LOGGER.error("[impact] 附魔引用的撞击配置 {} 不存在，检查 data/<命名空间>/impact_effects/ 下的文件名",
					impact);
			return;
		}
		// origin 是附魔触发的位置（命中点），item.owner() 是持有者，正好当作撞击来源
		ImpactEngine.execute(level, effect, origin, null, item.owner(), enchantmentLevel);
	}

	@Override
	public MapCodec<? extends EnchantmentEntityEffect> codec() {
		return CODEC;
	}
}
