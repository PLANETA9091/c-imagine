package dev.cim.f2v;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/** Native item layers for block quads whose source explicitly disables directional shading. */
final class UnlitBlockModel {
    // Native 26.3 world Light0. A parallel unit normal makes mix_light
    // saturate at 1 (.6 * 1 + .4), in both Overworld and Nether lighting.
    private static final double LENGTH = Math.sqrt(1.53);
    private static final double[] LIGHT = {.2 / LENGTH, 1 / LENGTH, -.7 / LENGTH};

    private UnlitBlockModel() {
    }

    record Plane(JsonObject element, JsonArray transformation) {
    }

    /**
     * Keep all surface points, UVs and colors. Bake the plane within 30
     * degrees of south, then restore positions with a supported item-local
     * affine transform whose normal is fully lit. This represents BLOCK
     * shade:false; an item's own shader has a different lighting contract.
     */
    static Plane plane(JsonObject source, double scale) {
        JsonObject old = source.getAsJsonObject("rotation");
        double[][] original = old == null ? identity()
                : transpose(ObjMesh.columnsOf(angle(old, "x"), angle(old, "y"), angle(old, "z")));
        double[] origin = old == null ? new double[]{.5, .5, .5} : vector(old, "origin", 1.0 / 16);
        double[] desired = apply(transpose(original), LIGHT);
        double magnitude = Math.hypot(desired[0], desired[1]);
        double[][] bake = identity();
        double[][] shear = identity();
        if (magnitude < 1e-10) {
            shear[2][2] = Math.copySign(1, desired[2]);
        } else {
            double tx = desired[0] / magnitude;
            double ty = desired[1] / magnitude;
            // Near a parallel normal use bounded depth scaling, not a huge
            // shear. All tangents in the source surface remain untouched.
            double sine = magnitude < .1 ? magnitude * .5 : .5;
            double cosine = Math.sqrt(1 - sine * sine);
            double[] axis = {ty, -tx, 0};
            double[][] cross = {{0, 0, -tx}, {0, 0, -ty}, {tx, ty, 0}};
            for (int r = 0; r < 3; r++) {
                for (int c = 0; c < 3; c++) {
                    bake[r][c] = (r == c ? cosine : 0)
                            + (1 - cosine) * axis[r] * axis[c] + sine * cross[r][c];
                }
            }
            double lambda = sine / magnitude;
            if (magnitude < .1) {
                shear[2][2] = cosine / (lambda * desired[2]);
            } else {
                double v = (cosine - lambda * desired[2]) / sine;
                shear[0][2] = v * tx;
                shear[1][2] = v * ty;
            }
        }
        double[] from = vector(source, "from", 1.0 / 16);
        double[] to = vector(source, "to", 1.0 / 16);
        double[] center = {(from[0] + to[0]) / 2, (from[1] + to[1]) / 2, to[2]};
        double[][] linear = multiply(multiply(original, shear), transpose(bake));
        double[] relative = new double[3];
        for (int i = 0; i < 3; i++) {
            relative[i] = center[i] - origin[i];
        }
        double[] world = apply(original, relative);
        double[] moved = apply(linear, center);
        JsonArray matrix = new JsonArray();
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                matrix.add(scale * linear[row][col]);
            }
            // Restore oversized meshes about the block center too.
            matrix.add(scale * (world[row] + origin[row] - moved[row]) + (1 - scale) / 2);
        }
        for (double value : new double[]{0, 0, 0, 1}) {
            matrix.add(value);
        }
        double[] angles = ObjMesh.eulerOf(transpose(bake));
        JsonObject rotation = new JsonObject();
        JsonArray pivot = new JsonArray();
        for (double value : center) {
            pivot.add(value * 16);
        }
        rotation.add("origin", pivot);
        rotation.addProperty("x", angles[0]);
        rotation.addProperty("y", angles[1]);
        rotation.addProperty("z", angles[2]);
        JsonObject element = source.deepCopy();
        element.add("rotation", rotation);
        return new Plane(element, matrix);
    }

    private static double angle(JsonObject object, String key) {
        return object.has(key) ? object.get(key).getAsDouble() : 0;
    }

    private static double[] vector(JsonObject object, String key, double scale) {
        JsonArray array = object.getAsJsonArray(key);
        return new double[]{array.get(0).getAsDouble() * scale, array.get(1).getAsDouble() * scale,
                array.get(2).getAsDouble() * scale};
    }

    private static double[][] identity() {
        return new double[][]{{1, 0, 0}, {0, 1, 0}, {0, 0, 1}};
    }

    private static double[][] transpose(double[][] matrix) {
        double[][] result = new double[3][3];
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 3; c++) {
                result[r][c] = matrix[c][r];
            }
        }
        return result;
    }

    private static double[] apply(double[][] matrix, double[] vector) {
        double[] result = new double[3];
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 3; c++) {
                result[r] += matrix[r][c] * vector[c];
            }
        }
        return result;
    }

    private static double[][] multiply(double[][] a, double[][] b) {
        double[][] result = new double[3][3];
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 3; c++) {
                for (int k = 0; k < 3; k++) {
                    result[r][c] += a[r][k] * b[k][c];
                }
            }
        }
        return result;
    }
}
