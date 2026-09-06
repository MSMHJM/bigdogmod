package com.bigdog.skill;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.wolf.Wolf;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import com.bigdog.BigDogMod;
import com.bigdog.access.BigDogCargoAccess;
import com.bigdog.net.BigDogBoostPayload;
import com.bigdog.net.BigDogSkillPayload;

/**
 * 大狗技能与动作：
 * - 嚼嚼嚼（0）：瞬发，范围/伤害随体型衰减，满级 8 格 / 25 伤
 * - 坚守者音波（1 按住蓄力 / 2 松开释放）：蓄力音效加速变调，满蓄自动发射；
 *   射程 = 蓄力时长 x 1.2，单次查询去重（每实体只判定一次），伤害按蓄力进度 x 体型
 * - 打开运载（4）：骑乘时按 E，打开狗的运载空间
 * - 疾速冲刺（5）：装疾速升级后，20 秒内速度 ×3，冷却 60 秒（HUD 左下角冷却条）
 * - 喷气推进（6）：装喷气升级后，跳跃未落地时再按空格，向狗朝向斜上方推力并放粒子（冷却 20 秒）
 * - 冷却升级：N 个 = (90 - 5N)% 冷却时间（1 个 85%、2 个 80%、3 个 75%），作用于升级技能冷却
 */
public final class BigDogSkills {
	public static final int SKILL_MUNCH = 0;
	public static final int SKILL_WOOF = 1;
	public static final int SKILL_WOOF_RELEASE = 2;
	public static final int SKILL_OPEN_CARGO = 4;
	public static final int SKILL_BOOST = 5;
	public static final int SKILL_JET = 6;

	/** 体型缩放：基础 1.0，每次喂食 +0.8（累加），最多放大 3 次到 3.4 倍 */
	public static final double MIN_SCALE = 1.0;
	public static final double MAX_SCALE = 1.0 + 3 * 0.8; // 3.4
	public static final double SCALE_STEP = 0.8;

	/** 声波满蓄时长（tick），5 秒 */
	public static final int MAX_CHARGE_TICKS = 100;
	/** 嚼嚼嚼冷却（tick） */
	private static final long COOLDOWN_TICKS = 15L;
	/** 声波发射后冷却（tick），1 秒内不能重新蓄力 */
	public static final long WOOF_COOLDOWN_TICKS = 20L;

	/** 疾速冲刺：持续 20 秒（400 tick），速度 ×3，冷却 60 秒（1200 tick） */
	public static final int BOOST_DURATION_TICKS = 20 * 20;
	public static final int BOOST_COOLDOWN_TICKS = 60 * 20;
	public static final float BOOST_MULTIPLIER = 3.0F;

	/** 极限过载：持续 5 秒（100 tick），速度 ×10，一次性（用后变损坏升级并掉 10 血） */
	public static final int OVERDRIVE_DURATION_TICKS = 5 * 20;
	public static final float OVERDRIVE_MULTIPLIER = 10.0F;
	/** 极限过载的掉血 */
	private static final float OVERDRIVE_SELF_DAMAGE = 10.0F;
	/** 触发极限过载后疾速冷却额外增加（tick），10 秒 */
	public static final int OVERDRIVE_COOLDOWN_PENALTY = 10 * 20;

	/** 喷气推进冷却（tick），20 秒（受冷却升级影响） */
	public static final int JET_COOLDOWN_TICKS = 20 * 20;
	/** 喷气推力持续 tick（0.4 秒，期间骑乘速度放大以保持水平推进，避免被覆盖） */
	public static final int JET_PUSH_TICKS = 8;
	/** 喷气期间骑乘速度放大倍率 */
	public static final float JET_SPEED_MULTIPLIER = 2.5F;

	/** 嚼嚼嚼伤害（满级）：34 伤 ≈ 15 次击杀坚守者（500 血）；攻击升级后 50 伤 ≈ 10 次 */
	private static final float MUNCH_DAMAGE_BASE = 34.0F;
	private static final float MUNCH_DAMAGE_UPGRADED = 50.0F;
	/** 吞噬恢复血量（每次） */
	private static final float SWALLOW_HEAL = 5.0F;

