package dev.vadyanik.smartshots.gui;

import dev.vadyanik.smartshots.storage.ShotMask;
import dev.vadyanik.smartshots.storage.ShotMeta;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.PlayerFaceExtractor;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.PlayerSkin;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Small pictures for info panel chips, drawn with the game's own renderers so resource packs
 * apply: item icons, spawn eggs for mobs, block items (or the block's particle sprite for blocks
 * without an item, like water or fire) and player skin faces.
 */
final class ChipIcons {
	/** Entities without a spawn egg or an item of the same id. */
	private static final Map<String, String> ENTITY_ITEMS = Map.ofEntries(
		Map.entry("minecraft:experience_orb", "minecraft:experience_bottle"),
		Map.entry("minecraft:fireball", "minecraft:fire_charge"),
		Map.entry("minecraft:small_fireball", "minecraft:fire_charge"),
		Map.entry("minecraft:dragon_fireball", "minecraft:dragon_breath"),
		Map.entry("minecraft:wither_skull", "minecraft:wither_skeleton_skull"),
		Map.entry("minecraft:eye_of_ender", "minecraft:ender_eye"),
		Map.entry("minecraft:fishing_bobber", "minecraft:fishing_rod"),
		Map.entry("minecraft:leash_knot", "minecraft:lead"),
		Map.entry("minecraft:area_effect_cloud", "minecraft:lingering_potion"),
		Map.entry("minecraft:lightning_bolt", "minecraft:lightning_rod"),
		Map.entry("minecraft:falling_block", "minecraft:sand"),
		Map.entry("minecraft:breeze_wind_charge", "minecraft:wind_charge"),
		Map.entry("minecraft:shulker_bullet", "minecraft:shulker_shell"),
		Map.entry("minecraft:llama_spit", "minecraft:llama_spawn_egg"),
		Map.entry("minecraft:evoker_fangs", "minecraft:evoker_spawn_egg"),
		Map.entry("minecraft:giant", "minecraft:zombie_spawn_egg"),
		Map.entry("minecraft:illusioner", "minecraft:pillager_spawn_egg"),
		Map.entry("minecraft:ender_dragon", "minecraft:dragon_head"),
		Map.entry("minecraft:player", "minecraft:player_head"),
		Map.entry("minecraft:ominous_item_spawner", "minecraft:trial_key")
	);
	private static final int WATER_TINT = 0xFF3F76E4;

	/** Resolved picture per key: an ItemStack, a BlockState (particle sprite) or NONE. */
	private static final Map<String, Object> CACHE = new HashMap<>();
	private static final Object NONE = new Object();
	private static final Map<UUID, ResolvableProfile> PROFILES = new HashMap<>();

	private ChipIcons() {
	}

	/** Whether {@link #draw} has a picture for the key (else the caller draws its own dot). */
	static boolean has(String key) {
		return key != null && (ShotMask.kind(key).equals(ShotMask.PLAYER) || resolve(key) != NONE);
	}

	/** Draws the picture of a key into a {@code size}×{@code size} box. */
	static void draw(GuiGraphicsExtractor g, String key, ShotMeta meta, int x, int y, int size) {
		if (ShotMask.kind(key).equals(ShotMask.PLAYER)) {
			PlayerFaceExtractor.extractRenderState(g, skin(ShotMask.value(key), meta), x, y, size);
			return;
		}
		Object o = resolve(key);
		if (o instanceof ItemStack stack) {
			g.pose().pushMatrix();
			g.pose().translate(x, y);
			g.pose().scale(size / 16f);
			g.item(stack, 0, 0);
			g.pose().popMatrix();
		} else if (o instanceof BlockState state) {
			// Looked up every frame: sprites are replaced on resource reload.
			TextureAtlasSprite sprite = Minecraft.getInstance().getModelManager().getBlockStateModelSet().getParticleMaterial(state).sprite();
			// Water's sprite is grey and tinted by biome in the world; use the default water color.
			int tint = state.getFluidState().is(FluidTags.WATER) ? WATER_TINT : -1;
			g.blitSprite(RenderPipelines.GUI_TEXTURED, sprite, x, y, size, size, tint);
		}
	}

	private static Object resolve(String key) {
		return CACHE.computeIfAbsent(key, k -> {
			try {
				return find(k);
			} catch (Exception e) {
				return NONE;
			}
		});
	}

	private static Object find(String key) {
		if (ShotMask.kind(key).equals(ShotMask.NAMED)) {
			return resolve(ShotMask.ENTITY + ShotMask.namedType(key));
		}
		String id = ShotMask.value(key);
		Identifier rl = Identifier.tryParse(id);
		if (rl == null) {
			return NONE;
		}
		switch (ShotMask.kind(key)) {
			case ShotMask.ITEM -> {
				return item(rl).map(Object.class::cast).orElse(NONE);
			}
			case ShotMask.ENTITY -> {
				Optional<EntityType<?>> type = BuiltInRegistries.ENTITY_TYPE.getOptional(rl);
				if (type.isPresent()) {
					Optional<Holder<Item>> egg = SpawnEggItem.byId(type.get());
					if (egg.isPresent()) {
						return new ItemStack(egg.get());
					}
				}
				Optional<ItemStack> same = item(rl);
				if (same.isPresent()) {
					return same.get();
				}
				String mapped = ENTITY_ITEMS.get(id);
				if (mapped != null) {
					Optional<ItemStack> m = item(Identifier.parse(mapped));
					if (m.isPresent()) {
						return m.get();
					}
				}
				return new ItemStack(Items.SPAWNER);
			}
			case ShotMask.BLOCK -> {
				Optional<Block> block = BuiltInRegistries.BLOCK.getOptional(rl);
				if (block.isEmpty()) {
					return NONE;
				}
				Item item = block.get().asItem();
				if (item != Items.AIR) {
					return new ItemStack(item);
				}
				return block.get().defaultBlockState();
			}
			case ShotMask.SIGN -> {
				return new ItemStack(Items.OAK_SIGN);
			}
			default -> {
				return NONE;
			}
		}
	}

	private static Optional<ItemStack> item(Identifier rl) {
		return BuiltInRegistries.ITEM.getOptional(rl)
			.filter(i -> i != Items.AIR)
			.map(ItemStack::new);
	}

	/**
	 * The skin of a player: from the server's player list when they're online right now, else a
	 * background profile lookup by UUID (Mojang's session servers; offline-mode UUIDs just fall back
	 * to the default skin), else the default skin.
	 */
	static PlayerSkin skin(String nick, ShotMeta meta) {
		Minecraft mc = Minecraft.getInstance();
		ClientPacketListener conn = mc.getConnection();
		if (conn != null) {
			PlayerInfo info = conn.getPlayerInfo(nick);
			if (info != null) {
				return info.getSkin();
			}
		}
		String id = meta.playerIds == null ? null : meta.playerIds.get(nick);
		UUID uuid = null;
		if (id != null) {
			try {
				uuid = UUID.fromString(id);
			} catch (IllegalArgumentException ignored) {
				// keep the default skin
			}
		}
		if (uuid == null) {
			return DefaultPlayerSkin.getDefaultSkin();
		}
		ResolvableProfile profile = PROFILES.computeIfAbsent(uuid, ResolvableProfile::createUnresolved);
		return mc.playerSkinRenderCache().getOrDefault(profile).playerSkin();
	}
}
