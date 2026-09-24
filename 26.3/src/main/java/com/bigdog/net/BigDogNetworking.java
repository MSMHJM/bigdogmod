package com.bigdog.net;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

import com.bigdog.BigDogMod;
import com.bigdog.skill.BigDogSettings;
import com.bigdog.skill.BigDogSkills;

public final class BigDogNetworking {
	public static final CustomPacketPayload.Type<BigDogSkillPayload> SKILL_TYPE =
			new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(BigDogMod.MOD_ID, "skill"));

	/** 服务端 -> 客户端：玩家生效的速度/转弯等级 */
	public static final CustomPacketPayload.Type<BigDogSettingsPayload> SETTINGS_TYPE =
			new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(BigDogMod.MOD_ID, "settings"));

	/** 客户端 -> 服务端：切换固定视角模式 */
	public static final CustomPacketPayload.Type<BigDogTogglePayload> TOGGLE_TYPE =
			new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(BigDogMod.MOD_ID, "toggle"));

	/** 服务端 -> 客户端：疾速冲刺状态（HUD 冷却条） */
	public static final CustomPacketPayload.Type<BigDogBoostPayload> BOOST_TYPE =
			new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(BigDogMod.MOD_ID, "boost"));

	private BigDogNetworking() {
	}

	public static void register() {
		PayloadTypeRegistry.serverboundPlay().register(SKILL_TYPE, BigDogSkillPayload.STREAM_CODEC);
		PayloadTypeRegistry.serverboundPlay().register(TOGGLE_TYPE, BigDogTogglePayload.STREAM_CODEC);
		PayloadTypeRegistry.clientboundPlay().register(SETTINGS_TYPE, BigDogSettingsPayload.STREAM_CODEC);
		PayloadTypeRegistry.clientboundPlay().register(BOOST_TYPE, BigDogBoostPayload.STREAM_CODEC);
		ServerPlayNetworking.registerGlobalReceiver(SKILL_TYPE, BigDogSkills::handle);
		ServerPlayNetworking.registerGlobalReceiver(TOGGLE_TYPE, (payload, context) -> context.server().execute(() -> {
			BigDogSkills.setFixedCamera(context.player(), payload.fixed());
		}));
		// 玩家加入后同步当前生效的设置（客户端骑乘移动需要一致数值）
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> sendSettings(handler.player));
	}

	/** 把当前生效的速度/转弯等级发给玩家（客户端权威模拟需要） */
	public static void sendSettings(ServerPlayer player) {
		BigDogSettings settings = BigDogSettings.get(player.level());
		ServerPlayNetworking.send(player,
				new BigDogSettingsPayload(settings.getSpeed(player.getUUID()), settings.getTurn(player.getUUID())));
	}
}
