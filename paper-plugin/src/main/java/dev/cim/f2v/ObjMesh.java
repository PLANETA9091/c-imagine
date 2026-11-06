package dev.cim.f2v;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.imageio.ImageIO;

/**
 * The {@code neoforge:obj} loader for a client that has no such loader: an OBJ
 * mesh becomes vanilla cuboid elements.
 *
 * <p>Each mesh quad is one single-face element, positioned by the 26.3 Euler
 * element rotation ({@code CuboidRotation$EulerXYZRotation}: Matrix4f
 * .rotationZYX, so R = Rz * Ry * Rx, about an arbitrary origin). A rectangle
 * with axis-aligned UVs maps exactly onto the source sprite. Triangulated
 * rectangles are merged back first. Any other polygon rides a rectangle
 * covering it, textured from a generated cutout sprite that holds the polygon's
 * pixels and is transparent elsewhere.
 *
 * <p>All coordinates here are model units (16 per block), UVs 0..16.
 */
final class ObjMesh {

    /** Element marker for a polygon awaiting its cutout sprite. */
    static final String POLY = "_cimPoly";

    /** The face plane's offset from the element's back: a zero-depth box bakes fat on 26.3. */
    private static final double DEPTH = 0.02;

    /** The element coordinate limits enforced by the 26.3 model deserializer. */
    private static final double MIN = -16.0;
    private static final double MAX = 32.0;

    private static final int ATLAS_WIDTH = 256;

    private ObjMesh() {
    }

    record Vert(double[] p, double[] uv, int pi, int ti) {
    }

    /** {@code colors}: per-vertex rgb multipliers (ambient occlusion x tint), or null. */
    record Face(List<Vert> verts, String tex, float[][] colors) {
        Face(List<Vert> verts, String tex) {
            this(verts, tex, null);
        }

        boolean colored() {
            if (colors != null) {
                for (float[] c : colors) {
                    for (float v : c) {
                        if (v < 0.995f) {
                            return true;
                        }
                    }
                }
            }
            return false;
        }
    }

    /** {@code newmtl} name -> texture ref ({@code map_Kd #3} -> {@code #3}). */
    static Map<String, String> parseMtl(String mtl) {
        Map<String, String> out = new HashMap<>();
        String cur = null;
        for (String raw : mtl.split("\n")) {
            String[] p = raw.trim().split("\\s+");
            if (p.length >= 2 && p[0].equals("newmtl")) {
                cur = p[1];
            } else if (p.length >= 2 && p[0].equals("map_Kd") && cur != null) {
                out.put(cur, p[1]);
            }
        }
        return out;
    }

    static List<Face> parse(String obj, Map<String, String> materials, boolean flipV) {
        List<double[]> pos = new ArrayList<>();
        List<double[]> uvs = new ArrayList<>();
        List<Face> faces = new ArrayList<>();
        String tex = null;
        for (String raw : obj.split("\n")) {
            String[] p = raw.trim().split("\\s+");
            switch (p[0]) {
                case "v" -> pos.add(new double[]{Double.parseDouble(p[1]) * 16,
                        Double.parseDouble(p[2]) * 16, Double.parseDouble(p[3]) * 16});
                case "vt" -> {
                    double v = Double.parseDouble(p[2]);
                    uvs.add(new double[]{Double.parseDouble(p[1]) * 16, (flipV ? 1 - v : v) * 16});
                }
                case "usemtl" -> tex = materials.get(p[1]);
                case "f" -> {
                    List<Vert> vs = new ArrayList<>();
                    for (int i = 1; i < p.length; i++) {
                        String[] idx = p[i].split("/");
                        int pi = index(idx[0], pos.size());
                        int ti = idx.length > 1 && !idx[1].isEmpty() ? index(idx[1], uvs.size()) : -1;
                        vs.add(new Vert(pos.get(pi), ti >= 0 ? uvs.get(ti) : new double[]{0, 0}, pi, ti));
                    }
                    if (tex != null) {
                        faces.add(new Face(vs, tex));
                    }
                }
                default -> {
                }
            }
        }
        return faces;
    }

    private static int index(String s, int size) {
        int i = Integer.parseInt(s);
        return i < 0 ? size + i : i - 1;
    }