	private static final Map<UUID, ChargeData> CHARGING = new HashMap<>();
	/** 嚼嚼嚼冷却（附带狼引用用于清理） */
	private static final Map<UUID, UseEntry> lastUse = new HashMap<>();
	/** 声波发射冷却（附带狼引用用于清理） */
	private static final Map<UUID, UseEntry> lastWoof = new HashMap<>();
	/** 疾速冲刺进行中（狼 uuid → 剩余 tick） */
	private static final Map<UUID, BoostData> BOOSTING = new HashMap<>();
	/** 疾速冲刺冷却（狼 uuid → 冷却起点 tick，附带狼引用用于清理） */
	private static final Map<UUID, UseEntry> lastBoost = new HashMap<>();
	/** 喷气推进冷却（狼 uuid → 冷却起点 tick，附带狼引用用于清理） */
	private static final Map<UUID, UseEntry> lastJet = new HashMap<>();
	/** 喷气推力进行中（狼 uuid → 喷气时刻，期间骑乘速度放大保持水平推进） */
	private static final Map<UUID, UseEntry> jetActive = new HashMap<>();
	/** 客户端：本地喷气推力剩余 tick（客户端权威模拟用） */
	public static volatile int clientJetActiveTicks = 0;

	/** 客户端缓存的生效速度/转弯等级（由服务端通过 settings 包同步，客户端权威模拟使用） */
	public static volatile int clientSpeed = BigDogSettings.DEFAULT;
	public static volatile int clientTurn = BigDogSettings.DEFAULT;

	/** 服务端：处于固定视角模式的玩家（客户端权威模拟时服务端转向逻辑保持一致） */
	private static final java.util.Set<UUID> fixedCameraPlayers = java.util.concurrent.ConcurrentHashMap.newKeySet();
	/** 客户端：本地固定视角开关 */
	public static volatile boolean clientFixedCamera = false;

	public static void setFixedCamera(Player player, boolean fixed) {
		if (fixed) {
			fixedCameraPlayers.add(player.getUUID());
		} else {
			fixedCameraPlayers.remove(player.getUUID());
		}
	}

	public static boolean isFixedCamera(Player player) {
		return fixedCameraPlayers.contains(player.getUUID());
	}

	/** 客户端/服务端都可用：当前骑乘者是否处于固定视角模式 */
	public static boolean fixedCameraOf(Player player, boolean clientSide) {
		return clientSide ? clientFixedCamera : isFixedCamera(player);
	}

	private static int cleanupTimer = 0;

	private BigDogSkills() {
	}

	/** 任意放大阶段都可骑乘/放技能（喂过至少一次） */
	public static boolean isRideable(Wolf wolf) {
		return wolf.getAttributeValue(Attributes.SCALE) > MIN_SCALE + 0.01;
	}

	public static boolean isCharging(Wolf wolf) {
		return CHARGING.containsKey(wolf.getUUID());
	}

	/** 只有驯服且归属该玩家的狼才能骑乘控制与放技能 */
	public static boolean isOwnedRideable(Wolf wolf, Player player) {
		return wolf.isTame() && wolf.isOwnedBy(player) && isRideable(wolf);
	}

	/** 狗是否装了运载空间（读取同步数据，客户端/服务端都可用） */
	public static boolean hasCargo(Wolf wolf) {
		return ((BigDogCargoAccess) wolf).hasCargo();
	}

	/** 服务端：打开狗的运载空间 */
	public static void openCargo(Wolf wolf, Player player) {
		((BigDogCargoAccess) wolf).openCargo(player);
	}

	/** 狗是否装了疾速升级（疾速冲刺前提） */
	public static boolean hasBoostUpgrade(Wolf wolf) {
		return ((BigDogCargoAccess) wolf).hasBoostUpgrade();
	}

	/** 狗是否装了喷气升级（喷气推进前提） */
	public static boolean hasJetUpgrade(Wolf wolf) {
		return ((BigDogCargoAccess) wolf).hasJetUpgrade();
	}

	/** 狗是否装了攻击升级（提升嚼嚼嚼伤害） */
	public static boolean hasAttackUpgrade(Wolf wolf) {
		return ((BigDogCargoAccess) wolf).hasAttackUpgrade();
	}

