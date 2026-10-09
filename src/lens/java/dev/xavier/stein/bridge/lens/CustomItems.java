/*
 * Copyright (c) 2026 Breno Xavier. All rights reserved.
 * Proprietary software: see the LICENSE file at the root of this repository.
 */

package dev.xavier.stein.bridge.lens;

import dev.xavier.stein.bridge.core.Log;
import dev.xavier.stein.bridge.engine.EnchCache;
import dev.xavier.stein.bridge.hooks.GameHooks;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.BlockPart;
import net.minecraft.client.renderer.block.model.BlockPartFace;
import net.minecraft.client.renderer.block.model.FaceBakery;
import net.minecraft.client.renderer.block.model.ItemModelGenerator;
import net.minecraft.client.renderer.block.model.ModelBlock;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.client.resources.model.IBakedModel;
import net.minecraft.client.resources.model.ModelRotation;
import net.minecraft.client.resources.model.SimpleBakedModel;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemArmor;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ResourceLocation;

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

/**
 * Item textures by enchantment, damage or stack size: the CIT (Custom Item Textures) resource-pack
 * format, read from the active resource packs — .properties files in assets/minecraft/mcpatcher/cit
 * and optifine/cit, folder or zip.
 *
 * <p>Format rules:
 *
 * <ul>
 *   <li>{@code type=item} swaps the item's icon and model (screen, hand, ground, frame); {@code
 *       type=armor} swaps the texture of the worn armor ({@code
 *       texture.<material>_layer_<1|2>[_overlay]}, or {@code texture});
 *   <li>{@code items} (ids or names), {@code enchantmentIDs} (ids or names), {@code
 *       enchantmentLevels}, {@code damage} and {@code stackSize} (values and "a-b" ranges); the ids
 *       and the levels are each checked against all of the item's enchantments. {@code damage}
 *       accepts a <b>percentage</b> ({@code damage=86%-100%}): the range is how much of the
 *       durability has already been used, not the raw value — which is how packs paint armor as it
 *       wears out;
 *   <li>the first rule that matches applies, with the higher {@code weight} ones first, then by
 *       folder and by file name; a relative texture is in the .properties folder.
 * </ul>
 *
 * Rules with something not supported here yet ({@code nbt.*}, {@code model}, {@code
 * type=enchantment}) are left out — they never match wrongly.
 *
 * <p>Cost: the rule of each ItemStack is chosen once and kept in a table by object identity; it
 * holds as long as the NBT is the same object and the enchantments version ({@link
 * EnchCache#version()}), the damage and the count do not change. An item without a rule costs one
 * lookup in an identity map of the Item. Each rule's model is built once per resource reload, the
 * first time it shows up, with the poses (hand, screen) of the item's original model.
 */
final class CustomItems {

    private static final String[] ROOTS = {
        PackScanner.ASSETS + "mcpatcher/cit/", PackScanner.ASSETS + "optifine/cit/"
    };
    private static final int[][] NO_ENCHANTMENTS = new int[0][];

    /** Higher weight first; then folder and file name (the format's tie-break). */
    private static final Comparator<Rule> ORDER =
            (a, b) -> PackScanner.order(a.weight, a.basePath, a.name, b.weight, b.basePath, b.name);

    /** Rules of each item, in the order in which they apply. */
    private Map<Item, Rule[]> byItem = Collections.emptyMap();

    /** Goes up on every resource reload: earlier resolutions and models stop being valid. */
    private int generation;

    // The choice for each ItemStack lives in the ItemStack itself (GameHooks.itemData, a field):
    // reading costs one field in an object the game has just read in order to draw it. Measured
    // in the crowd (400 players with swords and armor, ~2000 ItemStacks with a rule): a table of
    // 2048 slots collided in almost half and redid the choice every frame (-7% FPS); an identity
    // map still cost ~0.3 ms per frame in out-of-cache memory.

    private final FaceBakery faceBakery = new FaceBakery();
    private final ItemModelGenerator generator = new ItemModelGenerator();

    static final class Rule {
        final String name;
        final String basePath;
        final boolean armor;
        final int weight;
        final Ranges enchantmentIds;
        final Ranges enchantmentLevels;
        final Ranges damage;

        /** The damage range is a percentage of durability (damage=86%-100%), not the raw value. */
        final boolean damagePercent;

        final Ranges stackSize;

        /** type=item: the texture in the atlas; the model comes from it. */
        String sprite;

        /** type=armor: {@code texture} (default) and {@code texture.<key>}. */
        ResourceLocation texture;

