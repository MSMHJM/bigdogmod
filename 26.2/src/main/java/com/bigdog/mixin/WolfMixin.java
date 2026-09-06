package com.bigdog.mixin;

import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.wolf.Wolf;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;

import org.spongepowered.asm.mixin.Implements;
import org.spongepowered.asm.mixin.Interface;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.bigdog.BigDogMod;
import com.bigdog.access.BigDogCargoAccess;
import com.bigdog.skill.BigDogSkills;
import com.bigdog.skill.BigDogSounds;

/**
 * 大狗核心逻辑（目标类 Wolf 上直接声明的方法）：
 * - 大骨棒喂食：体型 +0.8，最多 3 次；速度越小越快（∝1/√体型）；变大后自动坐下不乱跑
 * - 毒马铃薯降级
 * - 运载升级：安装运载空间（容量随体型 9~27 格），安装后减速；按 E 打开
 * - 技能卸载：移除运载升级（空间必须为空）
 * - 训狗术（玩家学习）：骑乘时速度提升、转弯半径减小
 * - 骑乘：方向以玩家视角为基准（带转弯阻尼），跳跃高度随体型
 */
@Implements(@Interface(iface = BigDogCargoAccess.class, prefix = "bigdogcargo$"))
@Mixin(Wolf.class)
public abstract class WolfMixin {
	@Unique
	private static final String NBT_CARGO = "BigDogCargo";
	@Unique
	private static final String NBT_CARGO_ITEMS = "BigDogCargoItems";

	/** 是否有运载空间（同步数据，客户端也能读取，用于骑乘时按 E 打开） */
	@Unique
	private static final EntityDataAccessor<Boolean> DATA_HAS_CARGO =
			SynchedEntityData.defineId(Wolf.class, EntityDataSerializers.BOOLEAN);

	/** 疾速冲刺是否生效（同步数据，客户端读取用于速度模拟与 HUD 冷却条） */
	@Unique
	private static final EntityDataAccessor<Boolean> DATA_BOOSTED =
			SynchedEntityData.defineId(Wolf.class, EntityDataSerializers.BOOLEAN);

	/** 极限加速是否生效（同步数据，客户端区分 ×3 / ×10） */
	@Unique
	private static final EntityDataAccessor<Boolean> DATA_OVERDRIVE =
			SynchedEntityData.defineId(Wolf.class, EntityDataSerializers.BOOLEAN);

	/** 极限过载当前可用（有极限过载 + 前置疾速升级；同步数据，客户端提示用） */
	@Unique
	private static final EntityDataAccessor<Boolean> DATA_OVERDRIVE_READY =
			SynchedEntityData.defineId(Wolf.class, EntityDataSerializers.BOOLEAN);

	@Unique
	private SimpleContainer bigdog$cargo;

	/** 改造升级槽（生物扳手打开，放置各种升级物品，目前支持运载升级） */
	@Unique
	private SimpleContainer bigdog$upgrades;

	/** 喷气推进后免除摔落伤害（落地时清除） */
	@Unique
	private boolean bigdog$jetNoFall;

	/** 自动恢复喂食计时（每 5 秒一次） */
	@Unique
	private int bigdog$healTimer;

	@Unique
	private Wolf bigdog$self() {
		return (Wolf) (Object) this;
	}

	@Unique
	private boolean bigdog$isRideable() {
		return bigdog$self().getAttributeValue(Attributes.SCALE) > BigDogSkills.MIN_SCALE + 0.01;
	}

	/** 座位数：1/2 级 2 座，3 级（满级）3 座 */
	@Unique
	private int bigdog$maxSeats() {
		return bigdog$self().getAttributeValue(Attributes.SCALE) >= BigDogSkills.MAX_SCALE - 0.01 ? 3 : 2;
	}

	@Unique
	private boolean bigdog$owned(Player player) {
		Wolf self = bigdog$self();
		return self.isTame() && self.isOwnedBy(player);
	}