	/** 升级槽里冷却升级的数量（可叠加） */
	public static int cooldownUpgradeCount(Wolf wolf) {
		return ((BigDogCargoAccess) wolf).cooldownUpgradeCount();
	}

	/** 冷却因子：N 个冷却升级 = (90 - 5N)%（1 个 85%、2 个 80%、3 个 75%），最低 50% */
	public static float cooldownFactor(Wolf wolf) {
		int n = cooldownUpgradeCount(wolf);
		return Math.max(0.5F, 0.9F - 0.05F * n);
	}

	/** 升级槽里是否还有可用的极限过载升级 */
	public static boolean hasOverdriveUpgrade(Wolf wolf) {
		return ((BigDogCargoAccess) wolf).hasOverdriveUpgrade();
	}

	/** 消耗一个极限过载升级（替换为损坏升级） */
	public static void consumeOverdrive(Wolf wolf) {
		((BigDogCargoAccess) wolf).consumeOverdrive();
	}

	/** 极限加速是否生效（读同步数据，客户端/服务端都可用） */
	public static boolean isOverdrive(Wolf wolf) {
		return ((BigDogCargoAccess) wolf).isOverdrive();
	}

	/** 当前加速倍率：极限加速 ×10，普通疾速 ×3（客户端读同步数据，服务端读进行中状态） */
	public static float boostMultiplier(Wolf wolf) {
		if (wolf.level().isClientSide()) {
			return isOverdrive(wolf) ? OVERDRIVE_MULTIPLIER : BOOST_MULTIPLIER;
		}
		BoostData data = BOOSTING.get(wolf.getUUID());
		return (data != null && data.overdrive) ? OVERDRIVE_MULTIPLIER : BOOST_MULTIPLIER;
	}

	/** 喷气推力是否在进行（期间骑乘速度放大保持水平推进；客户端读本地标记） */
	public static boolean isJetActive(Wolf wolf) {
		if (wolf.level().isClientSide()) {
			return clientJetActiveTicks > 0;
		}
		UseEntry e = jetActive.get(wolf.getUUID());
		return e != null && e.time + JET_PUSH_TICKS > ((ServerLevel) wolf.level()).getGameTime();
	}

	/** 大狗等级：1/2/3（按体型） */
	public static int levelOf(Wolf wolf) {
		double scale = wolf.getAttributeValue(Attributes.SCALE);
		if (scale >= MAX_SCALE - 0.01) {
			return 3;
		}
		if (scale >= MIN_SCALE + 2 * SCALE_STEP - 0.01) {
			return 2;
		}
		return 1;
	}

	/** 疾速冲刺是否生效（读同步数据，客户端/服务端都可用） */
	public static boolean isBoosted(Wolf wolf) {
		return ((BigDogCargoAccess) wolf).isBoosted();
	}

	private static final class UseEntry {
		final Wolf wolf;
		long time;

		UseEntry(Wolf wolf, long time) {
			this.wolf = wolf;
			this.time = time;
		}
	}

	public static final class ChargeData {
		public final Wolf wolf;
		public final ServerPlayer rider;
		public int ticks;
		public int lastSoundTick;
		public int lastTickCount;

		public ChargeData(Wolf wolf, ServerPlayer rider) {
			this.wolf = wolf;
			this.rider = rider;
			this.lastTickCount = wolf.tickCount;
		}
	}

	/** 疾速冲刺/极限加速进行中的数据 */
	private static final class BoostData {
		final Wolf wolf;
		final ServerPlayer rider;
		int ticksLeft;
		final boolean overdrive;

		BoostData(Wolf wolf, ServerPlayer rider, int duration, boolean overdrive) {
			this.wolf = wolf;
			this.rider = rider;
			this.ticksLeft = duration;
			this.overdrive = overdrive;
		}
	}

