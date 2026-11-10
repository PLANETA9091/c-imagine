package dev.cim.hud;

/**
 * The glyph position code shared by the pack, the shader and the encoder.
 *
 * <p>Technique from BetterHud (MIT, toxicity188): a font's {@code ascent} moves
 * glyphs far below the screen, and the amount it moves them is the data.
 * {@code text.vsh} reads it back from the vertex Y, removes it and places the
 * glyph at the anchor it names. The carrier is a boss bar whose bar sprites are
 * transparent, so the client redraws it every frame with no packets, and only
 * a change of content costs one name update.
 *
 * <p>One id is an anchor plus a row. Each id needs its own font, because the
 * ascent is a property of the font.
 */
public final class HudLayout {

    public enum Anchor {
        TOP_LEFT, TOP_CENTER, TOP_RIGHT,
        MIDDLE_LEFT, CENTER, MIDDLE_RIGHT,
        BOTTOM_LEFT, BOTTOM_CENTER, BOTTOM_RIGHT,
        /** Any pixel: the glyph's colour carries its position, see {@link #freeColor}. */
        FREE,
        /** The same, tinted the grey of a vanilla screen label. */
        FREE_DARK,
        /**
         * The same, for white text. 1.21.1 draws GUI text through the lightmap, and its
         * full-bright texel at noon is 252, not 255: a measured count digit is 0xFC.
         */
        FREE_TEXT,
        /** The drop shadow of that text: a quarter of 0xFC through the same lightmap, 0x3E. */
        FREE_SHADOW;

        public boolean right() {
            return this == TOP_RIGHT || this == MIDDLE_RIGHT || this == BOTTOM_RIGHT;
        }

        public boolean centered() {
            return this == TOP_CENTER || this == CENTER || this == BOTTOM_CENTER;
        }
    }

    static final int HEIGHT_BIT = 13;
    static final int MAX_BIT = 10;
    static final int ROW_BITS = 6;

    public static final int TEXT_ROWS = 16;
    public static final int TEXT_ROW_HEIGHT = 10;
    static final int FREE_ZERO = 2048;

    private HudLayout() {
    }

    public static int rows(Anchor a) {
        return a == Anchor.FREE || a == Anchor.FREE_DARK || a == Anchor.FREE_TEXT || a == Anchor.FREE_SHADOW ? 0 : TEXT_ROWS;
    }

    public static int id(Anchor a, int row) {
        if (row < 0 || row >= rows(a)) {
            throw new IllegalArgumentException(a + " has no row " + row);
        }
        return (a.ordinal() << ROW_BITS) | row;
    }

    public static int freeDarkId() {
        return Anchor.FREE_DARK.ordinal() << ROW_BITS;
    }

    public static int freeTextId() {
        return Anchor.FREE_TEXT.ordinal() << ROW_BITS;
    }

    public static int freeShadowId() {
        return Anchor.FREE_SHADOW.ordinal() << ROW_BITS;
    }

    /** The one id every freely placed glyph shares. */
    public static int freeId() {
        return Anchor.FREE.ordinal() << ROW_BITS;
    }

    /**
     * Colour that tells the shader where to put a glyph, in GUI pixels from the
     * screen centre. Fixed rows would need a font per y; this needs one font,
     * so a cursor or a widget can sit on any pixel. The glyph itself draws white.
     */
    public static int freeColor(int x, int y) {
        int px = x + FREE_ZERO;
        int py = y + FREE_ZERO;
        if (px < 0 || px > 4095 || py < 0 || py > 4095) {
            throw new IllegalArgumentException("off the coordinate range: " + x + "," + y);
        }
        return (px << 12) | py;
    }

    /** The font ascent that makes the shader read {@code id}. */
    public static int ascent(int id) {
        return -(((1 << MAX_BIT) + id) << HEIGHT_BIT);
    }

    /** Vertex Y the shader sees for a glyph of that ascent, before the baseline. */
    public static int decode(int vertexY) {
        int bit = vertexY >> HEIGHT_BIT;
        return ((bit >> MAX_BIT) & 1) == 1 ? bit - (1 << MAX_BIT) : -1;
    }

    public static String fontName(int id) {
        return "l" + id;
    }

