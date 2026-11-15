package dev.vadyanik.smartshots.storage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Everything we know about one screenshot. Serialized with Gson. */
public class ShotMeta {
	public String trigger = Trigger.MANUAL.id;
	public long time;
	public int width;
	public int height;

	public String dimension;
	public String biome;
	public double x;
	public double y;
	public double z;
	public float yaw;
	public float pitch;

	/** Advancement titles (one shot can cover several advancements earned at once). */
	public List<String> advancements = new ArrayList<>();
	public List<String> advancementIds = new ArrayList<>();

	/**
	 * Legacy (pre-1.1): the local player was visible on the shot. New shots record the local player
	 * by nickname in {@link #players} like everyone else; old shots are migrated on load.
	 */
	@Deprecated
	public boolean self;
	public boolean thirdPerson;
	/** Players visible on the shot (the local player included, by nickname). */
	public List<String> players = new ArrayList<>();
	/** Player nickname -> UUID string (for skin faces in the info panel). */
	public Map<String, String> playerIds = new LinkedHashMap<>();
	/**
	 * Player nickname -> the name as the server styles it (TAB list / team prefix, colors, glyphs),
	 * component JSON ({@code StyledNames}). Display only; absent when it equals the plain nick.
	 */
	public Map<String, String> playerNames = new LinkedHashMap<>();
	/**
	 * Mobs renamed with a name tag, {@code "<entity type id>|<name>"} (see {@link ShotMask#NAMED}).
	 * May be null on old shots.
	 */
	public List<String> named = new ArrayList<>();
	/** Visible entity type id -> count. */
	public Map<String, Integer> entities = new LinkedHashMap<>();
	/** Item id -> count: dropped items on the ground and items shown in item frames. */
	public Map<String, Integer> items = new LinkedHashMap<>();
	/** Visible block id -> weight (share of the screen it covers, 0..1000). */
	public Map<String, Integer> blocks = new LinkedHashMap<>();
	/**
	 * Block id -> number of distinct visible block positions (from the mask, blocks seen through
	 * glass or water included). Absent on old shots and for fluids.
	 */
	public Map<String, Integer> blockCounts = new LinkedHashMap<>();
	/** Id -> localized name at capture time (fallback when the id is unknown later). */
	public Map<String, String> names = new LinkedHashMap<>();
	/** Text of the signs readable on the shot (one entry per sign, lines joined with spaces). */
	public List<String> signs = new ArrayList<>();
	/** Chat messages visible on the shot, oldest first (one entry per message). Null on old shots. */
	public List<String> chat = new ArrayList<>();
	/** Free-form tags: freecam, night, rain, underwater, tamed, named entity names, sidebar title... */
	public List<String> tags = new ArrayList<>();

	/** Highlight keys ({@link ShotMask} key format) whose outline is baked into the PNG file. */
	public List<String> highlights = new ArrayList<>();

	/**
	 * Drawings, text, blur, crop... in the order they were made (see {@link EditOp}). Baked into
	 * the PNG on top of the untouched original, so each one can be removed later. Null on old shots.
	 */
	public List<EditOp> edits = new ArrayList<>();

	/** Link of the last upload ({@code share.ShareService}), the SHA-1 of the uploaded file and when the link expires (0 = never). */
	public String shareUrl;
	public String shareHash;
	public long shareExpires;

	public boolean favorite;
	public String note = "";

	/** Already shrunk / recompressed by the archiver ({@code ShotArchiver}); never processed twice. */
	public boolean archived;

	public Trigger trigger() {
		return Trigger.byId(trigger);
	}
}