	public static void handle(BigDogSkillPayload payload, ServerPlayNetworking.Context context) {
		context.server().execute(() -> {
			ServerPlayer player = context.player();
			if (!(player.getVehicle() instanceof Wolf wolf) || !isRideable(wolf)) {
				return;
			}
			ServerLevel level = (ServerLevel) wolf.level();
			int skill = payload.skill();
			// 控制类动作只限主人
			if (!isOwnedRideable(wolf, player)) {
				return;
			}
			// 松开蓄力必须优先处理（蓄力中也要响应）
			if (skill == SKILL_WOOF_RELEASE) {
				releaseCharge(wolf);
				return;
			}
			// 骑乘时按 E：打开狗的运载空间（有就打开，没有就提示）
			if (skill == SKILL_OPEN_CARGO) {
				if (hasCargo(wolf)) {
					openCargo(wolf, player);
				} else {
					player.sendSystemMessage(Component.literal("这只狗没有运载空间"));
				}
				return;
			}
			if (isCharging(wolf)) {
				return;
			}
			if (skill == SKILL_MUNCH) {
				UseEntry entry = lastUse.get(wolf.getUUID());
				long now = level.getGameTime();
				if (entry != null && entry.time + COOLDOWN_TICKS > now) {
					return;
				}
				if (entry == null) {
					lastUse.put(wolf.getUUID(), new UseEntry(wolf, now));
				} else {
					entry.time = now;
				}
				munch(level, wolf, player);
			} else if (skill == SKILL_WOOF) {
				// 蓄力开始不受冷却影响，避免服务端丢弃导致客户端假蓄力
				startCharge(level, wolf, player);
			} else if (skill == SKILL_BOOST) {
				startBoost(level, wolf, player);
			} else if (skill == SKILL_JET) {
				jetBoost(level, wolf, player);
			}
		});
	}

	/** 每服务器 tick 推进蓄力，并定期清理已移除狼的缓存条目 */
	public static void tickCharges() {
		if (++cleanupTimer >= 100) {
			cleanupTimer = 0;
			lastUse.values().removeIf(entry -> entry.wolf.isRemoved());
			lastWoof.values().removeIf(entry -> entry.wolf.isRemoved());
			lastBoost.values().removeIf(entry -> entry.wolf.isRemoved());
			lastJet.values().removeIf(entry -> entry.wolf.isRemoved());
			jetActive.values().removeIf(entry -> entry.wolf.isRemoved());
		}
		if (CHARGING.isEmpty()) {
			return;
		}
		Iterator<ChargeData> it = CHARGING.values().iterator();
		while (it.hasNext()) {
			ChargeData data = it.next();
			Wolf wolf = data.wolf;
			if (wolf.isRemoved() || wolf.level().isClientSide()
					|| !(wolf.getFirstPassenger() instanceof Player) || data.rider.isRemoved()) {
				it.remove();
				continue;
			}
			// 区块未加载/未 tick 时暂停蓄力（不隔空充能）
			if (wolf.tickCount == data.lastTickCount) {
				continue;
			}
			data.lastTickCount = wolf.tickCount;
			ServerLevel level = (ServerLevel) wolf.level();
			data.ticks++;
			// 蓄力进度条由客户端本地 HUD 绘制（蓝色条形），服务端不再发 ActionBar
			float progress = (float) data.ticks / MAX_CHARGE_TICKS;
			// 蓄力音效：间隔越来越短、音调越来越高
			int interval = Math.max(2, (int) (12 - progress * 9));
			if (data.ticks - data.lastSoundTick >= interval) {
				data.lastSoundTick = data.ticks;
				BigDogSounds.playBark(level, wolf, 1.0F, 0.7F + progress * 1.5F);
			}
			if (data.ticks >= MAX_CHARGE_TICKS) {
				it.remove();
				fireBoom(level, wolf, data);
			}
		}
	}

	private static void startCharge(ServerLevel level, Wolf wolf, ServerPlayer rider) {
		// 发射后 1 秒冷却：冷却中不能重新蓄力
		UseEntry woof = lastWoof.get(wolf.getUUID());
		if (woof != null && woof.time + WOOF_COOLDOWN_TICKS > level.getGameTime()) {
			return;
		}
		CHARGING.put(wolf.getUUID(), new ChargeData(wolf, rider));
		BigDogSounds.playBark(level, wolf, 1.0F, 0.7F);
	}

	// ---------- 疾速冲刺 / 极限过载 ----------

