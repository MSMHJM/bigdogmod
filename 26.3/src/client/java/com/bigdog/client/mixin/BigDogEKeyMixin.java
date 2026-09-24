package com.bigdog.client.mixin;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.animal.wolf.Wolf;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.bigdog.net.BigDogSkillPayload;
import com.bigdog.skill.BigDogSkills;

/**
 * 骑乘大狗按 E：如果狗装了运载空间，改为请求服务端打开狗的运载空间，
 * 并吞掉本次 E 点击，避免打开玩家自己的物品栏。
 */
@Mixin(Minecraft.class)
public abstract class BigDogEKeyMixin {
	@Inject(method = "handleKeybinds", at = @At("HEAD"))
	private void bigdog$inventoryKey(CallbackInfo ci) {
		Minecraft mc = (Minecraft) (Object) this;
		if (mc.player != null
				&& mc.player.getVehicle() instanceof Wolf wolf
				&& BigDogSkills.isRideable(wolf)
				&& BigDogSkills.hasCargo(wolf)
				&& mc.options.keyInventory.consumeClick()) {
			ClientPlayNetworking.send(new BigDogSkillPayload(BigDogSkills.SKILL_OPEN_CARGO, 0, 0));
		}
	}
}
