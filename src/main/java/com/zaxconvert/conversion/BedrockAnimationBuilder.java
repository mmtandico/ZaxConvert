package com.zaxconvert.conversion;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.nio.charset.StandardCharsets;

/**
 * Builds standard Bedrock animations and attachable descriptions.
 * Uses the 4-bone hierarchy (geyser_custom -> geyser_custom_x -> geyser_custom_y -> geyser_custom_z)
 * and 5 distinct Molang animations for natural first-person, third-person, and ground placement.
 */
public final class BedrockAnimationBuilder {

    private BedrockAnimationBuilder() {
    }

    /**
     * Builds the item animation JSON containing the 5 perspective animations:
     * - thirdperson_main_hand
     * - thirdperson_off_hand
     * - head (furniture on ground / armor stand)
     * - firstperson_main_hand
     * - firstperson_off_hand
     */
    public static byte[] buildItemAnimation(String itemName, ResourcePackConverter.Model model, boolean isHandheld) {
        double[] tpRot = getDisplayValue(model, "thirdperson_righthand", "rotation",
                isHandheld ? new double[]{0, -90, 55} : new double[]{75, 45, 0});
        double[] tpTrans = getDisplayValue(model, "thirdperson_righthand", "translation",
                isHandheld ? new double[]{0, 4.0, 0.5} : new double[]{0, 2.5, 0});
        double[] tpScale = getDisplayValue(model, "thirdperson_righthand", "scale",
                isHandheld ? new double[]{0.85, 0.85, 0.85} : new double[]{0.375, 0.375, 0.375});

        double[] tpLeftRot = getDisplayValue(model, "thirdperson_lefthand", "rotation",
                new double[]{tpRot[0], -tpRot[1], -tpRot[2]});
        double[] tpLeftTrans = getDisplayValue(model, "thirdperson_lefthand", "translation",
                new double[]{-tpTrans[0], tpTrans[1], tpTrans[2]});
        double[] tpLeftScale = getDisplayValue(model, "thirdperson_lefthand", "scale", tpScale);

        double[] headRot = getDisplayValue(model, "head", "rotation", new double[]{0, 0, 0});
        double[] headTrans = getDisplayValue(model, "head", "translation", new double[]{0, 0, 0});
        double[] headScale = getDisplayValue(model, "head", "scale", new double[]{1.0, 1.0, 1.0});

        double[] fpRot = getDisplayValue(model, "firstperson_righthand", "rotation",
                isHandheld ? new double[]{0, -90, 25} : new double[]{0, 45, 0});
        double[] fpTrans = getDisplayValue(model, "firstperson_righthand", "translation",
                isHandheld ? new double[]{1.13, 3.2, 1.13} : new double[]{0, 0, 0});
        double[] fpScale = getDisplayValue(model, "firstperson_righthand", "scale",
                isHandheld ? new double[]{0.68, 0.68, 0.68} : new double[]{0.4, 0.4, 0.4});

        double[] fpLeftRot = getDisplayValue(model, "firstperson_lefthand", "rotation",
                new double[]{fpRot[0], -fpRot[1], -fpRot[2]});
        double[] fpLeftTrans = getDisplayValue(model, "firstperson_lefthand", "translation",
                new double[]{-fpTrans[0], fpTrans[1], fpTrans[2]});
        double[] fpLeftScale = getDisplayValue(model, "firstperson_lefthand", "scale", fpScale);

        JsonObject animRoot = new JsonObject();
        animRoot.addProperty("format_version", "1.8.0");
        JsonObject animations = new JsonObject();

        // 1. thirdperson_main_hand
        JsonObject tpMain = new JsonObject();
        tpMain.addProperty("loop", true);
        JsonObject tpMainBones = new JsonObject();
        JsonObject bGeyser = new JsonObject();
        bGeyser.add("rotation", dblArray(90, 0, 0));
        bGeyser.add("position", dblArray(0, 13, -3));
        tpMainBones.add("geyser_custom", bGeyser);
        JsonObject bX = new JsonObject();
        bX.add("rotation", dblArray(-tpRot[0], 0, 0));
        bX.add("position", dblArray(-tpTrans[0], tpTrans[1], tpTrans[2]));
        bX.add("scale", dblArray(tpScale[0], tpScale[1], tpScale[2]));
        tpMainBones.add("geyser_custom_x", bX);
        JsonObject bY = new JsonObject();
        bY.add("rotation", dblArray(0, -tpRot[1], 0));
        tpMainBones.add("geyser_custom_y", bY);
        JsonObject bZ = new JsonObject();
        bZ.add("rotation", dblArray(0, 0, tpRot[2]));
        tpMainBones.add("geyser_custom_z", bZ);
        tpMain.add("bones", tpMainBones);
        animations.add("animation.zaxconvert." + itemName + ".thirdperson_main_hand", tpMain);

        // 2. thirdperson_off_hand
        JsonObject tpOff = new JsonObject();
        tpOff.addProperty("loop", true);
        JsonObject tpOffBones = new JsonObject();
        JsonObject bGeyserOff = new JsonObject();
        bGeyserOff.add("rotation", dblArray(90, 0, 0));
        bGeyserOff.add("position", dblArray(0, 13, -3));
        tpOffBones.add("geyser_custom", bGeyserOff);
        JsonObject bXOff = new JsonObject();
        bXOff.add("rotation", dblArray(-tpLeftRot[0], 0, 0));
        bXOff.add("position", dblArray(tpLeftTrans[0], tpLeftTrans[1], tpLeftTrans[2]));
        bXOff.add("scale", dblArray(tpLeftScale[0], tpLeftScale[1], tpLeftScale[2]));
        tpOffBones.add("geyser_custom_x", bXOff);
        JsonObject bYOff = new JsonObject();
        bYOff.add("rotation", dblArray(0, -tpLeftRot[1], 0));
        tpOffBones.add("geyser_custom_y", bYOff);
        JsonObject bZOff = new JsonObject();
        bZOff.add("rotation", dblArray(0, 0, tpLeftRot[2]));
        tpOffBones.add("geyser_custom_z", bZOff);
        tpOff.add("bones", tpOffBones);
        animations.add("animation.zaxconvert." + itemName + ".thirdperson_off_hand", tpOff);

        // 3. head (furniture placed on armor stand or head gear)
        JsonObject headAnim = new JsonObject();
        headAnim.addProperty("loop", true);
        JsonObject headBones = new JsonObject();
        JsonObject bGeyserHead = new JsonObject();
        bGeyserHead.add("position", dblArray(0, 19.9, 0));
        headBones.add("geyser_custom", bGeyserHead);
        JsonObject bXHead = new JsonObject();
        bXHead.add("rotation", dblArray(-headRot[0], 0, 0));
        bXHead.add("position", dblArray(-headTrans[0] * 0.625, headTrans[1] * 0.625, headTrans[2] * 0.625));
        bXHead.add("scale", dblArray(headScale[0] * 0.625, headScale[1] * 0.625, headScale[2] * 0.625));
        headBones.add("geyser_custom_x", bXHead);
        JsonObject bYHead = new JsonObject();
        bYHead.add("rotation", dblArray(0, -headRot[1], 0));
        headBones.add("geyser_custom_y", bYHead);
        JsonObject bZHead = new JsonObject();
        bZHead.add("rotation", dblArray(0, 0, headRot[2]));
        headBones.add("geyser_custom_z", bZHead);
        headAnim.add("bones", headBones);
        animations.add("animation.zaxconvert." + itemName + ".head", headAnim);

        // 4. firstperson_main_hand
        JsonObject fpMain = new JsonObject();
        fpMain.addProperty("loop", true);
        JsonObject fpMainBones = new JsonObject();
        JsonObject bGeyserFP = new JsonObject();
        bGeyserFP.add("rotation", dblArray(90, 60, -40));
        bGeyserFP.add("position", dblArray(4, 10, 4));
        bGeyserFP.addProperty("scale", 1.5);
        fpMainBones.add("geyser_custom", bGeyserFP);
        JsonObject bXFP = new JsonObject();
        bXFP.add("rotation", dblArray(-fpRot[0], 0, 0));
        bXFP.add("position", dblArray(-fpTrans[0], fpTrans[1], -fpTrans[2]));
        bXFP.add("scale", dblArray(fpScale[0], fpScale[1], fpScale[2]));
        fpMainBones.add("geyser_custom_x", bXFP);
        JsonObject bYFP = new JsonObject();
        bYFP.add("rotation", dblArray(0, -fpRot[1], 0));
        fpMainBones.add("geyser_custom_y", bYFP);
        JsonObject bZFP = new JsonObject();
        bZFP.add("rotation", dblArray(0, 0, fpRot[2]));
        fpMainBones.add("geyser_custom_z", bZFP);
        fpMain.add("bones", fpMainBones);
        animations.add("animation.zaxconvert." + itemName + ".firstperson_main_hand", fpMain);

        // 5. firstperson_off_hand
        JsonObject fpOff = new JsonObject();
        fpOff.addProperty("loop", true);
        JsonObject fpOffBones = new JsonObject();
        JsonObject bGeyserFPOff = new JsonObject();
        bGeyserFPOff.add("rotation", dblArray(90, 60, -40));
        bGeyserFPOff.add("position", dblArray(4, 10, 4));
        bGeyserFPOff.addProperty("scale", 1.5);
        fpOffBones.add("geyser_custom", bGeyserFPOff);
        JsonObject bXFPOff = new JsonObject();
        bXFPOff.add("rotation", dblArray(-fpLeftRot[0], 0, 0));
        bXFPOff.add("position", dblArray(fpLeftTrans[0], fpLeftTrans[1], -fpLeftTrans[2]));
        bXFPOff.add("scale", dblArray(fpLeftScale[0], fpLeftScale[1], fpLeftScale[2]));
        fpOffBones.add("geyser_custom_x", bXFPOff);
        JsonObject bYFPOff = new JsonObject();
        bYFPOff.add("rotation", dblArray(0, -fpLeftRot[1], 0));
        fpOffBones.add("geyser_custom_y", bYFPOff);
        JsonObject bZFPOff = new JsonObject();
        bZFPOff.add("rotation", dblArray(0, 0, fpLeftRot[2]));
        fpOffBones.add("geyser_custom_z", bZFPOff);
        fpOff.add("bones", fpOffBones);
        animations.add("animation.zaxconvert." + itemName + ".firstperson_off_hand", fpOff);

        animRoot.add("animations", animations);
        return ResourcePackConverter.toBytes(animRoot);
    }