	/**
	 * 按 C 触发加速：平时是普通疾速冲刺；疾速冷却期间如果装了极限过载，则触发极限加速（一次性）。
	 * 普通疾速：20 秒 ×3，冷却 60 秒（受冷却升级影响），冷却从冲刺结束开始计时。
	 */
	private static void startBoost(ServerLevel level, Wolf wolf, ServerPlayer rider) {
		if (!hasBoostUpgrade(wolf)) {
			rider.sendSystemMessage(Component.literal("这只大狗没有疾速升级"));
			return;
		}
		if (BOOSTING.containsKey(wolf.getUUID())) {
			return;
		}
		long cd = (long) (BOOST_COOLDOWN_TICKS * cooldownFactor(wolf));
		UseEntry entry = lastBoost.get(wolf.getUUID());
		if (entry != null) {
			long now = level.getGameTime();
			long remaining = entry.time + cd - now;
			if (remaining > 0) {
				// 疾速冷却中：可改用极限过载（一次性爆发，不占疾速冷却）
				if (hasOverdriveUpgrade(wolf)) {
					overdrive(level, wolf, rider);
					return;
				}
				// 没有极限过载：同步剩余冷却
				sendBoost(rider, 2, (int) Math.max(1, remaining));
				return;
			}
			// 冷却已结束：清掉旧条目，避免残留影响下次判定
			lastBoost.remove(wolf.getUUID());
		}
		BOOSTING.put(wolf.getUUID(), new BoostData(wolf, rider, BOOST_DURATION_TICKS, false));
		((BigDogCargoAccess) wolf).setBoosted(true);
		sendBoost(rider, 1, BOOST_DURATION_TICKS);
	}

	/** 极限加速：5 秒 ×10 速度；消耗一个极限过载（变为损坏升级，不可修复）、大狗掉 10 血，疾速冷却额外 +10 秒 */
	private static void overdrive(ServerLevel level, Wolf wolf, ServerPlayer rider) {
		consumeOverdrive(wolf);
		wolf.hurtServer(level, level.damageSources().magic(), OVERDRIVE_SELF_DAMAGE);
		// 疾速冷却额外延长 10 秒（把冷却起点往前挪，服务端判定一致）
		UseEntry boostEntry = lastBoost.get(wolf.getUUID());
		if (boostEntry != null) {
			boostEntry.time -= OVERDRIVE_COOLDOWN_PENALTY;
		}
		BOOSTING.put(wolf.getUUID(), new BoostData(wolf, rider, OVERDRIVE_DURATION_TICKS, true));
		((BigDogCargoAccess) wolf).setBoosted(true);
		((BigDogCargoAccess) wolf).setOverdrive(true);
		sendBoost(rider, 3, OVERDRIVE_DURATION_TICKS);
		// 爆发粒子
		level.sendParticles(ParticleTypes.SONIC_BOOM, wolf.getX(), wolf.getY() + 1.0, wolf.getZ(), 6, 1.2, 0.8, 1.2, 0);
		level.sendParticles(ParticleTypes.CLOUD, wolf.getX(), wolf.getY() + 1.0, wolf.getZ(), 20, 0.8, 0.5, 0.8, 0.1);
	}

	/** 每服务器 tick 推进疾速/极限加速倒计时，并处理中途取消 */
	public static void tickBoosts() {
		if (BOOSTING.isEmpty()) {
			return;
		}
		Iterator<BoostData> it = BOOSTING.values().iterator();
		while (it.hasNext()) {
			BoostData data = it.next();
			Wolf wolf = data.wolf;
			// 狗被移除/卸载或疾速升级被取下：取消加速（不进入冷却）
			if (wolf.isRemoved() || wolf.level().isClientSide() || !hasBoostUpgrade(wolf)) {
				it.remove();
				((BigDogCargoAccess) wolf).setBoosted(false);
				((BigDogCargoAccess) wolf).setOverdrive(false);
				sendBoost(data.rider, 0, 0);
				continue;
			}
			if (--data.ticksLeft <= 0) {
				it.remove();
				((BigDogCargoAccess) wolf).setBoosted(false);
				if (data.overdrive) {
					// 极限加速是一次性的：结束后仅清加速状态，疾速冷却条继续走（不归零）
					((BigDogCargoAccess) wolf).setOverdrive(false);
					sendBoost(data.rider, 5, 0);
				} else if (wolf.level() instanceof ServerLevel level) {
					// 普通疾速自然结束：从此刻起进入冷却（含冷却升级因子）
					long cd = (long) (BOOST_COOLDOWN_TICKS * cooldownFactor(wolf));
					lastBoost.put(wolf.getUUID(), new UseEntry(wolf, level.getGameTime()));
					sendBoost(data.rider, 2, (int) cd);
				}
			}
		}
	}

