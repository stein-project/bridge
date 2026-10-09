/*
 * Copyright (c) 2026 Breno Xavier. All rights reserved.
 * Proprietary software: see the LICENSE file at the root of this repository.
 */

package dev.xavier.stein.bridge.lens;

import dev.xavier.stein.bridge.core.Log;
import dev.xavier.stein.bridge.hooks.GameHooks;

import net.minecraft.block.Block;
import net.minecraft.block.properties.IProperty;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BlockModelShapes;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.ItemCameraTransforms;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.client.resources.model.IBakedModel;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.IBlockAccess;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Connected blocks: the connected textures of resource packs — .properties files in
 * assets/minecraft/mcpatcher/ctm and optifine/ctm, folder or zip —, in the format the packs use.
 *
 * <ul>
 *   <li>methods: {@code ctm} (47 tiles, glass), {@code horizontal} and {@code vertical} (4), {@code
 *       top} (1), {@code random} (with {@code weights} and {@code symmetry}), {@code repeat}
 *       ({@code width} x {@code height}) and {@code fixed} (1);
 *   <li>{@code matchTiles} (texture name) and {@code matchBlocks} (name or id, with properties
 *       "name:prop=v1,v2" and {@code metadata}), {@code connect} (block, tile, state), {@code
 *       faces}, {@code heights}/{@code minHeight}/{@code maxHeight}; the first rule wins, with
 *       those of higher {@code weight} first, then the folder and the file name;
 *   <li>not supported for now — the rule is left out, it never matches wrongly: ctm_compact,
 *       overlay, horizontal+vertical, vertical+horizontal, biomes, name, tintIndex/tintBlock.
 * </ul>
 *
 * <p>The engine only passes the marked blocks through here ({@link GameHooks#watchBlockModel}), on
 * the chunk build threads; the others cost the read of a field. For each face with a rule, the tile
 * comes from the neighbors in the face's plane; the swapped quad (the same geometry, with the
 * texture coordinates moved to the tile) is built once per rule and stored.
 *
 * <p>Tiles of the ctm method: the order of the packs' template (47 tiles), read from the template
 * image itself — in each tile, the sides and diagonals marked as not connected.
 */
final class ConnectedTextures {

    private static final String[] ROOTS = {
        PackScanner.ASSETS + "mcpatcher/ctm/", PackScanner.ASSETS + "optifine/ctm/"
    };

    /** How the tile of a quad is chosen. */
    private enum Method {
        CTM,
        HORIZONTAL,
        VERTICAL,
        TOP,
        RANDOM,
        REPEAT,
        FIXED
    }

    /** What a neighbor must be to connect. */
    private enum Connect {
        BLOCK,
        TILE,
        STATE
    }

    /** Which faces of a block share the random choice. */
    private enum Symmetry {
        NONE,
        OPPOSITE,
        ALL
    }

    // Sides and diagonals of the texture, as bits of the connection mask.
    private static final int UP = 1;
    private static final int DOWN = 2;
    private static final int LEFT = 4;
    private static final int RIGHT = 8;
    private static final int UP_LEFT = 16;
    private static final int UP_RIGHT = 32;
    private static final int DOWN_LEFT = 64;
    private static final int DOWN_RIGHT = 128;

    /** World offset of the texture's up on a side face (the TOP method). */
    private static final int[] UP_AXIS = {0, 1, 0};

    // The reference hash of the random method (the format's own constants).
    private static final long HASH_X = 3129871L;
    private static final long HASH_Z = 116129781L;
    private static final long HASH_SQUARE = 42317861L;
    private static final long HASH_LINEAR = 11L;
    private static final long HASH_FACE = 0x9E3779B97F4A7C15L;

    /**
     * What connects in each template tile, 0 to 46 (a diagonal only counts with both sides around
     * it).
     */
    private static final int[] TEMPLATE = {
        0, 8, 12, 4, 10, 6, 11, 14, 47, 143, 95, 63, 2, 138, 206, 70, 9, 5, 13, 7, 31, 79, 207, 175,
        3, 171, 255, 87, 139, 78, 43, 142, 127, 191, 111, 159, 1, 41, 61, 21, 45, 23, 29, 71, 223,
        239, 15
    };

    /** Tile for each combination of sides and diagonals. */
    private static final int[] CTM_TILE = new int[256];

    static {
        int[] byMask = new int[256];
        Arrays.fill(byMask, -1);
        for (int i = 0; i < TEMPLATE.length; i++) {
            byMask[TEMPLATE[i]] = i;
        }
        for (int m = 0; m < 256; m++) {
            int t = byMask[canonical(m)];
            if (t < 0) {
                throw new IllegalStateException("template lacks the combination " + m);
            }
            CTM_TILE[m] = t;
        }
    }

    /** Diagonals only count with both sides around them connected. */
    private static int canonical(int m) {
        int c = m & (UP | DOWN | LEFT | RIGHT);
        if ((m & UP_LEFT) != 0 && (c & (UP | LEFT)) == (UP | LEFT)) {
            c |= UP_LEFT;
        }
        if ((m & UP_RIGHT) != 0 && (c & (UP | RIGHT)) == (UP | RIGHT)) {
            c |= UP_RIGHT;
        }
        if ((m & DOWN_LEFT) != 0 && (c & (DOWN | LEFT)) == (DOWN | LEFT)) {
            c |= DOWN_LEFT;
        }
        if ((m & DOWN_RIGHT) != 0 && (c & (DOWN | RIGHT)) == (DOWN | RIGHT)) {
            c |= DOWN_RIGHT;
        }
        return c;
    }

    /**
     * For each face (EnumFacing order: down, up, north, south, west, east), the world offset of the
     * texture's up, down, left and right, looking at the face from outside.
     */
    private static final int[][][] AXES = {
        {{0, 0, 1}, {0, 0, -1}, {-1, 0, 0}, {1, 0, 0}},
        {{0, 0, -1}, {0, 0, 1}, {-1, 0, 0}, {1, 0, 0}},
        {{0, 1, 0}, {0, -1, 0}, {1, 0, 0}, {-1, 0, 0}},
        {{0, 1, 0}, {0, -1, 0}, {-1, 0, 0}, {1, 0, 0}},
        {{0, 1, 0}, {0, -1, 0}, {0, 0, -1}, {0, 0, 1}},
        {{0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}},
    };

    private static final EnumFacing[] FACINGS = EnumFacing.values();

    /** The bit of a face in a faces mask (EnumFacing order). */
    private static int bit(EnumFacing f) {
        return 1 << f.ordinal();
    }

    private static final int ALL_FACES = (1 << FACINGS.length) - 1;
    private static final int SIDE_FACES =
            bit(EnumFacing.NORTH)
                    | bit(EnumFacing.SOUTH)
                    | bit(EnumFacing.WEST)
                    | bit(EnumFacing.EAST);

    /** Higher weight first; then the folder and the file name. */
    private static final Comparator<Rule> ORDER =
            (a, b) -> PackScanner.order(a.weight, a.basePath, a.name, b.weight, b.basePath, b.name);

    /** A matchBlocks block, with the requested metadatas and properties (null = any). */
    static final class BlockMatch {
        final Block block;
        final int[] metas;
        final Map<String, Set<String>> properties;

        BlockMatch(Block block, int[] metas, Map<String, Set<String>> properties) {
            this.block = block;
            this.metas = metas;
            this.properties = properties;
        }

        @SuppressWarnings("rawtypes") // the game's getProperties() returns raw types
        boolean matches(IBlockState state) {
            if (state.func_177230_c() != block) {
                return false;
            }
            if (metas != null) {
                int meta = block.func_176201_c(state);
                boolean any = false;
                for (int m : metas) {
                    if (m == meta) {
                        any = true;
                        break;
                    }
                }
                if (!any) {
                    return false;
                }
            }
            if (properties != null) {
                for (Map.Entry<IProperty, Comparable> e : state.func_177228_b().entrySet()) {
                    Set<String> allowed = properties.get(e.getKey().func_177701_a());
                    if (allowed != null && !allowed.contains(e.getValue().toString())) {
                        return false;
                    }
                }
            }
            return true;
        }
    }

    static final class Rule {
        final String name;
        final String basePath;
        final int weight;
        final Method method;
        final Connect connect;
        final int faces;
        final CustomItems.Ranges heights;
        final String[] tileNames;
        final Set<String> matchTiles;
        final List<BlockMatch> matchBlocks;
        final int[] weights;
        final int weightSum;
        final Symmetry symmetry;
        final int width;
        final int height;
        TextureAtlasSprite[] tiles;

        /** Swapped quad per original quad and tile (built once). */
        final ConcurrentHashMap<BakedQuad, BakedQuad[]> quads = new ConcurrentHashMap<>();

        Rule(
                String name,
                String basePath,
                int weight,
                Method method,
                Connect connect,
                int faces,
                CustomItems.Ranges heights,
                String[] tileNames,
                Set<String> matchTiles,
                List<BlockMatch> matchBlocks,
                int[] weights,
                Symmetry symmetry,
                int width,
                int height) {
            this.name = name;
            this.basePath = basePath;
            this.weight = weight;
            this.method = method;
            this.connect = connect;
            this.faces = faces;
            this.heights = heights;
            this.tileNames = tileNames;
            this.matchTiles = matchTiles;
            this.matchBlocks = matchBlocks;
            this.weights = weights;
            int sum = 0;
            if (weights != null) {
                for (int w : weights) {
                    sum += w;
                }
            }
            this.weightSum = sum;
            this.symmetry = symmetry;
            this.width = width;
            this.height = height;
        }

        /** Faces, height and block (matchBlocks, when the rule has one). */
        boolean applies(IBlockState state, BlockPos pos, EnumFacing face) {
            if ((faces & bit(face)) == 0) {
                return false;
            }
            if (heights != null && !heights.contains(pos.func_177956_o())) {
                return false;
            }
            if (matchBlocks != null) {
                for (BlockMatch m : matchBlocks) {
                    if (m.matches(state)) {
                        return true;
                    }
                }
                return false;
            }
            return true;
        }

        BakedQuad quad(BakedQuad q, int tile, TextureAtlasSprite from) {
            BakedQuad[] byTile = quads.computeIfAbsent(q, k -> new BakedQuad[tiles.length]);
            BakedQuad r = byTile[tile];
            if (r == null) {
                r = remap(q, from, tiles[tile]);
                byTile[tile] = r; // harmless race: the BakedQuad is immutable
            }
            return r;
        }
    }

    /** The texture of a quad and its rules, in the order they apply. */
    static final class QuadInfo {
        final TextureAtlasSprite sprite;
        final Rule[] rules;

        QuadInfo(TextureAtlasSprite sprite, Rule[] rules) {
            this.sprite = sprite;
            this.rules = rules;
        }
    }

    /** What applies after a resource reload; swapped whole (the build threads read it). */
    static final class State {
        final Map<String, Rule[]> bySprite;
        final Map<Block, Rule[]> byBlock;
        final Set<Block> watched;
        final BlockModelShapes shapes;
        final ConcurrentHashMap<BakedQuad, QuadInfo> quads = new ConcurrentHashMap<>();

        State(
                Map<String, Rule[]> bySprite,
                Map<Block, Rule[]> byBlock,
                Set<Block> watched,
                BlockModelShapes shapes) {
            this.bySprite = bySprite;
            this.byBlock = byBlock;
            this.watched = watched;
            this.shapes = shapes;
        }

        QuadInfo quad(BakedQuad q) {
            QuadInfo info = quads.get(q);
            if (info == null) {
                int[] v = q.func_178209_a();
                int stride = v.length / 4;
                float u = 0.0F;
                float w = 0.0F;
                for (int i = 0; i < 4; i++) {
                    u += Float.intBitsToFloat(v[i * stride + 4]);
                    w += Float.intBitsToFloat(v[i * stride + 5]);
                }
                TextureAtlasSprite sprite =
                        (TextureAtlasSprite) GameHooks.spriteAt(u / 4.0F, w / 4.0F);
                info =
                        new QuadInfo(
                                sprite,
                                sprite != null ? bySprite.get(sprite.func_94215_i()) : null);
                quads.putIfAbsent(q, info);
            }
            return info;
        }
    }

    private static final State EMPTY =
            new State(Collections.emptyMap(), Collections.emptyMap(), Collections.emptySet(), null);

    private List<Rule> loaded = Collections.emptyList();
    private volatile State state = EMPTY;
    private volatile boolean enabled = true;
    private int ruleCount;

    int ruleCount() {
        return ruleCount;
    }

    boolean enabled() {
        return enabled;
    }

    /**
     * Turns it on or off (game thread): marks or unmarks the blocks in the engine and, with
     * rebuild, rebuilds the chunks — the change only shows in a chunk that is built again.
     */
    void setEnabled(boolean on, boolean rebuild) {
        enabled = on;
        for (Block b : state.watched) {
            GameHooks.watchBlockModel(b, on);
        }
        Minecraft mc = Minecraft.func_71410_x();
        if (rebuild && mc.field_71438_f != null && mc.field_71441_e != null) {
            mc.field_71438_f.func_72712_a();
        }
    }

    // ---- loading (before the atlas is built) ----

    /**
     * Reads the active packs' rules and registers the tiles in the atlas that is about to be built.
     */
    void reload(TextureMap map) {
        List<Rule> rules = new ArrayList<>();
        PackScanner.LoadStats counts = new PackScanner.LoadStats();
        PackScanner.scanActivePacks(
                ROOTS, (basePath, name, p, st) -> add(basePath, name, p, rules, st), counts);
        rules.sort(ORDER);
        Set<String> sprites = new HashSet<>();
        for (Rule r : rules) {
            for (String t : r.tileNames) {
                if (t != null && sprites.add(t)) {
                    map.func_174942_a(new ResourceLocation(t));
                }
            }
        }
        loaded = rules;
        counts.report("connected-block rule(s)");
    }

    /**
     * Atlas and models ready (game thread): tiles resolved in the atlas, rules by texture and by
     * block, and the blocks that use any of them marked in the engine.
     */
    void resolve() {
        Minecraft mc = Minecraft.func_71410_x();
        if (mc.func_175602_ab() == null) {
            // First load, inside startGame: the BlockRendererDispatcher does not exist yet
            // (afterStartGame redoes it)
            return;
        }
        for (Block b : state.watched) {
            GameHooks.watchBlockModel(b, false);
        }
        TextureMap map = mc.func_147117_R();
        Map<String, List<Rule>> bySprite = new HashMap<>();
        Map<Block, List<Rule>> byBlock = new IdentityHashMap<>();
        int valid = 0;
        for (Rule r : loaded) {
            TextureAtlasSprite[] tiles = new TextureAtlasSprite[r.tileNames.length];
            boolean ok = true;
            for (int i = 0; i < tiles.length; i++) {
                if (r.tileNames[i] != null) {
                    tiles[i] = map.func_110572_b(r.tileNames[i]);
                    ok &= tiles[i] != null && !"missingno".equals(tiles[i].func_94215_i());
                }
            }
            if (!ok) {
                continue;
            }
            r.tiles = tiles;
            r.quads.clear();
            valid++;
            if (r.matchTiles != null) {
                for (String s : r.matchTiles) {
                    bySprite.computeIfAbsent(s, k -> new ArrayList<>()).add(r);
                }
            } else {
                for (BlockMatch m : r.matchBlocks) {
                    List<Rule> list = byBlock.computeIfAbsent(m.block, k -> new ArrayList<>());
                    if (!list.contains(r)) {
                        list.add(r);
                    }
                }
            }
        }
        BlockModelShapes shapes = mc.func_175602_ab().func_175023_a();
        State s = new State(toArrays(bySprite), toArrays(byBlock), new HashSet<>(), shapes);
        if (valid > 0) {
            for (Block b : Block.field_149771_c) {
                if (s.byBlock.containsKey(b) || !s.bySprite.isEmpty() && usesRuleSprite(s, b)) {
                    s.watched.add(b);
                }
            }
        }
        state = s;
        ruleCount = valid;
        for (Block b : s.watched) {
            GameHooks.watchBlockModel(b, enabled);
        }
        if (valid > 0) {
            Log.info(
                    "lens",
                    "connected blocks: " + valid + " rule(s), " + s.watched.size() + " block(s)");
        }
    }

    private static <K> Map<K, Rule[]> toArrays(Map<K, List<Rule>> lists) {
        Map<K, Rule[]> out =
                lists instanceof IdentityHashMap ? new IdentityHashMap<>() : new HashMap<>();
        for (Map.Entry<K, List<Rule>> e : lists.entrySet()) {
            List<Rule> l = e.getValue();
            l.sort(ORDER);
            out.put(e.getKey(), l.toArray(new Rule[0]));
        }
        return out;
    }

    private static boolean usesRuleSprite(State s, Block b) {
        for (IBlockState st : b.func_176194_O().func_177619_a()) {
            IBakedModel m = s.shapes.func_178125_b(st);
            if (m == null) {
                continue;
            }
            for (EnumFacing f : FACINGS) {
                for (BakedQuad q : m.func_177551_a(f)) {
                    if (s.quad(q).rules != null) {
                        return true;
                    }
                }
            }
            for (BakedQuad q : m.func_177550_a()) {
                if (s.quad(q).rules != null) {
                    return true;
                }
            }
        }
        return false;
    }

    /** A rule: its folder and name in the pack, and the .properties. */
    private void add(
            String basePath,
            String name,
            Properties p,
            List<Rule> rules,
            PackScanner.LoadStats counts) {
        Method method = method(p.getProperty("method", "ctm").trim());
        if (method == null || unsupported(p)) {
            counts.ignored++;
            return;
        }
        String matchTiles = p.getProperty("matchTiles");
        String matchBlocks = p.getProperty("matchBlocks");
        if (matchTiles == null && matchBlocks == null) {
            if (name.startsWith("block_")) {
                matchBlocks = name.substring("block_".length());
            } else {
                matchTiles = name;
            }
        }
        try {
            String[] tiles = tiles(p.getProperty("tiles"), basePath);
            int width = PackScanner.intOr(p.getProperty("width"), 0);
            int height = PackScanner.intOr(p.getProperty("height"), 0);
            int needed =
                    switch (method) {
                        case CTM -> 47;
                        case HORIZONTAL, VERTICAL -> 4;
                        case REPEAT -> width * height;
                        default -> 1;
                    };
            if (tiles.length < needed || needed <= 0) {
                counts.ignored++;
                return;
            }
            List<BlockMatch> blocks =
                    matchBlocks != null ? blocks(matchBlocks, p.getProperty("metadata")) : null;
            Set<String> sprites = matchTiles != null ? sprites(matchTiles) : null;
            if (blocks != null && blocks.isEmpty() || sprites != null && sprites.isEmpty()) {
                counts.ignored++;
                return;
            }
            String connectName =
                    p.getProperty("connect", sprites != null ? "tile" : "block").trim();
            int[] weights = null;
            if (method == Method.RANDOM && p.getProperty("weights") != null) {
                String[] ws = p.getProperty("weights").trim().split("\\s+");
                if (ws.length == tiles.length) {
                    weights = new int[ws.length];
                    for (int i = 0; i < ws.length; i++) {
                        weights[i] = Math.max(0, Integer.parseInt(ws[i]));
                    }
                }
            }
            if (method == Method.RANDOM && weights == null) {
                weights = new int[tiles.length];
                Arrays.fill(weights, 1);
            }
            String sym = p.getProperty("symmetry", "none").trim();
            CustomItems.Ranges heights = CustomItems.Ranges.parse(p.getProperty("heights"), false);
            if (heights == null
                    && (p.getProperty("minHeight") != null || p.getProperty("maxHeight") != null)) {
                heights =
                        CustomItems.Ranges.parse(
                                PackScanner.intOr(p.getProperty("minHeight"), 0)
                                        + "-"
                                        + PackScanner.intOr(p.getProperty("maxHeight"), 255),
                                false);
            }
            rules.add(
                    new Rule(
                            name,
                            basePath,
                            PackScanner.intOr(p.getProperty("weight"), 0),
                            method,
                            connectName.equals("state")
                                    ? Connect.STATE
                                    : connectName.equals("tile") ? Connect.TILE : Connect.BLOCK,
                            faces(p.getProperty("faces")),
                            heights,
                            tiles,
                            sprites,
                            blocks,
                            weights,
                            sym.equals("all")
                                    ? Symmetry.ALL
                                    : sym.equals("opposite") ? Symmetry.OPPOSITE : Symmetry.NONE,
                            width,
                            height));
        } catch (IllegalArgumentException e) {
            counts.ignored++;
        }
    }

    /** The method named in the file; null when it is not supported. */
    private static Method method(String m) {
        return switch (m) {
            case "ctm", "glass" -> Method.CTM;
            case "horizontal", "bookshelf" -> Method.HORIZONTAL;
            case "vertical" -> Method.VERTICAL;
            case "top", "sandstone" -> Method.TOP;
            case "random" -> Method.RANDOM;
            case "repeat", "pattern" -> Method.REPEAT;
            case "fixed", "static" -> Method.FIXED;
            default -> null;
        };
    }

    /**
     * Conditions that are not supported here yet: the rule is left out instead of matching wrongly.
     */
    private static boolean unsupported(Properties p) {
        for (String key : p.stringPropertyNames()) {
            if (key.equals("biomes")
                    || key.equals("name")
                    || key.equals("tintIndex")
                    || key.equals("tintBlock")
                    || key.equals("connectTiles")
                    || key.equals("connectBlocks")
                    || key.startsWith("ctm.")) {
                return true;
            }
        }
        return false;
    }

    /** "0-46", names, paths and &lt;default&gt; (null: the original texture). */
    private static String[] tiles(String s, String basePath) {
        if (s == null || s.trim().isEmpty()) {
            throw new IllegalArgumentException("no tiles");
        }
        List<String> out = new ArrayList<>();
        for (String tok : s.trim().split("\\s+")) {
            if (tok.equals("<default>")) {
                out.add(null);
                continue;
            }
            if (tok.equals("<skip>")) {
                throw new IllegalArgumentException("<skip> is only valid for overlay");
            }
            int dash = tok.indexOf('-', 1);
            if (dash > 0 && tok.chars().allMatch(c -> Character.isDigit(c) || c == '-')) {
                int hi = Integer.parseInt(tok.substring(dash + 1));
                for (int i = Integer.parseInt(tok.substring(0, dash)); i <= hi; i++) {
                    out.add(
                            PackScanner.spriteName(
                                    CustomItems.resolve(String.valueOf(i), basePath)));
                }
            } else {
                out.add(PackScanner.spriteName(CustomItems.resolve(tok, basePath)));
            }
        }
        return out.toArray(new String[0]);
    }

    /**
     * matchTiles: "glass" and "blocks/glass" become "minecraft:blocks/glass"; "mod:x" becomes
     * "mod:blocks/x".
     */
    private static Set<String> sprites(String s) {
        Set<String> out = new HashSet<>();
        for (String tok : s.trim().split("\\s+")) {
            if (tok.isEmpty() || tok.startsWith("mcpatcher/") || tok.startsWith("optifine/")) {
                continue;
            }
            int colon = tok.indexOf(':');
            String domain = colon >= 0 ? tok.substring(0, colon) : "minecraft";
            String path = colon >= 0 ? tok.substring(colon + 1) : tok;
            if (path.startsWith("textures/")) {
                path = path.substring("textures/".length());
            }
            out.add(domain + ":" + (path.contains("/") ? path : "blocks/" + path));
        }
        return out;
    }

    /** matchBlocks: "glass", "minecraft:glass", "20", "stained_glass:color=red,blue", "20:1". */
    private static List<BlockMatch> blocks(String s, String metadata) {
        int[] metaAll = metadata != null ? ints(metadata) : null;
        List<BlockMatch> out = new ArrayList<>();
        for (String tok : s.trim().split("\\s+")) {
            if (tok.isEmpty()) {
                continue;
            }
            String[] parts = tok.split(":");
            int i;
            String blockName;
            if (parts.length > 1
                    && !parts[1].contains("=")
                    && !isNumber(parts[0])
                    && !isNumber(parts[1])) {
                blockName = parts[0] + ":" + parts[1];
                i = 2;
            } else {
                blockName = parts[0];
                i = 1;
            }
            Block block =
                    isNumber(blockName)
                            ? Block.func_149729_e(Integer.parseInt(blockName))
                            : Block.func_149684_b(blockName);
            if (block == null) {
                continue;
            }
            List<Integer> metas = new ArrayList<>();
            Map<String, Set<String>> props = null;
            for (; i < parts.length; i++) {
                int eq = parts[i].indexOf('=');
                if (eq > 0) {
                    if (props == null) {
                        props = new HashMap<>();
                    }
                    props.put(
                            parts[i].substring(0, eq),
                            new HashSet<>(Arrays.asList(parts[i].substring(eq + 1).split(","))));
                } else if (isNumber(parts[i])) {
                    metas.add(Integer.parseInt(parts[i]));
                }
            }
            int[] m =
                    metas.isEmpty()
                            ? metaAll
                            : metas.stream().mapToInt(Integer::intValue).toArray();
            out.add(new BlockMatch(block, m, props));
        }
        return out;
    }

    private static int[] ints(String s) {
        List<Integer> out = new ArrayList<>();
        for (String tok : s.trim().split("[\\s,]+")) {
            int dash = tok.indexOf('-', 1);
            if (dash > 0) {
                int hi = Integer.parseInt(tok.substring(dash + 1));
                for (int v = Integer.parseInt(tok.substring(0, dash)); v <= hi; v++) {
                    out.add(v);
                }
            } else if (!tok.isEmpty()) {
                out.add(Integer.parseInt(tok));
            }
        }
        return out.stream().mapToInt(Integer::intValue).toArray();
    }

    private static boolean isNumber(String s) {
        return !s.isEmpty() && s.chars().allMatch(Character::isDigit);
    }

    /** Bits per EnumFacing ordinal; without the property, all of them. */
    private static int faces(String s) {
        if (s == null || s.trim().isEmpty()) {
            return ALL_FACES;
        }
        int bits = 0;
        for (String tok : s.trim().split("[\\s,]+")) {
            switch (tok) {
                case "bottom", "down" -> bits |= bit(EnumFacing.DOWN);
                case "top", "up" -> bits |= bit(EnumFacing.UP);
                case "north" -> bits |= bit(EnumFacing.NORTH);
                case "south" -> bits |= bit(EnumFacing.SOUTH);
                case "west" -> bits |= bit(EnumFacing.WEST);
                case "east" -> bits |= bit(EnumFacing.EAST);
                case "sides" -> bits |= SIDE_FACES;
                case "all" -> bits |= ALL_FACES;
                default -> {}
            }
        }
        return bits;
    }

    // ---- use (chunk build threads) ----

    /** The block's model at that position: with the faces swapped by the rules, or the original. */
    IBakedModel blockModel(IBlockAccess world, IBlockState state, BlockPos pos, IBakedModel model) {
        State s = this.state;
        if (!enabled || s.watched.isEmpty()) {
            return model;
        }
        Rule[] blockRules = s.byBlock.get(state.func_177230_c());
        List<BakedQuad>[] faces = null;
        for (EnumFacing f : FACINGS) {
            List<BakedQuad> in = model.func_177551_a(f);
            List<BakedQuad> out = remapList(s, in, world, state, pos, blockRules, f);
            if (out != in) {
                if (faces == null) {
                    faces = originals(model);
                }
                faces[f.ordinal()] = out;
            }
        }
        List<BakedQuad> general = model.func_177550_a();
        List<BakedQuad> generalOut = remapList(s, general, world, state, pos, blockRules, null);
        if (faces == null && generalOut == general) {
            return model;
        }
        return new Model(model, faces != null ? faces : originals(model), generalOut);
    }

    @SuppressWarnings("unchecked")
    private static List<BakedQuad>[] originals(IBakedModel model) {
        List<BakedQuad>[] out = new List[FACINGS.length];
        for (EnumFacing f : FACINGS) {
            out[f.ordinal()] = model.func_177551_a(f);
        }
        return out;
    }

    private List<BakedQuad> remapList(
            State s,
            List<BakedQuad> in,
            IBlockAccess world,
            IBlockState state,
            BlockPos pos,
            Rule[] blockRules,
            EnumFacing face) {
        List<BakedQuad> out = null;
        for (int i = 0; i < in.size(); i++) {
            BakedQuad q = in.get(i);
            BakedQuad r =
                    remap(
                            s,
                            q,
                            world,
                            state,
                            pos,
                            blockRules,
                            face != null ? face : q.func_178210_d());
            if (r != q) {
                if (out == null) {
                    out = new ArrayList<>(in);
                }
                out.set(i, r);
            }
        }
        return out != null ? out : in;
    }

    private BakedQuad remap(
            State s,
            BakedQuad q,
            IBlockAccess world,
            IBlockState state,
            BlockPos pos,
            Rule[] blockRules,
            EnumFacing face) {
        QuadInfo info = s.quad(q);
        if (info.sprite == null) {
            return q;
        }
        Rule rule = pick(info.rules, blockRules, state, pos, face);
        if (rule == null) {
            return q;
        }
        int tile = tile(s, rule, info.sprite, world, state, pos, face);
        if (tile < 0 || tile >= rule.tiles.length || rule.tiles[tile] == null) {
            return q;
        }
        return rule.quad(q, tile, info.sprite);
    }

    /** The first rule that applies, among the texture's and the block's, in rule order. */
    private static Rule pick(Rule[] a, Rule[] b, IBlockState state, BlockPos pos, EnumFacing face) {
        int i = 0;
        int j = 0;
        int na = a != null ? a.length : 0;
        int nb = b != null ? b.length : 0;
        while (i < na || j < nb) {
            Rule r = j >= nb || i < na && ORDER.compare(a[i], b[j]) <= 0 ? a[i++] : b[j++];
            if (r.applies(state, pos, face)) {
                return r;
            }
        }
        return null;
    }

    private int tile(
            State s,
            Rule r,
            TextureAtlasSprite sprite,
            IBlockAccess world,
            IBlockState state,
            BlockPos pos,
            EnumFacing face) {
        int[][] ax = AXES[face.ordinal()];
        switch (r.method) {
            case CTM:
                {
                    boolean up = connects(s, r, sprite, world, state, pos, face, ax[0], null);
                    boolean down = connects(s, r, sprite, world, state, pos, face, ax[1], null);
                    boolean left = connects(s, r, sprite, world, state, pos, face, ax[2], null);
                    boolean right = connects(s, r, sprite, world, state, pos, face, ax[3], null);
                    int m =
                            (up ? UP : 0)
                                    | (down ? DOWN : 0)
                                    | (left ? LEFT : 0)
                                    | (right ? RIGHT : 0);
                    if (up
                            && left
                            && connects(s, r, sprite, world, state, pos, face, ax[0], ax[2])) {
                        m |= UP_LEFT;
                    }
                    if (up
                            && right
                            && connects(s, r, sprite, world, state, pos, face, ax[0], ax[3])) {
                        m |= UP_RIGHT;
                    }
                    if (down
                            && left
                            && connects(s, r, sprite, world, state, pos, face, ax[1], ax[2])) {
                        m |= DOWN_LEFT;
                    }
                    if (down
                            && right
                            && connects(s, r, sprite, world, state, pos, face, ax[1], ax[3])) {
                        m |= DOWN_RIGHT;
                    }
                    return CTM_TILE[m];
                }
            case HORIZONTAL:
                {
                    boolean left = connects(s, r, sprite, world, state, pos, face, ax[2], null);
                    boolean right = connects(s, r, sprite, world, state, pos, face, ax[3], null);
                    return left && right ? 1 : left ? 2 : right ? 0 : 3;
                }
            case VERTICAL:
                {
                    boolean up = connects(s, r, sprite, world, state, pos, face, ax[0], null);
                    boolean down = connects(s, r, sprite, world, state, pos, face, ax[1], null);
                    return up && down ? 1 : up ? 0 : down ? 2 : 3;
                }
            case TOP:
                if (face == EnumFacing.UP || face == EnumFacing.DOWN) {
                    return -1;
                }
                return connects(s, r, sprite, world, state, pos, face, UP_AXIS, null) ? 0 : -1;
            case RANDOM:
                return random(r, pos, face);
            case REPEAT:
                return repeat(r, pos, face);
            default:
                return 0;
        }
    }

    /** Whether the neighbor at a (+ b, diagonally) connects, by the rule's connect. */
    private static boolean connects(
            State s,
            Rule r,
            TextureAtlasSprite sprite,
            IBlockAccess world,
            IBlockState state,
            BlockPos pos,
            EnumFacing face,
            int[] a,
            int[] b) {
        BlockPos n =
                b == null
                        ? pos.func_177982_a(a[0], a[1], a[2])
                        : pos.func_177982_a(a[0] + b[0], a[1] + b[1], a[2] + b[2]);
        IBlockState ns = world.func_180495_p(n);
        switch (r.connect) {
            case STATE:
                return ns == state;
            case BLOCK:
                return ns.func_177230_c() == state.func_177230_c();
            default:
                if (ns.func_177230_c() == state.func_177230_c()) {
                    return true;
                }
                IBakedModel m = s.shapes.func_178125_b(ns);
                if (m == null) {
                    return false;
                }
                for (BakedQuad q : m.func_177551_a(face)) {
                    if (s.quad(q).sprite == sprite) {
                        return true;
                    }
                }
                return false;
        }
    }

    /**
     * random: one tile per position and face (symmetry joins opposite faces or all), with the
     * weights.
     */
    private static int random(Rule r, BlockPos pos, EnumFacing face) {
        int f =
                r.symmetry == Symmetry.ALL
                        ? 0
                        : r.symmetry == Symmetry.OPPOSITE ? face.ordinal() & ~1 : face.ordinal();
        long h =
                (long) pos.func_177958_n() * HASH_X
                        ^ (long) pos.func_177952_p() * HASH_Z
                        ^ (long) pos.func_177956_o();
        h = h * h * HASH_SQUARE + h * HASH_LINEAR + f * HASH_FACE;
        h ^= h >>> 29;
        int pick = (int) Long.remainderUnsigned(h, r.weightSum);
        for (int i = 0; i < r.weights.length; i++) {
            pick -= r.weights[i];
            if (pick < 0) {
                return i;
            }
        }
        return 0;
    }

    /**
     * repeat: the width x height pattern anchored to the world, by the texture's right and down on
     * each face.
     */
    private static int repeat(Rule r, BlockPos pos, EnumFacing face) {
        int f = face.ordinal();
        if (r.symmetry == Symmetry.OPPOSITE && (f & 1) == 0 && f != 0) {
            f += 1; // north and west with the pattern of south and east (mirrored)
        }
        int[][] ax = AXES[f];
        int col =
                pos.func_177958_n() * ax[3][0]
                        + pos.func_177956_o() * ax[3][1]
                        + pos.func_177952_p() * ax[3][2];
        int row =
                pos.func_177958_n() * ax[1][0]
                        + pos.func_177956_o() * ax[1][1]
                        + pos.func_177952_p() * ax[1][2];
        return Math.floorMod(row, r.height) * r.width + Math.floorMod(col, r.width);
    }

    /** The quad with the texture coordinates moved from the original texture to the tile. */
    private static BakedQuad remap(BakedQuad q, TextureAtlasSprite from, TextureAtlasSprite to) {
        int[] v = q.func_178209_a().clone();
        int stride = v.length / 4;
        float fu0 = from.func_94209_e();
        float fu1 = from.func_94212_f();
        float fv0 = from.func_94206_g();
        float fv1 = from.func_94210_h();
        float tu0 = to.func_94209_e();
        float tu1 = to.func_94212_f();
        float tv0 = to.func_94206_g();
        float tv1 = to.func_94210_h();
        for (int i = 0; i < 4; i++) {
            int k = i * stride + 4;
            float u = Float.intBitsToFloat(v[k]);
            float w = Float.intBitsToFloat(v[k + 1]);
            v[k] = Float.floatToRawIntBits(tu0 + (u - fu0) / (fu1 - fu0) * (tu1 - tu0));
            v[k + 1] = Float.floatToRawIntBits(tv0 + (w - fv0) / (fv1 - fv0) * (tv1 - tv0));
        }
        return new BakedQuad(v, q.func_178211_c(), q.func_178210_d());
    }

    /** The model of a position: the original with the quad lists swapped. */
    private static final class Model implements IBakedModel {
        private final IBakedModel base;
        private final List<BakedQuad>[] faces;
        private final List<BakedQuad> general;

        Model(IBakedModel base, List<BakedQuad>[] faces, List<BakedQuad> general) {
            this.base = base;
            this.faces = faces;
            this.general = general;
        }

        @Override
        public List<BakedQuad> func_177551_a(EnumFacing f) {
            return faces[f.ordinal()];
        }

        @Override
        public List<BakedQuad> func_177550_a() {
            return general;
        }

        @Override
        public boolean func_177555_b() {
            return base.func_177555_b();
        }

        @Override
        public boolean func_177556_c() {
            return base.func_177556_c();
        }

        @Override
        public boolean func_177553_d() {
            return base.func_177553_d();
        }

        @Override
        public TextureAtlasSprite func_177554_e() {
            return base.func_177554_e();
        }

        @Override
        public ItemCameraTransforms func_177552_f() {
            return base.func_177552_f();
        }
    }
}
