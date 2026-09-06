package com.bigdog.mixin;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.wolf.Wolf;
import net.minecraft.world.entity.player.Player;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.bigdog.skill.BigDogSkills;

/**
 * 允许放大过的且驯服归属主人控制的玩家乘客成为控制乘客。
 * getControllingPassenger 声明于 Mob，故在此类注入。
 */
@Mixin(Mob.class)
public abstract class BigDogMobRideMixin {
	@Inject(method = "getControllingPassenger", at = @At("HEAD"), cancellable = true)
	private void bigdog$getControllingPassenger(CallbackInfoReturnable<LivingEntity> cir) {
		if ((Object) this instanceof Wolf wolf && BigDogSkills.isRideable(wolf)
				&& wolf.getFirstPassenger() instanceof Player player && wolf.isOwnedBy(player)) {
			cir.setReturnValue(player);
		}
	}
}