	/**
	 * 喷气推进：跳跃后未落地时再按空格，向狗朝向斜上方推力并释放粒子。
	 * 喷气后免除所有摔落伤害直到落地；冷却 20 秒（受冷却升级影响），HUD 显示喷气冷却条。
	 */
	private static void jetBoost(ServerLevel level, Wolf wolf, ServerPlayer rider) {
		if (!hasJetUpgrade(wolf)) {
			return;
		}
		long cd = (long) (JET_COOLDOWN_TICKS * cooldownFactor(wolf));
		UseEntry entry = lastJet.get(wolf.getUUID());
		if (entry != null) {
			long now = level.getGameTime();
			long remaining = entry.time + cd - now;
			if (remaining > 0) {
				// 冷却中：HUD 喷气冷却条同步剩余
				sendBoost(rider, 4, (int) Math.max(1, remaining));
				return;
			}
			lastJet.remove(wolf.getUUID());
		}
		lastJet.put(wolf.getUUID(), new UseEntry(wolf, level.getGameTime()));
		// 推力标记：接下来一段时间骑乘速度放大，保持水平推进不被骑乘系统覆盖
		jetActive.put(wolf.getUUID(), new UseEntry(wolf, level.getGameTime()));
		// 强推力：狗朝向斜上方冲出（水平 1.6，垂直 1.2）
		Vec3 dir = Vec3.directionFromRotation(0.0F, wolf.getYRot());
		Vec3 v = wolf.getDeltaMovement();
		wolf.setDeltaMovement(v.x + dir.x * 1.6, 1.2, v.z + dir.z * 1.6);
		wolf.fallDistance = 0.0F;
		// 喷气后免除摔落伤害直到落地（由 WolfMixin.aiStep 持续清零并落地解除）
		((BigDogCargoAccess) wolf).setJetNoFall(true);
		// 粒子：云朵环绕狗身（喷气特效）
		level.sendParticles(ParticleTypes.CLOUD, wolf.getX(), wolf.getY() + 0.5, wolf.getZ(),
				16, 0.6, 0.3, 0.6, 0.08);
		// 喷气冷却条（HUD）开始计时
		sendBoost(rider, 4, (int) cd);
	}

	/** 服务端 → 客户端：同步加速/冷却状态（1=疾速生效 2=疾速冷却 3=极限加速生效 4=喷气冷却 5=极限加速结束 0=清除） */
	private static void sendBoost(ServerPlayer rider, int state, int ticks) {
		if (rider != null && !rider.isRemoved()) {
			ServerPlayNetworking.send(rider, new BigDogBoostPayload(state, ticks));
		}
	}

	/** 松开按键：立即按当前蓄力进度发射 */
	private static void releaseCharge(Wolf wolf) {
		ChargeData data = CHARGING.remove(wolf.getUUID());
		if (data != null) {
			ServerLevel level = (ServerLevel) wolf.level();
			fireBoom(level, wolf, data);
		}
	}

