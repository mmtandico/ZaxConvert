package com.zaxconvert.conversion;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Builds Minecraft Bedrock 1.16.0 3D geometry from Java block/item models.
 * Applies the 4-bone hierarchy (geyser_custom -> geyser_custom_x -> geyser_custom_y -> geyser_custom_z).
 */
public final class BedrockModelBuilder {

    private BedrockModelBuilder() {
    }

    public static boolean build3d(String itemName, ResourcePackConverter.Model model,
                                  Map<String, ResourcePackConverter.TexInfo> texByRef,
                                  boolean isHandheld, Map<String, byte[]> out) throws IOException {
        if (model.elements == null || model.elements.isEmpty() || texByRef == null || texByRef.isEmpty()) {
            return false;
        }

        Map<String, int[]> layout = new HashMap<>();
        int atlasW;
        int atlasH;
        ResourcePackConverter.TexInfo animatedTex = null;
        byte[] textureBytes;
        if (texByRef.size() == 1) {
            ResourcePackConverter.TexInfo t = texByRef.values().iterator().next();
            layout.put(texByRef.keySet().iterator().next(), new int[]{0, t.fw, t.fh});
            atlasW = t.fw;
            atlasH = t.fh;
            textureBytes = t.png;
            if (t.frames > 1) animatedTex = t;
        } else {
            atlasW = 0;
            atlasH = 0;
            for (ResourcePackConverter.TexInfo t : texByRef.values()) {
                atlasW = Math.max(atlasW, t.fw);
                atlasH += t.fh;
            }
            BufferedImage atlas = new BufferedImage(atlasW, atlasH, BufferedImage.TYPE_INT_ARGB);
            int y = 0;
            for (Map.Entry<String, ResourcePackConverter.TexInfo> e : texByRef.entrySet()) {
                ResourcePackConverter.TexInfo t = e.getValue();
                atlas.getGraphics().drawImage(t.img.getSubimage(0, 0, t.fw, t.fh), 0, y, null);
                layout.put(e.getKey(), new int[]{y, t.fw, t.fh});
                y += t.fh;
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            ImageIO.write(atlas, "png", bos);
            textureBytes = bos.toByteArray();
        }

        // Standard Bedrock coordinate conversion:
        // Java [fx, fy, fz] to [tx, ty, tz] centered at (8, 8, 8):
        // origin: [-tx + 8, fy, fz - 8]
        // size: [tx - fx, ty - fy, tz - fz]
        JsonArray cubes = new JsonArray();
        for (JsonObject el : model.elements) {
            if (!el.has("from") || !el.has("to") || !el.has("faces")) continue;
            double[] from = ResourcePackConverter.doubles(el.getAsJsonArray("from"));
            double[] to = ResourcePackConverter.doubles(el.getAsJsonArray("to"));
            JsonObject cube = new JsonObject();
            cube.add("origin", dblArray(-to[0] + 8, from[1], from[2] - 8));
            cube.add("size", dblArray(to[0] - from[0], to[1] - from[1], to[2] - from[2]));

            if (el.has("rotation") && el.get("rotation").isJsonObject()) {
                JsonObject rot = el.getAsJsonObject("rotation");
                double angle = rot.has("angle") ? rot.get("angle").getAsDouble() : 0;
                String axis = rot.has("axis") ? rot.get("axis").getAsString() : "y";
                double[] o = rot.has("origin") ? ResourcePackConverter.doubles(rot.getAsJsonArray("origin")) : new double[]{8, 8, 8};
                cube.add("pivot", dblArray(-o[0] + 8, o[1], o[2] - 8));
                switch (axis) {
                    case "x":
                        cube.add("rotation", dblArray(-angle, 0, 0));
                        break;
                    case "y":
                        cube.add("rotation", dblArray(0, -angle, 0));
                        break;
                    default:
                        cube.add("rotation", dblArray(0, 0, angle));
                }
            }

            JsonObject uvs = new JsonObject();
            for (Map.Entry<String, JsonElement> fe : el.getAsJsonObject("faces").entrySet()) {
                String dir = fe.getKey();
                JsonObject face = fe.getValue().getAsJsonObject();
                if (!face.has("texture")) continue;
                String ref = ResourcePackConverter.resolveRefPublic(model.textures, face.get("texture").getAsString());
                int[] lay = ref == null ? null : layout.get(ref);
                if (lay == null) continue;

                double[] uv;
                if (face.has("uv")) {
                    uv = ResourcePackConverter.doubles(face.getAsJsonArray("uv"));
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

                String bedrockDir = dir.equals("east") ? "west" : dir.equals("west") ? "east" : dir;
                JsonObject f = new JsonObject();
                if (dir.equals("up") || dir.equals("down")) {
                    f.add("uv", dblArray(u2, v2));
                    f.add("uv_size", dblArray(u1 - u2, v1 - v2));
                } else {
                    f.add("uv", dblArray(u1, v1));
                    f.add("uv_size", dblArray(u2 - u1, v2 - v1));
                }
                uvs.add(bedrockDir, f);
            }
            cube.add("uv", uvs);
            cubes.add(cube);
        }
        if (cubes.size() == 0) return false;

        String geoId = "geometry.zaxconvert." + itemName;

        // 4-Bone Hierarchy matching Geyser / Kas-tle standard:
        // geyser_custom -> geyser_custom_x -> geyser_custom_y -> geyser_custom_z (cubes)
        JsonArray bones = new JsonArray();
        JsonObject rootBone = new JsonObject();
        rootBone.addProperty("name", "geyser_custom");
        rootBone.addProperty("binding", "c.item_slot == 'head' ? 'head' : q.item_slot_to_bone_name(c.item_slot)");
        rootBone.add("pivot", intArray(0, 8, 0));
        bones.add(rootBone);

        JsonObject boneX = new JsonObject();
        boneX.addProperty("name", "geyser_custom_x");
        boneX.addProperty("parent", "geyser_custom");
        boneX.add("pivot", intArray(0, 8, 0));
        bones.add(boneX);

        JsonObject boneY = new JsonObject();
        boneY.addProperty("name", "geyser_custom_y");
        boneY.addProperty("parent", "geyser_custom_x");
        boneY.add("pivot", intArray(0, 8, 0));
        bones.add(boneY);

        JsonObject boneZ = new JsonObject();
        boneZ.addProperty("name", "geyser_custom_z");
        boneZ.addProperty("parent", "geyser_custom_y");
        boneZ.add("pivot", intArray(0, 8, 0));
        boneZ.add("cubes", cubes);
        bones.add(boneZ);

        JsonObject desc = new JsonObject();
        desc.addProperty("identifier", geoId);
        desc.addProperty("texture_width", atlasW);
        desc.addProperty("texture_height", atlasH);
        desc.addProperty("visible_bounds_width", 4);
        desc.addProperty("visible_bounds_height", 4.5);
        desc.add("visible_bounds_offset", dblArray(0, 0.75, 0));

        JsonObject geo = new JsonObject();
        geo.add("description", desc);
        geo.add("bones", bones);
        JsonArray geoArr = new JsonArray();
        geoArr.add(geo);
        JsonObject geoRoot = new JsonObject();
        geoRoot.addProperty("format_version", "1.16.0");
        geoRoot.add("minecraft:geometry", geoArr);
        out.put("models/entity/" + itemName + ".geo.json", ResourcePackConverter.toBytes(geoRoot));
        out.put("textures/zaxconvert/" + itemName + ".png", textureBytes);

        // Build item animation file with separate first-person, third-person, and head animations
        out.put("animations/" + itemName + ".animation.json", BedrockAnimationBuilder.buildItemAnimation(itemName, model, isHandheld));

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
            out.put("render_controllers/" + itemName + ".render_controllers.json", ResourcePackConverter.toBytes(rcRoot));
        }

        out.put("attachables/" + itemName + ".json", BedrockAnimationBuilder.build3dAttachable(itemName, geoId, controller));
        return true;
    }

    private static JsonArray dblArray(double... v) {
        JsonArray a = new JsonArray();
        for (double d : v) a.add(d);
        return a;
    }

    private static JsonArray intArray(int... v) {
        JsonArray a = new JsonArray();
        for (int i : v) a.add(i);
        return a;
    }
}
