package com.bigdog.client.render;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.model.animal.wolf.WolfModel;
import net.minecraft.client.model.object.chest.ChestModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.state.WolfRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;

import com.bigdog.access.BigDogWolfStateAccess;

/**
 * 大狗背上的箱子（纯模型，无碰撞）。装了运载升级的狼在背上渲染一个箱子，
 * 随体型一起缩放。
 */
public class BigDogChestLayer extends RenderLayer<WolfRenderState, WolfModel> {
	private static final Identifier CHEST_TEXTURE =
			Identifier.fromNamespaceAndPath("minecraft", "textures/entity/chest/normal.png");
	private final ChestModel chestModel;

	public BigDogChestLayer(RenderLayerParent<WolfRenderState, WolfModel> parent) {
		super(parent);
		this.chestModel = new ChestModel(ChestModel.createSingleBodyLayer().bakeRoot());
	}

	@Override
	public void submit(PoseStack poseStack, SubmitNodeCollector collector, int light,
			WolfRenderState state, float partialTick, float interpolatedPitch) {
		if (!((BigDogWolfStateAccess) state).hasCargo() || state.isInvisible) {
			return;
		}
		poseStack.pushPose();
		// 放在身体正上方（模型空间：身体顶 ~y=10），缩小到约半格宽
		poseStack.translate(0.0F, 10.0F, 1.0F);
		poseStack.scale(0.55F, 0.55F, 0.55F);
		collector.order(1).submitModel(this.chestModel, 0.0F, poseStack,
				RenderTypes.entityCutout(CHEST_TEXTURE), light, OverlayTexture.NO_OVERLAY,
				0xFFFFFFFF, null, state.outlineColor);
		poseStack.popPose();
	}
}
