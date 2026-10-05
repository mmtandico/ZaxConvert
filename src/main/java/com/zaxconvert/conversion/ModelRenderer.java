package com.zaxconvert.conversion;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Small software renderer that draws a Java block-model (elements + display.gui)
 * into a 2D icon, the same way the Java client renders it in inventories.
 */
public final class ModelRenderer {

    private ModelRenderer() {
    }

    private static final class Quad {
        final double[][] v = new double[4][3]; // TL, TR, BR, BL (screen-space after transform)
        final double[][] uv = new double[4][2]; // in 0..16 units
        BufferedImage img;
        double shade = 1.0;
    }

    /**
     * @param elements  Java model elements
     * @param textures  texture variable map of the model (#name -> ref)
     * @param images    resolved texture ref -> first-frame image
     * @param gui       the display.gui object (may be null)
     * @param flatLight true when gui_light is "front" (no directional shading)
     * @param size      output size in pixels
     * @return the rendered icon, or null if nothing was drawn
     */
    public static BufferedImage render(List<JsonObject> elements, Map<String, String> textures,
                                       Map<String, BufferedImage> images, JsonObject gui,
                                       boolean flatLight, int size) {
        double[] rot = gui != null && gui.has("rotation") ? arr(gui.getAsJsonArray("rotation")) : new double[]{30, 225, 0};
        double[] trn = gui != null && gui.has("translation") ? arr(gui.getAsJsonArray("translation")) : new double[]{0, 0, 0};
        double[] scl = gui != null && gui.has("scale") ? arr(gui.getAsJsonArray("scale")) : new double[]{0.625, 0.625, 0.625};

        List<Quad> quads = new ArrayList<>();
        for (JsonObject el : elements) {
            if (!el.has("from") || !el.has("to") || !el.has("faces")) continue;
            double[] f = arr(el.getAsJsonArray("from"));
            double[] t = arr(el.getAsJsonArray("to"));

            double elAngle = 0;
            String elAxis = "y";
            double[] elOrigin = {8, 8, 8};
            if (el.has("rotation") && el.get("rotation").isJsonObject()) {
                JsonObject r = el.getAsJsonObject("rotation");
                elAngle = r.has("angle") ? r.get("angle").getAsDouble() : 0;
                elAxis = r.has("axis") ? r.get("axis").getAsString() : "y";
                if (r.has("origin")) elOrigin = arr(r.getAsJsonArray("origin"));
            }

            for (Map.Entry<String, JsonElement> fe : el.getAsJsonObject("faces").entrySet()) {
                String dir = fe.getKey();
                JsonObject face = fe.getValue().getAsJsonObject();
                if (!face.has("texture")) continue;
                String ref = ResourcePackConverter.resolveRefPublic(textures, face.get("texture").getAsString());
                BufferedImage img = ref == null ? null : images.get(ref);
                if (img == null) continue;

                double[][] c = corners(dir, f, t);
                if (c == null) continue;
                double[] uv = face.has("uv") ? arr(face.getAsJsonArray("uv")) : defaultUv(dir, f, t);
                double[][] uvc = {{uv[0], uv[1]}, {uv[2], uv[1]}, {uv[2], uv[3]}, {uv[0], uv[3]}};
                int rotSteps = face.has("rotation") ? ((face.get("rotation").getAsInt() / 90) % 4 + 4) % 4 : 0;

                Quad q = new Quad();
                q.img = img;
                for (int i = 0; i < 4; i++) {
                    q.uv[i] = uvc[(i + rotSteps) % 4];
                    double[] p = c[i].clone();
                    if (elAngle != 0) p = rotateAbout(p, elOrigin, elAxis, elAngle);
                    // to centered block space
                    p = new double[]{(p[0] - 8) / 16.0, (p[1] - 8) / 16.0, (p[2] - 8) / 16.0};
                    // display: scale, rotate (X then Y then Z), translate
                    p = new double[]{p[0] * scl[0], p[1] * scl[1], p[2] * scl[2]};
                    p = rotX(p, rot[0]);
                    p = rotY(p, rot[1]);
                    p = rotZ(p, rot[2]);
                    p = new double[]{p[0] + trn[0] / 16.0, p[1] + trn[1] / 16.0, p[2] + trn[2] / 16.0};
                    q.v[i] = p;
                }
                q.shade = flatLight ? 1.0 : shade(q.v);
                quads.add(q);
            }
        }
        if (quads.isEmpty()) return null;

        // Compute 2D screen bounding box to perfectly frame and center the model
        double minX = Double.POSITIVE_INFINITY, maxX = Double.NEGATIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
        for (Quad q : quads) {
            for (double[] p : q.v) {
                minX = Math.min(minX, p[0]);
                maxX = Math.max(maxX, p[0]);
                minY = Math.min(minY, p[1]);
                maxY = Math.max(maxY, p[1]);
            }
        }

        double spanX = Math.max(1e-4, maxX - minX);
        double spanY = Math.max(1e-4, maxY - minY);
        double midX = (minX + maxX) / 2.0;
        double midY = (minY + maxY) / 2.0;

        // If the model specifies display.gui, check if it fits nicely in the slot:
        boolean useExplicitGui = (gui != null && (gui.has("rotation") || gui.has("scale") || gui.has("translation")));
        boolean fitsNaturally = minX >= -0.52 && maxX <= 0.52 && minY >= -0.52 && maxY <= 0.52
                && spanX > 0.15 && spanY > 0.15;

        double scale;
        double offsetX;
        double offsetY;
        if (useExplicitGui && fitsNaturally) {
            // Respect the author's custom GUI transforms directly
            scale = size;
            offsetX = 0.0;
            offsetY = 0.0;
        } else {
            // Auto-frame and center model with 4px padding so it never clips or appears microscopic
            double targetSize = Math.max(8, size - 8.0);
            scale = Math.min(targetSize / spanX, targetSize / spanY);
            offsetX = midX;
            offsetY = midY;
        }

        BufferedImage out = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        double[] zbuf = new double[size * size];
        java.util.Arrays.fill(zbuf, -1e9);
        boolean drew = false;

        for (Quad q : quads) {
            double[][] s = new double[4][3];
            for (int i = 0; i < 4; i++) {
                s[i][0] = size / 2.0 + (q.v[i][0] - offsetX) * scale;
                s[i][1] = size / 2.0 - (q.v[i][1] - offsetY) * scale;
                s[i][2] = q.v[i][2];
            }
            drew |= raster(out, zbuf, size, q, s, 0, 1, 2);
            drew |= raster(out, zbuf, size, q, s, 0, 2, 3);
        }
        return drew ? out : null;
    }

