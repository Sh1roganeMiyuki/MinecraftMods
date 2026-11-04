package com.example.funmod.doorslam;

import com.example.funmod.FunMod;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 门扇挥扫冲击。把开关门抽象成一块绕门轴旋转 90° 的刚体，
 * 对它扫过的那四分之一圆内的实体施加切线方向的瞬时冲力。
 *
 * <h2>门轴（铰链）在哪</h2>
 * 原版 {@code DoorBlock} 关闭时门板厚 3/16，贴在方块的 FACING 那一侧边缘，
 * 铰链是门板的一条垂直棱，位于方块的左棱或右棱上：
 * <pre>
 *   俯视，FACING=NORTH（-Z，图中向上），HINGE=LEFT
 *
 *              北
 *          ┌──●━━━━┓        ● = 门轴，在方块西棱 ∩ 门板平面
 *          │  ┃    │        ┃ = 关闭时的门板（贴在北缘）
 *          └──┸━━━━┛
 *              ↓
 *      开门后门扇绕 ● 向南展开
 * </pre>
 * {@code HINGE=LEFT} 对应 {@code FACING.getCounterClockWise()}，
 * {@code HINGE=RIGHT} 对应 {@code FACING.getClockWise()}。
 * 这是从原版 {@code getStateForPlacement} 的铰链判定反推的：FACING=NORTH 时
 * 点击方块西半（x&lt;0.5）会得到 LEFT，而面朝北时的左手边正是西。
 *
 * <h2>旋转方向</h2>
 * 关门时门扇自由端指向 {@code -side}，开门后指向 {@code -FACING}（门朝背离
 * FACING 的方向展开）。两者的叉积 y 分量给出俯视旋转方向，关→开与开→关取反。
 */
public final class DoorSlam {
	/** 门板平面中心相对方块中心的内缩量：0.5 - (3/16)/2 */
	private static final double PANEL_INSET = 0.5D - 3.0D / 32.0D;

	/** 刚体旋转时线速度正比于半径，这里给出杠杆系数的上下限 */
	private static final double LEVERAGE_MIN = 0.8D;
	private static final double LEVERAGE_MAX = 1.2D;

	/** 门上下两半合计高度 */
	private static final double DOOR_HEIGHT = 2.0D;

	/** 去重表膨胀到该规模时清理陈旧条目 */
	private static final int MAX_TRACKED_DOORS = 256;

	/** 门轴 → 上次处理的 gameTime，用于压掉上下两半的重复触发 */
	private static final Map<GlobalPos, Long> RECENT = new ConcurrentHashMap<>();

	private DoorSlam() {
	}

	/**
	 * 门开关的唯一入口，由 {@code DoorBlockMixin} 在 {@code DoorBlock#playSound} 头部调用。
	 *
	 * @param source 触发者，红石触发时为 null
	 * @param open   变更<b>之后</b>的开关状态
	 */
	public static void onDoorToggled(@Nullable Entity source, Level level, BlockPos pos, boolean open) {
		// 客户端会为了交互预测同样跑一遍 useWithoutItem，物理判定必须只在服务端做
		if (level.isClientSide) {
			return;
		}
		DoorSlamConfig config = FunMod.DOOR_SLAM;
		if (!config.enabled) {
			return;
		}
		BlockState state = level.getBlockState(pos);
		if (!(state.getBlock() instanceof DoorBlock)) {
			return;
		}

		// 红石会让上下两半各触发一次，先归一化到下半再去重，否则冲力会翻倍
		DoubleBlockHalf half = state.getValue(DoorBlock.HALF);
		BlockPos anchor = half == DoubleBlockHalf.LOWER ? pos : pos.relative(half.getDirectionToOther());
		if (!claimOnce(level, anchor)) {
			return;
		}

		Swing swing = Swing.of(state, anchor, open, config);

		AABB area = new AABB(
				swing.hinge().x - config.reach, anchor.getY(), swing.hinge().z - config.reach,
				swing.hinge().x + config.reach, anchor.getY() + DOOR_HEIGHT, swing.hinge().z + config.reach);
		List<Entity> candidates = level.getEntities((Entity) null, area, DoorSlam::canBeSlammed);

		int hits = 0;
		for (Entity target : candidates) {
			if (slam(target, swing, config)) {
				hits++;
			}
		}
		if (hits > 0) {
			feedback(level, anchor, swing, config);
			FunMod.LOGGER.debug("[door-slam] {} 在 {} 扫中 {} 个实体（方向模式 {}）",
					open ? "开门" : "关门", anchor.toShortString(), hits, swing.mode());
		}
	}

