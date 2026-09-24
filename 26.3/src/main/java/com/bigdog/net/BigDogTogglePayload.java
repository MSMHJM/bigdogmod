package com.bigdog.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** 客户端 -> 服务端：切换固定视角模式（true 开启 / false 关闭） */
public record BigDogTogglePayload(boolean fixed) implements CustomPacketPayload {
	@Override
	public Type<? extends CustomPacketPayload> type() {
		return BigDogNetworking.TOGGLE_TYPE;
	}

	public static final StreamCodec<RegistryFriendlyByteBuf, BigDogTogglePayload> STREAM_CODEC = StreamCodec.of(
			(buf, payload) -> buf.writeBoolean(payload.fixed()),
			buf -> new BigDogTogglePayload(buf.readBoolean()));
}