	/**
	 * 累加式改变体型（速度保持狼的原速度，训狗术再乘 1.2），变大时扩容运载空间。
	 */
	@Unique
	private void bigdog$changeScale(double delta) {
		Wolf self = bigdog$self();
		AttributeInstance scale = self.getAttribute(Attributes.SCALE);
		if (scale == null) {
			return;
		}
		double oldScale = scale.getBaseValue();
		double newScale = Math.max(BigDogSkills.MIN_SCALE, Math.min(BigDogSkills.MAX_SCALE, oldScale + delta));
		if (newScale == oldScale) {
			return;
		}
		scale.setBaseValue(newScale);
		bigdog$applyLevelHealth();
		if (delta > 0) {
			bigdog$resizeCargo();
		}
	}

	/** 大狗等级血量：1 级 30（15 颗心 / 1.5 排）、2 级 50（25 颗心 / 2.5 排）、3 级 70（35 颗心 / 3.5 排）；完全降级恢复原版狼 20 */
	@Unique
	private void bigdog$applyLevelHealth() {
		Wolf self = bigdog$self();
		AttributeInstance maxHp = self.getAttribute(Attributes.MAX_HEALTH);
		if (maxHp == null) {
			return;
		}
		double scale = self.getAttributeValue(Attributes.SCALE);
		double hp;
		if (scale >= BigDogSkills.MAX_SCALE - 0.01) {
			hp = 70.0;
		} else if (scale >= BigDogSkills.MIN_SCALE + 2 * BigDogSkills.SCALE_STEP - 0.01) {
			hp = 50.0;
		} else if (scale > BigDogSkills.MIN_SCALE + 0.01) {
			hp = 30.0;
		} else {
			hp = 20.0; // 未升级/完全降级：原版狼血量
		}
		if (maxHp.getBaseValue() != hp) {
			maxHp.setBaseValue(hp);
			self.setHealth((float) hp); // 升级/降级后回满对应等级血量
		}
	}

	// ---------- 运载空间 ----------

	@Unique
	private int bigdog$cargoCapacity() {
		double scale = bigdog$self().getAttributeValue(Attributes.SCALE);
		int cap = 27 + (int) ((scale - BigDogSkills.MIN_SCALE) / (BigDogSkills.MAX_SCALE - BigDogSkills.MIN_SCALE) * 27);
		return Math.max(27, Math.min(54, cap));
	}

	@Unique
	private void bigdog$ensureCargo() {
		if (bigdog$cargo == null) {
			bigdog$cargo = new SimpleContainer(bigdog$cargoCapacity());
			bigdog$self().getEntityData().set(DATA_HAS_CARGO, true);
		}
	}

	@Unique
	private void bigdog$resizeCargo() {
		if (bigdog$cargo == null) {
			return;
		}
		int newCap = bigdog$cargoCapacity();
		if (newCap <= bigdog$cargo.getContainerSize()) {
			return;
		}
		SimpleContainer bigger = new SimpleContainer(newCap);
		for (int i = 0; i < bigdog$cargo.getContainerSize(); i++) {
			bigger.setItem(i, bigdog$cargo.getItem(i));
		}
		bigdog$cargo = bigger;
	}

	@Unique
	private boolean bigdog$hasCargo() {
		return bigdog$self().getEntityData().get(DATA_HAS_CARGO);
	}

	// ---------- 改造升级槽 ----------

	/** 是否为可放置的升级物品（运载/疾速/喷气/冷却/攻击/极限过载/损坏/自动恢复，未来新增升级在此扩展） */
	@Unique
	private boolean bigdog$isUpgradeItem(ItemStack stack) {
		return stack.is(BigDogMod.CARGO_UPGRADE) || stack.is(BigDogMod.SPEED_UPGRADE)
				|| stack.is(BigDogMod.JET_UPGRADE) || stack.is(BigDogMod.COOLDOWN_UPGRADE)
				|| stack.is(BigDogMod.ATTACK_UPGRADE) || stack.is(BigDogMod.OVERDRIVE_UPGRADE)
				|| stack.is(BigDogMod.DAMAGED_UPGRADE) || stack.is(BigDogMod.AUTO_HEAL_UPGRADE);
	}

