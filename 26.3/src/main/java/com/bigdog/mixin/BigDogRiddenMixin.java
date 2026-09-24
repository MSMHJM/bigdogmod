package com.bigdog.mixin;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.wolf.Wolf;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.bigdog.skill.BigDogSettings;
import com.bigdog.skill.BigDogSkills;

/**
 * 大狗骑乘移动控制（26.3 骑乘移动由 travelRidden → getRiddenInput/getRiddenSpeed 驱动，
 * 这两个方法声明在 LivingEntity 上，且在狼的 AI（serverAiStep）之后调用，不会被 AI 覆盖）。
 * - 方向以玩家视角为基准并固定（D=-90°、A=+90°、W+D=-45°…），带转弯阻尼（转弯半径）
 * - 后退（S）：不转动狗，直接后退（半速）
 * - 蓄力：返回零向量，不能移动
 * - 速度：原本速度（狼的基础速度）× 训狗术 1.2，运载再降 30%，带惯性缓动
 */
@Mixin(LivingEntity.class)
public abstract class BigDogRiddenMixin {
	@Unique
	private static float bigdog$approachAngle(float target, float current, float step) {
		float diff = target - current;
		while (diff > 180.0F) {
			diff -= 360.0F;
		}
		while (diff < -180.0F) {
			diff += 360.0F;
		}
		return current + Math.max(-step, Math.min(step, diff));
	}

	@Inject(method = "getRiddenInput", at = @At("HEAD"), cancellable = true)
	private void bigdog$riddenInput(Player player, Vec3 travelVector, CallbackInfoReturnable<Vec3> cir) {
		LivingEntity self = (LivingEntity) (Object) this;
		if (!(self instanceof Wolf wolf) || !BigDogSkills.isRideable(wolf) || wolf.getControllingPassenger() != player) {
			return;
		}
		if (BigDogSkills.isCharging(wolf)) {
			// 蓄力：跟随玩家视角，不能移动
			wolf.setYRot(player.getYRot());
			wolf.setYHeadRot(player.getYRot());
			cir.setReturnValue(Vec3.ZERO);
			return;
		}
		// 固定视角模式（开车）：A/D 只原地转向（不前进），W/S 前进/后退
		// 转向速度与转弯等级成反比（数值越小转得越快），默认 20 级 = 3°/tick
		if (BigDogSkills.fixedCameraOf(player, wolf.level().isClientSide())) {
			float turn = 3.0F * 20.0F / ridingTurnLevel(wolf, player);
			if (player.xxa < 0.0F) {
				wolf.setYRot(wolf.getYRot() + turn); // A → 狗往右转
			} else if (player.xxa > 0.0F) {
				wolf.setYRot(wolf.getYRot() - turn); // D → 狗往左转
			}
			wolf.setYBodyRot(wolf.getYRot());
			wolf.setYHeadRot(wolf.getYRot());
			float z = 0.0F;
			if (player.zza > 0.0F) {
				z = 1.0F;
			} else if (player.zza < 0.0F) {
				z = -0.5F;
			}
			cir.setReturnValue(new Vec3(0.0, 0.0, z));
			return;
		}
		boolean moving = player.zza != 0.0F || player.xxa != 0.0F;
		if (player.zza < 0.0F) {
			// 后退：保持朝向直接后退（半速，由 z=-0.5 控制）
			wolf.setYBodyRot(wolf.getYRot());
			wolf.setYHeadRot(bigdog$approachAngle(player.getYRot(), wolf.getYHeadRot(), 18.0F));
			cir.setReturnValue(new Vec3(0.0, 0.0, -0.5));
			return;
		}
		// 转向：方向键 = ±90°，方向键+前进 = ±45°，带阻尼
		float turnStep = 11.0F * 20.0F / ridingTurnLevel(wolf, player);
		float local = (float) Math.toDegrees(Math.atan2(player.xxa, player.zza));
		float targetYaw = player.getYRot() - local;
		wolf.setYRot(bigdog$approachAngle(targetYaw, wolf.getYRot(), turnStep));
		wolf.setXRot(player.getXRot() * 0.5F);
		wolf.setYBodyRot(wolf.getYRot());
		wolf.setYHeadRot(bigdog$approachAngle(player.getYRot(), wolf.getYHeadRot(), 18.0F));
		cir.setReturnValue(moving ? new Vec3(0.0, 0.0, 1.0) : Vec3.ZERO);
	}

	@Unique
	private static int ridingSpeedLevel(Wolf wolf, Player player) {
		if (wolf.level().isClientSide()) {
			return BigDogSkills.clientSpeed;
		}
		return BigDogSettings.get((net.minecraft.server.level.ServerLevel) wolf.level()).getSpeed(player.getUUID());
	}

	@Unique
	private static int ridingTurnLevel(Wolf wolf, Player player) {
		if (wolf.level().isClientSide()) {
			return BigDogSkills.clientTurn;
		}
		return BigDogSettings.get((net.minecraft.server.level.ServerLevel) wolf.level()).getTurn(player.getUUID());
	}

	@Inject(method = "getRiddenSpeed", at = @At("HEAD"), cancellable = true)
	private void bigdog$riddenSpeed(Player player, CallbackInfoReturnable<Float> cir) {
		LivingEntity self = (LivingEntity) (Object) this;
		if (!(self instanceof Wolf wolf) || !BigDogSkills.isRideable(wolf) || wolf.getControllingPassenger() != player) {
			return;
		}
		boolean moving = player.zza != 0.0F || player.xxa != 0.0F;
		if (!moving || BigDogSkills.isCharging(wolf)) {
			cir.setReturnValue(0.0F);
			return;
		}
		// 速度：原速 × 1.2 × (速度等级/20)；运载、速度药水、疾速冲刺再叠加
		float targetSpeed = (float) wolf.getAttributeValue(Attributes.MOVEMENT_SPEED) * 1.2F
				* (ridingSpeedLevel(wolf, player) / 20.0F);
		if (BigDogSkills.hasCargo(wolf)) {
			targetSpeed *= 0.7F; // 运载减速
		}
		// 速度药水效果直接叠加（每级 +20%，可超过指令速度上限）
		net.minecraft.world.effect.MobEffectInstance speedPotion =
				wolf.getEffect(net.minecraft.world.effect.MobEffects.SPEED);
		if (speedPotion != null) {
			targetSpeed *= 1.0F + 0.2F * (speedPotion.getAmplifier() + 1);
		}
		// 疾速冲刺/极限加速：读同步数据，客户端权威模拟一致（×3 / ×10）
		if (BigDogSkills.isBoosted(wolf)) {
			targetSpeed *= BigDogSkills.boostMultiplier(wolf);
		}
		// 喷气推进期间保持水平推力（避免被骑乘速度覆盖导致"感觉不到"）
		if (BigDogSkills.isJetActive(wolf)) {
			targetSpeed *= BigDogSkills.JET_SPEED_MULTIPLIER;
		}
		float currentSpeed = wolf.getSpeed();
		cir.setReturnValue(currentSpeed + (targetSpeed - currentSpeed) * 0.18F);
	}
}
