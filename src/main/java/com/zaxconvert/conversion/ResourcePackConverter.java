package com.zaxconvert.conversion;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.GsonBuilder;
import com.zaxconvert.ZaxConvert;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.awt.image.BufferedImage;
import java.io.*;
import javax.imageio.ImageIO;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Converts Java resource packs (Nexo / ItemsAdder / Oraxen) into a Bedrock
 * .mcpack plus a Geyser custom item mappings file (format_version: 2).
 *
 * Full support for:
 * - 3D models with proper hand placement and swing physics
 * - Custom wearable armor with body layers (helmet, chestplate, leggings, boots)
 * - 2D item extrusion and natural handheld positioning via geometry.item_default
 * - Animated textures (flipbook)
 * - Full inventory icons registered into Bedrock's vanilla atlas
 */
public class ResourcePackConverter {

    private final ZaxConvert plugin;
    private FileConfiguration config;

    public ResourcePackConverter(ZaxConvert plugin) {
        this.plugin = plugin;
        this.config = plugin.getConfig();
    }

    /** Result of converting a single pack. */
    public static class Result {
        public final String provider;
        public boolean success;
        public int items;
        public int models3d;
        public int animated;
        public int armors;
        public int skipped;
        public String message = "";
        public File mcpack;
        public File mappings;

        Result(String provider) {
            this.provider = provider;
        }
    }

    /** Receives (level, message) where level is info/success/warn/error. */
    private BiConsumer<String, String> progress;
    private Map<String, String> modelIndex = new HashMap<>();
    private Map<String, String> texIndex = new HashMap<>();
    private final Map<String, TexInfo> textureCache = new HashMap<>();
    private final Map<String, Model> modelCache = new HashMap<>();

    private void say(String level, String msg) {
        plugin.getLogger().info("[" + level + "] " + msg);
        if (progress != null) {
            try {
                progress.accept(level, msg);
            } catch (Exception ignored) {
            }
        }
    }

    public List<Result> convertAllProviders() {
        return convertAllProviders(null);
    }

    /** Scans all enabled providers and converts whatever is found. */
    public synchronized List<Result> convertAllProviders(BiConsumer<String, String> listener) {
        this.progress = listener;
        try {
            List<Result> results = new ArrayList<>();
            File pluginsDir = plugin.getDataFolder().getParentFile();
            say("info", "Scanning providers (Nexo, ItemsAdder, Oraxen)...");
            for (String provider : new String[]{"nexo", "itemsadder", "oraxen"}) {
                if (!config.getBoolean("plugin-support." + provider + "-enabled", true)) {
                    continue;
                }
                File pack = findProviderPack(pluginsDir, provider);
                if (pack == null) {
                    Result r = new Result(provider);
                    r.message = "No pack found (provider not installed or pack not generated yet)";
                    say("warn", provider + ": " + r.message);
                    results.add(r);
                    continue;
                }
                say("info", "Found " + provider + " pack: " + pack.getName());
                results.add(doConvert(provider, pack));
            }
            return results;
        } finally {
            this.progress = null;
        }
    }

    public Result convertPack(String name, File pack) {
        return convertPack(name, pack, null);
    }

    /** Converts one manually specified pack (zip or folder). */
    public synchronized Result convertPack(String name, File pack, BiConsumer<String, String> listener) {
        this.progress = listener;
        try {
            return doConvert(name, pack);
        } finally {
            this.progress = null;
        }
    }