	@Unique
	private void bigdog$ensureUpgrades() {
		if (bigdog$upgrades == null) {
			bigdog$upgrades = new SimpleContainer(9) {
				@Override
				public int getMaxStackSize() {
					return 1; // 每格只能放 1 个
				}

				@Override
				public boolean canPlaceItem(int slot, ItemStack stack) {
					return bigdog$isUpgradeItem(stack); // 只允许升级物品
				}
			};
		}
	}

	/** 升级槽里是否放了指定升级物品 */
	@Unique
	private boolean bigdog$hasUpgrade(Item item) {
		if (bigdog$upgrades == null) {
			return false;
		}
		for (int i = 0; i < bigdog$upgrades.getContainerSize(); i++) {
			if (bigdog$upgrades.getItem(i).is(item)) {
				return true;
			}
		}
		return false;
	}

	/** 升级槽里指定升级物品的数量（冷却升级可叠加） */
	@Unique
	private int bigdog$countUpgrade(Item item) {
		if (bigdog$upgrades == null) {
			return 0;
		}
		int count = 0;
		for (int i = 0; i < bigdog$upgrades.getContainerSize(); i++) {
			if (bigdog$upgrades.getItem(i).is(item)) {
				count++;
			}
		}
		return count;
	}

	/** 打开改造界面（升级槽） */
	@Unique
	private void bigdog$openUpgrades(Player player) {
		bigdog$ensureUpgrades();
		player.openMenu(new SimpleMenuProvider(
				(id, inv, p) -> new ChestMenu(net.minecraft.world.inventory.MenuType.GENERIC_9x1,
						id, inv, bigdog$upgrades, 1),
				Component.literal("大狗改造")));
	}

	/** 打开运载空间（用运载升级物品右键已安装的狗触发；骑乘时按 E 由服务端调用） */
	@Unique
	private void bigdog$openCargo(Player player) {
		bigdog$ensureCargo();
		player.openMenu(new SimpleMenuProvider(
				(id, inv, p) -> new ChestMenu(bigdog$menuType(), id, inv, bigdog$cargo, bigdog$cargoCapacity() / 9),
				Component.literal("运载")));
	}

	/** 供技能层/骑乘控制调用的接口实现（经 BigDogCargoAccess 访问，生产环境安全） */
	@Unique
	public boolean bigdogcargo$hasCargo() {
		return bigdog$hasCargo();
	}

	/** 供技能层调用的接口实现：打开狗的运载空间（仅服务端） */
	@Unique
	public void bigdogcargo$openCargo(Player player) {
		bigdog$openCargo(player);
	}

	/** 接口实现：升级槽里是否放了疾速升级 */
	@Unique
	public boolean bigdogcargo$hasBoostUpgrade() {
		return bigdog$hasUpgrade(BigDogMod.SPEED_UPGRADE);
	}

	/** 接口实现：升级槽里是否放了喷气升级 */
	@Unique
	public boolean bigdogcargo$hasJetUpgrade() {
		return bigdog$hasUpgrade(BigDogMod.JET_UPGRADE);
	}

	/** 接口实现：升级槽里是否放了攻击升级 */
	@Unique
	public boolean bigdogcargo$hasAttackUpgrade() {
		return bigdog$hasUpgrade(BigDogMod.ATTACK_UPGRADE);
	}

	/** 接口实现：升级槽里冷却升级的数量（可叠加） */
	@Unique
	public int bigdogcargo$cooldownUpgradeCount() {
		return bigdog$countUpgrade(BigDogMod.COOLDOWN_UPGRADE);
	}

	/** 接口实现：升级槽里是否还有可用的极限过载升级 */
	@Unique
	public boolean bigdogcargo$hasOverdriveUpgrade() {
		return bigdog$hasUpgrade(BigDogMod.OVERDRIVE_UPGRADE);
	}

	/** 接口实现：极限过载当前可用（读同步数据，客户端提示用） */
	@Unique
	public boolean bigdogcargo$hasOverdriveReady() {
		return bigdog$self().getEntityData().get(DATA_OVERDRIVE_READY);
	}