        final Map<String, ResourceLocation> textures = new HashMap<>();

        /**
         * Render-thread cache of the textures of the last armor material, per layer and overlay.
         */
        String cachedMaterial;

        final ResourceLocation[] cachedTextures = new ResourceLocation[4];
        final boolean[] cachedKnown = new boolean[4];
        IBakedModel model;
        int modelGeneration = -1;

        Rule(
                String name,
                String basePath,
                boolean armor,
                int weight,
                Ranges ids,
                Ranges levels,
                Ranges damage,
                boolean damagePercent,
                Ranges stackSize) {
            this.name = name;
            this.basePath = basePath;
            this.armor = armor;
            this.weight = weight;
            this.enchantmentIds = ids;
            this.enchantmentLevels = levels;
            this.damage = damage;
            this.damagePercent = damagePercent;
            this.stackSize = stackSize;
        }
    }

    /** The choice for an ItemStack and the key it was made with. */
    static final class Resolved {
        Object tag;
        int version;
        int damage;
        int size;
        int generation = -1;
        Rule item;
        Rule armor;
    }

    /** List of values and ranges ("5", "10-1000", "5 6 7", "3-"). */
    static final class Ranges {
        private final int[] min;
        private final int[] max;

        private Ranges(int[] min, int[] max) {
            this.min = min;
            this.max = max;
        }

        boolean contains(int v) {
            for (int i = 0; i < min.length; i++) {
                if (v >= min[i] && v <= max[i]) {
                    return true;
                }
            }
            return false;
        }

        /**
         * null if the property does not exist; throws IllegalArgumentException if it is not
         * understood.
         */
        static Ranges parse(String s, boolean enchantments) {
            if (s == null || s.trim().isEmpty()) {
                return null;
            }
            List<int[]> out = new ArrayList<>();
            for (String tok : s.trim().split("[\\s,]+")) {
                int dash = tok.indexOf('-', 1);
                if (dash > 0) {
                    int lo = number(tok.substring(0, dash), enchantments);
                    String hi = tok.substring(dash + 1);
                    out.add(
                            new int[] {
                                lo, hi.isEmpty() ? Integer.MAX_VALUE : number(hi, enchantments)
                            });
                } else {
                    int v = number(tok, enchantments);
                    out.add(new int[] {v, v});
                }
            }
            int[] min = new int[out.size()];
            int[] max = new int[out.size()];
            for (int i = 0; i < min.length; i++) {
                min[i] = out.get(i)[0];
                max[i] = out.get(i)[1];
            }
            return new Ranges(min, max);
        }

        private static int number(String tok, boolean enchantments) {
            try {
                return Integer.parseInt(tok);
            } catch (NumberFormatException e) {
                if (enchantments) {
                    Enchantment ench =
                            Enchantment.func_180305_b(tok.contains(":") ? tok : "minecraft:" + tok);
                    if (ench != null) {
                        return ench.field_77352_x;
                    }
                }
                throw new IllegalArgumentException(tok);
            }
        }
    }

    int ruleCount() {
        int n = 0;
        for (Rule[] r : byItem.values()) {
            n += r.length;
        }
        return n;
    }

    // ---- loading (before the atlas is built) ----

    /**
     * Reads the rules from the active packs and registers the item textures in the atlas about to
     * be built.
     */
    void reload(TextureMap map) {
        generation++; // earlier choices and models stop being valid
        Map<Item, List<Rule>> lists = new IdentityHashMap<>();
        PackScanner.LoadStats counts = new PackScanner.LoadStats();
        PackScanner.scanActivePacks(
                ROOTS, (basePath, name, p, st) -> add(basePath, name, p, lists, st), counts);
        Map<Item, Rule[]> out = new IdentityHashMap<>();
        Set<String> sprites = new HashSet<>();
        for (Map.Entry<Item, List<Rule>> en : lists.entrySet()) {
            List<Rule> rules = en.getValue();
            rules.sort(ORDER);
            out.put(en.getKey(), rules.toArray(new Rule[0]));
            for (Rule r : rules) {
                if (r.sprite != null && sprites.add(r.sprite)) {
                    map.func_174942_a(new ResourceLocation(r.sprite));
                }
            }
        }
        byItem = out;
        counts.report("per-item texture rule(s)");
    }

