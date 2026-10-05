package com.zaxconvert.conversion;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Handles custom wearable humanoid armor conversion (helmet, chestplate, leggings, boots)
 * for Bedrock Edition.
 */
public final class ArmorConverter {

    private ArmorConverter() {
    }

    public static boolean processArmor(ResourcePackConverter.PackSource src, ResourcePackConverter.ItemDef def,
                                       String itemName, String slot, Map<String, String> texIndex,
                                       Map<String, byte[]> out) {
        String armorName = def.armorTexture;
        if (armorName == null) return false;
        if (armorName.contains(":")) armorName = armorName.substring(armorName.indexOf(':') + 1);
        if (armorName.contains("/")) armorName = armorName.substring(armorName.lastIndexOf('/') + 1);
        armorName = armorName.toLowerCase(Locale.ROOT);

        String layerSuffix = slot.equals("leggings") ? "layer_2" : "layer_1";
        byte[] layerBytes = findArmorTexture(src, armorName, layerSuffix, texIndex);
        if (layerBytes == null) {
            layerBytes = findArmorTexture(src, armorName, slot.equals("leggings") ? "2" : "1", texIndex);
        }
        if (layerBytes == null) return false;

        String bedrockLayerPath = "textures/models/armor/" + armorName + "_" + layerSuffix;
        out.put(bedrockLayerPath + ".png", layerBytes);

        // Build worn armor attachable:
        // When held in hand or dropped on ground, attachable is inactive -> Bedrock renders the 2D item icon!
        // When equipped on the body -> attachable is active -> Bedrock renders 3D humanoid armor on the player!
        JsonObject aDesc = new JsonObject();
        aDesc.addProperty("identifier", "s_mc:" + itemName);
        JsonObject itemObj = new JsonObject();
        itemObj.addProperty("s_mc:" + itemName, "c.item_slot != 'main_hand' && c.item_slot != 'off_hand'");
        aDesc.add("item", itemObj);

        JsonObject materials = new JsonObject();
        materials.addProperty("default", "armor");
        materials.addProperty("enchanted", "armor_enchanted");
        aDesc.add("materials", materials);

        JsonObject aTex = new JsonObject();
        aTex.addProperty("default", bedrockLayerPath);
        aTex.addProperty("enchanted", "textures/misc/enchanted_item_glint");
        aDesc.add("textures", aTex);

        JsonObject aGeo = new JsonObject();
        aGeo.addProperty("default", "geometry.humanoid.armor." + slot);
        aDesc.add("geometry", aGeo);

        JsonObject scripts = new JsonObject();
        String varName = slot.equals("helmet") ? "variable.helmet_layer_visible"
                : slot.equals("chestplate") ? "variable.chest_layer_visible"
                : slot.equals("leggings") ? "variable.leg_layer_visible" : "variable.boot_layer_visible";
        scripts.addProperty("parent_setup", varName + " = false;");
        aDesc.add("scripts", scripts);

        JsonArray rcs = new JsonArray();
        rcs.add("controller.render.armor");
        aDesc.add("render_controllers", rcs);

        JsonObject att = new JsonObject();
        att.add("description", aDesc);
        JsonObject attRoot = new JsonObject();
        attRoot.addProperty("format_version", "1.10.0");
        attRoot.add("minecraft:attachable", att);
        out.put("attachables/" + itemName + ".json", ResourcePackConverter.toBytes(attRoot));
        return true;
    }

    private static byte[] findArmorTexture(ResourcePackConverter.PackSource src, String armorName, String suffix,
                                           Map<String, String> texIndex) {
        if (armorName == null || armorName.isEmpty()) return null;
        String clean = armorName.toLowerCase(Locale.ROOT);
        String s = suffix.toLowerCase(Locale.ROOT);

        List<String> candidates = new ArrayList<>();
        candidates.add("models/armor/" + clean + "_" + s);
        candidates.add("armor/" + clean + "_" + s);
        candidates.add("entity/equipment/" + clean + "/" + s);
        candidates.add("entity/equipment/" + clean + "_" + s);
        candidates.add("entity/equipment/humanoid/" + clean + "/" + s);
        candidates.add("entity/equipment/humanoid/" + clean + "_" + s);
        candidates.add("textures/models/armor/" + clean + "_" + s);
        candidates.add("textures/entity/equipment/" + clean + "/" + s);
        candidates.add(clean + "_" + s);

        for (String c : candidates) {
            String path = texIndex.get(c);
            if (path == null) path = texIndex.get(c + ".png");
            if (path != null && src.exists(path)) {
                try {
                    return src.read(path);
                } catch (IOException ignored) {}
            }
        }

        // Direct search using texIndex values
        for (String p : texIndex.values()) {
            String lower = p.toLowerCase(Locale.ROOT);
            if (lower.contains(clean) && (lower.contains(s) || lower.contains(s.replace("layer_", "")) || lower.contains(s.replace("_", "")))) {
                try {
                    return src.read(p);
                } catch (IOException ignored) {}
            }
        }
        return null;
    }
}
