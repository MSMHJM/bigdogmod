package com.bigdog.client.mixin;

import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.WolfRenderer;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.entity.state.WolfRenderState;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.wolf.Wolf;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.bigdog.access.BigDogCargoAccess;
import com.bigdog.access.BigDogWolfStateAccess;
import com.bigdog.client.render.BigDogChestLayer;

/**
 * 给狼渲染器挂背上箱子层（@Mixin 指向方法声明所在类 LivingEntityRenderer，
 * 用 instanceof WolfRenderer 限定只对狼生效）：
 * - 提取渲染状态时同步运载标志（供箱子层判断）
 * - 构造时挂上背上箱子层
 */
@Mixin(LivingEntityRenderer.class)
public abstract class WolfRendererMixin {
	@Shadow
	protected abstract boolean addLayer(RenderLayer layer);

	@Inject(method = "extractRenderState", at = @At("TAIL"))
	private void bigdog$extractCargo(LivingEntity entity, LivingEntityRenderState state, float f, CallbackInfo ci) {
		if ((Object) this instanceof WolfRenderer && entity instanceof Wolf wolf
				&& state instanceof WolfRenderState wolfState) {
			((BigDogWolfStateAccess) wolfState).setCargo(((BigDogCargoAccess) wolf).hasCargo());
		}
	}

	@Inject(method = "<init>", at = @At("TAIL"))
	private void bigdog$addChestLayer(CallbackInfo ci) {
		if ((Object) this instanceof WolfRenderer) {
			this.addLayer(new BigDogChestLayer((RenderLayerParent) (Object) this));
		}
	}
}