    /** The smallest power-of-two shrink that fits the mesh in the element limits. */
    static double fitScale(List<Face> faces) {
        double k = 1;
        for (Face f : faces) {
            for (Vert v : f.verts()) {
                for (double c : v.p()) {
                    while (8 + (c - 8) / k < MIN || 8 + (c - 8) / k > MAX) {
                        k *= 2;
                    }
                }
            }
        }
        return k;
    }

    /**
     * The shrink the emitted elements need. from/to are limited BEFORE the
     * rotation, so a long diagonal face overflows even when every vertex fits.
     */
    static double fitElements(List<Face> faces) {
        double k = fitScale(faces);
        while (!inLimits(toElements(faces, k))) {
            k *= 2;
        }
        return k;
    }

    private static boolean inLimits(JsonArray elements) {
        for (int i = 0; i < elements.size(); i++) {
            JsonObject el = elements.get(i).getAsJsonObject();
            if (el.has(POLY)) {
                if (!polyInLimits(el.getAsJsonObject(POLY).getAsJsonArray("p"))) {
                    return false;
                }
                continue;
            }
            for (String key : List.of("from", "to")) {
                JsonArray v = el.getAsJsonArray(key);
                for (int c = 0; c < 3; c++) {
                    double d = v.get(c).getAsDouble();
                    if (d < MIN || d > MAX) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /** The rectangle's box stays within its bounding sphere about the centroid, plus the rect margin. */
    private static boolean polyInLimits(JsonArray pts) {
        double[][] p = new double[pts.size()][3];
        double[] c = new double[3];
        for (int i = 0; i < p.length; i++) {
            for (int k = 0; k < 3; k++) {
                p[i][k] = pts.get(i).getAsJsonArray().get(k).getAsDouble();
            }
            c = add(c, scale(p[i], 1.0 / p.length));
        }
        double r = 0;
        for (double[] q : p) {
            r = Math.max(r, len(sub(q, c)));
        }
        for (double d : c) {
            if (d - r < MIN || d + r > MAX) {
                return false;
            }
        }
        return true;
    }

    /** Elements for the mesh, shrunk about the block centre by {@code k}. */
    static JsonArray toElements(List<Face> source, double k) {
        List<Face> faces = new ArrayList<>();
        for (Face f : source) {
            List<Vert> vs = new ArrayList<>();
            for (Vert v : f.verts()) {
                double[] p = {8 + (v.p()[0] - 8) / k, 8 + (v.p()[1] - 8) / k, 8 + (v.p()[2] - 8) / k};
                vs.add(new Vert(p, v.uv(), v.pi(), v.ti()));
            }
            faces.add(new Face(vs, f.tex(), f.colors()));
        }
        JsonArray out = new JsonArray();
        List<Face> tris = new ArrayList<>();
        for (Face f : faces) {
            if (f.colored()) {
                out.add(polyElement(f.verts(), f.tex(), f.colors()));
            } else if (f.verts().size() == 3) {
                tris.add(f);
            } else {
                emit(out, f.verts(), f.tex());
            }
        }
        mergeTriangles(out, tris);
        return out;
    }

    private static void emit(JsonArray out, List<Vert> q, String tex) {
        if (q.size() == 4 && isRect(q)) {
            out.add(rectElement(q, tex));
        } else if (planar(q)) {
            out.add(polyElement(q, tex));
        } else {
            for (int i = 1; i + 1 < q.size(); i++) {
                out.add(polyElement(List.of(q.get(0), q.get(i), q.get(i + 1)), tex));
            }
        }
    }

    /** Rejoin triangle pairs that form a mappable rectangle. */
    private static void mergeTriangles(JsonArray out, List<Face> tris) {
        Map<Long, List<Integer>> byEdge = new HashMap<>();
        for (int n = 0; n < tris.size(); n++) {
            List<Vert> t = tris.get(n).verts();
            for (int e = 0; e < 3; e++) {
                byEdge.computeIfAbsent(edgeKey(t.get(e), t.get((e + 1) % 3)), x -> new ArrayList<>()).add(n);
            }
        }
        Set<Integer> used = new HashSet<>();
        for (int n = 0; n < tris.size(); n++) {
            if (used.contains(n)) {
                continue;
            }
            used.add(n);
            Face f = tris.get(n);
            List<Vert> t = f.verts();
            List<Vert> merged = null;
            for (int e = 0; e < 3 && merged == null; e++) {
                Vert a = t.get(e);
                Vert b = t.get((e + 1) % 3);
                Vert c = t.get((e + 2) % 3);
                for (int m : byEdge.get(edgeKey(a, b))) {
                    if (used.contains(m) || !tris.get(m).tex().equals(f.tex())) {
                        continue;
                    }
                    Vert d = opposite(tris.get(m).verts(), a, b);
                    // keep the winding: a -> d -> b -> c walks the shared
                    // diagonal's two triangles the way t is wound
                    List<Vert> q = d == null ? null : List.of(a, d, b, c);
                    if (q != null && sameUvOnEdge(tris.get(m).verts(), a, b) && isRect(q)) {
                        used.add(m);
                        merged = q;
                        break;
                    }
                }
            }
            if (merged != null) {
                out.add(rectElement(merged, f.tex()));
            } else {
                out.add(polyElement(t, f.tex()));
            }
        }
    }

    private static long edgeKey(Vert a, Vert b) {
        return ((long) Math.min(a.pi(), b.pi()) << 32) | Math.max(a.pi(), b.pi());
    }

    private static Vert opposite(List<Vert> t, Vert a, Vert b) {
        for (Vert v : t) {
            if (v.pi() != a.pi() && v.pi() != b.pi()) {
                return v;
            }
        }
        return null;
    }

    private static boolean sameUvOnEdge(List<Vert> t, Vert a, Vert b) {
        for (Vert v : t) {
            if (v.pi() == a.pi() && len(sub2(v.uv(), a.uv())) > 1e-3) {
                return false;
            }
            if (v.pi() == b.pi() && len(sub2(v.uv(), b.uv())) > 1e-3) {
                return false;
            }
        }
        return true;
    }

    static boolean isRect(List<Vert> q) {
        double[] e0 = sub(q.get(1).p(), q.get(0).p());
        double[] e1 = sub(q.get(3).p(), q.get(0).p());
        double[] d = sub(sub(sub(q.get(2).p(), q.get(0).p()), e0), e1);
        double l0 = len(e0);
        double l1 = len(e1);
        if (l0 < 1e-5 || l1 < 1e-5 || len(d) > 1e-3 * Math.max(l0, l1) + 1e-4
                || Math.abs(dot(e0, e1)) / (l0 * l1) > 1e-3) {
            return false;
        }
        double[] u0 = sub2(q.get(1).uv(), q.get(0).uv());
        double[] u1 = sub2(q.get(3).uv(), q.get(0).uv());
        boolean axisAligned = (Math.abs(u0[0]) < 1e-3 || Math.abs(u0[1]) < 1e-3)
                && (Math.abs(u1[0]) < 1e-3 || Math.abs(u1[1]) < 1e-3);
        double[] far = sub2(q.get(2).uv(), q.get(0).uv());
        return axisAligned && Math.abs(far[0] - u0[0] - u1[0]) < 1e-3
                && Math.abs(far[1] - u0[1] - u1[1]) < 1e-3;
    }

    private static boolean planar(List<Vert> q) {
        double[] n = normal(q);
        if (len(n) < 1e-9) {
            return false;
        }
        for (Vert v : q) {
            if (Math.abs(dot(sub(v.p(), q.get(0).p()), n)) > 1e-3) {
                return false;
            }
        }
        return true;
    }

    /** Newell normal, unit length (zero for a degenerate polygon). */
    static double[] normal(List<Vert> q) {
        double[] n = new double[3];
        for (int i = 0; i < q.size(); i++) {
            double[] a = q.get(i).p();
            double[] b = q.get((i + 1) % q.size()).p();
            n[0] += (a[1] - b[1]) * (a[2] + b[2]);
            n[1] += (a[2] - b[2]) * (a[0] + b[0]);
            n[2] += (a[0] - b[0]) * (a[1] + b[1]);
        }
        double l = len(n);
        return l < 1e-12 ? new double[3] : scale(n, 1 / l);
    }

    static JsonObject rectElement(List<Vert> q, String tex) {
        double[] e0 = sub(q.get(1).p(), q.get(0).p());
        double[] e1 = sub(q.get(3).p(), q.get(0).p());
        double[] n = normal(q);
        double[] du = sub2(q.get(1).uv(), q.get(0).uv());
        double[] dv = sub2(q.get(3).uv(), q.get(0).uv());
        // the world directions of +u and +v
        double[] eu = null;
        double[] ev = null;
        if (Math.abs(du[0]) > 1e-3) {
            eu = scale(e0, Math.signum(du[0]) / len(e0));
        } else if (Math.abs(dv[0]) > 1e-3) {
            eu = scale(e1, Math.signum(dv[0]) / len(e1));
        }
        if (Math.abs(du[1]) > 1e-3) {
            ev = scale(e0, Math.signum(du[1]) / len(e0));
        } else if (Math.abs(dv[1]) > 1e-3) {
            ev = scale(e1, Math.signum(dv[1]) / len(e1));
        }
        if (eu == null && ev == null) {
            eu = scale(e0, 1 / len(e0));
        }
        if (eu == null) {
            eu = cross(ev, n);
        }
        if (ev == null) {
            ev = cross(n, eu);
        }
        double[] ex = eu;
        double[] ey = scale(ev, -1); // v grows downwards
        boolean mirrored = dot(cross(ex, ey), n) < 0;
        if (mirrored) {
            ex = scale(ex, -1);
        }
        double w = Math.abs(dot(e0, ex)) + Math.abs(dot(e1, ex));
        double h = Math.abs(dot(e0, ey)) + Math.abs(dot(e1, ey));
        double uMin = Double.MAX_VALUE;
        double uMax = -Double.MAX_VALUE;
        double vMin = Double.MAX_VALUE;
        double vMax = -Double.MAX_VALUE;
        double[] c = new double[3];
        for (Vert v : q) {
            uMin = Math.min(uMin, v.uv()[0]);
            uMax = Math.max(uMax, v.uv()[0]);
            vMin = Math.min(vMin, v.uv()[1]);
            vMax = Math.max(vMax, v.uv()[1]);
            c = add(c, scale(v.p(), 0.25));
        }
        double[] uv = mirrored ? new double[]{uMax, vMin, uMin, vMax}
                : new double[]{uMin, vMin, uMax, vMax};
        return element(c, ex, ey, n, w, h, tex, uv);
    }

    /** One south-faced element whose face is the given rectangle in model space. */
    private static JsonObject element(double[] c, double[] ex, double[] ey, double[] n,
                                      double w, double h, String tex, double[] uv) {
        JsonObject el = new JsonObject();
        // The 26.3 element deserializer hard-rejects from/to outside [-16, 32]
        // (CuboidModelElement.getPosition -> 'from'/'to' specifier exceeds the
        // allowed boundaries), and ONE rejected model fails the whole carrier
        // bake: the paper.json range_dispatch references every gallery model,
        // so a single boundary graze unbaked ALL 72k cells into missing cubes
        // (#141: 'to' x=32.02 / 'from' x=-16.02 — the materialize-replaced
        // rects were never re-checked; fitElements only validates the
        // pre-replace poly geometry). Clamp at the single emission point: the
        // clamp moves the offending edge by <= 0.02 units = 0.076 px at the
        // contract scale, far under any gate-visible threshold.
        el.add("from", vec(clampLimit(c[0] - w / 2), clampLimit(c[1] - h / 2), clampLimit(c[2] - DEPTH)));
        el.add("to", vec(clampLimit(c[0] + w / 2), clampLimit(c[1] + h / 2), clampLimit(c[2])));
        double[] e = eulerOf(new double[][]{ex, ey, n});
        JsonObject rot = new JsonObject();
        rot.add("origin", vec(c[0], c[1], c[2]));
        rot.addProperty("x", round(e[0]));
        rot.addProperty("y", round(e[1]));
        rot.addProperty("z", round(e[2]));
        el.add("rotation", rot);
        JsonObject face = new JsonObject();
        JsonArray uvs = new JsonArray();
        for (double d : uv) {
            uvs.add(round(d));
        }
        face.add("uv", uvs);
        face.addProperty("texture", tex);
        JsonObject faces = new JsonObject();
        faces.add("south", face);
        el.add("faces", faces);
        return el;
    }

    private static JsonObject polyElement(List<Vert> q, String tex) {
        return polyElement(q, tex, null);
    }

    private static JsonObject polyElement(List<Vert> q, String tex, float[][] colors) {
        JsonObject el = new JsonObject();
        JsonObject poly = new JsonObject();
        poly.addProperty("tex", tex);
        JsonArray pts = new JsonArray();
        JsonArray uvs = new JsonArray();
        for (Vert v : q) {
            pts.add(vec(v.p()[0], v.p()[1], v.p()[2]));
            JsonArray uv = new JsonArray();
            uv.add(v.uv()[0]);
            uv.add(v.uv()[1]);
            uvs.add(uv);
        }
        poly.add("p", pts);
        poly.add("uv", uvs);
        if (colors != null) {
            JsonArray cs = new JsonArray();
            for (float[] c : colors) {
                JsonArray rgb = new JsonArray();
                for (float v : c) {
                    rgb.add(v);
                }
                cs.add(rgb);
            }
            poly.add("c", cs);
        }
        el.add(POLY, poly);
        return el;
    }

    /** Reads a sprite's PNG by id; null when it is missing. */
    interface SpriteReader {
        byte[] read(String sprite) throws IOException;
    }

    /**
     * Replace every polygon marker with a rectangle on a shared baked sheet:
     * the polygon's pixels, multiplied by its per-vertex colour, transparent
     * outside the shape. Identical tiles are stored once across the whole pack.
     */
    static void materialize(JsonArray elements, Map<String, String> textures, SpriteReader reader,
                            String spriteNs, Map<String, byte[]> entries,
                            List<String> warnings) throws IOException {
        for (int i = 0; i < elements.size(); i++) {
            JsonObject el = elements.get(i).getAsJsonObject();
            if (!el.has(POLY)) {
                continue;
            }
            JsonObject poly = el.getAsJsonObject(POLY);
            String ref = poly.get("tex").getAsString();
            String sprite = textures.get(ref.startsWith("#") ? ref.substring(1) : ref);
            BufferedImage src = BAKER.image(sprite, reader, warnings);
            Cell cell = new Cell(el, poly, src);
            int[] at = BAKER.place(cell.render(), cell.pw, cell.ph);
            String key = "cimcut" + at[0];
            textures.put(key, spriteNs + ":block/" + Baker.SHEET + at[0]);
            cell.x = at[1];
            cell.y = at[2];
            cell.replace(key, Baker.SIZE, Baker.SIZE);
        }
    }

    private static final Baker BAKER = new Baker();

    static void resetBaker() {
        BAKER.reset();
    }

    /**
     * 14-bs: an exact-pixel tile for a rect face's sprite window. The
     * near-miss statics sample the direct sprites through each leg's atlas;
     * the atlas layouts and mip chains differ (1.21.1 vs 26.3) and the
     * minified wall sampling blends neighbours differently. Baking the face's
     * own window as a tile (the shared sheets carry 2px gutters — wider than
     * the mip radius) makes the face sample ITS OWN pixels 1:1 and the atlas
     * layout stops mattering. Returns {sheet, x, y} like {@link Baker#place}.
     */
    static int[] placeExactTile(java.awt.image.BufferedImage img) {
        int w = img.getWidth();
        int h = img.getHeight();
        int[] argb = img.getRGB(0, 0, w, h, null, 0, w);
        return BAKER.place(argb, w, h);
    }

    /** Writes the baked sheets into the pack; call once, after every model is emitted. */
    static void flushBaker(String spriteNs, Map<String, byte[]> entries) throws IOException {
        BAKER.flush(spriteNs, entries);
    }

    /** Shared tile sheets with exact-tile dedupe. */
    private static final class Baker {
        static final int SIZE = 1024;
        static final String SHEET = "objcut/sheet";
        private final List<BufferedImage> sheets = new ArrayList<>();
        private final Map<String, int[]> tiles = new HashMap<>();
        private final Map<String, BufferedImage> images = new HashMap<>();
        private int x;
        private int y;
        private int shelf;

        void reset() {
            sheets.clear();
            tiles.clear();
            images.clear();
            x = 0;
            y = 0;
            shelf = 0;
        }

        BufferedImage image(String sprite, SpriteReader reader, List<String> warnings) throws IOException {
            if (sprite == null) {
                return null;
            }
            if (!images.containsKey(sprite)) {
                byte[] png = reader.read(sprite);
                if (png == null) {
                    warnings.add("obj: sprite missing: " + sprite);
                }
                images.put(sprite, png == null ? null : ImageIO.read(new java.io.ByteArrayInputStream(png)));
            }
            return images.get(sprite);
        }

        /** {sheet, x, y} of the tile, storing it when new. */
        int[] place(int[] argb, int w, int h) {
            String key = w + "x" + h + ":" + java.util.Arrays.hashCode(argb) + ":"
                    + java.util.Arrays.hashCode(java.util.Arrays.copyOf(argb, argb.length / 2));
            int[] hit = tiles.get(key);
            if (hit != null) {
                return hit;
            }
            if (sheets.isEmpty() || x + w + 2 > SIZE) {
                x = 0;
                y += shelf;
                shelf = 0;
            }
            if (sheets.isEmpty() || y + h + 2 > SIZE) {
                sheets.add(new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB));
                x = 0;
                y = 0;
                shelf = 0;
            }
            int sheet = sheets.size() - 1;
            int[] at = {sheet, x + 1, y + 1};
            sheets.get(sheet).setRGB(at[1], at[2], w, h, argb, 0, w);
            x += w + 2;
            shelf = Math.max(shelf, h + 2);
            tiles.put(key, at);
            return at;
        }

        void flush(String spriteNs, Map<String, byte[]> entries) throws IOException {
            for (int i = 0; i < sheets.size(); i++) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                ImageIO.write(sheets.get(i), "png", bytes);
                entries.put("assets/" + spriteNs + "/textures/block/" + SHEET + i + ".png", bytes.toByteArray());
            }
        }
    }

    /** One polygon's rectangle in its own plane, and its place in the atlas. */
    private static final class Cell {
        final JsonObject el;
        final BufferedImage src;
        final double[][] pts;
        final double[][] uvs;
        final double[] ex;
        final double[] ey;
        final double[] n;
        final double a0;
        final double a1;
        final double b0;
        final double b1;
        final int pw;
        final int ph;
        final String tex;
        final float[][] colors;
        int x;
        int y;

        Cell(JsonObject el, JsonObject poly, BufferedImage src) {
            this.el = el;
            this.src = src;
            tex = poly.get("tex").getAsString();
            JsonArray p = poly.getAsJsonArray("p");
            JsonArray u = poly.getAsJsonArray("uv");
            colors = poly.has("c") ? readColors(poly.getAsJsonArray("c")) : null;
            pts = new double[p.size()][3];
            uvs = new double[p.size()][2];
            List<Vert> vs = new ArrayList<>();
            for (int i = 0; i < p.size(); i++) {
                for (int k = 0; k < 3; k++) {
                    pts[i][k] = p.get(i).getAsJsonArray().get(k).getAsDouble();
                }
                uvs[i][0] = u.get(i).getAsJsonArray().get(0).getAsDouble();
                uvs[i][1] = u.get(i).getAsJsonArray().get(1).getAsDouble();
                vs.add(new Vert(pts[i], uvs[i], i, i));
            }
            n = normal(vs);
            double[] e = sub(pts[1], pts[0]);
            ex = scale(e, 1 / Math.max(len(e), 1e-9));
            ey = cross(n, ex);
            double aMin = Double.MAX_VALUE;
            double aMax = -Double.MAX_VALUE;
            double bMin = Double.MAX_VALUE;
            double bMax = -Double.MAX_VALUE;
            for (double[] q : pts) {
                aMin = Math.min(aMin, dot(q, ex));
                aMax = Math.max(aMax, dot(q, ex));
                bMin = Math.min(bMin, dot(q, ey));
                bMax = Math.max(bMax, dot(q, ey));
            }
            a0 = aMin;
            a1 = aMax;
            b0 = bMin;
            b1 = bMax;
            // four output pixels per source texel keep the slanted edge sharp:
            // 14-bj measured the BOP plant reds at ~110k ae on crops that look
            // near-identical — the truth's native render samples a 16-px sprite
            // at ~3.75 px/texel on the 60px/cell wall, and the old 2px/texel
            // cutout loses the edge-stepping race (lumaloop 21, glowworm_silk
            // 19, high_grass 13 at the #161 baseline). 4px/texel matches the
            // native density; the 120 cap keeps the sheet budget bounded.
            double texels = 1;
            int size = src == null ? 16 : src.getWidth();
            for (int i = 0; i < pts.length; i++) {
                int j = (i + 1) % pts.length;
                double dp = len(sub(pts[j], pts[i]));
                if (dp > 1e-6) {
                    texels = Math.max(texels, len(sub2(uvs[j], uvs[i])) / 16 * size / dp);
                }
            }
            pw = Math.max(2, Math.min(120, (int) Math.ceil((a1 - a0) * texels * 4)));
            ph = Math.max(2, Math.min(120, (int) Math.ceil((b1 - b0) * texels * 4)));
        }

        private static float[][] readColors(JsonArray cs) {
            float[][] out = new float[cs.size()][3];
            for (int i = 0; i < out.length; i++) {
                for (int k = 0; k < 3; k++) {
                    out[i][k] = cs.get(i).getAsJsonArray().get(k).getAsFloat();
                }
            }
            return out;
        }

        /** The tile: the polygon's texels times its interpolated colour, split as the GPU splits a quad. */
        int[] render() {
            int[] out = new int[pw * ph];
            if (src == null) {
                return out;
            }
            int w = src.getWidth();
            double[][] flat = new double[pts.length][];
            for (int i = 0; i < pts.length; i++) {
                flat[i] = new double[]{dot(pts[i], ex), dot(pts[i], ey)};
            }
            for (int j = 0; j < ph; j++) {
                double b = b1 - (j + 0.5) / ph * (b1 - b0);
                for (int i = 0; i < pw; i++) {
                    double a = a0 + (i + 0.5) / pw * (a1 - a0);
                    for (int t = 1; t + 1 < pts.length; t++) {
                        double[] l = bary(a, b, flat[0], flat[t], flat[t + 1]);
                        if (l == null) {
                            continue;
                        }
                        double u = l[0] * uvs[0][0] + l[1] * uvs[t][0] + l[2] * uvs[t + 1][0];
                        double v = l[0] * uvs[0][1] + l[1] * uvs[t][1] + l[2] * uvs[t + 1][1];
                        // 14-bm: the native render samples the sprite LINEARLY — the
                        // 14-bj audit measured the door panels' sub-pixel phase race
                        // on exactly this: the tile baked NEAREST texels and the GPU's
                        // linear filter then reproduced the staircase, not the sprite's
                        // gradients. Sample the sprite bilinearly at the same (u,v);
                        // the GPU's filter over the 4px/texel tile reconstructs the
                        // linear surface instead of the nearest staircase.
                        double sx = u / 16 * w - 0.5;
                        double sy = v / 16 * w - 0.5; // frame 0 of an animation
                        int px = (int) Math.floor(sx);
                        int py = (int) Math.floor(sy);
                        double fx = sx - px;
                        double fy = sy - py;
                        // vanilla atlas pads sprites by extending their edge texels
                        // (the stitcher's border pass) — clamp, never repeat
                        int tx0 = Math.min(w - 1, Math.max(0, px));
                        int ty0 = Math.min(w - 1, Math.max(0, py));
                        int tx1 = Math.min(w - 1, Math.max(0, px + 1));
                        int ty1 = Math.min(w - 1, Math.max(0, py + 1));
                        int argb = bilerp(src.getRGB(tx0, ty0), src.getRGB(tx1, ty0),
                                src.getRGB(tx0, ty1), src.getRGB(tx1, ty1), fx, fy);
                        if (colors != null) {
                            int rgb = 0;
                            for (int c = 0; c < 3; c++) {
                                double m = l[0] * colors[0][c] + l[1] * colors[t][c] + l[2] * colors[t + 1][c];
                                int shift = 16 - 8 * c;
                                int value = (int) Math.round(((argb >> shift) & 255) * m);
                                rgb |= Math.min(255, value) << shift;
                            }
                            argb = (argb & 0xFF000000) | rgb;
                        }
                        out[j * pw + i] = argb;
                        break;
                    }
                }
            }
            return out;
        }

        void replace(String key, int atlasW, int atlasH) {
            double[] c = add(add(scale(ex, (a0 + a1) / 2), scale(ey, (b0 + b1) / 2)),
                    scale(n, dot(pts[0], n)));
            double[] uv = {(double) x / atlasW * 16, (double) y / atlasH * 16,
                    (double) (x + pw) / atlasW * 16, (double) (y + ph) / atlasH * 16};
            JsonObject r = element(c, ex, ey, n, a1 - a0, b1 - b0, "#" + key, uv);
            el.remove(POLY);
            for (String k : List.of("from", "to", "rotation", "faces")) {
                el.add(k, r.get(k));
            }
        }
    }

    /** Straight-alpha bilinear of four texels — the GPU's magnification filter, exact. */
    private static int bilerp(int c00, int c10, int c01, int c11, double fx, double fy) {
        double w00 = (1 - fx) * (1 - fy);
        double w10 = fx * (1 - fy);
        double w01 = (1 - fx) * fy;
        double w11 = fx * fy;
        int argb = 0xFF000000;
        for (int c = 0; c < 4; c++) {
            int shift = 8 * c;
            double s = w00 * ((c00 >>> shift) & 255) + w10 * ((c10 >>> shift) & 255)
                    + w01 * ((c01 >>> shift) & 255) + w11 * ((c11 >>> shift) & 255);
            argb |= Math.min(255, Math.max(0, (int) Math.round(s))) << shift;
        }
        return argb;
    }

    private static double[] bary(double a, double b, double[] p0, double[] p1, double[] p2) {
        double d = (p1[1] - p2[1]) * (p0[0] - p2[0]) + (p2[0] - p1[0]) * (p0[1] - p2[1]);
        if (Math.abs(d) < 1e-12) {
            return null;
        }
        double l0 = ((p1[1] - p2[1]) * (a - p2[0]) + (p2[0] - p1[0]) * (b - p2[1])) / d;
        double l1 = ((p2[1] - p0[1]) * (a - p2[0]) + (p0[0] - p2[0]) * (b - p2[1])) / d;
        double l2 = 1 - l0 - l1;
        return l0 < -1e-9 || l1 < -1e-9 || l2 < -1e-9 ? null : new double[]{l0, l1, l2};
    }

    /** Columns {ex, ey, n} of R = Rz(z) Ry(y) Rx(x) -> {x, y, z} in degrees. */
    static double[] eulerOf(double[][] c) {
        double r00 = c[0][0];
        double r10 = c[0][1];
        double r20 = c[0][2];
        double r11 = c[1][1];
        double r21 = c[1][2];
        double r12 = c[2][1];
        double r22 = c[2][2];
        double y = Math.asin(Math.max(-1, Math.min(1, -r20)));
        double x;
        double z;
        if (Math.abs(r20) < 1 - 1e-9) {
            x = Math.atan2(r21, r22);
            z = Math.atan2(r10, r00);
        } else {
            x = Math.atan2(-r12, r11);
            z = 0;
        }
        return new double[]{Math.toDegrees(x), Math.toDegrees(y), Math.toDegrees(z)};
    }

    /** {x, y, z} degrees -> the columns of R = Rz(z) Ry(y) Rx(x). */
    static double[][] columnsOf(double xDeg, double yDeg, double zDeg) {
        double sx = Math.sin(Math.toRadians(xDeg));
        double cx = Math.cos(Math.toRadians(xDeg));
        double sy = Math.sin(Math.toRadians(yDeg));
        double cy = Math.cos(Math.toRadians(yDeg));
        double sz = Math.sin(Math.toRadians(zDeg));
        double cz = Math.cos(Math.toRadians(zDeg));
        return new double[][]{
                {cz * cy, sz * cy, -sy},
                {cz * sy * sx - sz * cx, sz * sy * sx + cz * cx, cy * sx},
                {cz * sy * cx + sz * sx, sz * sy * cx - cz * sx, cy * cx}};
    }

    private static double round(double d) {
        return Math.round(d * 100000.0) / 100000.0;
    }

    /** The 26.3 element boundary: from/to outside [-16, 32] kill the whole model. */
    private static double clampLimit(double d) {
        return d < MIN ? MIN : d > MAX ? MAX : d;
    }

    private static JsonArray vec(double a, double b, double c) {
        JsonArray v = new JsonArray();
        v.add(round(a));
        v.add(round(b));
        v.add(round(c));
        return v;
    }

    private static double[] add(double[] a, double[] b) {
        return new double[]{a[0] + b[0], a[1] + b[1], a[2] + b[2]};
    }

    private static double[] sub(double[] a, double[] b) {
        return new double[]{a[0] - b[0], a[1] - b[1], a[2] - b[2]};
    }

    private static double[] sub2(double[] a, double[] b) {
        return new double[]{a[0] - b[0], a[1] - b[1], 0};
    }

    private static double[] scale(double[] a, double k) {
        return new double[]{a[0] * k, a[1] * k, a[2] * k};
    }

    private static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    private static double[] cross(double[] a, double[] b) {
        return new double[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2],
                a[0] * b[1] - a[1] * b[0]};
    }

    private static double len(double[] a) {
        return Math.sqrt(dot(a, a));
    }
}
