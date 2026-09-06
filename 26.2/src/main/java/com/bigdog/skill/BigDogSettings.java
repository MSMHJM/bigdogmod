package com.bigdog.skill;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * 大狗速度/转弯配置：全局默认 + 按玩家覆盖，存储在存档（data/bigdog_settings.dat）。
 * 数值范围 1~50，默认 20；实际效果按 value/20 比例生效。
 */
public class BigDogSettings extends SavedData {
	public static final int DEFAULT = 20;
	public static final int MIN = 1;
	public static final int MAX_SPEED = 100;
	public static final int MAX_TURN = 50;

	public record PlayerValue(int speed, int turn) {
	}

	private static final Codec<PlayerValue> PLAYER_CODEC = RecordCodecBuilder.create(instance -> instance.group(
			Codec.INT.fieldOf("speed").forGetter(PlayerValue::speed),
			Codec.INT.fieldOf("turn").forGetter(PlayerValue::turn))
			.apply(instance, PlayerValue::new));

	private static final Codec<BigDogSettings> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			Codec.INT.optionalFieldOf("speed", DEFAULT).forGetter(s -> s.globalSpeed),
			Codec.INT.optionalFieldOf("turn", DEFAULT).forGetter(s -> s.globalTurn),
			Codec.unboundedMap(Codec.STRING, PLAYER_CODEC)
					.optionalFieldOf("players", Map.of()).forGetter(s -> s.players))
			.apply(instance, BigDogSettings::new));

	public static final SavedDataType<BigDogSettings> TYPE = new SavedDataType<>(
			Identifier.fromNamespaceAndPath("bigdog", "settings"),
			BigDogSettings::new, CODEC, DataFixTypes.SAVED_DATA_MAP_DATA);

	private int globalSpeed = DEFAULT;
	private int globalTurn = DEFAULT;
	private final Map<String, PlayerValue> players = new HashMap<>();

	public BigDogSettings() {
	}

	private BigDogSettings(int globalSpeed, int globalTurn, Map<String, PlayerValue> players) {
		this.globalSpeed = globalSpeed;
		this.globalTurn = globalTurn;
		this.players.putAll(players);
	}

	public static BigDogSettings get(ServerLevel level) {
		return level.getDataStorage().computeIfAbsent(TYPE);
	}

	private static int clampSpeed(int v) {
		return Math.max(MIN, Math.min(MAX_SPEED, v));
	}

	private static int clampTurn(int v) {
		return Math.max(MIN, Math.min(MAX_TURN, v));
	}

	/** 玩家生效的速度等级：玩家有覆盖用玩家的，否则用全局 */
	public int getSpeed(UUID player) {
		PlayerValue v = players.get(player.toString());
		return v != null ? v.speed() : globalSpeed;
	}

	/** 玩家生效的转弯等级：玩家有覆盖用玩家的，否则用全局 */
	public int getTurn(UUID player) {
		PlayerValue v = players.get(player.toString());
		return v != null ? v.turn() : globalTurn;
	}

	public void setGlobalSpeed(int v) {
		globalSpeed = clampSpeed(v);
		setDirty();
	}

	public void setGlobalTurn(int v) {
		globalTurn = clampTurn(v);
		setDirty();
	}

	public void setPlayerSpeed(UUID player, int v) {
		PlayerValue cur = players.getOrDefault(player.toString(), new PlayerValue(globalSpeed, globalTurn));
		players.put(player.toString(), new PlayerValue(clampSpeed(v), cur.turn()));
		setDirty();
	}

	public void setPlayerTurn(UUID player, int v) {
		PlayerValue cur = players.getOrDefault(player.toString(), new PlayerValue(globalSpeed, globalTurn));
		players.put(player.toString(), new PlayerValue(cur.speed(), clampTurn(v)));
		setDirty();
	}

	public void resetAll() {
		globalSpeed = DEFAULT;
		globalTurn = DEFAULT;
		players.clear();
		setDirty();
	}

	public void resetPlayer(UUID player) {
		players.remove(player.toString());
		setDirty();
	}
}