	/** 击飞方向的算法，对应配置项 {@code knockbackMode} */
	public enum KnockbackMode {
		/** 切线 v = ω × r，物理最准，但扇形边缘会把实体推向门轴一侧 */
		TANGENTIAL,
		/** 门扇自由端的位移方向（closedDir → openDir 的差），45° 斜向 */
		SWEEP,
		/** 垂直于**交互后**的门板：开门推向 closedDir，关门推向 openDir */
		NORMAL,
		/** 从门轴往外推，永远远离门轴 */
		RADIAL
	}

	/** 大小写不敏感地解析模式名，认不出来返回 null，让调用方决定怎么兜底 */
	public static KnockbackMode parseMode(String name) {
		if (name == null) {
			return null;
		}
		try {
			return KnockbackMode.valueOf(name.trim().toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	/**
	 * 一次门开关的几何信息。算一次，给判定区里每个实体复用。
	 *
	 * @param hinge     门轴坐标
	 * @param closedDir 关门时门扇自由端的朝向
	 * @param openDir   开门后门扇自由端的朝向
	 * @param spin      角速度符号，+1 俯视逆时针、-1 顺时针，已按开/关取好向
	 * @param sweep     SWEEP 模式用的固定方向
	 * @param normal    NORMAL 模式用的固定方向，即交互后门板的法线
	 * @param mode      击飞方向算法
	 */
	private record Swing(Vec3 hinge, Vec3 closedDir, Vec3 openDir, double spin, Vec3 sweep,
			Vec3 normal, KnockbackMode mode) {

		static Swing of(BlockState state, BlockPos anchor, boolean open, DoorSlamConfig config) {
			Direction facing = state.getValue(DoorBlock.FACING);
			Direction side = state.getValue(DoorBlock.HINGE) == DoorHingeSide.LEFT
					? facing.getCounterClockWise()
					: facing.getClockWise();
			Vec3 hinge = Vec3.atBottomCenterOf(anchor)
					.add(vector(facing).scale(PANEL_INSET))
					.add(vector(side).scale(0.5D));

			// 门扇自由端的两个极限朝向，二者天然正交
			Vec3 closed = vector(side).scale(-1.0D);
			Vec3 opened = vector(facing).scale(-1.0D);
			double spin = Math.signum(closed.cross(opened).y);
			if (!open) {
				spin = -spin;
			}

			// SWEEP：门扇自由端从 closed 移到 opened 的位移方向，关门时反过来。
			// 关键区别在于它不随实体站位变化，所以不会出现
			// “人站在正南、却沿切线被拍向正西门轴” 那种别扭观感
			Vec3 sweep = opened.subtract(closed);
			sweep = sweep.lengthSqr() < 1.0E-6
					? Vec3.ZERO
					: sweep.normalize().scale(open ? 1.0D : -1.0D);

			// NORMAL：垂直于交互后的门板。开门后门板沿 opened，法线就是 closed；
			// 关门后门板沿 closed，法线是 opened。因为两者本来就正交，不用额外算
			Vec3 normal = open ? closed : opened;

			KnockbackMode parsed = parseMode(config.knockbackMode);
			return new Swing(hinge, closed, opened, spin, sweep, normal,
					parsed == null ? KnockbackMode.TANGENTIAL : parsed);
		}

		/** 按当前模式算出击飞方向，返回单位水平向量 */
		Vec3 pushDirection(Vec3 radial) {
			return switch (mode) {
				// v = ω × r，ω 沿 Y 轴，因此切线方向是 (spin·z, 0, -spin·x)
				case TANGENTIAL -> new Vec3(spin * radial.z, 0.0D, -spin * radial.x);
				case SWEEP -> sweep;
				case NORMAL -> normal;
				case RADIAL -> radial;
			};
		}
	}

	/**
	 * 对单个实体施加冲力。
	 *
	 * @return 是否真的命中（落在门扇扫过的扇形内）
	 */
	private static boolean slam(Entity target, Swing swing, DoorSlamConfig config) {
		Vec3 offset = target.position().subtract(swing.hinge());
		double radius = Math.sqrt(offset.x * offset.x + offset.z * offset.z);
		if (radius > config.reach || radius < 1.0E-4D) {
			return false;
		}

		Vec3 radial = new Vec3(offset.x / radius, 0.0D, offset.z / radius);
		// 门扇只扫过 closedDir 与 openDir 夹出的那 90°，扇形外的实体（多半在墙里）不该挨打
		if (radial.dot(swing.closedDir()) < -config.angleTolerance
				|| radial.dot(swing.openDir()) < -config.angleTolerance) {
			return false;
		}

		Vec3 push = swing.pushDirection(radial);
		double horizontal = config.horizontalStrength * Mth.clamp(radius, LEVERAGE_MIN, LEVERAGE_MAX);
		Vec3 motion = target.getDeltaMovement();
		target.setDeltaMovement(
				push.x * horizontal,
				Math.max(motion.y, 0.0D) + config.verticalStrength,
				push.z * horizontal);

		// 只改速度不同步的话，客户端会按自己预测的位置把玩家拽回去
		target.hurtMarked = true;
		if (target instanceof ServerPlayer player) {
			player.connection.send(new ClientboundSetEntityMotionPacket(player));
		}
		return true;
	}

	/** 观察者模式不参与，乘客的位置由载具接管，单独打飞会把两者撕裂。 */
	private static boolean canBeSlammed(Entity entity) {
		return entity.isAlive() && !entity.isSpectator() && !entity.isPassenger();
	}

	private static void feedback(Level level, BlockPos anchor, Swing swing, DoorSlamConfig config) {
		level.playSound(null, anchor, SoundEvents.SHIELD_BLOCK, SoundSource.BLOCKS,
				config.soundVolume, 0.85F + level.getRandom().nextFloat() * 0.3F);

		if (config.particles && level instanceof ServerLevel server) {
			// 扇形的角平分线中点，大致就是门扇拍中东西的位置
			Vec3 mid = swing.hinge()
					.add(swing.closedDir().add(swing.openDir()).normalize().scale(config.reach * 0.5D))
					.add(0.0D, 1.0D, 0.0D);
			server.sendParticles(ParticleTypes.CRIT, mid.x, mid.y, mid.z, 14, 0.35D, 0.9D, 0.35D, 0.05D);
			server.sendParticles(ParticleTypes.SMOKE, mid.x, mid.y, mid.z, 8, 0.3D, 0.8D, 0.3D, 0.01D);
		}
	}

	/**
	 * 同一游戏刻内同一扇门只放行一次。
	 * {@code neighborChanged} 综合了上下两半的红石信号，两半都会各调一次 playSound。
	 */
	private static boolean claimOnce(Level level, BlockPos anchor) {
		GlobalPos key = GlobalPos.of(level.dimension(), anchor);
		long gameTime = level.getGameTime();
		Long previous = RECENT.put(key, gameTime);
		if (previous != null && previous == gameTime) {
			return false;
		}
		if (RECENT.size() > MAX_TRACKED_DOORS) {
			RECENT.values().removeIf(seen -> seen < gameTime - 2L);
		}
		return true;
	}

	private static Vec3 vector(Direction direction) {
		return new Vec3(direction.getStepX(), direction.getStepY(), direction.getStepZ());
	}
}
