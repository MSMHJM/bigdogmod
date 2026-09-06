package com.bigdog.skill;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;

import com.bigdog.BigDogMod;

/** 大狗音效：升级音（大狗）、声波音（叫）、降级音（小狗） */
public final class BigDogSounds {
	public static final Identifier UPGRADE = Identifier.fromNamespaceAndPath(BigDogMod.MOD_ID, "upgrade");
	public static final Identifier BARK = Identifier.fromNamespaceAndPath(BigDogMod.MOD_ID, "bark");
	public static final Identifier PUPPY = Identifier.fromNamespaceAndPath(BigDogMod.MOD_ID, "puppy");

	private static SoundEvent upgradeEvent;
	private static SoundEvent barkEvent;
	private static SoundEvent puppyEvent;

	private BigDogSounds() {
	}

	/** 注册到声音注册表，否则 SoundManager 无法解析导致无声 */
	public static void register() {
		upgradeEvent = Registry.register(BuiltInRegistries.SOUND_EVENT, UPGRADE,
				SoundEvent.createVariableRangeEvent(UPGRADE));
		barkEvent = Registry.register(BuiltInRegistries.SOUND_EVENT, BARK,
				SoundEvent.createVariableRangeEvent(BARK));
		puppyEvent = Registry.register(BuiltInRegistries.SOUND_EVENT, PUPPY,
				SoundEvent.createVariableRangeEvent(PUPPY));
	}

	public static SoundEvent upgrade() {
		if (upgradeEvent == null) {
			upgradeEvent = SoundEvent.createVariableRangeEvent(UPGRADE);
		}
		return upgradeEvent;
	}

	public static SoundEvent bark() {
		if (barkEvent == null) {
			barkEvent = SoundEvent.createVariableRangeEvent(BARK);
		}
		return barkEvent;
	}

	public static SoundEvent puppy() {
		if (puppyEvent == null) {
			puppyEvent = SoundEvent.createVariableRangeEvent(PUPPY);
		}
		return puppyEvent;
	}

	/** 大狗升级（体型变大）时播放 */
	public static void playUpgrade(Level level, Entity source) {
		level.playSound(null, source.getX(), source.getY(), source.getZ(), upgrade(), SoundSource.NEUTRAL, 1.0F, 1.0F);
	}

	/** 声波（叫）音效，可调节音量与音调 */
	public static void playBark(Level level, Entity source, float volume, float pitch) {
		level.playSound(null, source.getX(), source.getY(), source.getZ(), bark(), SoundSource.NEUTRAL, volume, pitch);
	}

	/** 喂毒马铃薯降级时播放 */
	public static void playPuppy(Level level, Entity source) {
		level.playSound(null, source.getX(), source.getY(), source.getZ(), puppy(), SoundSource.NEUTRAL, 1.0F, 1.0F);
	}
}