    private static boolean raster(BufferedImage out, double[] zbuf, int size, Quad q, double[][] s, int a, int b, int c) {
        double x0 = s[a][0], y0 = s[a][1], x1 = s[b][0], y1 = s[b][1], x2 = s[c][0], y2 = s[c][1];
        double denom = (y1 - y2) * (x0 - x2) + (x2 - x1) * (y0 - y2);
        if (Math.abs(denom) < 1e-9) return false;
        int minX = (int) Math.max(0, Math.floor(Math.min(x0, Math.min(x1, x2))));
        int maxX = (int) Math.min(size - 1, Math.ceil(Math.max(x0, Math.max(x1, x2))));
        int minY = (int) Math.max(0, Math.floor(Math.min(y0, Math.min(y1, y2))));
        int maxY = (int) Math.min(size - 1, Math.ceil(Math.max(y0, Math.max(y1, y2))));
        int iw = q.img.getWidth(), ih = q.img.getHeight();
        boolean drew = false;
        for (int py = minY; py <= maxY; py++) {
            for (int px = minX; px <= maxX; px++) {
                double cx = px + 0.5, cy = py + 0.5;
                double w0 = ((y1 - y2) * (cx - x2) + (x2 - x1) * (cy - y2)) / denom;
                double w1 = ((y2 - y0) * (cx - x2) + (x0 - x2) * (cy - y2)) / denom;
                double w2 = 1 - w0 - w1;
                if (w0 < -1e-6 || w1 < -1e-6 || w2 < -1e-6) continue;
                double z = w0 * s[a][2] + w1 * s[b][2] + w2 * s[c][2];
                int idx = py * size + px;
                if (z < zbuf[idx]) continue;
                double u = w0 * q.uv[a][0] + w1 * q.uv[b][0] + w2 * q.uv[c][0];
                double v = w0 * q.uv[a][1] + w1 * q.uv[b][1] + w2 * q.uv[c][1];
                int tx = clamp((int) Math.floor(u / 16.0 * iw), iw - 1);
                int ty = clamp((int) Math.floor(v / 16.0 * ih), ih - 1);
                int argb = q.img.getRGB(tx, ty);
                int alpha = argb >>> 24;
                if (alpha < 16) continue;
                int r = (int) Math.min(255, ((argb >> 16) & 255) * q.shade);
                int g = (int) Math.min(255, ((argb >> 8) & 255) * q.shade);
                int bl = (int) Math.min(255, (argb & 255) * q.shade);
                out.setRGB(px, py, (255 << 24) | (r << 16) | (g << 8) | bl);
                zbuf[idx] = z;
                drew = true;
            }
        }
        return drew;
    }

