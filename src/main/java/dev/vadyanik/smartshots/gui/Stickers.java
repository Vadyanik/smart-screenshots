package dev.vadyanik.smartshots.gui;

import com.mojang.blaze3d.platform.NativeImage;
import dev.vadyanik.smartshots.SmartShots;
import dev.vadyanik.smartshots.storage.EditRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.PlayerSkin;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.Optional;

/**
 * Sticker pictures, made on the render thread from the game's textures (resource packs apply):
 * flat items use their sprite, full blocks become a small isometric cube, players get their skin
 * face with the hat layer. The result is a base64 PNG stored in the edit itself, so the baked
 * image never needs the game's resources again.
 */
final class Stickers {
	private Stickers() {
	}

	/** Sticker picture of an item stack (spawn eggs for mobs too), or null. */
	static String item(ItemStack stack) {
		try {
			Minecraft mc = Minecraft.getInstance();
			if (stack.getItem() instanceof BlockItem bi && fullCube(bi.getBlock())) {
				String cube = cube(bi.getBlock(), particle(mc, stack));
				if (cube != null) {
					return cube;
				}
			}
			Identifier sprite = particle(mc, stack);
			BufferedImage im = sprite != null ? texture(sprite) : null;
			return im != null ? encode(im) : null;
		} catch (Exception e) {
			SmartShots.LOGGER.warn("Couldn't make a sticker of {}", stack, e);
			return null;
		}
	}

	/** Face (with the hat layer) of a skin, or null while the skin isn't loaded. */
	static String head(PlayerSkin skin) {
		try {
			BufferedImage s = skinImage(skin);
			if (s == null) {
				return null;
			}
			int k = Math.max(1, s.getWidth() / 64);
			BufferedImage face = new BufferedImage(8 * k, 8 * k, BufferedImage.TYPE_INT_ARGB);
			Graphics2D g = face.createGraphics();
			g.drawImage(s.getSubimage(8 * k, 8 * k, 8 * k, 8 * k), 0, 0, null);
			if (s.getHeight() >= 16 * k) {
				g.drawImage(s.getSubimage(40 * k, 8 * k, 8 * k, 8 * k), 0, 0, null);
			}
			g.dispose();
			return encode(face);
		} catch (Exception e) {
			SmartShots.LOGGER.warn("Couldn't make a head sticker", e);
			return null;
		}
	}

	private static BufferedImage skinImage(PlayerSkin skin) throws Exception {
		Identifier path = skin.body().texturePath();
		AbstractTexture t = Minecraft.getInstance().getTextureManager().getTexture(path);
		if (t instanceof DynamicTexture dt && dt.getPixels() != null) {
			return toImage(dt.getPixels(), 0, 0, dt.getPixels().getWidth(), dt.getPixels().getHeight());
		}
		return readResource(path, -1, -1);
	}

	/** The sprite the item model shows as its particle: for flat items, their texture. */
	private static Identifier particle(Minecraft mc, ItemStack stack) {
		ItemStackRenderState state = new ItemStackRenderState();
		mc.getItemModelResolver().updateForTopItem(state, stack, ItemDisplayContext.GUI, mc.level, null, 0);
		Material.Baked m = state.pickParticleMaterial(RandomSource.create(0));
		if (m == null) {
			return null;
		}
		TextureAtlasSprite sprite = m.sprite();
		return sprite.contents().name();
	}

	private static boolean fullCube(Block block) {
		try {
			BlockState s = block.defaultBlockState();
			return Block.isShapeFullBlock(s.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO));
		} catch (Exception e) {
			return false;
		}
	}

	/** Isometric cube from the block's top and side textures (falls back to the particle). */
	private static String cube(Block block, Identifier particle) throws Exception {
		Identifier id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(block);
		String base = "block/" + id.getPath();
		BufferedImage top = first(id.getNamespace(), base + "_top", base + "_end", base, null);
		BufferedImage side = first(id.getNamespace(), base + "_side", base + "_front", base, null);
		if (particle != null) {
			BufferedImage p = texture(particle);
			if (top == null) {
				top = p;
			}
			if (side == null) {
				side = p;
			}
		}
		if (top == null || side == null) {
			return null;
		}
		int t = Math.max(top.getWidth(), side.getWidth());
		int c = Math.max(64, 4 * t);
		float u = c / 64f;
		BufferedImage out = new BufferedImage(c, c, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = out.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
		face(g, top, new AffineTransform(32 * u / top.getWidth(), -16 * u / top.getWidth(), 32 * u / top.getHeight(), 16 * u / top.getHeight(), 0, 16 * u), 1f);
		face(g, side, new AffineTransform(32 * u / side.getWidth(), 16 * u / side.getWidth(), 0, 32 * u / side.getHeight(), 0, 16 * u), 0.78f);
		face(g, side, new AffineTransform(32 * u / side.getWidth(), -16 * u / side.getWidth(), 0, 32 * u / side.getHeight(), 32 * u, 32 * u), 0.6f);
		g.dispose();
		return encode(out);
	}

	private static void face(Graphics2D g, BufferedImage tex, AffineTransform t, float light) {
		BufferedImage shaded = tex;
		if (light < 1) {
			shaded = new BufferedImage(tex.getWidth(), tex.getHeight(), BufferedImage.TYPE_INT_ARGB);
			Graphics2D sg = shaded.createGraphics();
			sg.drawImage(tex, 0, 0, null);
			sg.setComposite(AlphaComposite.SrcAtop);
			sg.setColor(new Color(0, 0, 0, Math.round((1 - light) * 255)));
			sg.fillRect(0, 0, tex.getWidth(), tex.getHeight());
			sg.dispose();
		}
		g.drawImage(shaded, t, null);
	}

	private static BufferedImage first(String ns, String... paths) throws Exception {
		for (String p : paths) {
			if (p == null) {
				continue;
			}
			BufferedImage im = texture(Identifier.fromNamespaceAndPath(ns, p));
			if (im != null) {
				return im;
			}
		}
		return null;
	}

	/** A sprite texture ("minecraft:item/diamond"), first animation frame, or null. */
	private static BufferedImage texture(Identifier sprite) throws Exception {
		Identifier file = Identifier.fromNamespaceAndPath(sprite.getNamespace(), "textures/" + sprite.getPath() + ".png");
		return readResource(file, -1, -1);
	}

	private static BufferedImage readResource(Identifier file, int w, int h) throws Exception {
		Optional<Resource> res = Minecraft.getInstance().getResourceManager().getResource(file);
		if (res.isEmpty()) {
			return null;
		}
		NativeImage img;
		try (InputStream in = res.get().open()) {
			img = NativeImage.read(in);
		}
		try (img) {
			int fw = w > 0 ? w : img.getWidth();
			// Animated textures are vertical strips of square frames: keep the first one.
			int fh = h > 0 ? h : Math.min(img.getHeight(), img.getWidth());
			return toImage(img, 0, 0, fw, fh);
		}
	}

	private static BufferedImage toImage(NativeImage img, int x0, int y0, int w, int h) {
		int[] abgr = img.getPixelsABGR();
		int iw = img.getWidth();
		BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				int p = abgr[(y0 + y) * iw + x0 + x];
				out.setRGB(x, y, (p & 0xFF00FF00) | ((p >> 16) & 0xFF) | ((p & 0xFF) << 16));
			}
		}
		return out;
	}

	private static String encode(BufferedImage im) {
		int[] px = im.getRGB(0, 0, im.getWidth(), im.getHeight(), null, 0, im.getWidth());
		return EditRenderer.encodeSticker(px, im.getWidth(), im.getHeight());
	}
}