    private Result doConvert(String name, File pack) {
        Result result = new Result(name);
        say("info", "Reading " + name + " models, textures, and armors...");

        try (PackSource src = PackSource.open(pack)) {
            File outDir = new File(plugin.getDataFolder(), "generated");
            outDir.mkdirs();
            textureCache.clear();
            modelCache.clear();

            // Build case-insensitive indexes for all models and textures
            modelIndex = buildModelIndex(src);
            texIndex = buildTextureIndex(src);

            Map<String, byte[]> bedrockFiles = new LinkedHashMap<>();
            JsonObject mappingItems = new JsonObject();
            Set<String> usedNames = new HashSet<>();
            JsonObject textureData = new JsonObject();
            JsonArray flipbooks = new JsonArray();
            StringBuilder langFile = new StringBuilder();
            boolean want3d = config.getBoolean("conversion.convert-3d-models", true);

            // Add standard item default render controller and disable animation (required by Bedrock attachables)
            bedrockFiles.put("render_controllers/item_default.render_controllers.json", BedrockAnimationBuilder.buildDefaultRenderController());
            bedrockFiles.put("render_controllers/armor.render_controllers.json", BedrockAnimationBuilder.buildArmorRenderControllers());
            bedrockFiles.put("animations/zaxconvert.disable.animation.json", BedrockAnimationBuilder.buildDisableAnimation());

            int count = 0;
            int models3d = 0;
            int animated = 0;
            int armors = 0;

            for (ItemDef def : collectDefinitions(src, name)) {
                try {
                    String targetModelId = def.definition ? resolveDefModelId(src, def.modelId) : def.modelId;
                    Model model = resolveModel(src, targetModelId);
                    String itemName = uniqueName(usedNames, "smc_" + hash7(name + "|" + def.key()));

                    boolean isHandheld = isHandheldItem(def.baseItem, def.modelId, def.displayName);
                    String armorSlot = getArmorSlot(def.baseItem, def.modelId, def.displayName);
                    boolean isArmor = (armorSlot != null);
                    if (isArmor && (def.baseItem == null || !def.baseItem.endsWith(armorSlot))) {
                        def.baseItem = "chainmail_" + armorSlot;
                    }

                    // Icon: 3D models are rendered like the Java GUI; flat models use their layers
                    byte[] iconPng = null;
                    TexInfo icon = null;
                    Map<String, TexInfo> texByRef = null;
                    boolean has3d = model.elements != null && !model.elements.isEmpty();

                    if (has3d) {
                        texByRef = gatherTextures(src, model);
                        iconPng = renderIcon(model, texByRef);
                    }
                    if (iconPng == null) {
                        icon = loadFlatIcon(src, model);
                        if (icon != null) iconPng = icon.png;
                    }
                    if (iconPng == null && texByRef != null && !texByRef.isEmpty()) {
                        TexInfo firstTex = texByRef.values().iterator().next();
                        if (firstTex != null) {
                            icon = firstTex;
                            iconPng = firstTex.png;
                        }
                    }
                    if (iconPng == null) {
                        // Direct O(1) search by modelId
                        String clean = def.modelId.substring(Math.max(def.modelId.lastIndexOf(':'), def.modelId.lastIndexOf('/')) + 1).toLowerCase(Locale.ROOT);
                        String path = texIndex.get("textures/item/" + clean + ".png");
                        if (path == null) path = texIndex.get("textures/items/" + clean + ".png");
                        if (path == null) path = texIndex.get(clean + ".png");
                        if (path == null) path = texIndex.get(clean);
                        if (path != null) {
                            icon = loadTexture(src, path);
                            if (icon != null) iconPng = icon.png;
                        }
                    }
                    if (iconPng == null && isArmor && def.armorTexture != null) {
                        String clean = def.armorTexture.toLowerCase(Locale.ROOT);
                        String path = texIndex.get("textures/item/" + clean + "_" + armorSlot + ".png");
                        if (path == null) path = texIndex.get("textures/items/" + clean + "_" + armorSlot + ".png");
                        if (path == null) path = texIndex.get("textures/item/" + clean + ".png");
                        if (path == null) path = texIndex.get(clean + "_" + armorSlot + ".png");
                        if (path == null) path = texIndex.get(clean + ".png");
                        if (path == null) path = texIndex.get(clean);
                        if (path != null) {
                            icon = loadTexture(src, path);
                            if (icon != null) iconPng = icon.png;
                        }
                    }
                    if (iconPng == null && isArmor) {
                        String path = texIndex.get("textures/items/chainmail_" + armorSlot + ".png");
                        if (path == null) path = texIndex.get("textures/item/chainmail_" + armorSlot + ".png");
                        if (path == null) path = texIndex.get("chainmail_" + armorSlot + ".png");
                        if (path != null) {
                            icon = loadTexture(src, path);
                            if (icon != null) iconPng = icon.png;
                        }
                    }
                    if (iconPng == null) {
                        result.skipped++;
                        if (result.skipped <= 5) {
                            say("warn", "No usable texture for model " + def.modelId + " (skipped)");
                        }
                        continue;
                    }

                    // Save icon texture
                    bedrockFiles.put("textures/items/" + itemName + ".png", iconPng);
                    JsonObject td = new JsonObject();
                    td.addProperty("textures", "textures/items/" + itemName);
                    textureData.add(itemName, td);

                    if (icon != null && icon.frames > 1) {
                        JsonObject fb = new JsonObject();
                        fb.addProperty("flipbook_texture", "textures/items/" + itemName);
                        fb.addProperty("atlas_tile", itemName);
                        fb.addProperty("ticks_per_frame", icon.ticks);
                        if (icon.frameOrder != null) {
                            JsonArray fr = new JsonArray();
                            for (int f : icon.frameOrder) fr.add(f);
                            fb.add("frames", fr);
                        }
                        flipbooks.add(fb);
                        animated++;
                    }

                    if (isArmor) {
                        if (ArmorConverter.processArmor(src, def, itemName, armorSlot, texIndex, bedrockFiles)) {
                            armors++;
                        }
                    } else if (want3d && has3d && BedrockModelBuilder.build3d(itemName, model, texByRef, isHandheld, bedrockFiles)) {
                        models3d++;
                    }
                    // Note: Flat 2D items (tools, swords, materials) do NOT require an attachable file.
                    // Minecraft Bedrock's native item rendering automatically extrudes the 2D icon in hand,
                    // respects display_handheld for tool swinging, and floats/rotates the item when dropped.

                    // Mapping entry matching smcconverter format_version 2
                    JsonObject entry = new JsonObject();
                    entry.addProperty("type", def.definition ? "definition" : "legacy");
                    if (def.definition) {
                        entry.addProperty("model", def.modelId);
                    } else {
                        entry.addProperty("custom_model_data", def.cmd);
                    }
                    entry.addProperty("bedrock_identifier", "s_mc:" + itemName);
                    entry.addProperty("display_name", def.displayName);
                    JsonObject bedrockOptions = new JsonObject();
                    bedrockOptions.addProperty("icon", itemName);
                    bedrockOptions.addProperty("allow_offhand", true);

                    if (isHandheld) {
                        bedrockOptions.addProperty("display_handheld", true);
                    }
                    if (isArmor) {
                        bedrockOptions.addProperty("creative_category", "equipment");
                        int prot = "chestplate".equals(armorSlot) ? 8 : "leggings".equals(armorSlot) ? 6 : 3;
                        bedrockOptions.addProperty("protection_value", prot);
                        bedrockOptions.addProperty("creative_group", "itemGroup.name." + armorSlot);
                    } else if (isHandheld) {
                        bedrockOptions.addProperty("creative_category", "equipment");
                    }
                    entry.add("bedrock_options", bedrockOptions);

                    if (!mappingItems.has("minecraft:" + def.baseItem)) {
                        mappingItems.add("minecraft:" + def.baseItem, new JsonArray());
                    }
                    mappingItems.getAsJsonArray("minecraft:" + def.baseItem).add(entry);

                    // Add translation for Bedrock item hover tooltip
                    langFile.append("item.s_mc:").append(itemName).append(".name=")
                            .append(def.displayName).append("\n");

                    count++;
                } catch (Exception ex) {
                    result.skipped++;
                    plugin.getLogger().warning("Skipped model " + def.modelId + ": " + ex);
                }
            }

            if (count == 0) {
                result.message = "No custom items found in pack";
                return result;
            }

            if (flipbooks.size() > 0) {
                bedrockFiles.put("textures/flipbook_textures.json", toBytes(flipbooks));
            }

            // Language files
            bedrockFiles.put("texts/en_US.lang", langFile.toString().getBytes(StandardCharsets.UTF_8));
            JsonArray langs = new JsonArray();
            langs.add("en_US");
            bedrockFiles.put("texts/languages.json", toBytes(langs));

            // Render controllers for all attachables (items, tools, armor)
            bedrockFiles.put("render_controllers/item_default.render_controllers.json", BedrockAnimationBuilder.buildDefaultRenderController());
            bedrockFiles.put("render_controllers/armor.render_controllers.json", BedrockAnimationBuilder.buildArmorRenderControllers());

            result.models3d = models3d;
            result.animated = animated;
            result.armors = armors;
            say("info", name + ": " + count + " items (" + models3d + " 3D, " + armors + " armors, "
                    + animated + " animated, " + result.skipped + " skipped)");
            say("info", "Packaging " + name + " for Bedrock...");

            // Bedrock pack metadata
            UUID headerId = UUID.nameUUIDFromBytes(("zaxconvert-header-" + name).getBytes(StandardCharsets.UTF_8));
            UUID moduleId = UUID.nameUUIDFromBytes(("zaxconvert-module-" + name).getBytes(StandardCharsets.UTF_8));
            JsonObject manifest = new JsonObject();
            manifest.addProperty("format_version", 2);
            JsonObject header = new JsonObject();
            header.addProperty("name", "ZaxConvert " + name);
            header.addProperty("description", "Converted from Java by ZaxConvert");
            header.addProperty("uuid", headerId.toString());
            header.add("version", intArray(1, 0, 0));
            header.add("min_engine_version", intArray(1, 21, 0));
            manifest.add("header", header);
            JsonObject module = new JsonObject();
            module.addProperty("type", "resources");
            module.addProperty("uuid", moduleId.toString());
            module.add("version", intArray(1, 0, 0));
            JsonArray modules = new JsonArray();
            modules.add(module);
            manifest.add("modules", modules);
            bedrockFiles.put("manifest.json", toBytes(manifest));

            // Item texture atlas definition for Bedrock
            JsonObject itemTexture = new JsonObject();
            itemTexture.addProperty("resource_pack_name", "zaxconvert_" + name);
            itemTexture.addProperty("texture_name", "atlas.items");
            itemTexture.add("texture_data", textureData);
            bedrockFiles.put("textures/item_texture.json", toBytes(itemTexture));

            // Write mcpack
            File mcpack = new File(outDir, "ZaxConvert_" + name + ".mcpack");
            try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(mcpack))) {
                for (Map.Entry<String, byte[]> e : bedrockFiles.entrySet()) {
                    zos.putNextEntry(new ZipEntry(e.getKey()));
                    zos.write(e.getValue());
                    zos.closeEntry();
                }
            }

