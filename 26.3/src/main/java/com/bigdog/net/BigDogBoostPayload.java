package com.bigdog.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * 服务端 -> 客户端：同步加速/冷却状态（HUD 条用）。
 * state: 1=疾速生效（ticks=剩余） 2=疾速冷却（ticks=剩余） 3=极限加速生效（ticks=剩余） 4=喷气冷却（ticks=剩余） 5=极限加速结束 0=清除
 */
public record BigDogBoostPayload(int state, int ticks) implements CustomPacketPayload {
	@Override
	public Type<? extends CustomPacketPayload> type() {
		return BigDogNetworking.BOOST_TYPE;
	}

	public static final StreamCodec<RegistryFriendlyByteBuf, BigDogBoostPayload> STREAM_CODEC = StreamCodec.of(
			(buf, payload) -> {
				buf.writeVarInt(payload.state());
				buf.writeVarInt(payload.ticks());
			},
			buf -> new BigDogBoostPayload(buf.readVarInt(), buf.readVarInt()));
}