	/** 接口实现：消耗一个极限过载升级，替换为损坏升级（多装时逐个消耗） */
	@Unique
	public void bigdogcargo$consumeOverdrive() {
		if (bigdog$upgrades == null) {
			return;
		}
		for (int i = 0; i < bigdog$upgrades.getContainerSize(); i++) {
			if (bigdog$upgrades.getItem(i).is(BigDogMod.OVERDRIVE_UPGRADE)) {
				bigdog$upgrades.setItem(i, new ItemStack(BigDogMod.DAMAGED_UPGRADE));
				break;
			}
		}
	}

	/** 接口实现：喷气推进后设置免摔落标记（落地时由 aiStep 清除） */
	@Unique
	public void bigdogcargo$setJetNoFall(boolean noFall) {
		bigdog$jetNoFall = noFall;
	}

	/** 接口实现：极限加速是否生效（读同步数据） */
	@Unique
	public boolean bigdogcargo$isOverdrive() {
		return bigdog$self().getEntityData().get(DATA_OVERDRIVE);
	}

	/** 接口实现：服务端设置极限加速生效状态（同步到客户端） */
	@Unique
	public void bigdogcargo$setOverdrive(boolean overdrive) {
		bigdog$self().getEntityData().set(DATA_OVERDRIVE, overdrive);
	}

	/** 接口实现：疾速冲刺是否生效（读同步数据，客户端/服务端都可用） */
	@Unique
	public boolean bigdogcargo$isBoosted() {
		return bigdog$self().getEntityData().get(DATA_BOOSTED);
	}

	/** 接口实现：服务端设置疾速冲刺生效状态（同步到客户端） */
	@Unique
	public void bigdogcargo$setBoosted(boolean boosted) {
		bigdog$self().getEntityData().set(DATA_BOOSTED, boosted);
	}

	/** 注册同步数据位：客户端也能知道狗是否装了运载、是否在疾速/极限加速、极限过载是否可用 */
	@Inject(method = "defineSynchedData", at = @At("TAIL"))
	private void bigdog$defineData(SynchedEntityData.Builder builder, CallbackInfo ci) {
		builder.define(DATA_HAS_CARGO, false);
		builder.define(DATA_BOOSTED, false);
		builder.define(DATA_OVERDRIVE, false);
		builder.define(DATA_OVERDRIVE_READY, false);
	}

	@Unique
	private net.minecraft.world.inventory.MenuType<?> bigdog$menuType() {
		switch (bigdog$cargoCapacity() / 9) {
			case 4:
				return net.minecraft.world.inventory.MenuType.GENERIC_9x4;
			case 5:
				return net.minecraft.world.inventory.MenuType.GENERIC_9x5;
			case 6:
				return net.minecraft.world.inventory.MenuType.GENERIC_9x6;
			default:
				return net.minecraft.world.inventory.MenuType.GENERIC_9x3;
		}
	}

	// ---------- NBT 持久化 ----------

	@Inject(method = "addAdditionalSaveData", at = @At("TAIL"))
	private void bigdog$writeData(ValueOutput output, CallbackInfo ci) {
		output.putBoolean(NBT_CARGO, bigdog$hasCargo());
		if (bigdog$cargo != null) {
			ContainerHelper.saveAllItems(output, bigdog$cargo.getItems());
		}
		// 升级槽：自定义 "Upgrades" 键（避开 cargo 的 "Items"）
		if (bigdog$upgrades != null) {
			net.minecraft.world.level.storage.ValueOutput.TypedOutputList list =
					output.list("Upgrades", net.minecraft.world.ItemStackWithSlot.CODEC);
			for (int i = 0; i < bigdog$upgrades.getContainerSize(); i++) {
				net.minecraft.world.item.ItemStack s = bigdog$upgrades.getItem(i);
				if (!s.isEmpty()) {
					list.add(new net.minecraft.world.ItemStackWithSlot(i, s));
				}
			}
			if (list.isEmpty()) {
				output.discard("Upgrades");
			}
		}
	}