            // Write Geyser mappings
            JsonObject mappings = new JsonObject();
            mappings.addProperty("format_version", 2);
            mappings.add("items", mappingItems);
            File mappingsFile = new File(outDir, "zaxconvert_" + name + ".json");
            Files.write(mappingsFile.toPath(), toBytes(mappings));

            result.mcpack = mcpack;
            result.mappings = mappingsFile;
            result.items = count;
            result.success = true;
            result.message = count + " items (" + models3d + " 3D, " + armors + " armors, "
                    + animated + " animated, " + result.skipped + " skipped)";

            if (config.getBoolean("geyser.auto-place-files", true)) {
                placeInGeyser(result);
            }
        } catch (Exception e) {
            result.message = "Failed: " + e.getMessage();
            plugin.getLogger().severe("Conversion of " + name + " failed: " + e);
            if (config.getBoolean("advanced.debug-mode")) {
                e.printStackTrace();
            }
        }
        return result;
    }

    private void placeInGeyser(Result result) throws IOException {
        File geyser = findGeyserFolder();
        if (geyser == null) {
            result.message += " (Geyser folder not found; files left in plugins/ZaxConvert/generated)";
            return;
        }
        File packs = new File(geyser, config.getString("geyser.resource-packs-folder", "packs"));
        File maps = new File(geyser, config.getString("geyser.custom-mappings-folder", "custom_mappings"));
        packs.mkdirs();
        maps.mkdirs();
        Files.copy(result.mcpack.toPath(), new File(packs, result.mcpack.getName()).toPath(),
                StandardCopyOption.REPLACE_EXISTING);
        Files.copy(result.mappings.toPath(), new File(maps, result.mappings.getName()).toPath(),
                StandardCopyOption.REPLACE_EXISTING);
        result.message += " -> installed to " + geyser.getName();
    }

    private File findGeyserFolder() {
        File pluginsDir = plugin.getDataFolder().getParentFile();
        List<String> candidates = new ArrayList<>();
        candidates.add(config.getString("geyser.folder", "Geyser-Spigot"));
        candidates.addAll(Arrays.asList("Geyser-Spigot", "Geyser-Velocity", "Geyser-BungeeCord", "Geyser"));
        for (String c : candidates) {
            File f = new File(pluginsDir, c);
            if (f.isDirectory()) return f;
        }
        return null;
    }

    private File findProviderPack(File pluginsDir, String provider) {
        List<String> paths = config.getStringList("plugin-support.paths." + provider);
        for (String p : paths) {
            File f = new File(pluginsDir, p);
            if (f.exists()) return f;
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Item definitions, models, textures
    // ------------------------------------------------------------------

    static class ItemDef {
        String baseItem;
        final Integer cmd;
        final String modelId;
        final boolean definition;
        String displayName;
        String armorTexture;

        ItemDef(String baseItem, Integer cmd, String modelId, boolean definition, String displayName, String armorTexture) {
            this.baseItem = baseItem;
            this.cmd = cmd;
            this.modelId = modelId;
            this.definition = definition;
            this.displayName = displayName;
            this.armorTexture = armorTexture;
        }

        String key() {
            return definition ? "def#" + modelId : baseItem + "#" + cmd;
        }
    }

    private static String getArmorSlot(String baseItem) {
        return getArmorSlot(baseItem, null, null);
    }

    private static String getArmorSlot(String baseItem, String modelId, String name) {
        String s = ((baseItem == null ? "" : baseItem) + " "
                + (modelId == null ? "" : modelId) + " "
                + (name == null ? "" : name)).toLowerCase(Locale.ROOT);
        if (s.contains("furniture") || s.contains("block/") || s.contains("statue") || s.contains("trophy")) {
            return null;
        }
        if (s.contains("helmet") || s.contains("cap") || s.contains("hood") || s.contains("crown")) return "helmet";
        if (s.contains("chestplate") || s.contains("tunic") || s.contains("jacket") || s.contains("shirt") || s.contains("robe")) return "chestplate";
        if (s.contains("leggings") || s.contains("pants")) return "leggings";
        if (s.contains("boots") || s.contains("shoes")) return "boots";
        return null;
    }

    private static boolean isHandheldItem(String baseItem, String modelId, String name) {
        String lower = ((baseItem == null ? "" : baseItem) + " "
                + (modelId == null ? "" : modelId) + " "
                + (name == null ? "" : name)).toLowerCase(Locale.ROOT);
        if (lower.contains("furniture") || lower.contains("block/")) return false;
        return lower.contains("sword") || lower.contains("pickaxe") || lower.contains("axe")
                || lower.contains("shovel") || lower.contains("hoe") || lower.contains("mace")
                || lower.contains("trident") || lower.contains("blade") || lower.contains("dagger")
                || lower.contains("spear") || lower.contains("staff") || lower.contains("wand")
                || lower.contains("hammer") || lower.contains("scythe") || lower.contains("halberd")
                || lower.contains("katana") || lower.contains("saber") || lower.contains("bow")
                || lower.contains("crossbow") || lower.contains("rod") || lower.contains("shears")
                || lower.contains("brush") || lower.contains("tool") || lower.contains("weapon");
    }

    private static String titleCase(String id) {
        String last = id.substring(Math.max(id.lastIndexOf('/'), id.lastIndexOf(':')) + 1).replace('_', ' ');
        StringBuilder sb = new StringBuilder();
        for (String w : last.split(" ")) {
            if (w.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return sb.toString();
    }

    private static String cleanName(String raw) {
        return raw.replaceAll("(?i)[\u00a7&][0-9a-fk-or]", "").replaceAll("<[^>]+>", "").trim();
    }

    private static String hash7(String s) {
        return UUID.nameUUIDFromBytes(s.getBytes(StandardCharsets.UTF_8)).toString().replace("-", "").substring(0, 7);
    }

    /** Resolves an item definition id (ns:name) to the model it points at; falls back to the id itself. */
    private String resolveDefModelId(PackSource src, String id) {
        String path = findDefPath(src, id);
        if (path != null) {
            JsonObject root = readJson(src, path);
            if (root != null && root.has("model")) {
                String m = anyModelId(root.get("model"));
                if (m != null) return m;
            }
        }
        return id;
    }

    /** Depth-first search for the first plain model reference in an item definition tree. */
    private String anyModelId(JsonElement el) {
        if (el == null) return null;
        if (el.isJsonArray()) {
            for (JsonElement e : el.getAsJsonArray()) {
                String r = anyModelId(e);
                if (r != null) return r;
            }
            return null;
        }
        if (!el.isJsonObject()) return null;
        JsonObject o = el.getAsJsonObject();
        String type = o.has("type") ? o.get("type").getAsString().replace("minecraft:", "") : "";
        if (type.equals("model") && o.has("model")) return o.get("model").getAsString();
        for (String key : new String[]{"model", "fallback", "on_false", "on_true", "cases", "entries"}) {
            if (o.has(key)) {
                String r = anyModelId(o.get(key));
                if (r != null) return r;
            }
        }
        return null;
    }

    /** Reads Nexo / ItemsAdder / Oraxen item configs: material, display name, model, custom_model_data, armor. */
    private void readProviderItems(String provider, PackSource src, List<ItemDef> defs, Set<String> seen,
                                   Map<String, String> cmdNames) {
        File pluginsDir = plugin.getDataFolder().getParentFile();
        List<File> searchDirs = new ArrayList<>();
        if (provider.equals("nexo")) {
            searchDirs.add(new File(pluginsDir, "Nexo/items"));
            searchDirs.add(new File(pluginsDir, "nexo/items"));
        } else if (provider.equals("oraxen")) {
            searchDirs.add(new File(pluginsDir, "Oraxen/items"));
            searchDirs.add(new File(pluginsDir, "oraxen/items"));
        } else if (provider.equals("itemsadder")) {
            searchDirs.add(new File(pluginsDir, "ItemsAdder/contents"));
            searchDirs.add(new File(pluginsDir, "ItemsAdder/data"));
            searchDirs.add(new File(pluginsDir, "itemsadder/contents"));
            searchDirs.add(new File(pluginsDir, "itemsadder/data"));
        }

        List<File> files = new ArrayList<>();
        for (File dir : searchDirs) {
            if (!dir.isDirectory()) continue;
            try (Stream<Path> s = Files.walk(dir.toPath())) {
                s.filter(p -> p.toString().endsWith(".yml")).forEach(p -> files.add(p.toFile()));
            } catch (IOException ignored) {}
        }
        if (files.isEmpty()) return;

        for (File f : files) {
            YamlConfiguration y;
            try {
                y = YamlConfiguration.loadConfiguration(f);
            } catch (Exception ignored) {
                continue;
            }

            // Items can be at root or under "items" section
            ConfigurationSection itemsSec = y.getConfigurationSection("items");
            Set<String> itemKeys = itemsSec != null ? itemsSec.getKeys(false) : y.getKeys(false);

            for (String key : itemKeys) {
                ConfigurationSection sec = itemsSec != null ? itemsSec.getConfigurationSection(key) : y.getConfigurationSection(key);
                if (sec == null) continue;

                String material = sec.getString("material", sec.getString("resource.material"));
                String slot = getArmorSlot(material, key, null);

                // If material is missing or not armor, but key indicates armor, use appropriate vanilla base
                if (material == null && slot != null) {
                    material = "chainmail_" + slot;
                } else if (material == null) {
                    continue;
                }
                String base = material.toLowerCase(Locale.ROOT);
                if (slot != null && !base.endsWith(slot)) {
                    base = "chainmail_" + slot;
                }

                ConfigurationSection pack = sec.getConfigurationSection("Pack");
                if (pack == null) pack = sec.getConfigurationSection("pack");

                String model = sec.getString("model", sec.getString("resource.model_path",
                        sec.getString("resource.model", sec.getString("item_model"))));
                if (model == null && pack != null) {
                    model = pack.getString("model", pack.getString("item_model"));
                }
                if (model == null) model = key;

                Integer cmd = null;
                if (pack != null && pack.contains("custom_model_data")) cmd = pack.getInt("custom_model_data");
                if (cmd == null && sec.contains("custom_model_data")) cmd = sec.getInt("custom_model_data");
                if (cmd == null && sec.contains("resource.model_id")) cmd = sec.getInt("resource.model_id");

                String rawName = sec.getString("display_name", sec.getString("itemname", sec.getString("displayname")));
                String name = rawName != null ? cleanName(rawName) : titleCase(key);
                if (name.isEmpty()) name = titleCase(key);

                // Detect armor texture
                String armorTex = sec.getString("armor.texture", sec.getString("armor_texture", sec.getString("resource.armor_texture")));
                if (armorTex == null && pack != null) {
                    armorTex = pack.getString("armor_texture", pack.getString("armor.texture"));
                }
                if (armorTex == null && slot != null) {
                    armorTex = key.replace("_helmet", "").replace("_chestplate", "")
                            .replace("_leggings", "").replace("_boots", "");
                }

                String id = model.contains(":") ? model : provider + ":" + model;
                boolean hasDef = (findDefPath(src, id) != null);
                boolean hasModel = (findModelPath(src, id) != null);

                if (hasDef || (!hasModel && cmd == null)) {
                    ItemDef d = new ItemDef(base, null, id, true, name, armorTex);
                    if (seen.add(d.key())) defs.add(d);
                } else if (cmd != null) {
                    ItemDef d = new ItemDef(base, cmd, id, false, name, armorTex);
                    if (seen.add(d.key())) defs.add(d);
                    cmdNames.put(base + "#" + cmd, name);
                } else {
                    ItemDef d = new ItemDef(base, null, id, true, name, armorTex);
                    if (seen.add(d.key())) defs.add(d);
                }
            }
        }
    }

    static class Model {
        List<JsonObject> elements;
        final Map<String, String> textures = new LinkedHashMap<>();
        final Map<String, JsonObject> display = new HashMap<>();
        String guiLight = null;
    }

    static class TexInfo {
        byte[] png;
        BufferedImage img;
        int fw, fh, frames = 1, ticks = 1;
        int[] frameOrder;
    }

    /** Collects custom_model_data entries from legacy overrides and 1.21.4 item definitions. */
    private List<ItemDef> collectDefinitions(PackSource src, String provider) throws IOException {
        List<ItemDef> defs = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Map<String, String> cmdNames = new HashMap<>();

        // Provider configs first: they know the real base material and display name
        readProviderItems(provider, src, defs, seen, cmdNames);

        // Legacy: assets/minecraft/models/item/<base>.json overrides
        for (String path : src.list("assets/minecraft/models/item/", ".json")) {
            JsonObject model = readJson(src, path);
            if (model == null || !model.has("overrides")) continue;
            String base = path.substring(path.lastIndexOf('/') + 1, path.length() - 5);
            for (JsonElement el : model.getAsJsonArray("overrides")) {
                JsonObject ov = el.getAsJsonObject();
                if (!ov.has("predicate") || !ov.has("model")) continue;
                JsonObject pred = ov.getAsJsonObject("predicate");
                if (!pred.has("custom_model_data")) continue;
                // Skip transient secondary animation states (e.g. shield blocking, bow pulling)
                // so the primary resting model is registered for the item and its GUI icon
                if (pred.has("blocking") && pred.get("blocking").getAsDouble() > 0) continue;
                if (pred.has("pulling") && pred.get("pulling").getAsDouble() > 0) continue;
                if (pred.has("pull") && pred.get("pull").getAsDouble() > 0) continue;

                int cmd = (int) pred.get("custom_model_data").getAsDouble();
                String mId = ov.get("model").getAsString();
                String armorTex = null;
                String slot = getArmorSlot(base, mId, null);
                if (slot != null) {
                    armorTex = mId.substring(Math.max(mId.lastIndexOf('/'), mId.lastIndexOf(':')) + 1)
                            .replace("_helmet", "").replace("_chestplate", "")
                            .replace("_leggings", "").replace("_boots", "");
                    if (getArmorSlot(base) == null) {
                        base = "chainmail_" + slot;
                    }
                }
                if (seen.add(base + "#" + cmd)) {
                    defs.add(new ItemDef(base, cmd, mId, false, null, armorTex));
                }
            }
        }

        // 1.21.4+: assets/minecraft/items/<base>.json range_dispatch on custom_model_data
        for (String path : src.list("assets/minecraft/items/", ".json")) {
            JsonObject root = readJson(src, path);
            if (root == null || !root.has("model")) continue;
            String base = path.substring(path.lastIndexOf('/') + 1, path.length() - 5);
            walkItemModel(root.get("model"), base, defs, seen);
        }

        // Item definitions of custom namespaces (item_model component), e.g. assets/nexo/items/foo.json
        Set<String> definedModels = new HashSet<>();
        for (ItemDef d : defs) {
            if (d.definition) definedModels.add(d.modelId);
        }
        for (String path : src.list("assets/", ".json")) {
            String[] parts = path.split("/");
            if (parts.length < 4 || !parts[2].equals("items") || parts[1].equals("minecraft")) continue;
            String rel = path.substring(("assets/" + parts[1] + "/items/").length(), path.length() - 5);
            String id = parts[1] + ":" + rel;
            if (definedModels.contains(id)) continue;
            String base = "paper";
            String armorTex = null;
            String slot = getArmorSlot(null, rel, null);
            if (slot != null) {
                base = "chainmail_" + slot;
                armorTex = rel.substring(Math.max(rel.lastIndexOf('/'), rel.lastIndexOf(':')) + 1)
                        .replace("_helmet", "").replace("_chestplate", "")
                        .replace("_leggings", "").replace("_boots", "");
            }
            ItemDef d = new ItemDef(base, null, id, true, null, armorTex);
            if (seen.add(d.key())) defs.add(d);
        }

        for (ItemDef d : defs) {
            if (d.displayName == null && !d.definition) d.displayName = cmdNames.get(d.key());
            if (d.displayName == null) d.displayName = titleCase(d.modelId);
        }
        return defs;
    }

    private void walkItemModel(JsonElement el, String base, List<ItemDef> defs, Set<String> seen) {
        if (el == null || !el.isJsonObject()) return;
        JsonObject o = el.getAsJsonObject();
        String type = o.has("type") ? o.get("type").getAsString().replace("minecraft:", "") : "";
        if (type.equals("range_dispatch") && o.has("property")
                && o.get("property").getAsString().replace("minecraft:", "").equals("custom_model_data")
                && o.has("entries")) {
            for (JsonElement ee : o.getAsJsonArray("entries")) {
                if (!ee.isJsonObject()) continue;
                JsonObject entry = ee.getAsJsonObject();
                if (!entry.has("threshold") || !entry.has("model")) continue;
                int cmd = (int) entry.get("threshold").getAsDouble();
                String modelId = firstModelId(entry.get("model"));
                if (modelId != null) {
                    String effBase = base;
                    String armorTex = null;
                    String slot = getArmorSlot(effBase, modelId, null);
                    if (slot != null) {
                        armorTex = modelId.substring(Math.max(modelId.lastIndexOf('/'), modelId.lastIndexOf(':')) + 1)
                                .replace("_helmet", "").replace("_chestplate", "")
                                .replace("_leggings", "").replace("_boots", "");
                        if (getArmorSlot(effBase) == null) {
                            effBase = "chainmail_" + slot;
                        }
                    }
                    if (seen.add(effBase + "#" + cmd)) {
                        defs.add(new ItemDef(effBase, cmd, modelId, false, null, armorTex));
                    }
                }
            }
        }
        for (String key : new String[]{"fallback", "on_true", "on_false"}) {
            if (o.has(key)) walkItemModel(o.get(key), base, defs, seen);
        }
        for (String key : new String[]{"cases", "entries"}) {
            if (o.has(key) && o.get(key).isJsonArray() && !type.equals("range_dispatch")) {
                for (JsonElement c : o.getAsJsonArray(key)) {
                    if (c.isJsonObject() && c.getAsJsonObject().has("model")) {
                        walkItemModel(c.getAsJsonObject().get("model"), base, defs, seen);
                    }
                }
            }
        }
    }

    /** Returns the model id of a plain "minecraft:model" node, following simple wrappers. */
    private String firstModelId(JsonElement el) {
        if (el == null || !el.isJsonObject()) return null;
        JsonObject o = el.getAsJsonObject();
        String type = o.has("type") ? o.get("type").getAsString().replace("minecraft:", "") : "";
        if (type.equals("model") && o.has("model")) return o.get("model").getAsString();
        for (String key : new String[]{"fallback", "on_false", "on_true"}) {
            String r = firstModelId(o.get(key));
            if (r != null) return r;
        }
        return null;
    }

    private Model resolveModel(PackSource src, String modelId) {
        if (modelId == null) return new Model();
        if (modelCache.containsKey(modelId)) {
            return modelCache.get(modelId);
        }
        Model m = new Model();
        String cur = modelId;
        for (int depth = 0; depth < 10 && cur != null; depth++) {
            String plain = cur.replace("minecraft:", "");
            String path = findModelPath(src, cur);
            JsonObject json = (path != null) ? readJson(src, path) : null;

            if (json == null) {
                if (m.elements == null) {
                    if (plain.equals("block/cube_all")) {
                        m.elements = Collections.singletonList(cubeElement("#all", "#all", "#all", "#all", "#all", "#all"));
                    } else if (plain.equals("block/cube")) {
                        m.elements = Collections.singletonList(cubeElement("#down", "#up", "#north", "#south", "#west", "#east"));
                    } else if (plain.equals("block/cube_column")) {
                        m.elements = Collections.singletonList(cubeElement("#end", "#end", "#side", "#side", "#side", "#side"));
                    }
                }
                break;
            }
            if (json.has("textures") && json.get("textures").isJsonObject()) {
                for (Map.Entry<String, JsonElement> e : json.getAsJsonObject("textures").entrySet()) {
                    if (e.getValue().isJsonPrimitive()) m.textures.putIfAbsent(e.getKey(), e.getValue().getAsString());
                }
            }
            if (json.has("display") && json.get("display").isJsonObject()) {
                for (Map.Entry<String, JsonElement> e : json.getAsJsonObject("display").entrySet()) {
                    if (e.getValue().isJsonObject()) m.display.putIfAbsent(e.getKey(), e.getValue().getAsJsonObject());
                }
            }
            if (m.guiLight == null && json.has("gui_light")) {
                m.guiLight = json.get("gui_light").getAsString();
            }
            if (m.elements == null && json.has("elements") && json.get("elements").isJsonArray()) {
                m.elements = new ArrayList<>();
                for (JsonElement el : json.getAsJsonArray("elements")) {
                    if (el.isJsonObject()) m.elements.add(el.getAsJsonObject());
                }
            }
            cur = json.has("parent") ? json.get("parent").getAsString() : null;
        }
        modelCache.put(modelId, m);
        return m;
    }

    private static JsonObject cubeElement(String down, String up, String north, String south, String west, String east) {
        JsonObject el = new JsonObject();
        el.add("from", intArray(0, 0, 0));
        el.add("to", intArray(16, 16, 16));
        JsonObject faces = new JsonObject();
        String[][] f = {{"down", down}, {"up", up}, {"north", north}, {"south", south}, {"west", west}, {"east", east}};
        for (String[] pair : f) {
            JsonObject face = new JsonObject();
            face.addProperty("texture", pair[1]);
            faces.add(pair[0], face);
        }
        el.add("faces", faces);
        return el;
    }

    static String resolveRefPublic(Map<String, String> textures, String ref) {
        return resolveRef(textures, ref);
    }

    /** Index of model paths in pack for instant lookup across namespaces and subfolders. */
    private Map<String, String> buildModelIndex(PackSource src) {
        Map<String, String> idx = new HashMap<>();
        for (String p : src.list("assets/", ".json")) {
            String[] parts = p.split("/");
            if (parts.length >= 4 && parts[2].equals("models")) {
                String ns = parts[1].toLowerCase(Locale.ROOT);
                String sub = p.substring(("assets/" + parts[1] + "/models/").length(), p.length() - 5).toLowerCase(Locale.ROOT);
                idx.putIfAbsent(ns + ":" + sub, p);
                idx.putIfAbsent(sub, p);
                if (sub.startsWith("item/")) {
                    idx.putIfAbsent(ns + ":" + sub.substring(5), p);
                    idx.putIfAbsent(sub.substring(5), p);
                } else if (sub.startsWith("block/")) {
                    idx.putIfAbsent(ns + ":" + sub.substring(6), p);
                    idx.putIfAbsent(sub.substring(6), p);
                } else {
                    idx.putIfAbsent(ns + ":item/" + sub, p);
                    idx.putIfAbsent(ns + ":block/" + sub, p);
                }
            }
        }
        return idx;
    }

    /** Index of texture paths in pack for instant lookup. */
    private Map<String, String> buildTextureIndex(PackSource src) {
        Map<String, String> idx = new HashMap<>();
        for (String p : src.list("assets/", ".png")) {
            String[] parts = p.split("/");
            String fileName = parts[parts.length - 1].toLowerCase(Locale.ROOT);
            String simpleName = fileName.endsWith(".png") ? fileName.substring(0, fileName.length() - 4) : fileName;
            idx.putIfAbsent(fileName, p);
            idx.putIfAbsent(simpleName, p);

            if (parts.length >= 4 && parts[2].equals("textures")) {
                String ns = parts[1].toLowerCase(Locale.ROOT);
                String sub = p.substring(("assets/" + parts[1] + "/textures/").length(), p.length() - 4).toLowerCase(Locale.ROOT);
                idx.putIfAbsent(ns + ":" + sub, p);
                idx.putIfAbsent(sub, p);
                idx.putIfAbsent(ns + ":" + sub + ".png", p);
                idx.putIfAbsent(sub + ".png", p);
                if (sub.startsWith("item/")) {
                    idx.putIfAbsent(ns + ":" + sub.substring(5), p);
                    idx.putIfAbsent(sub.substring(5), p);
                    idx.putIfAbsent("textures/item/" + simpleName + ".png", p);
                    idx.putIfAbsent("textures/items/" + simpleName + ".png", p);
                } else if (sub.startsWith("block/")) {
                    idx.putIfAbsent(ns + ":" + sub.substring(6), p);
                    idx.putIfAbsent(sub.substring(6), p);
                    idx.putIfAbsent("textures/block/" + simpleName + ".png", p);
                    idx.putIfAbsent("textures/blocks/" + simpleName + ".png", p);
                }
                idx.putIfAbsent("textures/" + sub + ".png", p);
            }
        }
        return idx;
    }

    private String findModelPath(PackSource src, String id) {
        if (id == null) return null;
        String clean = id.toLowerCase(Locale.ROOT);
        String found = modelIndex.get(clean);
        if (found != null && src.exists(found)) return found;

        String[] s = split(id);
        String[] candidates = {
                "assets/" + s[0] + "/models/" + s[1] + ".json",
                "assets/" + s[0] + "/models/item/" + s[1] + ".json",
                "assets/" + s[0] + "/models/block/" + s[1] + ".json",
                "assets/" + s[0] + "/models/furniture/" + s[1] + ".json",
                "assets/" + s[0] + "/models/custom/" + s[1] + ".json"
        };
        for (String c : candidates) {
            if (src.exists(c)) return c;
        }
        return null;
    }

    private String findDefPath(PackSource src, String id) {
        if (id == null) return null;
        String[] s = split(id);
        String c = "assets/" + s[0] + "/items/" + s[1] + ".json";
        return src.exists(c) ? c : null;
    }

    /** Loads every distinct texture referenced by the model faces. */
    private Map<String, TexInfo> gatherTextures(PackSource src, Model model) throws IOException {
        Map<String, TexInfo> texByRef = new LinkedHashMap<>();
        for (JsonObject el : model.elements) {
            if (!el.has("faces")) continue;
            for (Map.Entry<String, JsonElement> fe : el.getAsJsonObject("faces").entrySet()) {
                JsonObject face = fe.getValue().getAsJsonObject();
                if (!face.has("texture")) continue;
                String ref = resolveRef(model.textures, face.get("texture").getAsString());
                if (ref == null || texByRef.containsKey(ref)) continue;
                TexInfo t = loadTexture(src, ref);
                if (t != null) texByRef.put(ref, t);
            }
        }
        return texByRef;
    }

    /** Renders a 3D model to a PNG icon using its display.gui transform. */
    private byte[] renderIcon(Model model, Map<String, TexInfo> texByRef) throws IOException {
        if (texByRef.isEmpty()) return null;
        Map<String, BufferedImage> frames = new HashMap<>();
        for (Map.Entry<String, TexInfo> e : texByRef.entrySet()) {
            TexInfo t = e.getValue();
            frames.put(e.getKey(), t.img.getSubimage(0, 0, Math.min(t.fw, t.img.getWidth()), Math.min(t.fh, t.img.getHeight())));
        }
        int size = Math.max(16, Math.min(256, config.getInt("conversion.icon-size", 64)));
        BufferedImage img = ModelRenderer.render(model.elements, model.textures, frames,
                model.display.get("gui"), "front".equals(model.guiLight), size);
        if (img == null) return null;
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", bos);
        return bos.toByteArray();
    }

    /** Flat (builtin/generated) icon: layer0..N composited, or the first usable texture. */
    private TexInfo loadFlatIcon(PackSource src, Model model) throws IOException {
        List<String> layerKeys = new ArrayList<>();
        for (String k : model.textures.keySet()) {
            if (k.startsWith("layer")) layerKeys.add(k);
        }
        layerKeys.sort(Comparator.comparing(k -> {
            try {
                return Integer.parseInt(k.substring(5));
            } catch (NumberFormatException e) {
                return 99;
            }
        }));
        List<TexInfo> layers = new ArrayList<>();
        for (String k : layerKeys) {
            String ref = resolveRef(model.textures, "#" + k);
            TexInfo t = ref == null ? null : loadTexture(src, ref);
            if (t != null) layers.add(t);
        }
        if (layers.isEmpty()) {
            for (String k : model.textures.keySet()) {
                if (k.equals("particle")) continue;
                String ref = resolveRef(model.textures, "#" + k);
                TexInfo t = ref == null ? null : loadTexture(src, ref);
                if (t != null) {
                    layers.add(t);
                    break;
                }
            }
        }
        if (layers.isEmpty() && model.textures.containsKey("particle")) {
            String ref = resolveRef(model.textures, "#particle");
            TexInfo t = ref == null ? null : loadTexture(src, ref);
            if (t != null) layers.add(t);
        }
        if (layers.isEmpty()) return null;
        if (layers.size() == 1) return layers.get(0);

        int maxW = 0, maxH = 0;
        for (TexInfo t : layers) {
            maxW = Math.max(maxW, t.fw);
            maxH = Math.max(maxH, t.fh);
        }
        BufferedImage out = new BufferedImage(maxW, maxH, BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = out.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        for (TexInfo t : layers) {
            g.drawImage(t.img.getSubimage(0, 0, t.fw, t.fh), 0, 0, maxW, maxH, null);
        }
        g.dispose();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ImageIO.write(out, "png", bos);
        TexInfo res = new TexInfo();
        res.png = bos.toByteArray();
        res.img = out;
        res.fw = maxW;
        res.fh = maxH;
        return res;
    }

    private static String resolveRef(Map<String, String> textures, String ref) {
        for (int i = 0; i < 10 && ref != null; i++) {
            if (!ref.startsWith("#")) return ref;
            ref = textures.get(ref.substring(1));
        }
        return null;
    }

    /** Loads a PNG and its optional .mcmeta animation info. */
    private TexInfo loadTexture(PackSource src, String ref) throws IOException {
        if (ref == null) return null;
        String clean = ref.toLowerCase(Locale.ROOT);
        if (textureCache.containsKey(clean)) {
            return textureCache.get(clean);
        }
        String path = texIndex.get(clean);
        if (path == null) path = texturePath(ref);
        if (!src.exists(path)) {
            String[] parts = split(ref);
            path = texIndex.get("textures/" + parts[1].toLowerCase(Locale.ROOT) + ".png");
        }
        if (path == null || !src.exists(path)) return null;

        byte[] png = src.read(path);
        if (png == null) return null;
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
        if (img == null) return null;

        TexInfo t = new TexInfo();
        t.png = png;
        t.img = img;
        t.fw = img.getWidth();
        t.fh = img.getHeight();

        JsonObject meta = readJson(src, path + ".mcmeta");
        if (meta != null && meta.has("animation") && meta.get("animation").isJsonObject()) {
            JsonObject anim = meta.getAsJsonObject("animation");
            if (anim.has("width")) t.fw = anim.get("width").getAsInt();
            t.fh = anim.has("height") ? anim.get("height").getAsInt() : t.fw;
            if (t.fw <= 0 || t.fh <= 0 || t.fh > img.getHeight() || t.fw > img.getWidth()) {
                t.fw = img.getWidth();
                t.fh = img.getHeight();
                textureCache.put(clean, t);
                return t;
            }
            t.frames = Math.max(1, img.getHeight() / t.fh);
            t.ticks = anim.has("frametime") ? Math.max(1, anim.get("frametime").getAsInt()) : 1;
            if (anim.has("frames") && anim.get("frames").isJsonArray()) {
                List<Integer> order = new ArrayList<>();
                for (JsonElement fe : anim.getAsJsonArray("frames")) {
                    if (fe.isJsonPrimitive()) {
                        order.add(fe.getAsInt());
                    } else if (fe.isJsonObject() && fe.getAsJsonObject().has("index")) {
                        order.add(fe.getAsJsonObject().get("index").getAsInt());
                    }
                }
                if (!order.isEmpty()) {
                    t.frameOrder = order.stream().mapToInt(Integer::intValue).toArray();
                }
            }
        }
        textureCache.put(clean, t);
        return t;
    }

    // ------------------------------------------------------------------
    // Math, JSON, and Pack Source Utilities
    // ------------------------------------------------------------------

    static double[] doubles(JsonArray a) {
        double[] d = new double[a.size()];
        for (int i = 0; i < d.length; i++) d[i] = a.get(i).getAsDouble();
        return d;
    }

    private static JsonArray dblArray(double... v) {
        JsonArray a = new JsonArray();
        for (double d : v) a.add(d);
        return a;
    }

    private static String[] split(String id) {
        int i = id.indexOf(':');
        return i < 0 ? new String[]{"minecraft", id} : new String[]{id.substring(0, i), id.substring(i + 1)};
    }

    private static String texturePath(String id) {
        String[] s = split(id);
        return "assets/" + s[0] + "/textures/" + s[1] + ".png";
    }

    private static String uniqueName(Set<String> used, String raw) {
        String base = raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
        String name = base;
        int i = 2;
        while (!used.add(name)) {
            name = base + "_" + i++;
        }
        return name;
    }

    private static JsonArray intArray(int... v) {
        JsonArray a = new JsonArray();
        for (int i : v) a.add(i);
        return a;
    }

    static byte[] toBytes(JsonElement o) {
        return new GsonBuilder().setPrettyPrinting().create().toJson(o).getBytes(StandardCharsets.UTF_8);
    }

    private static JsonObject readJson(PackSource src, String path) {
        try {
            byte[] data = src.read(path);
            if (data == null) return null;
            JsonElement el = JsonParser.parseString(new String(data, StandardCharsets.UTF_8));
            return el.isJsonObject() ? el.getAsJsonObject() : null;
        } catch (Exception e) {
            return null; // Malformed JSON in the pack; skip it
        }
    }

    public void reload() {
        plugin.reloadConfig();
        this.config = plugin.getConfig();
        plugin.getLogger().info("ResourcePackConverter configuration reloaded");
    }

    // ------------------------------------------------------------------
    // Pack sources (zip or folder)
    // ------------------------------------------------------------------

    interface PackSource extends Closeable {
        byte[] read(String path) throws IOException;

        boolean exists(String path);

        List<String> list(String prefix, String suffix);

        static PackSource open(File f) throws IOException {
            if (f.isDirectory()) return new DirSource(f.toPath());
            return new ZipSource(new ZipFile(f));
        }
    }

    private static class DirSource implements PackSource {
        private final Path root;

        DirSource(Path root) {
            this.root = root;
        }

        public byte[] read(String path) throws IOException {
            Path p = root.resolve(path);
            return Files.isRegularFile(p) ? Files.readAllBytes(p) : null;
        }

        public boolean exists(String path) {
            return Files.isRegularFile(root.resolve(path));
        }

        public List<String> list(String prefix, String suffix) {
            List<String> out = new ArrayList<>();
            Path dir = root.resolve(prefix);
            if (!Files.isDirectory(dir)) return out;
            try (Stream<Path> s = Files.walk(dir)) {
                s.filter(Files::isRegularFile)
                        .map(p -> root.relativize(p).toString().replace('\\', '/'))
                        .filter(p -> p.endsWith(suffix))
                        .forEach(out::add);
            } catch (IOException ignored) {
            }
            return out;
        }

        public void close() {
        }
    }

    private static class ZipSource implements PackSource {
        private final ZipFile zip;
        private final Map<String, ZipEntry> entryMap = new HashMap<>();

        ZipSource(ZipFile zip) {
            this.zip = zip;
            Enumeration<? extends ZipEntry> en = zip.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                String name = e.getName().replace('\\', '/').toLowerCase(Locale.ROOT);
                entryMap.putIfAbsent(name, e);
            }
        }

        public byte[] read(String path) throws IOException {
            String clean = path.replace('\\', '/').toLowerCase(Locale.ROOT);
            ZipEntry e = entryMap.get(clean);
            if (e == null) return null;
            try (InputStream in = zip.getInputStream(e)) {
                return in.readAllBytes();
            }
        }

        public boolean exists(String path) {
            String clean = path.replace('\\', '/').toLowerCase(Locale.ROOT);
            return entryMap.containsKey(clean);
        }

        public List<String> list(String prefix, String suffix) {
            List<String> out = new ArrayList<>();
            String cleanPrefix = prefix.toLowerCase(Locale.ROOT);
            String cleanSuffix = suffix.toLowerCase(Locale.ROOT);
            for (Map.Entry<String, ZipEntry> e : entryMap.entrySet()) {
                String k = e.getKey();
                if (!e.getValue().isDirectory() && k.startsWith(cleanPrefix) && k.endsWith(cleanSuffix)) {
                    out.add(e.getValue().getName());
                }
            }
            return out;
        }

        public void close() throws IOException {
            zip.close();
        }
    }
}