    /** A rule: its folder and name in the pack, and the .properties. */
    private void add(
            String basePath,
            String name,
            Properties p,
            Map<Item, List<Rule>> lists,
            PackScanner.LoadStats counts) {
        String type = p.getProperty("type", "item").trim();
        boolean armor = type.equals("armor");
        // damage=86%-100%: the range is a percentage of the piece's durability, not the raw
        // damage value. It is how packs paint armor as it wears out.
        String damage = p.getProperty("damage");
        boolean damagePercent = damage != null && damage.contains("%");
        if (damagePercent) {
            damage = damage.replace("%", "");
        }
        if (!armor && !type.equals("item") || unsupported(p)) {
            counts.ignored++;
            return;
        }
        List<Item> items = items(p.getProperty("items", p.getProperty("matchItems")));
        Rule r;
        try {
            r =
                    new Rule(
                            name,
                            basePath,
                            armor,
                            PackScanner.intOr(p.getProperty("weight"), 0),
                            Ranges.parse(p.getProperty("enchantmentIDs"), true),
                            Ranges.parse(p.getProperty("enchantmentLevels"), false),
                            Ranges.parse(damage, false),
                            damagePercent,
                            Ranges.parse(p.getProperty("stackSize"), false));
        } catch (IllegalArgumentException e) {
            counts.ignored++;
            return;
        }
        if (items.isEmpty()) {
            counts.ignored++;
            return;
        }
        String texture = p.getProperty("texture");
        if (armor) {
            for (String key : p.stringPropertyNames()) {
                if (key.startsWith("texture.")) {
                    r.textures.put(key, armorLocation(resolve(p.getProperty(key), basePath)));
                }
            }
            if (texture != null || r.textures.isEmpty()) {
                r.texture = armorLocation(resolve(texture != null ? texture : name, basePath));
            }
        } else {
            r.sprite = PackScanner.spriteName(resolve(texture != null ? texture : name, basePath));
        }
        for (Item item : items) {
            lists.computeIfAbsent(item, k -> new ArrayList<>()).add(r);
        }
    }

    /** Conditions not supported here yet: the rule is left out instead of matching wrongly. */
    private static boolean unsupported(Properties p) {
        for (String key : p.stringPropertyNames()) {
            if (key.startsWith("nbt.")
                    || key.equals("model")
                    || key.startsWith("model.")
                    || key.equals("damageMask")) {
                return true;
            }
        }
        String hand = p.getProperty("hand");
        return hand != null && hand.trim().equals("off");
    }

    private static List<Item> items(String s) {
        List<Item> out = new ArrayList<>();
        if (s == null) {
            return out;
        }
        for (String tok : s.trim().split("\\s+")) {
            if (tok.isEmpty()) {
                continue;
            }
            int dash = tok.indexOf('-', 1);
            try {
                if (dash > 0) {
                    int hi = Integer.parseInt(tok.substring(dash + 1));
                    for (int id = Integer.parseInt(tok.substring(0, dash)); id <= hi; id++) {
                        addItem(out, Item.func_150899_d(id));
                    }
                } else if (Character.isDigit(tok.charAt(0))) {
                    addItem(out, Item.func_150899_d(Integer.parseInt(tok)));
                } else {
                    addItem(
                            out,
                            Item.field_150901_e.func_82594_a(
                                    new ResourceLocation(
                                            tok.contains(":") ? tok : "minecraft:" + tok)));
                }
            } catch (NumberFormatException ignored) {
                // item that does not exist in 1.8.9: left out
            }
        }
        return out;
    }

    private static void addItem(List<Item> out, Item item) {
        if (item != null && !out.contains(item)) {
            out.add(item);
        }
    }

    /** Folders a texture path may already start from, as the resource-pack format allows. */
    private static final String[] TEXTURE_ROOTS = {"textures/", "mcpatcher/", "optifine/"};

    /**
     * The texture path a rule names, as the resource-pack format defines it: an optional {@code
     * namespace:} is ignored and so is the {@code .png} extension; {@code ./x} is next to the
     * .properties file, {@code ~/x} is under {@code optifine/}, a path from one of the {@link
     * #TEXTURE_ROOTS} (or already under the rule's folder) is used as written, and any other path
     * is relative to the rule's folder.
     */
    static String resolve(String texture, String basePath) {
        String path = texture.trim();
        path = path.substring(path.indexOf(':') + 1);
        if (path.endsWith(".png")) {
            path = path.substring(0, path.length() - ".png".length());
        }
        if (path.startsWith("./")) {
            return basePath + path.substring(1);
        }
        if (path.startsWith("~/")) {
            return "optifine" + path.substring(1);
        }
        return isRooted(path, basePath) ? path : basePath + "/" + path;
    }

