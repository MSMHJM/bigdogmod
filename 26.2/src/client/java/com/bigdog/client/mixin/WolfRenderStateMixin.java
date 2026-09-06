package com.bigdog.client.mixin;

import net.minecraft.client.renderer.entity.state.WolfRenderState;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.Implements;
import org.spongepowered.asm.mixin.Interface;

import com.bigdog.access.BigDogWolfStateAccess;

/** 给 WolfRenderState 加一个同步的运载标志（经接口访问，避免直接引用 Mixin 类） */
@Implements(@Interface(iface = BigDogWolfStateAccess.class, prefix = "bigdogstate$"))
@Mixin(WolfRenderState.class)
public abstract class WolfRenderStateMixin {
	@Unique
	private boolean bigdogstate$cargo;

	@Unique
	public boolean bigdogstate$hasCargo() {
		return bigdogstate$cargo;
	}

	@Unique
	public void bigdogstate$setCargo(boolean cargo) {
		bigdogstate$cargo = cargo;
	}
}
