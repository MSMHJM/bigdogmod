package com.bigdog.access;

import net.minecraft.world.entity.player.Player;

/**
 * 通过 @Implements 注入到 Wolf 类的访问接口（生产环境不允许普通代码直接引用 Mixin 类，
 * 必须经接口访问）。方法名与前缀 "bigdogcargo$" 拼接后对应 WolfMixin 中的实现。
 * 注意：不能放在 mixin 包内，否则会被当作 Mixin 类拒绝直接引用。
 */
public interface BigDogCargoAccess {
	boolean hasCargo();

	void openCargo(Player player);

	/** 升级槽里是否放了疾速升级（疾速冲刺技能前提） */
	boolean hasBoostUpgrade();

	/** 升级槽里是否放了喷气升级（喷气推进技能前提） */
	boolean hasJetUpgrade();

	/** 升级槽里是否放了攻击升级（提升嚼嚼嚼伤害） */
	boolean hasAttackUpgrade();

	/** 升级槽里冷却升级的数量（可叠加） */
	int cooldownUpgradeCount();

	/** 升级槽里是否还有可用的极限过载升级（前置：疾速升级） */
	boolean hasOverdriveUpgrade();

	/** 极限过载当前可用（有极限过载 + 前置疾速升级；同步数据，客户端/服务端都可用） */
	boolean hasOverdriveReady();

	/** 消耗一个极限过载：槽里第一个极限过载替换为损坏升级 */
	void consumeOverdrive();

	/** 喷气推进后免除摔落伤害（服务端标记，落地时清除） */
	void setJetNoFall(boolean noFall);

	/** 极限加速是否生效（同步数据，客户端/服务端都可用） */
	boolean isOverdrive();

	/** 服务端设置极限加速生效状态（同步到客户端） */
	void setOverdrive(boolean overdrive);

	/** 疾速冲刺是否生效（同步数据，客户端/服务端都可用） */
	boolean isBoosted();

	/** 服务端设置疾速冲刺生效状态（同步到客户端） */
	void setBoosted(boolean boosted);
}