	@Inject(method = "readAdditionalSaveData", at = @At("TAIL"))
	private void bigdog$readData(ValueInput input, CallbackInfo ci) {
		if (input.getBooleanOr(NBT_CARGO, false)) {
			bigdog$ensureCargo();
			ContainerHelper.loadAllItems(input, bigdog$cargo.getItems());
		}
		net.minecraft.world.level.storage.ValueInput.TypedInputList<net.minecraft.world.ItemStackWithSlot> list =
				input.listOrEmpty("Upgrades", net.minecraft.world.ItemStackWithSlot.CODEC);
		if (!list.isEmpty()) {
			bigdog$ensureUpgrades();
			for (net.minecraft.world.ItemStackWithSlot slot : list) {
				bigdog$upgrades.setItem(slot.slot(), slot.stack());
			}
		}
		// 重新应用等级血量（属性数据与存档不同步时兜底）
		bigdog$applyLevelHealth();
	}

	// ---------- 交互 ----------

	@Inject(method = "mobInteract", at = @At("HEAD"), cancellable = true)
	private void bigdog$onInteract(Player player, InteractionHand hand, CallbackInfoReturnable<InteractionResult> cir) {
		Wolf self = bigdog$self();
		if (self.isBaby()) {
			return; // 只作用于成年狗
		}
		ItemStack stack = player.getItemInHand(hand);
		Level level = self.level();

		if (stack.is(BigDogMod.BIG_BONE)) {
			if (!level.isClientSide() && self.getAttributeValue(Attributes.SCALE) < BigDogSkills.MAX_SCALE - 0.01) {
				bigdog$changeScale(BigDogSkills.SCALE_STEP);
				stack.consume(1, player);
				BigDogSounds.playUpgrade(level, self); // 升级音效
				self.setOrderedToSit(true); // 变大的狗安静待命，不乱跑
			}
			cir.setReturnValue(InteractionResult.CONSUME);
			return;
		}

		if (stack.is(Items.POISONOUS_POTATO)) {
			if (!level.isClientSide() && self.getAttributeValue(Attributes.SCALE) > BigDogSkills.MIN_SCALE) {
				bigdog$changeScale(-BigDogSkills.SCALE_STEP);
				stack.consume(1, player);
				BigDogSounds.playPuppy(level, self); // 降级音效
			}
			cir.setReturnValue(InteractionResult.CONSUME);
			return;
		}

		boolean owned = bigdog$owned(player) && bigdog$isRideable();

		// 生物扳手：打开改造界面（升级槽），可放置/取出各种升级（目前：运载升级）
		if (owned && stack.is(BigDogMod.SKILL_UNINSTALL)) {
			if (!level.isClientSide()) {
				bigdog$openUpgrades(player);
			}
			cir.setReturnValue(InteractionResult.SUCCESS);
			return;
		}

		// 骑乘：主人先骑上；其他玩家需主人已在背上且有座位
		// 1/2 级 2 座，3 级（满级）3 座
		if (stack.isEmpty() && !player.isSecondaryUseActive() && bigdog$isRideable()) {
			if (!level.isClientSide()) {
				if (bigdog$owned(player)) {
					if (player.getVehicle() != self) {
						player.startRiding(self, true, false);
						player.sendSystemMessage(Component.literal("骑乘大狗：按 Shift 下马"));
					}
				} else {
					boolean ownerOnBoard = self.getPassengers().stream()
							.anyMatch(p -> p instanceof Player owner && self.isOwnedBy(owner));
					if (!ownerOnBoard) {
						player.sendSystemMessage(Component.literal("需要大狗的主人先骑上去"));
					} else if (self.getPassengers().size() >= bigdog$maxSeats()) {
						player.sendSystemMessage(Component.literal("大狗上没有座位了"));
					} else {
						player.startRiding(self, true, false);
					}
				}
			}
			cir.setReturnValue(InteractionResult.SUCCESS);
			return;
		}
	}

	// ---------- 骑乘控制 ----------

