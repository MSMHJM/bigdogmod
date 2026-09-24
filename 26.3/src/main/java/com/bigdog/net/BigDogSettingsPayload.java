package com.bigdog.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * 服务端 -> 客户端：玩家当前生效的大狗速度/转弯等级。
 * 客户端骑乘移动是权威模拟，必须拿到与服务端一致的数值。
 */
public record BigDogSettingsPayload(int speed, int turn) implements CustomPacketPayload {
	@Override
	public Type<? extends CustomPacketPayload> type() {
		return BigDogNetworking.SETTINGS_TYPE;
	}

	public static final StreamCodec<RegistryFriendlyByteBuf, BigDogSettingsPayload> STREAM_CODEC = StreamCodec.of(
			(buf, payload) -> {
				buf.writeVarInt(payload.speed());
				buf.writeVarInt(payload.turn());
			},
			buf -> new BigDogSettingsPayload(buf.readVarInt(), buf.readVarInt()));
}
