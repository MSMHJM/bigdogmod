package com.bigdog.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.wolf.Wolf;

import com.mojang.blaze3d.platform.InputConstants;

import com.bigdog.BigDogMod;
import com.bigdog.access.BigDogCargoAccess;
import com.bigdog.net.BigDogBoostPayload;
import com.bigdog.net.BigDogNetworking;
import com.bigdog.net.BigDogSkillPayload;
import com.bigdog.net.BigDogTogglePayload;
import com.bigdog.skill.BigDogSkills;

public class BigDogClient implements ClientModInitializer {
	private static final KeyMapping KEY_MUNCH = KeyMappingHelper.registerKeyMapping(
			new KeyMapping("key.bigdog.munch",
					InputConstants.getKey("key.keyboard.r").getValue(), KeyMapping.Category.GAMEPLAY));
	private static final KeyMapping KEY_WOOF = KeyMappingHelper.registerKeyMapping(
			new KeyMapping("key.bigdog.woof",
					InputConstants.getKey("key.keyboard.g").getValue(), KeyMapping.Category.GAMEPLAY));
	private static final KeyMapping KEY_FIXED = KeyMappingHelper.registerKeyMapping(
			new KeyMapping("key.bigdog.fixed",
					InputConstants.getKey("key.keyboard.v").getValue(), KeyMapping.Category.GAMEPLAY));
	private static final KeyMapping KEY_BOOST = KeyMappingHelper.registerKeyMapping(
			new KeyMapping("key.bigdog.boost",
					InputConstants.getKey("key.keyboard.c").getValue(), KeyMapping.Category.GAMEPLAY));

	/** 声波蓄力状态（按住 G 蓄力，松开 G 发射；满蓄服务端自动发射） */
	private static boolean charging = false;
	private static int chargeTicksLeft = 0;
	/** 声波发射后的本地冷却（与服务器 1 秒冷却保持一致，避免假蓄力） */
	private static int woofCooldownTicks = 0;
	/** 固定视角模式下锁定的俯仰角 */
	private static float fixedXRot = 0.0F;
	/** 疾速冲刺剩余持续 tick（HUD 显示，服务端通过 boost 包同步） */
	private static int boostTicksLeft = 0;
	/** 疾速冲刺剩余冷却 tick（HUD 显示，服务端通过 boost 包同步） */
	private static int boostCooldownTicks = 0;
	/** 喷气推进剩余冷却 tick（HUD 显示，服务端通过 boost 包同步） */
	private static int jetCooldownTicks = 0;
	/** 当前加速是否为极限加速（HUD 显示红色条） */
	private static boolean boostOverdrive = false;
	/** 跳跃键上一 tick 状态（喷气推进边缘触发用） */
	private static boolean prevJumpDown = false;