	/**
	 * 蓄力锁定与跳跃：在 aiStep 开头处理。
	 * 说明：26.3 骑乘移动由 travelRidden → getRiddenInput/getRiddenSpeed 驱动（见 BigDogRiddenMixin），
	 * 它们在狼的 AI（serverAiStep）之后才调用，因此移动/转向/速度放那边，避免被 AI 覆盖。
	 */
	@Inject(method = "aiStep", at = @At("HEAD"))
	private void bigdog$aiStep(CallbackInfo ci) {
		Wolf self = bigdog$self();
		// 喷气推进后免除摔落伤害：持续清零摔落累计，直到落地才解除标记
		if (!self.level().isClientSide() && bigdog$jetNoFall) {
			self.fallDistance = 0.0F;
			if (self.onGround()) {
				bigdog$jetNoFall = false;
			}
		}
		// 主人不在背上时，其他乘客全部下马（仅服务端执行）
		if (!self.level().isClientSide() && !self.getPassengers().isEmpty()
				&& !(self.getControllingPassenger() instanceof Player)) {
			self.ejectPassengers();
		}
		// 服务端：升级槽内容驱动运载状态（有运载升级物品 → 启用运载空间）
		if (!self.level().isClientSide()) {
			boolean hasCargoUpgrade = bigdog$hasUpgrade(BigDogMod.CARGO_UPGRADE);
			if (hasCargoUpgrade && bigdog$cargo == null) {
				bigdog$ensureCargo();
			}
			if (bigdog$hasCargo() != hasCargoUpgrade) {
				self.getEntityData().set(DATA_HAS_CARGO, hasCargoUpgrade);
			}
			// 同步"极限过载可用"：有极限过载 + 前置疾速升级（客户端提示用）
			boolean overdriveReady = bigdog$hasUpgrade(BigDogMod.OVERDRIVE_UPGRADE)
					&& bigdog$hasUpgrade(BigDogMod.SPEED_UPGRADE);
			self.getEntityData().set(DATA_OVERDRIVE_READY, overdriveReady);

			// 自动恢复：前置运载升级，每 5 秒消耗运载内狗粮喂狗回血（放多个不缩短时间）
			if (bigdog$hasUpgrade(BigDogMod.AUTO_HEAL_UPGRADE) && bigdog$hasUpgrade(BigDogMod.CARGO_UPGRADE)
					&& bigdog$cargo != null && self.getHealth() < self.getMaxHealth()) {
				if (++bigdog$healTimer >= 100) {
					bigdog$healTimer = 0;
					for (int i = 0; i < bigdog$cargo.getContainerSize(); i++) {
						ItemStack food = bigdog$cargo.getItem(i);
						if (!food.isEmpty() && self.isFood(food)) {
							food.shrink(1);
							self.heal(4.0F);
							break;
						}
					}
				}
			} else {
				bigdog$healTimer = 0;
			}
		}
		if (!bigdog$isRideable()) {
			return;
		}
		if (!(self.getControllingPassenger() instanceof Player player)) {
			return;
		}
		if (BigDogSkills.isCharging(self)) {
			// 蓄力：不能移动（保留竖直速度，避免缓降），跟随玩家视角
			self.xxa = 0.0F;
			self.zza = 0.0F;
			self.setDeltaMovement(new Vec3(0, self.getDeltaMovement().y, 0));
			self.getNavigation().stop();
			return;
		}
		// 跳跃：1/2/3 级分别能跳 1.5/2/2.5 格；跳跃提升药水再叠加（大狗被动受喷溅药水影响）
		if (player.isJumping() && self.onGround()) {
			self.jumpFromGround();
			double scale = self.getAttributeValue(Attributes.SCALE);
			double targetHeight;
			if (scale >= BigDogSkills.MAX_SCALE - 0.01) {
				targetHeight = 2.5;
			} else if (scale >= BigDogSkills.MIN_SCALE + 2 * BigDogSkills.SCALE_STEP - 0.01) {
				targetHeight = 2.0;
			} else {
				targetHeight = 1.5;
			}
			net.minecraft.world.effect.MobEffectInstance jump = self.getEffect(net.minecraft.world.effect.MobEffects.JUMP_BOOST);
			if (jump != null) {
				targetHeight += 0.5 * (jump.getAmplifier() + 1);
			}
			// MC 重力 0.08：跳高 h 需要初速 sqrt(h * 0.16)
			double v0 = Math.sqrt(targetHeight * 0.16);
			Vec3 v = self.getDeltaMovement();
			self.setDeltaMovement(v.x, v0, v.z);
			self.fallDistance = 0.0F;
		}
		self.getNavigation().stop();
	}
}