	/**
	 * 蓄力完成/松开自动发射：射程 = 蓄力时长 x 1.2（无方块阻挡，满蓄 120 格）。
	 * 单次 AABB 查询 + 射线距离过滤，每个实体只判定一次；按释放时骑手视角发射。
	 */
	private static void fireBoom(ServerLevel level, Wolf wolf, ChargeData data) {
		float scale = (float) wolf.getAttributeValue(Attributes.SCALE);
		double range = Math.max(2.0, data.ticks * 1.2);
		float damage = (float) (20.0 * (double) data.ticks / MAX_CHARGE_TICKS * scale / MAX_SCALE);
		if (damage < 2.0F) {
			damage = 2.0F;
		}
		Vec3 pos = wolf.getEyePosition();
		// 释放时按骑手当前视角发射（蓄力期间可自由转向）
		Vec3 dir = Vec3.directionFromRotation(data.rider.getXRot(), data.rider.getYRot()).normalize();
		DamageSource source = damageSource(level, "woof", data.rider);

		AABB beamBox = new AABB(pos, pos.add(dir.scale(range))).inflate(2.0);
		List<Entity> ents = level.getEntities(wolf, beamBox,
				e -> e instanceof LivingEntity le && le.isAlive() && e != wolf && e != data.rider);
		for (Entity target : ents) {
			Vec3 rel = target.getEyePosition().subtract(pos);
			double along = rel.dot(dir);
			if (along < 0 || along > range) {
				continue;
			}
			Vec3 perp = rel.subtract(dir.scale(along));
			if (perp.lengthSqr() > 4.0) { // 横向距离 > 2 格不打
				continue;
			}
			((LivingEntity) target).hurtServer(level, source, damage);
		}

		BigDogSounds.playBark(level, wolf, 2.0F, 1.0F);
		for (double d = 0.5; d <= range; d += 1.0) {
			Vec3 point = pos.add(dir.scale(d));
			level.sendParticles(ParticleTypes.SONIC_BOOM, point.x, point.y, point.z, 1, 0, 0, 0, 0);
		}
		// 蓄力条由客户端本地 HUD 消失（charging 复位），服务端无需发清除消息
		// 记录发射冷却：1 秒内不能重新蓄力
		lastWoof.put(wolf.getUUID(), new UseEntry(wolf, level.getGameTime()));
	}

	/**
	 * 嚼嚼嚼：向前方范围啃咬，满级 8 格，其余按体型衰减。
	 * 吞噬：对当前血量低于等级阈值（1/2/3 级 <5/10/15）的生物直接吞噬并恢复自身血量；
	 * 其余按伤害攻击（攻击升级提升伤害：满级 34 → 50）。
	 */
	private static void munch(ServerLevel level, Wolf wolf, ServerPlayer rider) {
		float scale = (float) wolf.getAttributeValue(Attributes.SCALE);
		double range = 8.0 * scale / MAX_SCALE;
		float base = hasAttackUpgrade(wolf) ? MUNCH_DAMAGE_UPGRADED : MUNCH_DAMAGE_BASE;
		float damage = (float) (base * scale / MAX_SCALE);
		float threshold = switch (levelOf(wolf)) {
			case 3 -> 15.0F;
			case 2 -> 10.0F;
			default -> 5.0F;
		};

		Vec3 pos = wolf.getEyePosition();
		Vec3 dir = wolf.getLookAngle();
		Vec3 end = pos.add(dir.x * range, dir.y * range, dir.z * range);
		AABB box = new AABB(pos, end).inflate(2.0);
		DamageSource source = damageSource(level, "munch", rider);

		List<Entity> targets = level.getEntities(wolf, box,
				e -> e instanceof LivingEntity le && le.isAlive() && e != wolf && e != rider);
		boolean hit = false;
		for (Entity target : targets) {
			LivingEntity living = (LivingEntity) target;
			if (living.getEyePosition().subtract(pos).dot(dir) < 0) {
				continue;
			}
			hit = true;
			if (living.getHealth() < threshold) {
				// 吞噬：极大伤害以触发自定义死亡信息，并恢复大狗血量
				living.hurtServer(level, source, living.getHealth() + living.getMaxHealth() + 1000.0F);
				wolf.heal(SWALLOW_HEAL);
			} else {
				living.hurtServer(level, source, damage);
			}
		}
		if (hit) {
			level.sendParticles(ParticleTypes.CRIT, wolf.getX(), wolf.getEyeY(), wolf.getZ(), 12, 1.0, 0.5, 1.0, 0.1);
		}
	}

	/** 自定义伤害类型，数据包缺失时降级为魔法伤害 */
	private static DamageSource damageSource(ServerLevel level, String path, Entity attacker) {
		Registry<DamageType> reg = level.registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE);
		ResourceKey<DamageType> key = ResourceKey.create(Registries.DAMAGE_TYPE,
				Identifier.fromNamespaceAndPath(BigDogMod.MOD_ID, path));
		Holder<DamageType> holder = reg.get(key).orElse(null);
		if (holder == null) {
			holder = level.damageSources().magic().typeHolder();
		}
		return new DamageSource(holder, attacker);
	}
}