	@Override
	public void onInitializeClient() {
		// 断线/重进时重置状态，避免卡锁定
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			charging = false;
			woofCooldownTicks = 0;
			boostTicksLeft = 0;
			boostCooldownTicks = 0;
			jetCooldownTicks = 0;
			boostOverdrive = false;
			BigDogSkills.clientJetActiveTicks = 0;
			BigDogSkills.clientFixedCamera = false;
		});
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
			charging = false;
			woofCooldownTicks = 0;
			boostTicksLeft = 0;
			boostCooldownTicks = 0;
			jetCooldownTicks = 0;
			boostOverdrive = false;
			BigDogSkills.clientJetActiveTicks = 0;
			BigDogSkills.clientFixedCamera = false;
		});

		// 服务端同步的速度/转弯等级（客户端权威模拟需要）
		ClientPlayNetworking.registerGlobalReceiver(BigDogNetworking.SETTINGS_TYPE, (payload, context) -> {
			BigDogSkills.clientSpeed = payload.speed();
			BigDogSkills.clientTurn = payload.turn();
		});

		// 服务端同步的加速/冷却状态（HUD 条：1=疾速生效 2=疾速冷却 3=极限加速 4=喷气冷却 5=极限加速结束 0=清除）
		ClientPlayNetworking.registerGlobalReceiver(BigDogNetworking.BOOST_TYPE, (payload, context) -> {
			switch (payload.state()) {
				case 1 -> {
					boostTicksLeft = payload.ticks();
					boostCooldownTicks = 0;
					jetCooldownTicks = 0;
					boostOverdrive = false;
				}
				case 2 -> {
					boostTicksLeft = 0;
					boostCooldownTicks = payload.ticks();
					jetCooldownTicks = 0;
					boostOverdrive = false;
				}
				case 3 -> {
					// 极限加速生效：疾速冷却条保留继续走，并额外 +10 秒（与服务端一致）
					boostTicksLeft = payload.ticks();
					jetCooldownTicks = 0;
					boostOverdrive = true;
					if (boostCooldownTicks > 0) {
						boostCooldownTicks += BigDogSkills.OVERDRIVE_COOLDOWN_PENALTY;
					}
				}
				case 4 -> jetCooldownTicks = payload.ticks();
				case 5 -> {
					// 极限加速结束：只清加速状态，疾速冷却条保留
					boostTicksLeft = 0;
					boostOverdrive = false;
				}
				default -> {
					boostTicksLeft = 0;
					boostCooldownTicks = 0;
					jetCooldownTicks = 0;
					boostOverdrive = false;
				}
			}
		});

		// 疾速冲刺冷却条（屏幕左下角）
		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(BigDogMod.MOD_ID, "boost_bar"),
				(extractor, delta) -> drawBoostBar(extractor));

		// 大狗物品：按住 Shift 显示用法解释（自动换行）
		ItemTooltipCallback.EVENT.register((stack, context, flag, lines) -> {
			String tipKey = tipKeyOf(stack.getItem());
			if (tipKey == null) {
				return;
			}
			if (isShiftDown()) {
				addWrappedLines(lines, Component.translatable(tipKey).getString(), ChatFormatting.YELLOW);
			} else {
				lines.add(Component.translatable("item.bigdog.tip.press_shift").withStyle(ChatFormatting.GRAY));
			}
		});

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (KEY_MUNCH.consumeClick()) {
				if (isRidingBigDog(client) && !charging) {
					ClientPlayNetworking.send(new BigDogSkillPayload(BigDogSkills.SKILL_MUNCH, 0, 0));
				}
			}
			while (KEY_FIXED.consumeClick()) {
				if (isRidingBigDog(client) && client.player != null) {
					BigDogSkills.clientFixedCamera = !BigDogSkills.clientFixedCamera;
					fixedXRot = client.player.getXRot();
					ClientPlayNetworking.send(new BigDogTogglePayload(BigDogSkills.clientFixedCamera));
				}
			}
			while (KEY_BOOST.consumeClick()) {
				if (isRidingBigDog(client) && !charging) {
					ClientPlayNetworking.send(new BigDogSkillPayload(BigDogSkills.SKILL_BOOST, 0, 0));
				}
			}
			// 固定视角：视角锁定跟随狗朝向，俯仰保持进入时的角度
			if (BigDogSkills.clientFixedCamera && isRidingBigDog(client) && client.player != null
					&& client.player.getVehicle() instanceof Wolf wolf) {
				client.player.setYRot(wolf.getYRot());
				client.player.setXRot(fixedXRot);
			}

			// 喷气推进：跳跃后未落地时再按空格（边缘触发，蓄力中不可用）
			boolean jumpDown = client.options.keyJump.isDown();
			if (jumpDown && !prevJumpDown && !charging && isRidingBigDog(client) && client.player != null
					&& client.player.getVehicle() instanceof Wolf wolf && !wolf.onGround()) {
				ClientPlayNetworking.send(new BigDogSkillPayload(BigDogSkills.SKILL_JET, 0, 0));
				// 本地喷气推力标记（客户端权威模拟：骑乘速度放大保持水平推进）
				BigDogSkills.clientJetActiveTicks = BigDogSkills.JET_PUSH_TICKS;
			}
			prevJumpDown = jumpDown;

			// 疾速/喷气/极限加速本地倒计时（HUD 显示用；起点由服务端 boost 包决定）
			// 暂停时冻结，避免客户端时钟比服务端快导致"冷却被吞"（服务端世界暂停不走冷却）
			if (!client.isPaused()) {
				if (boostTicksLeft > 0) {
					boostTicksLeft--;
				} else if (boostCooldownTicks > 0) {
					boostCooldownTicks--;
				}
				if (jetCooldownTicks > 0) {
					jetCooldownTicks--;
				}
				if (BigDogSkills.clientJetActiveTicks > 0) {
					BigDogSkills.clientJetActiveTicks--;
				}
			}

			boolean holding = KEY_WOOF.isDown();
			if (woofCooldownTicks > 0 && !client.isPaused()) {
				woofCooldownTicks--;
			}
			if (!charging && holding && isRidingBigDog(client) && woofCooldownTicks <= 0) {
				// 按下 G：开始蓄力（蓄力期间可自由转动视角）
				charging = true;
				chargeTicksLeft = BigDogSkills.MAX_CHARGE_TICKS;
				ClientPlayNetworking.send(new BigDogSkillPayload(BigDogSkills.SKILL_WOOF, 0, 0));
			}
			if (charging && client.player != null) {
				if (!client.isPaused()) {
					chargeTicksLeft--;
				}
				if (chargeTicksLeft <= 0) {
					// 满蓄，服务端已自动发射
					charging = false;
					woofCooldownTicks = (int) BigDogSkills.WOOF_COOLDOWN_TICKS;
				}
				if (!holding || !isRidingBigDog(client)) {
					// 松开 G（或下马）：按当前蓄力进度发射
					charging = false;
					woofCooldownTicks = (int) BigDogSkills.WOOF_COOLDOWN_TICKS;
					ClientPlayNetworking.send(new BigDogSkillPayload(BigDogSkills.SKILL_WOOF_RELEASE, 0, 0));
				}
			}
		});
	}

	/** 屏幕左下角绘制加速/冷却条与喷气冷却条；声波蓄力条居中显示在快捷栏上方（仅骑乘大狗时显示） */
	private static void drawBoostBar(GuiGraphicsExtractor g) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.font == null || !isRidingBigDog(mc)) {
			return;
		}
		int y = g.guiHeight() - 10; // 最底部一组的条形 y
		// 疾速/极限加速条（最底部）
		if (boostTicksLeft > 0) {
			if (boostOverdrive) {
				drawBar(g, mc, 4, y, (float) boostTicksLeft / BigDogSkills.OVERDRIVE_DURATION_TICKS, 0xFFE53935,
						"极限加速中 " + (int) Math.ceil(boostTicksLeft / 20.0F) + "s");
			} else {
				drawBar(g, mc, 4, y, (float) boostTicksLeft / BigDogSkills.BOOST_DURATION_TICKS, 0xFF66BB6A,
						"疾速加速中 " + (int) Math.ceil(boostTicksLeft / 20.0F) + "s");
			}
			y -= 16;
		}
		// 疾速冷却条：与加速条/极限加速条独立显示，触发极限过载时不清零
		if (boostCooldownTicks > 0) {
			drawBar(g, mc, 4, y, (float) boostCooldownTicks / BigDogSkills.BOOST_COOLDOWN_TICKS, 0xFFF57C00,
					"疾速冷却 " + (int) Math.ceil(boostCooldownTicks / 20.0F) + "s");
			y -= 16;
		}
		// 喷气冷却条（左侧，疾速条上方）
		if (jetCooldownTicks > 0) {
			drawBar(g, mc, 4, y, (float) jetCooldownTicks / BigDogSkills.JET_COOLDOWN_TICKS, 0xFF42A5F5,
					"喷气冷却 " + (int) Math.ceil(jetCooldownTicks / 20.0F) + "s");
			y -= 16;
		}
		// 疾速冷却期间且极限过载可用：快捷栏上方提示
		if (boostCooldownTicks > 0 && mc.player.getVehicle() instanceof Wolf wolf
				&& ((BigDogCargoAccess) wolf).hasOverdriveReady()) {
			String tip = "按 C 触发极限过载";
			int tw = mc.font.width(tip);
			g.text(mc.font, Component.literal(tip), (g.guiWidth() - tw) / 2, g.guiHeight() - 32, 0xFFFF7043);
		}
		// 声波蓄力条：屏幕水平居中，快捷栏上方（蓄力进度从 0 涨到 100%）
		if (charging && chargeTicksLeft > 0) {
			int progressTicks = BigDogSkills.MAX_CHARGE_TICKS - chargeTicksLeft;
			int percent = (int) (progressTicks * 100.0F / BigDogSkills.MAX_CHARGE_TICKS);
			drawBar(g, mc, (g.guiWidth() - BAR_W) / 2, g.guiHeight() - 44,
					(float) progressTicks / BigDogSkills.MAX_CHARGE_TICKS, 0xFF42A5F5,
					"声波蓄力 " + percent + "%");
		}
	}

	/** 进度条尺寸 */
	private static final int BAR_W = 96;
	private static final int BAR_H = 6;

	private static void drawBar(GuiGraphicsExtractor g, Minecraft mc, int x, int y, float fraction, int color, String label) {
		g.text(mc.font, Component.literal(label), x, y - 10, 0xFFFFFFFF);
		g.fill(x - 1, y - 1, x + BAR_W + 1, y + BAR_H + 1, 0xFF000000); // 边框
		g.fill(x, y, x + BAR_W, y + BAR_H, 0xAA000000); // 背景
		int filled = (int) (BAR_W * Math.max(0.0F, Math.min(1.0F, fraction)));
		if (filled > 0) {
			g.fill(x, y, x + filled, y + BAR_H, color);
		}
	}

	/** 左/右 Shift 是否按下（直接查询键盘状态，避免 KeyMapping tick 延迟） */
	private static boolean isShiftDown() {
		return InputConstants.isKeyDown(InputConstants.KEY_LSHIFT)
				|| InputConstants.isKeyDown(InputConstants.KEY_RSHIFT);
	}

	/** 按字体宽度把解释文本自动换行（工具提示不自动换行，需要手动分段） */
	private static void addWrappedLines(java.util.List<Component> lines, String text, ChatFormatting color) {
		Font font = Minecraft.getInstance().font;
		if (font == null) {
			lines.add(Component.literal(text).withStyle(color));
			return;
		}
		StringBuilder sb = new StringBuilder();
		for (char c : text.toCharArray()) {
			if (sb.length() > 0 && font.width(sb.toString()) + font.width(String.valueOf(c)) > 130) {
				lines.add(Component.literal(sb.toString()).withStyle(color));
				sb.setLength(0);
			}
			sb.append(c);
		}
		if (sb.length() > 0) {
			lines.add(Component.literal(sb.toString()).withStyle(color));
		}
	}

	/** 大狗物品对应的用法解释 lang key（无则返回 null） */
	private static String tipKeyOf(net.minecraft.world.item.Item item) {
		if (item == BigDogMod.BIG_BONE) {
			return "item.bigdog.big_bone.tip";
		}
		if (item == BigDogMod.CARGO_UPGRADE) {
			return "item.bigdog.cargo_upgrade.tip";
		}
		if (item == BigDogMod.SPEED_UPGRADE) {
			return "item.bigdog.speed_upgrade.tip";
		}
		if (item == BigDogMod.JET_UPGRADE) {
			return "item.bigdog.jet_upgrade.tip";
		}
		if (item == BigDogMod.COOLDOWN_UPGRADE) {
			return "item.bigdog.cooldown_upgrade.tip";
		}
		if (item == BigDogMod.ATTACK_UPGRADE) {
			return "item.bigdog.attack_upgrade.tip";
		}
		if (item == BigDogMod.OVERDRIVE_UPGRADE) {
			return "item.bigdog.overdrive_upgrade.tip";
		}
		if (item == BigDogMod.DAMAGED_UPGRADE) {
			return "item.bigdog.damaged_upgrade.tip";
		}
		if (item == BigDogMod.AUTO_HEAL_UPGRADE) {
			return "item.bigdog.auto_heal_upgrade.tip";
		}
		if (item == BigDogMod.SKILL_UNINSTALL) {
			return "item.bigdog.skill_uninstall.tip";
		}
		return null;
	}

	private static boolean isRidingBigDog(Minecraft client) {
		Entity vehicle = client.player != null ? client.player.getVehicle() : null;
		return vehicle instanceof Wolf wolf && BigDogSkills.isRideable(wolf);
	}
}