    /** The 26.3 {@code core/text.vsh} with the decoder added. */
    static String vertexShader() {
        return """
                #version 330
                #extension GL_ARB_separate_shader_objects : require

                #if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
                #include <minecraft:fog.glsl>
                #include <minecraft:sample_lightmap.glsl>
                #endif

                #include <minecraft:dynamictransforms.glsl>
                #include <minecraft:projection.glsl>

                layout(location = 0) in vec3 Position;
                layout(location = 1) in vec4 Color;
                layout(location = 2) in vec2 UV0;
                #if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
                layout(location = 3) in ivec2 UV2;
                #endif

                #if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
                uniform sampler2D Sampler2;
                layout(location = 0) out float sphericalVertexDistance;
                layout(location = 1) out float cylindricalVertexDistance;
                #endif

                layout(location = 2) out vec4 vertexColor;
                layout(location = 3) out vec2 texCoord0;

                vec4 cimColor;

                // c-imagine HUD decoder. Technique: BetterHud (MIT), see HudLayout.java.
                vec3 cimHudPlace(vec3 pos) {
                    vec2 ui = ceil(2.0 / vec2(ProjMat[0][0], -ProjMat[1][1]));
                    if (pos.y < ui.y || ProjMat[3].x != -1.0) {
                        return pos;
                    }
                    int bit = int(pos.y) >> HEIGHT_BIT;
                    if (((bit >> MAX_BIT) & 1) != 1) {
                        return pos;
                    }
                    int id = bit - (1 << MAX_BIT);
                    int anchor = id >> ROW_BITS;
                    int row = id & ((1 << ROW_BITS) - 1);
                    pos.y -= float(bit << HEIGHT_BIT) + BASELINE;
                    if (anchor == FREE_ANCHOR || anchor == FREE_DARK_ANCHOR || anchor == FREE_TEXT_ANCHOR || anchor == FREE_SHADOW_ANCHOR) {
                        ivec3 c = ivec3(round(Color.rgb * 255.0));
                        // Drawn shadows carry a quarter of this colour, so runs turn them off.
                        pos.x += float((c.r << 4) | (c.g >> 4)) - FREE_ORIGIN;
                        pos.y += 0.5 * ui.y + float(((c.g & 15) << 8) | c.b) - FREE_ORIGIN + FREE_Y;
                        float shade = anchor == FREE_DARK_ANCHOR ? 0.25 : anchor == FREE_TEXT_ANCHOR ? 252.0 / 255.0
                                : anchor == FREE_SHADOW_ANCHOR ? 62.0 / 255.0 : 1.0;
                        cimColor = vec4(shade, shade, shade, Color.a);
                        return pos;
                    }
                    // The carrier centres its text, and the encoder nets every run to zero advance.
                    pos.x -= 0.5 * ui.x;
                    int col = anchor % 3;
                    int band = anchor / 3;
                    pos.x += float(col) * 0.5 * ui.x + (col == 0 ? MARGIN : col == 2 ? -MARGIN : 0.0);
                    float lines = float(row * TEXT_ROW_HEIGHT);
                    pos.y += band == 0 ? MARGIN + lines
                           : band == 1 ? 0.5 * ui.y + lines - TEXT_BLOCK / 2.0
                           : ui.y - BOTTOM_CLEAR - TEXT_BLOCK + lines;
                    return pos;
                }

                void main() {
                    cimColor = Color;
                    gl_Position = ProjMat * ModelViewMat * vec4(cimHudPlace(Position), 1.0);

                #if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
                    sphericalVertexDistance = fog_spherical_distance(Position);
                    cylindricalVertexDistance = fog_cylindrical_distance(Position);
                    vertexColor = cimColor * sample_lightmap(Sampler2, UV2);
                #else
                    vertexColor = cimColor;
                #endif
                    texCoord0 = UV0;
                }
                """
                .replace("HEIGHT_BIT", String.valueOf(HEIGHT_BIT))
                .replace("MAX_BIT", String.valueOf(MAX_BIT))
                .replace("ROW_BITS", String.valueOf(ROW_BITS))
                .replace("FREE_DARK_ANCHOR", String.valueOf(Anchor.FREE_DARK.ordinal()))
                .replace("FREE_TEXT_ANCHOR", String.valueOf(Anchor.FREE_TEXT.ordinal()))
                .replace("FREE_SHADOW_ANCHOR", String.valueOf(Anchor.FREE_SHADOW.ordinal()))
                .replace("FREE_ANCHOR", String.valueOf(Anchor.FREE.ordinal()))
                .replace("FREE_ORIGIN", FREE_ZERO + ".0")
                .replace("FREE_Y", "2.0")
                .replace("TEXT_ROW_HEIGHT", String.valueOf(TEXT_ROW_HEIGHT))
                .replace("TEXT_BLOCK", (TEXT_ROWS * TEXT_ROW_HEIGHT) + ".0")
                .replace("BOTTOM_CLEAR", "44.0")
                .replace("MARGIN", "4.0")
                .replace("BASELINE", "12.0");
    }
}