    private static boolean isRooted(String path, String basePath) {
        if (path.startsWith(basePath)) {
            return true;
        }
        for (String root : TEXTURE_ROOTS) {
            if (path.startsWith(root)) {
                return true;
            }
        }
        return false;
    }

    private static ResourceLocation armorLocation(String path) {
        return new ResourceLocation("minecraft", path + ".png");
    }

    // ---- use (render thread) ----

    /** The item's model: the matching rule's, or the original. */
    IBakedModel itemModel(ItemStack stack, IBakedModel model) {
        Resolved r = resolve(stack);
        if (r == null || r.item == null) {
            return model;
        }
        IBakedModel custom = model(r.item, model);
        return custom != null ? custom : model;
    }

    /** The worn piece's texture: the matching armor rule's, or the original. */
    ResourceLocation armorTexture(
            ItemStack stack, int layer, String overlay, ResourceLocation location) {
        Resolved r = resolve(stack);
        if (r == null || r.armor == null) {
            return location;
        }
        Rule rule = r.armor;
        if (!rule.textures.isEmpty() && stack.func_77973_b() instanceof ItemArmor armor) {
            String material = armor.func_82812_d().func_179242_c();
            ResourceLocation t = armorRuleTexture(rule, material, layer, overlay);
            if (t != null) {
                return t;
            }
        }
        return rule.texture != null ? rule.texture : location;
    }

    /**
     * The rule's texture for the material, layer and overlay. The usual layers (1, 2, with or
     * without "overlay") are cached in the rule, so nothing is built per worn piece per frame.
     */
    private static ResourceLocation armorRuleTexture(
            Rule rule, String material, int layer, String overlay) {
        int slot = -1;
        if ((layer == 1 || layer == 2) && (overlay == null || overlay.equals("overlay"))) {
            slot = (layer - 1) * 2 + (overlay == null ? 0 : 1);
        }
        if (slot < 0) {
            return rule.textures.get(textureKey(material, layer, overlay));
        }
        if (!material.equals(rule.cachedMaterial)) {
            Arrays.fill(rule.cachedKnown, false);
            rule.cachedMaterial = material;
        }
        if (!rule.cachedKnown[slot]) {
            rule.cachedTextures[slot] = rule.textures.get(textureKey(material, layer, overlay));
            rule.cachedKnown[slot] = true;
        }
        return rule.cachedTextures[slot];
    }

    private static String textureKey(String material, int layer, String overlay) {
        return "texture." + material + "_layer_" + layer + (overlay != null ? "_" + overlay : "");
    }

    /**
     * {@code -Dsteinlens.debug=true}: lookups, recomputed choices and enchantment version steps,
     * every 2 s.
     */
    private static final boolean DEBUG = LensOptions.DEBUG;

    private long debugCalls;
    private long debugRecomputes;
    private int debugVersion;
    private long debugLast;

    private void debugReport() {
        long now = System.nanoTime();
        if (now - debugLast > 2_000_000_000L) {
            int v = EnchCache.version();
            Log.info(
                    "lens",
                    "custom items: "
                            + debugCalls / 2
                            + " lookups/s, "
                            + debugRecomputes / 2
                            + " recomputed choices/s, enchantment version +"
                            + (v - debugVersion)
                            + " in 2 s");
            debugLast = now;
            debugVersion = v;
            debugCalls = 0;
            debugRecomputes = 0;
        }
    }

    private Resolved resolve(ItemStack stack) {
        Rule[] rules = byItem.get(stack.func_77973_b());
        if (rules == null) {
            return null;
        }
        if (DEBUG) {
            debugCalls++;
            debugReport();
        }
        Object tag = stack.func_77978_p();
        int version = EnchCache.version();
        int damage = stack.func_77952_i();
        int size = stack.field_77994_a;
        Resolved r = GameHooks.itemData(stack) instanceof Resolved saved ? saved : null;
        if (r != null
                && r.tag == tag
                && r.version == version
                && r.damage == damage
                && r.size == size
                && r.generation == generation) {
            return r;
        }
        if (r == null) {
            r = new Resolved();
            GameHooks.setItemData(stack, r);
        }
        if (DEBUG) {
            debugRecomputes++;
        }
        r.tag = tag;
        r.version = version;
        r.damage = damage;
        r.size = size;
        r.generation = generation;
        r.item = null;
        r.armor = null;
        int[][] enchantments = enchantments(stack);
        for (Rule rule : rules) {
            if (rule.armor ? r.armor == null : r.item == null) {
                if (matches(rule, stack, enchantments)) {
                    if (rule.armor) {
                        r.armor = rule;
                    } else {
                        r.item = rule;
                    }
                    if (r.armor != null && r.item != null) {
                        break;
                    }
                }
            }
        }
        return r;
    }

