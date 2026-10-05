package com.zaxconvert.conversion;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.GsonBuilder;
import com.zaxconvert.ZaxConvert;
import org.bukkit.configuration.file.FileConfiguration;

import java.awt.image.BufferedImage;
import java.io.*;
import javax.imageio.ImageIO;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Converts Java resource packs (Nexo / ItemsAdder / Oraxen) into a Bedrock
 * .mcpack plus a Geyser custom item mappings file.
 *
 * Items are converted from "custom_model_data" overrides in
 * assets/minecraft/models/item/*.json. Each override becomes a Geyser custom
 * item whose icon is the model's 2D texture.
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
        public String message = "";
        public File mcpack;
        public File mappings;

        Result(String provider) {
            this.provider = provider;
        }
    }

    /** Scans all enabled providers and converts whatever is found. */
    public List<Result> convertAllProviders() {
        List<Result> results = new ArrayList<>();
        File pluginsDir = plugin.getDataFolder().getParentFile();
        for (String provider : new String[]{"nexo", "itemsadder", "oraxen"}) {
            if (!config.getBoolean("plugin-support." + provider + "-enabled", true)) {
                continue;
            }
            File pack = findProviderPack(pluginsDir, provider);
            if (pack == null) {
                Result r = new Result(provider);
                r.message = "No pack found (provider not installed or pack not generated yet)";
                results.add(r);
                continue;
            }
            results.add(convertPack(provider, pack));
        }
        return results;
    }

    /** Converts one manually specified pack (zip or folder). */
    public Result convertPack(String name, File pack) {
        Result result = new Result(name);
        plugin.getLogger().info("Converting '" + name + "' from " + pack.getPath());
        try (PackSource src = PackSource.open(pack)) {
            File outDir = new File(plugin.getDataFolder(), "generated");
            outDir.mkdirs();
            Map<String, byte[]> bedrockFiles = new LinkedHashMap<>();
            JsonObject mappingItems = new JsonObject();
            Set<String> usedNames = new HashSet<>();
            JsonObject textureData = new JsonObject();
            JsonArray flipbooks = new JsonArray();
            boolean want3d = config.getBoolean("conversion.convert-3d-models", true);

            int count = 0;
            int models3d = 0;
            int animated = 0;
            for (ItemDef def : collectDefinitions(src)) {
                try {
                    Model model = resolveModel(src, def.modelId);
                    String itemName = uniqueName(usedNames, name + "_" + def.modelId);

                    // Icon texture: 2D layer0 or the first texture of the model
                    String iconRef = null;
                    if (model.textures.containsKey("layer0")) {
                        iconRef = resolveRef(model.textures, "#layer0");
                    }
                    if (iconRef == null) {
                        for (String key : model.textures.keySet()) {
                            iconRef = resolveRef(model.textures, "#" + key);
                            if (iconRef != null && src.exists(texturePath(iconRef))) break;
                            iconRef = null;
                        }
                    }
                    TexInfo icon = iconRef == null ? null : loadTexture(src, iconRef);
                    if (icon == null) continue;

                    bedrockFiles.put("textures/items/" + itemName + ".png", icon.png);
                    JsonObject td = new JsonObject();
                    td.addProperty("textures", "textures/items/" + itemName);
                    textureData.add(itemName, td);
                    if (icon.frames > 1) {
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

                    if (want3d && model.elements != null && !model.elements.isEmpty()
                            && build3d(src, itemName, model, bedrockFiles)) {
                        models3d++;
                    }

                    JsonObject entry = new JsonObject();
                    entry.addProperty("name", itemName);
                    entry.addProperty("custom_model_data", def.cmd);
                    entry.addProperty("icon", itemName);
                    entry.addProperty("allow_offhand", true);
                    if (!mappingItems.has("minecraft:" + def.baseItem)) {
                        mappingItems.add("minecraft:" + def.baseItem, new JsonArray());
                    }
                    mappingItems.getAsJsonArray("minecraft:" + def.baseItem).add(entry);
                    count++;
                } catch (Exception ex) {
                    plugin.getLogger().warning("Skipped model " + def.modelId + ": " + ex);
                }
            }

            if (count == 0) {
                result.message = "No custom_model_data items found in pack";
                return result;
            }
            if (flipbooks.size() > 0) {
                bedrockFiles.put("textures/flipbook_textures.json", toBytes(flipbooks));
            }
            plugin.getLogger().info(name + ": " + count + " items, " + models3d + " 3D models, "
                    + animated + " animated icons");

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
            mappings.addProperty("format_version", 1);
            mappings.add("items", mappingItems);
            File mappingsFile = new File(outDir, "zaxconvert_" + name + ".json");
            Files.write(mappingsFile.toPath(), toBytes(mappings));

            result.mcpack = mcpack;
            result.mappings = mappingsFile;
            result.items = count;
            result.success = true;
            result.message = count + " items converted";

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

    /** Follows a model (and its parents) to find a 2D texture file path inside the pack. */
    private String resolveTexture(PackSource src, String modelId) {
        String current = modelId;
        for (int depth = 0; depth < 5 && current != null; depth++) {
            JsonObject model = readJson(src, modelPath(current));
            if (model == null) return null;
            if (model.has("textures") && model.get("textures").isJsonObject()) {
                JsonObject tex = model.getAsJsonObject("textures");
                String ref = null;
                if (tex.has("layer0")) {
                    ref = tex.get("layer0").getAsString();
                } else {
                    for (Map.Entry<String, JsonElement> e : tex.entrySet()) {
                        if (e.getValue().isJsonPrimitive() && !e.getValue().getAsString().startsWith("#")) {
                            ref = e.getValue().getAsString();
                            break;
                        }
                    }
                }
                if (ref != null && !ref.startsWith("#")) {
                    String path = texturePath(ref);
                    if (src.exists(path)) return path;
                }
            }
            current = model.has("parent") ? model.get("parent").getAsString() : null;
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Item definitions, models, textures
    // ------------------------------------------------------------------

    private static class ItemDef {
        final String baseItem;
        final int cmd;
        final String modelId;

        ItemDef(String baseItem, int cmd, String modelId) {
            this.baseItem = baseItem;
            this.cmd = cmd;
            this.modelId = modelId;
        }
    }

    private static class Model {
        List<JsonObject> elements;
        final Map<String, String> textures = new LinkedHashMap<>();
    }

    private static class TexInfo {
        byte[] png;
        BufferedImage img;
        int fw, fh, frames = 1, ticks = 1;
        int[] frameOrder;
    }

    /** Collects custom_model_data entries from legacy overrides and 1.21.4 item definitions. */
    private List<ItemDef> collectDefinitions(PackSource src) throws IOException {
        List<ItemDef> defs = new ArrayList<>();
        Set<String> seen = new HashSet<>();

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
                int cmd = (int) pred.get("custom_model_data").getAsDouble();
                if (seen.add(base + "#" + cmd)) {
                    defs.add(new ItemDef(base, cmd, ov.get("model").getAsString()));
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
                if (modelId != null && seen.add(base + "#" + cmd)) {
                    defs.add(new ItemDef(base, cmd, modelId));
                }
            }
        }
        // Descend into nested models (conditions, selects, fallbacks)
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
        Model m = new Model();
        String cur = modelId;
        for (int depth = 0; depth < 10 && cur != null; depth++) {
            String plain = cur.replace("minecraft:", "");
            JsonObject json = readJson(src, modelPath(cur));
            if (json == null) {
                // Vanilla parents that are not shipped in the pack
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
            if (m.elements == null && json.has("elements") && json.get("elements").isJsonArray()) {
                m.elements = new ArrayList<>();
                for (JsonElement el : json.getAsJsonArray("elements")) {
                    if (el.isJsonObject()) m.elements.add(el.getAsJsonObject());
                }
            }
            cur = json.has("parent") ? json.get("parent").getAsString() : null;
        }
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

    private static String resolveRef(Map<String, String> textures, String ref) {
        for (int i = 0; i < 10 && ref != null; i++) {
            if (!ref.startsWith("#")) return ref;
            ref = textures.get(ref.substring(1));
        }
        return null;
    }

    /** Loads a PNG and its optional .mcmeta animation info. */
    private TexInfo loadTexture(PackSource src, String ref) throws IOException {
        String path = texturePath(ref);
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
        return t;
    }

    // ------------------------------------------------------------------
    // 3D: Java model -> Bedrock geometry + attachable
    // ------------------------------------------------------------------

    private boolean build3d(PackSource src, String itemName, Model model, Map<String, byte[]> out) throws IOException {
        // Gather the distinct textures used by faces
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
        if (texByRef.isEmpty()) return false;

        // Texture layout: single texture is used as-is (keeps animation); several are stacked into an atlas
        Map<String, int[]> layout = new HashMap<>(); // ref -> {yOffset, frameW, frameH}
        int atlasW;
        int atlasH;
        TexInfo animatedTex = null;
        byte[] textureBytes;
        if (texByRef.size() == 1) {
            TexInfo t = texByRef.values().iterator().next();
            layout.put(texByRef.keySet().iterator().next(), new int[]{0, t.fw, t.fh});
            atlasW = t.fw;
            atlasH = t.fh;
            textureBytes = t.png;
            if (t.frames > 1) animatedTex = t;
        } else {
            atlasW = 0;
            atlasH = 0;
            for (TexInfo t : texByRef.values()) {
                atlasW = Math.max(atlasW, t.fw);
                atlasH += t.fh;
            }
            BufferedImage atlas = new BufferedImage(atlasW, atlasH, BufferedImage.TYPE_INT_ARGB);
            int y = 0;
            for (Map.Entry<String, TexInfo> e : texByRef.entrySet()) {
                TexInfo t = e.getValue();
                atlas.getGraphics().drawImage(t.img.getSubimage(0, 0, t.fw, t.fh), 0, y, null);
                layout.put(e.getKey(), new int[]{y, t.fw, t.fh});
                y += t.fh;
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            ImageIO.write(atlas, "png", bos);
            textureBytes = bos.toByteArray();
        }

        // Cubes. Java x is mirrored for Bedrock: x' = 8 - x, z' = z - 8
        JsonArray cubes = new JsonArray();
        for (JsonObject el : model.elements) {
            if (!el.has("from") || !el.has("to") || !el.has("faces")) continue;
            double[] from = doubles(el.getAsJsonArray("from"));
            double[] to = doubles(el.getAsJsonArray("to"));
            JsonObject cube = new JsonObject();
            cube.add("origin", dblArray(8 - to[0], from[1], from[2] - 8));
            cube.add("size", dblArray(to[0] - from[0], to[1] - from[1], to[2] - from[2]));

            if (el.has("rotation") && el.get("rotation").isJsonObject()) {
                JsonObject rot = el.getAsJsonObject("rotation");
                double angle = rot.has("angle") ? rot.get("angle").getAsDouble() : 0;
                String axis = rot.has("axis") ? rot.get("axis").getAsString() : "y";
                double[] o = rot.has("origin") ? doubles(rot.getAsJsonArray("origin")) : new double[]{8, 8, 8};
                cube.add("pivot", dblArray(8 - o[0], o[1], o[2] - 8));
                switch (axis) {
                    case "x":
                        cube.add("rotation", dblArray(angle, 0, 0));
                        break;
                    case "y":
                        cube.add("rotation", dblArray(0, -angle, 0));
                        break;
                    default:
                        cube.add("rotation", dblArray(0, 0, -angle));
                }
            }

            JsonObject uvs = new JsonObject();
            for (Map.Entry<String, JsonElement> fe : el.getAsJsonObject("faces").entrySet()) {
                String dir = fe.getKey();
                JsonObject face = fe.getValue().getAsJsonObject();
                if (!face.has("texture")) continue;
                String ref = resolveRef(model.textures, face.get("texture").getAsString());
                int[] lay = ref == null ? null : layout.get(ref);
                if (lay == null) continue;

                double[] uv;
                if (face.has("uv")) {
                    uv = doubles(face.getAsJsonArray("uv"));
                } else {
                    switch (dir) {
                        case "north":
                        case "south":
                            uv = new double[]{from[0], 16 - to[1], to[0], 16 - from[1]};
                            break;
                        case "east":
                        case "west":
                            uv = new double[]{from[2], 16 - to[1], to[2], 16 - from[1]};
                            break;
                        default:
                            uv = new double[]{from[0], from[2], to[0], to[2]};
                    }
                }
                double sx = lay[1] / 16.0;
                double sy = lay[2] / 16.0;
                double u1 = uv[0] * sx, v1 = lay[0] + uv[1] * sy;
                double u2 = uv[2] * sx, v2 = lay[0] + uv[3] * sy;

                // X mirror: swap east/west and flip horizontally
                String bedrockDir = dir.equals("east") ? "west" : dir.equals("west") ? "east" : dir;
                JsonObject f = new JsonObject();
                f.add("uv", dblArray(u2, v1));
                f.add("uv_size", dblArray(-(u2 - u1), v2 - v1));
                uvs.add(bedrockDir, f);
            }
            cube.add("uv", uvs);
            cubes.add(cube);
        }
        if (cubes.size() == 0) return false;

        String geoId = "geometry.zaxconvert." + itemName;
        JsonObject bone = new JsonObject();
        bone.addProperty("name", "item");
        bone.add("pivot", intArray(0, 0, 0));
        bone.add("cubes", cubes);
        JsonArray bones = new JsonArray();
        bones.add(bone);
        JsonObject desc = new JsonObject();
        desc.addProperty("identifier", geoId);
        desc.addProperty("texture_width", atlasW);
        desc.addProperty("texture_height", atlasH);
        desc.addProperty("visible_bounds_width", 4);
        desc.addProperty("visible_bounds_height", 4);
        desc.add("visible_bounds_offset", intArray(0, 1, 0));
        JsonObject geo = new JsonObject();
        geo.add("description", desc);
        geo.add("bones", bones);
        JsonArray geoArr = new JsonArray();
        geoArr.add(geo);
        JsonObject geoRoot = new JsonObject();
        geoRoot.addProperty("format_version", "1.12.0");
        geoRoot.add("minecraft:geometry", geoArr);
        out.put("models/entity/" + itemName + ".geo.json", toBytes(geoRoot));
        out.put("textures/zaxconvert/" + itemName + ".png", textureBytes);

        // Render controller (animated textures use uv_anim to step through frames)
        String controller = "controller.render.item_default";
        if (animatedTex != null) {
            controller = "controller.render.zaxconvert_" + itemName;
            int frames = animatedTex.frames;
            JsonObject rc = new JsonObject();
            rc.addProperty("geometry", "Geometry.default");
            JsonArray mats = new JsonArray();
            JsonObject mat = new JsonObject();
            mat.addProperty("*", "Material.default");
            mats.add(mat);
            rc.add("materials", mats);
            JsonArray texs = new JsonArray();
            texs.add("Texture.default");
            rc.add("textures", texs);
            JsonObject uvAnim = new JsonObject();
            JsonArray offset = new JsonArray();
            offset.add("0.0");
            offset.add("math.floor(query.life_time * 20 / " + animatedTex.ticks + ") / " + frames);
            uvAnim.add("offset", offset);
            JsonArray scale = new JsonArray();
            scale.add("1.0");
            scale.add(String.valueOf(1.0 / frames));
            uvAnim.add("scale", scale);
            rc.add("uv_anim", uvAnim);
            JsonObject controllers = new JsonObject();
            controllers.add(controller, rc);
            JsonObject rcRoot = new JsonObject();
            rcRoot.addProperty("format_version", "1.10.0");
            rcRoot.add("render_controllers", controllers);
            out.put("render_controllers/" + itemName + ".render_controllers.json", toBytes(rcRoot));
        }

        // Attachable (Geyser custom items use the geyser_custom: namespace)
        JsonObject aDesc = new JsonObject();
        aDesc.addProperty("identifier", "geyser_custom:" + itemName);
        JsonObject materials = new JsonObject();
        materials.addProperty("default", "entity_alphatest");
        materials.addProperty("enchanted", "entity_alphatest_glint");
        aDesc.add("materials", materials);
        JsonObject aTex = new JsonObject();
        aTex.addProperty("default", "textures/zaxconvert/" + itemName);
        aTex.addProperty("enchanted", "textures/misc/enchanted_item_glint");
        aDesc.add("textures", aTex);
        JsonObject aGeo = new JsonObject();
        aGeo.addProperty("default", geoId);
        aDesc.add("geometry", aGeo);
        JsonArray rcs = new JsonArray();
        rcs.add(controller);
        aDesc.add("render_controllers", rcs);
        JsonObject att = new JsonObject();
        att.add("description", aDesc);
        JsonObject attRoot = new JsonObject();
        attRoot.addProperty("format_version", "1.10.0");
        attRoot.add("minecraft:attachable", att);
        out.put("attachables/" + itemName + ".json", toBytes(attRoot));
        return true;
    }

    private static double[] doubles(JsonArray a) {
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

    private static String modelPath(String id) {
        String[] s = split(id);
        return "assets/" + s[0] + "/models/" + s[1] + ".json";
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

    private static byte[] toBytes(JsonElement o) {
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

    private interface PackSource extends Closeable {
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

        ZipSource(ZipFile zip) {
            this.zip = zip;
        }

        public byte[] read(String path) throws IOException {
            ZipEntry e = zip.getEntry(path);
            if (e == null) return null;
            try (InputStream in = zip.getInputStream(e)) {
                return in.readAllBytes();
            }
        }

        public boolean exists(String path) {
            return zip.getEntry(path) != null;
        }

        public List<String> list(String prefix, String suffix) {
            List<String> out = new ArrayList<>();
            Enumeration<? extends ZipEntry> en = zip.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                String n = e.getName();
                if (!e.isDirectory() && n.startsWith(prefix) && n.endsWith(suffix)) out.add(n);
            }
            return out;
        }

        public void close() throws IOException {
            zip.close();
        }
    }
}