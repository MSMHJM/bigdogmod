package com.bigdog.client.mixin;

import net.minecraft.client.model.animal.wolf.WolfModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.entity.state.WolfRenderState;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 减小尾巴摆动幅度：原版摆动 1.4 弧度（约 80°），大狗身上容易穿模，
 * 改为约 1/4，摆动更自然。
 */
@Mixin(WolfModel.class)
public abstract class WolfModelMixin {
	@Shadow
	protected ModelPart tail;

	@Inject(method = "setupAnim", at = @At("TAIL"))
	private void bigdog$calmTail(WolfRenderState state, CallbackInfo ci) {
		this.tail.yRot *= 0.25F;
	}
}