    /**
     * Builds the attachable JSON file for 3D items with pre_animation variables and animate queries.
     */
    public static byte[] build3dAttachable(String itemName, String geoId, String controller) {
        JsonObject aDesc = new JsonObject();
        aDesc.addProperty("identifier", "s_mc:" + itemName);
        JsonObject itemObj = new JsonObject();
        itemObj.addProperty("s_mc:" + itemName, "query.is_owner_identifier_any('minecraft:player', 'minecraft:armor_stand')");
        aDesc.add("item", itemObj);

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

        JsonObject scripts = new JsonObject();
        JsonArray preAnim = new JsonArray();
        preAnim.add("v.main_hand = c.item_slot == 'main_hand';");
        preAnim.add("v.off_hand = c.item_slot == 'off_hand';");
        preAnim.add("v.head = c.item_slot == 'head';");
        scripts.add("pre_animation", preAnim);

        JsonArray animate = new JsonArray();
        JsonObject a1 = new JsonObject(); a1.addProperty("thirdperson_main_hand", "v.main_hand && !c.is_first_person"); animate.add(a1);
        JsonObject a2 = new JsonObject(); a2.addProperty("thirdperson_off_hand", "v.off_hand && !c.is_first_person"); animate.add(a2);
        JsonObject a3 = new JsonObject(); a3.addProperty("thirdperson_head", "v.head && !c.is_first_person"); animate.add(a3);
        JsonObject a4 = new JsonObject(); a4.addProperty("firstperson_main_hand", "v.main_hand && c.is_first_person"); animate.add(a4);
        JsonObject a5 = new JsonObject(); a5.addProperty("firstperson_off_hand", "v.off_hand && c.is_first_person"); animate.add(a5);
        JsonObject a6 = new JsonObject(); a6.addProperty("firstperson_head", "c.is_first_person && v.head"); animate.add(a6);
        scripts.add("animate", animate);
        aDesc.add("scripts", scripts);

        JsonObject animations = new JsonObject();
        animations.addProperty("thirdperson_main_hand", "animation.zaxconvert." + itemName + ".thirdperson_main_hand");
        animations.addProperty("thirdperson_off_hand", "animation.zaxconvert." + itemName + ".thirdperson_off_hand");
        animations.addProperty("thirdperson_head", "animation.zaxconvert." + itemName + ".head");
        animations.addProperty("firstperson_main_hand", "animation.zaxconvert." + itemName + ".firstperson_main_hand");
        animations.addProperty("firstperson_off_hand", "animation.zaxconvert." + itemName + ".firstperson_off_hand");
        animations.addProperty("firstperson_head", "animation.zaxconvert.disable");
        aDesc.add("animations", animations);

        JsonArray rcs = new JsonArray();
        rcs.add(controller);
        aDesc.add("render_controllers", rcs);

        JsonObject att = new JsonObject();
        att.add("description", aDesc);
        JsonObject attRoot = new JsonObject();
        attRoot.addProperty("format_version", "1.10.0");
        attRoot.add("minecraft:attachable", att);
        return ResourcePackConverter.toBytes(attRoot);
    }

