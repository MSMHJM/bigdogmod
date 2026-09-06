package com.bigdog.command;

import java.util.Collection;
import java.util.UUID;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;

import com.bigdog.net.BigDogNetworking;
import com.bigdog.skill.BigDogSettings;

/**
 * 大狗设置指令（需要权限 2）：
 * /bigdog speed <1-50|reset> [玩家名]   —— 全局或指定玩家的速度等级
 * /bigdog turn <1-50|reset> [玩家名]    —— 全局或指定玩家的转弯等级
 * /bigdog reset [玩家名]                —— 恢复默认（20）
 */
public final class BigDogCommands {
	private BigDogCommands() {
	}

	public static void register() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
				dispatcher.register(Commands.literal("bigdog")
						.then(Commands.literal("speed")
								.then(Commands.argument("value", IntegerArgumentType.integer(
										BigDogSettings.MIN, BigDogSettings.MAX_SPEED))
										.executes(ctx -> applySpeed(ctx, IntegerArgumentType.getInteger(ctx, "value"), null))
										.then(playerArg().executes(ctx -> applySpeed(ctx,
												IntegerArgumentType.getInteger(ctx, "value"),
												firstProfile(ctx)))))
								.then(Commands.literal("reset")
										.executes(ctx -> applySpeed(ctx, BigDogSettings.DEFAULT, null))
										.then(playerArg().executes(ctx -> applySpeed(ctx,
												BigDogSettings.DEFAULT, firstProfile(ctx))))))
						.then(Commands.literal("turn")
								.then(Commands.argument("value", IntegerArgumentType.integer(
										BigDogSettings.MIN, BigDogSettings.MAX_TURN))
										.executes(ctx -> applyTurn(ctx, IntegerArgumentType.getInteger(ctx, "value"), null))
										.then(playerArg().executes(ctx -> applyTurn(ctx,
												IntegerArgumentType.getInteger(ctx, "value"),
												firstProfile(ctx)))))
								.then(Commands.literal("reset")
										.executes(ctx -> applyTurn(ctx, BigDogSettings.DEFAULT, null))
										.then(playerArg().executes(ctx -> applyTurn(ctx,
												BigDogSettings.DEFAULT, firstProfile(ctx))))))
						.then(Commands.literal("reset")
								.executes(ctx -> resetAll(ctx))
								.then(playerArg().executes(ctx -> resetPlayer(ctx, firstProfile(ctx)))))));
	}

	private static com.mojang.brigadier.builder.ArgumentBuilder<CommandSourceStack, ?> playerArg() {
		return Commands.argument("player", GameProfileArgument.gameProfile());
	}

	private static NameAndId firstProfile(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx)
			throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		Collection<NameAndId> profiles = GameProfileArgument.getGameProfiles(ctx, "player");
		return profiles.iterator().next();
	}

	private static int applySpeed(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx,
			int value, NameAndId target) {
		BigDogSettings settings = BigDogSettings.get(ctx.getSource().getLevel());
		if (target == null) {
			settings.setGlobalSpeed(value);
			ctx.getSource().sendSuccess(() -> Component.literal("大狗全局速度等级已设为 " + value
					+ "（范围 " + BigDogSettings.MIN + "~" + BigDogSettings.MAX_SPEED + "，默认 " + BigDogSettings.DEFAULT + "）"), true);
			broadcastAll(ctx.getSource().getServer());
		} else {
			settings.setPlayerSpeed(target.id(), value);
			ctx.getSource().sendSuccess(() -> Component.literal("已为 " + target.name() + " 设置大狗速度等级 " + value), true);
			sendToPlayer(ctx.getSource().getServer(), target.id());
		}
		return 1;
	}

	private static int applyTurn(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx,
			int value, NameAndId target) {
		BigDogSettings settings = BigDogSettings.get(ctx.getSource().getLevel());
		if (target == null) {
			settings.setGlobalTurn(value);
			ctx.getSource().sendSuccess(() -> Component.literal("大狗全局转弯等级已设为 " + value
					+ "（范围 " + BigDogSettings.MIN + "~" + BigDogSettings.MAX_TURN + "，默认 " + BigDogSettings.DEFAULT + "）"), true);
			broadcastAll(ctx.getSource().getServer());
		} else {
			settings.setPlayerTurn(target.id(), value);
			ctx.getSource().sendSuccess(() -> Component.literal("已为 " + target.name() + " 设置大狗转弯等级 " + value), true);
			sendToPlayer(ctx.getSource().getServer(), target.id());
		}
		return 1;
	}

	private static int resetAll(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx) {
		BigDogSettings.get(ctx.getSource().getLevel()).resetAll();
		ctx.getSource().sendSuccess(() -> Component.literal("大狗速度/转弯已全部恢复默认 " + BigDogSettings.DEFAULT), true);
		broadcastAll(ctx.getSource().getServer());
		return 1;
	}

	private static int resetPlayer(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx, NameAndId target) {
		BigDogSettings.get(ctx.getSource().getLevel()).resetPlayer(target.id());
		ctx.getSource().sendSuccess(() -> Component.literal("已恢复 " + target.name() + " 的大狗设置（回退到全局默认）"), true);
		sendToPlayer(ctx.getSource().getServer(), target.id());
		return 1;
	}

	private static void broadcastAll(net.minecraft.server.MinecraftServer server) {
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			BigDogNetworking.sendSettings(player);
		}
	}

	private static void sendToPlayer(net.minecraft.server.MinecraftServer server, UUID uuid) {
		ServerPlayer player = server.getPlayerList().getPlayer(uuid);
		if (player != null) {
			BigDogNetworking.sendSettings(player);
		}
	}
}
