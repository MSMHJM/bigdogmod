package com.bigdog.mixin;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.animal.wolf.Wolf;
import net.minecraft.world.phys.Vec3;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.bigdog.skill.BigDogSkills;

/**
 * 大狗多座位：主人（乘客 0）坐背前，其余乘客依次往后。
 * 注意：26.3 调用 getPassengerAttachmentPoint 时 scale 参数固定为 1.0，
 * 位置必须自己乘狗的 getScale()（随体型缩放）。
 */
@Mixin(Entity.class)
public abstract class BigDogSeatMixin {
	@Inject(method = "getPassengerAttachmentPoint", at = @At("HEAD"), cancellable = true)
	private void bigdog$seatPoint(Entity passenger, EntityDimensions dimensions, float scale,
			CallbackInfoReturnable<Vec3> cir) {
		Entity self = (Entity) (Object) this;
		if (!(self instanceof Wolf wolf) || !BigDogSkills.isRideable(wolf)) {
			return;
		}
		float s = wolf.getScale();
		int index = Math.max(0, wolf.getPassengers().indexOf(passenger));
		// 实测：+Z 是狗头方向（前方）。主人坐背前，客人依次往后。
		float z;
		switch (index) {
			case 1:
				z = -0.3F;
				break;
			case 2:
				z = -1.2F;
				break;
			default:
				z = -0.2F;
		}
		double y = 0.7 * s;
		cir.setReturnValue(new Vec3(0.0, y, z * s)
				.yRot(-wolf.getYRot() * (float) (Math.PI / 180.0)));
	}
}
