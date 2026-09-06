package com.bigdog.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * 客户端 -> 服务端：请求大狗动作。
 * skill: 0=嚼嚼嚼 1=开始蓄力 2=松开蓄力 4=打开运载 5=疾速冲刺 6=喷气推进
 * yaw/pitch：开始蓄力时锁定的视角（消除准星偏差），其余动作传 0。
 */
public record BigDogSkillPayload(int skill, float yaw, float pitch) implements CustomPacketPayload {
	@Override
	public Type<? extends CustomPacketPayload> type() {
		return BigDogNetworking.SKILL_TYPE;
	}

	public static final StreamCodec<RegistryFriendlyByteBuf, BigDogSkillPayload> STREAM_CODEC = StreamCodec.of(
			(buf, payload) -> {
				buf.writeVarInt(payload.skill());
				buf.writeFloat(payload.yaw());
				buf.writeFloat(payload.pitch());
			},
			buf -> new BigDogSkillPayload(buf.readVarInt(), buf.readFloat(), buf.readFloat()));
}
