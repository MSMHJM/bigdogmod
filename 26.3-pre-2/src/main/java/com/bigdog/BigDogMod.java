package com.bigdog;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.fabricmc.fabric.api.creativetab.v1.FabricCreativeModeTab;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import com.bigdog.command.BigDogCommands;
import com.bigdog.net.BigDogNetworking;
import com.bigdog.skill.BigDogSkills;
import com.bigdog.skill.BigDogSounds;

public class BigDogMod implements ModInitializer {
	public static final String MOD_ID = "bigdog";

	/** 大骨棒：喂成年狗让其变大，最多放大 3 次 */
	public static final ResourceKey<Item> BIG_BONE_KEY =
			ResourceKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(MOD_ID, "big_bone"));
	public static final Item BIG_BONE = new Item(new Item.Properties().setId(BIG_BONE_KEY));

	/** 运载升级：右键大狗安装运载空间（容量随体型），安装后减速 */
	public static final ResourceKey<Item> CARGO_UPGRADE_KEY =
			ResourceKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(MOD_ID, "cargo_upgrade"));
	public static final Item CARGO_UPGRADE = new Item(new Item.Properties().setId(CARGO_UPGRADE_KEY));

	/** 疾速升级：放入改造槽，骑乘时按 C 激活疾速冲刺（20 秒 ×3 速度，冷却 60 秒） */
	public static final ResourceKey<Item> SPEED_UPGRADE_KEY =
			ResourceKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(MOD_ID, "speed_upgrade"));
	public static final Item SPEED_UPGRADE = new Item(new Item.Properties().setId(SPEED_UPGRADE_KEY));

	/** 喷气升级：跳跃后空中再按空格，向狗朝向斜上方喷气推进并释放粒子（冷却 20 秒） */
	public static final ResourceKey<Item> JET_UPGRADE_KEY =
			ResourceKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(MOD_ID, "jet_upgrade"));
	public static final Item JET_UPGRADE = new Item(new Item.Properties().setId(JET_UPGRADE_KEY));

	/** 冷却升级：可叠加，N 个 = (90 - 5N)% 冷却时间（1 个 85%、2 个 80%、3 个 75%） */
	public static final ResourceKey<Item> COOLDOWN_UPGRADE_KEY =
			ResourceKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(MOD_ID, "cooldown_upgrade"));
	public static final Item COOLDOWN_UPGRADE = new Item(new Item.Properties().setId(COOLDOWN_UPGRADE_KEY));

	/** 攻击升级：提升嚼嚼嚼伤害（满级 15 次击杀 → 10 次击杀坚守者） */
	public static final ResourceKey<Item> ATTACK_UPGRADE_KEY =
			ResourceKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(MOD_ID, "attack_upgrade"));
	public static final Item ATTACK_UPGRADE = new Item(new Item.Properties().setId(ATTACK_UPGRADE_KEY));

	/** 极限过载升级：前置疾速升级，疾速冷却期间按 C 极限加速（5 秒 ×10 速度），用后变为损坏升级并掉 10 血；可多装 */
	public static final ResourceKey<Item> OVERDRIVE_UPGRADE_KEY =
			ResourceKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(MOD_ID, "overdrive_upgrade"));
	public static final Item OVERDRIVE_UPGRADE = new Item(new Item.Properties().setId(OVERDRIVE_UPGRADE_KEY));

	/** 损坏升级：极限过载使用后的残留，占用槽位且无法修复/使用 */
	public static final ResourceKey<Item> DAMAGED_UPGRADE_KEY =
			ResourceKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(MOD_ID, "damaged_upgrade"));
	public static final Item DAMAGED_UPGRADE = new Item(new Item.Properties().setId(DAMAGED_UPGRADE_KEY));

	/** 自动恢复升级：前置运载升级，每 5 秒消耗运载内可喂食的肉回血（放多个不缩短时间） */
	public static final ResourceKey<Item> AUTO_HEAL_UPGRADE_KEY =
			ResourceKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(MOD_ID, "auto_heal_upgrade"));
	public static final Item AUTO_HEAL_UPGRADE = new Item(new Item.Properties().setId(AUTO_HEAL_UPGRADE_KEY));

	/** 技能卸载：右键有运载空间的狗，空间为空时可取下 */
	public static final ResourceKey<Item> SKILL_UNINSTALL_KEY =
			ResourceKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(MOD_ID, "skill_uninstall"));
	public static final Item SKILL_UNINSTALL = new Item(new Item.Properties().setId(SKILL_UNINSTALL_KEY));

	public static final ResourceKey<CreativeModeTab> TAB_KEY =
			ResourceKey.create(Registries.CREATIVE_MODE_TAB, Identifier.fromNamespaceAndPath(MOD_ID, "main"));

	public static final CreativeModeTab BIGDOG_TAB = FabricCreativeModeTab.builder()
			.title(Component.translatable("itemGroup.bigdog"))
			.icon(() -> new ItemStack(BIG_BONE))
			.build();

	@Override
	public void onInitialize() {
		Registry.register(BuiltInRegistries.ITEM, BIG_BONE_KEY, BIG_BONE);
		Registry.register(BuiltInRegistries.ITEM, CARGO_UPGRADE_KEY, CARGO_UPGRADE);
		Registry.register(BuiltInRegistries.ITEM, SPEED_UPGRADE_KEY, SPEED_UPGRADE);
		Registry.register(BuiltInRegistries.ITEM, JET_UPGRADE_KEY, JET_UPGRADE);
		Registry.register(BuiltInRegistries.ITEM, COOLDOWN_UPGRADE_KEY, COOLDOWN_UPGRADE);
		Registry.register(BuiltInRegistries.ITEM, ATTACK_UPGRADE_KEY, ATTACK_UPGRADE);
		Registry.register(BuiltInRegistries.ITEM, OVERDRIVE_UPGRADE_KEY, OVERDRIVE_UPGRADE);
		Registry.register(BuiltInRegistries.ITEM, DAMAGED_UPGRADE_KEY, DAMAGED_UPGRADE);
		Registry.register(BuiltInRegistries.ITEM, AUTO_HEAL_UPGRADE_KEY, AUTO_HEAL_UPGRADE);
		Registry.register(BuiltInRegistries.ITEM, SKILL_UNINSTALL_KEY, SKILL_UNINSTALL);
		Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB, TAB_KEY, BIGDOG_TAB);
		CreativeModeTabEvents.modifyOutputEvent(TAB_KEY).register(output -> {
			output.accept(new ItemStack(BIG_BONE));
			output.accept(new ItemStack(CARGO_UPGRADE));
			output.accept(new ItemStack(SPEED_UPGRADE));
			output.accept(new ItemStack(JET_UPGRADE));
			output.accept(new ItemStack(COOLDOWN_UPGRADE));
			output.accept(new ItemStack(ATTACK_UPGRADE));
			output.accept(new ItemStack(OVERDRIVE_UPGRADE));
			output.accept(new ItemStack(DAMAGED_UPGRADE));
			output.accept(new ItemStack(AUTO_HEAL_UPGRADE));
			output.accept(new ItemStack(SKILL_UNINSTALL));
		});
		BigDogNetworking.register();
		BigDogSounds.register();
		BigDogCommands.register();
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			BigDogSkills.tickCharges();
			BigDogSkills.tickBoosts();
		});
	}
}