    private static int clamp(int v, int max) {
        return Math.max(0, Math.min(max, v));
    }

    /** Face corners as TL, TR, BR, BL when viewed from outside (matches Java UV orientation). */
    private static double[][] corners(String dir, double[] f, double[] t) {
        switch (dir) {
            case "north":
                return new double[][]{{t[0], t[1], f[2]}, {f[0], t[1], f[2]}, {f[0], f[1], f[2]}, {t[0], f[1], f[2]}};
            case "south":
                return new double[][]{{f[0], t[1], t[2]}, {t[0], t[1], t[2]}, {t[0], f[1], t[2]}, {f[0], f[1], t[2]}};
            case "east":
                return new double[][]{{t[0], t[1], t[2]}, {t[0], t[1], f[2]}, {t[0], f[1], f[2]}, {t[0], f[1], t[2]}};
            case "west":
                return new double[][]{{f[0], t[1], f[2]}, {f[0], t[1], t[2]}, {f[0], f[1], t[2]}, {f[0], f[1], f[2]}};
            case "up":
                return new double[][]{{f[0], t[1], f[2]}, {t[0], t[1], f[2]}, {t[0], t[1], t[2]}, {f[0], t[1], t[2]}};
            case "down":
                return new double[][]{{f[0], f[1], t[2]}, {t[0], f[1], t[2]}, {t[0], f[1], f[2]}, {f[0], f[1], f[2]}};
            default:
                return null;
        }
    }

    static double[] defaultUv(String dir, double[] f, double[] t) {
        switch (dir) {
            case "north":
            case "south":
                return new double[]{f[0], 16 - t[1], t[0], 16 - f[1]};
            case "east":
            case "west":
                return new double[]{f[2], 16 - t[1], t[2], 16 - f[1]};
            case "up":
                return new double[]{f[0], f[2], t[0], t[2]};
            default: // down
                return new double[]{f[0], 16 - t[2], t[0], 16 - f[2]};
        }
    }

    private static double shade(double[][] v) {
        double[] a = sub(v[3], v[0]);
        double[] b = sub(v[1], v[0]);
        double[] n = {a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
        double len = Math.sqrt(n[0] * n[0] + n[1] * n[1] + n[2] * n[2]);
        if (len < 1e-12) return 1;
        n[0] /= len;
        n[1] /= len;
        n[2] /= len;
        // Vanilla GUI diffuse lights
        double[] l0 = norm(0.2, 1.0, -0.7);
        double[] l1 = norm(-0.2, 1.0, 0.7);
        double d = Math.max(0, dot(n, l0)) + Math.max(0, dot(n, l1));
        return Math.min(1.0, 0.4 + 0.6 * d);
    }

    private static double[] sub(double[] a, double[] b) {
        return new double[]{a[0] - b[0], a[1] - b[1], a[2] - b[2]};
    }

    private static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    private static double[] norm(double x, double y, double z) {
        double l = Math.sqrt(x * x + y * y + z * z);
        return new double[]{x / l, y / l, z / l};
    }

    private static double[] rotateAbout(double[] p, double[] o, String axis, double deg) {
        double[] q = {p[0] - o[0], p[1] - o[1], p[2] - o[2]};
        switch (axis) {
            case "x":
                q = rotX(q, deg);
                break;
            case "z":
                q = rotZ(q, deg);
                break;
            default:
                q = rotY(q, deg);
        }
        return new double[]{q[0] + o[0], q[1] + o[1], q[2] + o[2]};
    }

    private static double[] rotX(double[] p, double deg) {
        double r = Math.toRadians(deg), c = Math.cos(r), s = Math.sin(r);
        return new double[]{p[0], p[1] * c - p[2] * s, p[1] * s + p[2] * c};
    }

    private static double[] rotY(double[] p, double deg) {
        double r = Math.toRadians(deg), c = Math.cos(r), s = Math.sin(r);
        return new double[]{p[0] * c + p[2] * s, p[1], -p[0] * s + p[2] * c};
    }

    private static double[] rotZ(double[] p, double deg) {
        double r = Math.toRadians(deg), c = Math.cos(r), s = Math.sin(r);
        return new double[]{p[0] * c - p[1] * s, p[0] * s + p[1] * c, p[2]};
    }

    private static double[] arr(JsonArray a) {
        double[] d = new double[a.size()];
        for (int i = 0; i < d.length; i++) d[i] = a.get(i).getAsDouble();
        return d;
    }
}