    /** {id, level} pairs of the enchantments (of the book: the stored ones). */
    private static int[][] enchantments(ItemStack stack) {
        NBTTagList list =
                stack.func_77973_b() == Items.field_151134_bR
                        ? Items.field_151134_bR.func_92110_g(stack)
                        : stack.func_77986_q();
        if (list == null || list.func_74745_c() == 0) {
            return NO_ENCHANTMENTS;
        }
        int[][] out = new int[list.func_74745_c()][];
        for (int i = 0; i < out.length; i++) {
            NBTTagCompound c = list.func_150305_b(i);
            out[i] = new int[] {c.func_74765_d("id"), c.func_74765_d("lvl")};
        }
        return out;
    }

    /** Ids and levels checked, each one, against all of the item's enchantments. */
    private static boolean matches(Rule r, ItemStack stack, int[][] enchantments) {
        if (r.damage != null && !r.damage.contains(damageForRule(r, stack))) {
            return false;
        }
        if (r.stackSize != null && !r.stackSize.contains(stack.field_77994_a)) {
            return false;
        }
        if (r.enchantmentIds != null && !any(r.enchantmentIds, enchantments, 0)) {
            return false;
        }
        return r.enchantmentLevels == null || any(r.enchantmentLevels, enchantments, 1);
    }

    /**
     * The value that the {@code damage} range checks: the raw damage, or the percentage of
     * durability used when the rule came with {@code %} ({@code damage=86%-100%} paints the piece
     * that is about to break). The computation is damage * 100 / durability, in integers. An item
     * that does not wear (zero durability) never matches a % range.
     */
    private static int damageForRule(Rule r, ItemStack stack) {
        int damage = stack.func_77952_i();
        if (!r.damagePercent) {
            return damage;
        }
        int total = stack.func_77958_k();
        return total > 0 ? damage * 100 / total : -1;
    }

    private static boolean any(Ranges ranges, int[][] enchantments, int field) {
        for (int[] e : enchantments) {
            if (ranges.contains(e[field])) {
                return true;
            }
        }
        return false;
    }

    /**
     * The rule's model: the texture as a generated item (the same math as the game's
     * builtin/generated, in the same sequence as ModelBakery), with the poses and the way of
     * drawing of the item's original model. Built once per resource reload.
     */
    private IBakedModel model(Rule rule, IBakedModel original) {
        if (rule.modelGeneration == generation) {
            return rule.model;
        }
        rule.modelGeneration = generation;
        rule.model = null;
        TextureMap map = Minecraft.func_71410_x().func_147117_R();
        ModelBlock base =
                ModelBlock.func_178294_a(
                        "{\"parent\":\"builtin/generated\",\"textures\":{\"layer0\":\""
                                + rule.sprite
                                + "\"}}");
        ModelBlock generated = generator.func_178392_a(map, base);
        if (generated == null) {
            return null;
        }
        List<BakedQuad> general = new ArrayList<>();
        List<List<BakedQuad>> faces = new ArrayList<>();
        for (int i = 0; i < EnumFacing.values().length; i++) {
            faces.add(new ArrayList<>());
        }
        for (BlockPart part : generated.func_178298_a()) {
            for (Map.Entry<EnumFacing, BlockPartFace> e : part.field_178240_c.entrySet()) {
                BlockPartFace face = e.getValue();
                // As in ModelBakery: the resolved name goes through ResourceLocation (adds the
                // domain).
                TextureAtlasSprite sprite =
                        map.func_110572_b(
                                new ResourceLocation(generated.func_178308_c(face.field_178242_d))
                                        .toString());
                BakedQuad quad =
                        faceBakery.func_178414_a(
                                part.field_178241_a,
                                part.field_178239_b,
                                face,
                                sprite,
                                e.getKey(),
                                ModelRotation.X0_Y0,
                                part.field_178237_d,
                                false,
                                part.field_178238_e);
                if (face.field_178244_b == null) {
                    general.add(quad);
                } else {
                    faces.get(face.field_178244_b.ordinal()).add(quad);
                }
            }
        }
        rule.model =
                new SimpleBakedModel(
                        general,
                        faces,
                        original.func_177555_b(),
                        original.func_177556_c(),
                        map.func_110572_b(rule.sprite),
                        original.func_177552_f());
        return rule.model;
    }
}