    /**
     * Disable animation: scales geyser_custom root bone to 0.0 in first person head slot.
     */
    public static byte[] buildDisableAnimation() {
        JsonObject animRoot = new JsonObject();
        animRoot.addProperty("format_version", "1.8.0");
        JsonObject anims = new JsonObject();
        JsonObject dis = new JsonObject();
        dis.addProperty("loop", true);
        JsonObject bones = new JsonObject();
        JsonObject rootBone = new JsonObject();
        rootBone.addProperty("scale", 0.0);
        bones.add("geyser_custom", rootBone);
        dis.add("bones", bones);
        anims.add("animation.zaxconvert.disable", dis);
        animRoot.add("animations", anims);
        return ResourcePackConverter.toBytes(animRoot);
    }

    /** Default item render controller required for all Bedrock attachables. */
    public static byte[] buildDefaultRenderController() {
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

        JsonObject controllers = new JsonObject();
        controllers.add("controller.render.item_default", rc);
        JsonObject root = new JsonObject();
        root.addProperty("format_version", "1.10.0");
        root.add("render_controllers", controllers);
        return ResourcePackConverter.toBytes(root);
    }

    /** Armor render controller for worn helmets, chestplates, leggings, and boots. */
    public static byte[] buildArmorRenderControllers() {
        JsonObject controllers = new JsonObject();
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

        controllers.add("controller.render.armor", rc);
        JsonObject root = new JsonObject();
        root.addProperty("format_version", "1.10.0");
        root.add("render_controllers", controllers);
        return ResourcePackConverter.toBytes(root);
    }

    private static double[] getDisplayValue(ResourcePackConverter.Model model, String slot, String prop, double[] fallback) {
        if (model.display != null && model.display.containsKey(slot)) {
            JsonObject o = model.display.get(slot);
            if (o.has(prop)) {
                return ResourcePackConverter.doubles(o.getAsJsonArray(prop));
            }
        }
        return fallback;
    }

    private static JsonArray dblArray(double... v) {
        JsonArray a = new JsonArray();
        for (double d : v) a.add(d);
        return a;
    }
}
