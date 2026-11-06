package dev.f2v.vm.discovery;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Discovers client-side texture assemblers without loading client classes.
 *
 * <p>A surprisingly common renderer pattern is: create a NativeImage, copy rectangles from
 * atlas sprites into it, wrap it in DynamicTexture, then register the generated ResourceLocation.
 * Dedicated servers have all source PNGs but do not run that client bootstrap. This scanner turns
 * the bytecode construction chain into a small declarative recipe that pack generation can replay
 * with ImageIO. It intentionally keys off constructor/method shapes and NativeImage semantics,
 * never mod ids or concrete helper class names.</p>
 */
public final class DynamicTextureBytecodeScanner {

    private static final String RESOURCE_LOCATION = "net/minecraft/resources/ResourceLocation";
    private static final String NATIVE_IMAGE = "com/mojang/blaze3d/platform/NativeImage";
    private static final String DYNAMIC_TEXTURE = "net/minecraft/client/renderer/texture/DynamicTexture";

    private DynamicTextureBytecodeScanner() {}

    public static final class TextureRecipe {
        public String producerClass;
        public String producerMethod;
        /** Helper class that owns the NativeImage/DynamicTexture assembly implementation. */
        public String assemblerClass;
        public String outputNamespace;
        public String outputPathTemplate;
        public int width;
        public int height;
        public final List<MaterialRecipe> materials = new ArrayList<>();
    }

    public static final class MaterialRecipe {
        public String sourceNamespace = "minecraft";
        public String sourcePathTemplate;
        public int baseSize = 16;
        public final List<FillRecipe> fills = new ArrayList<>();

        MaterialRecipe copy() {
            MaterialRecipe out = new MaterialRecipe();
            out.sourceNamespace = sourceNamespace;
            out.sourcePathTemplate = sourcePathTemplate;
            out.baseSize = baseSize;
            for (FillRecipe fill : fills) out.fills.add(fill.copy());
            return out;
        }
    }

    public static final class FillRecipe {
        public int x;
        public int y;
        public int width;
        public int height;
        public int u;
        public int v;
        /** Quarter turns clockwise in source-image space. */
        public int quarterTurns;

        FillRecipe copy() {
            FillRecipe out = new FillRecipe();
            out.x = x; out.y = y; out.width = width; out.height = height;
            out.u = u; out.v = v; out.quarterTurns = quarterTurns;
            return out;
        }
    }

    private record MaterialAt(int start, int end, int local, MaterialRecipe recipe) {}

    public static List<TextureRecipe> scan(Map<String, ClassNode> nodes, String modId) {
        if (nodes == null || nodes.isEmpty()) return List.of();
        Set<String> assemblers = new LinkedHashSet<>();
        Set<String> materials = new LinkedHashSet<>();
        for (ClassNode node : nodes.values()) {
            if (isAssembler(node)) assemblers.add(node.name);
            if (isMaterial(node)) materials.add(node.name);
        }
        if (assemblers.isEmpty() || materials.isEmpty()) return List.of();

        List<TextureRecipe> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (ClassNode owner : nodes.values()) {
            for (MethodNode method : owner.methods) {
                List<AbstractInsnNode> code = instructions(method);
                if (code.isEmpty()) continue;
                List<MaterialAt> materialRecipes = parseMaterials(code, materials, assemblers);
                Map<Integer, MaterialRecipe> locals = new HashMap<>();
                for (MaterialAt material : materialRecipes) {
                    if (material.local >= 0) locals.put(material.local, material.recipe);
                }
                for (int i = 0; i < code.size(); i++) {
                    AbstractInsnNode insn = code.get(i);
                    if (!(insn instanceof TypeInsnNode type) || type.getOpcode() != Opcodes.NEW
                            || !assemblers.contains(type.desc)) continue;
                    TextureRecipe recipe = parseAssembler(code, i, type.desc, materialRecipes,
                            locals, modId, owner.name, method.name + method.desc);
                    if (recipe == null || recipe.outputPathTemplate == null
                            || recipe.materials.isEmpty()) continue;
                    String key = recipe.outputNamespace + ':' + recipe.outputPathTemplate + '|'
                            + recipe.width + 'x' + recipe.height;
                    if (seen.add(key)) out.add(recipe);
                }
            }
        }
        out.sort(Comparator.comparing((TextureRecipe r) -> r.outputNamespace)
                .thenComparing(r -> r.outputPathTemplate));
        return out;
    }

    private static boolean isAssembler(ClassNode node) {
        boolean ctor = node.methods.stream().anyMatch(method -> method.name.equals("<init>")
                && method.desc.equals("(Lnet/minecraft/resources/ResourceLocation;II)V"));
        if (!ctor) return false;
        for (MethodNode method : node.methods) {
            boolean image = false, texture = false;
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof TypeInsnNode type && type.getOpcode() == Opcodes.NEW) {
                    image |= NATIVE_IMAGE.equals(type.desc);
                    texture |= DYNAMIC_TEXTURE.equals(type.desc);
                }
            }
            if (image && texture) return true;
        }
        return false;
    }

    private static boolean isMaterial(ClassNode node) {
        boolean ctor = node.methods.stream().anyMatch(method -> method.name.equals("<init>")
                && method.desc.equals("(Lnet/minecraft/resources/ResourceLocation;I)V"));
        if (!ctor) return false;
        String result = 'L' + node.name + ';';
        return node.methods.stream().anyMatch(method -> method.desc.startsWith("(IIII")
                && Type.getReturnType(method.desc).getDescriptor().equals(result));
    }

    private static List<MaterialAt> parseMaterials(List<AbstractInsnNode> code,
                                                    Set<String> materials,
                                                    Set<String> assemblers) {
        List<MaterialAt> out = new ArrayList<>();
        for (int start = 0; start < code.size(); start++) {
            AbstractInsnNode raw = code.get(start);
            if (!(raw instanceof TypeInsnNode type) || raw.getOpcode() != Opcodes.NEW
                    || !materials.contains(type.desc)) continue;
            int ctor = findConstructor(code, start, type.desc,
                    "(Lnet/minecraft/resources/ResourceLocation;I)V", 30);
            if (ctor < 0) continue;
            MaterialRecipe recipe = new MaterialRecipe();
            Integer size = intValue(previous(code, ctor));
            if (size != null && size > 0) recipe.baseSize = size;
            String template = stringTemplateBefore(code, ctor, 24);
            if (template == null) continue;
            String[] source = splitResource(template, "minecraft");
            recipe.sourceNamespace = source[0];
            recipe.sourcePathTemplate = stripTextureAffixes(source[1]);

            int end = ctor;
            int local = -1;
            for (int at = ctor + 1; at < Math.min(code.size(), ctor + 120); at++) {
                AbstractInsnNode insn = code.get(at);
                if (insn instanceof TypeInsnNode next && next.getOpcode() == Opcodes.NEW
                        && (materials.contains(next.desc) || assemblers.contains(next.desc))) break;
                if (insn instanceof MethodInsnNode call && call.owner.equals(type.desc)
                        && Type.getReturnType(call.desc).getDescriptor().equals('L' + type.desc + ';')) {
                    FillRecipe fill = parseFill(code, at, call);
                    if (fill != null) recipe.fills.add(fill);
                    end = at;
                }
                if (insn instanceof VarInsnNode var && var.getOpcode() == Opcodes.ASTORE) {
                    local = var.var;
                    end = at;
                    break;
                }
                if (insn instanceof MethodInsnNode call && assemblers.contains(call.owner)) {
                    end = at;
                    break;
                }
            }
            if (!recipe.fills.isEmpty()) out.add(new MaterialAt(start, end, local, recipe));
        }
        return out;
    }

    private static TextureRecipe parseAssembler(List<AbstractInsnNode> code, int start,
                                                 String assembler,
                                                 List<MaterialAt> materials,
                                                 Map<Integer, MaterialRecipe> locals,
                                                 String modId, String owner, String method) {
        int ctor = findConstructor(code, start, assembler,
                "(Lnet/minecraft/resources/ResourceLocation;II)V", 40);
        if (ctor < 0) return null;
        AbstractInsnNode heightInsn = previous(code, ctor);
        AbstractInsnNode widthInsn = previous(code, indexOf(code, heightInsn));
        Integer width = intValue(widthInsn), height = intValue(heightInsn);
        String output = stringTemplateBefore(code, ctor, 32);
        if (width == null || height == null || width <= 0 || height <= 0 || output == null) return null;

        TextureRecipe recipe = new TextureRecipe();
        recipe.producerClass = owner;
        recipe.producerMethod = method;
        recipe.assemblerClass = assembler;
        String[] target = splitResource(output, modId == null || modId.isBlank() ? "minecraft" : modId);
        recipe.outputNamespace = target[0];
        recipe.outputPathTemplate = ensureTexturePng(target[1]);
        recipe.width = width;
        recipe.height = height;

        int end = code.size();
        for (int i = ctor + 1; i < code.size(); i++) {
            if (code.get(i) instanceof TypeInsnNode next && next.getOpcode() == Opcodes.NEW
                    && assembler.equals(next.desc)) {
                end = i;
                break;
            }
        }
        int previousAdd = ctor;
        for (int at = ctor + 1; at < end; at++) {
            AbstractInsnNode insn = code.get(at);
            if (!(insn instanceof MethodInsnNode call) || !assembler.equals(call.owner)) continue;
            Type[] args = Type.getArgumentTypes(call.desc);
            if (args.length != 1 || args[0].getSort() != Type.OBJECT) continue;
            AbstractInsnNode before = previous(code, at);
            MaterialRecipe selected = null;
            if (before instanceof VarInsnNode var && var.getOpcode() == Opcodes.ALOAD) {
                selected = locals.get(var.var);
            }
            if (selected == null) {
                MaterialAt best = null;
                for (MaterialAt candidate : materials) {
                    if (candidate.start <= previousAdd || candidate.start >= at) continue;
                    if (best == null || candidate.start > best.start) best = candidate;
                }
                if (best != null) selected = best.recipe;
            }
            if (selected != null) recipe.materials.add(selected.copy());
            previousAdd = at;
        }
        return recipe;
    }

    private static FillRecipe parseFill(List<AbstractInsnNode> code, int at, MethodInsnNode call) {
        Type[] args = Type.getArgumentTypes(call.desc);
        if (args.length < 4 || args[0].getSort() != Type.INT || args[1].getSort() != Type.INT
                || args[2].getSort() != Type.INT || args[3].getSort() != Type.INT) return null;
        List<AbstractInsnNode> producers = new ArrayList<>();
        int cursor = at;
        for (int i = args.length - 1; i >= 0; i--) {
            AbstractInsnNode source = previous(code, cursor);
            if (source == null) return null;
            producers.add(0, source);
            cursor = indexOf(code, source);
        }
        Integer x = intValue(producers.get(0)), y = intValue(producers.get(1));
        Integer w = intValue(producers.get(2)), h = intValue(producers.get(3));
        if (x == null || y == null || w == null || h == null) return null;
        FillRecipe fill = new FillRecipe();
        fill.x = x; fill.y = y; fill.width = w; fill.height = h;
        int matrixIndex = -1;
        for (int i = 4; i < args.length; i++) {
            if (args[i].getSort() == Type.ARRAY) {
                matrixIndex = i;
                fill.quarterTurns = matrixTurns(producers.get(i));
                break;
            }
        }
        if (matrixIndex >= 0 && matrixIndex + 2 < producers.size()) {
            Integer u = intValue(producers.get(matrixIndex + 1));
            Integer v = intValue(producers.get(matrixIndex + 2));
            fill.u = u == null ? 0 : u;
            fill.v = v == null ? 0 : v;
        }
        return fill;
    }

    private static int matrixTurns(AbstractInsnNode source) {
        if (!(source instanceof FieldInsnNode field)) return 0;
        // Degree-bearing names are common for reusable rotation matrices, but this remains a
        // semantic fallback rather than a class-name dependency. Unknown matrices fail to R0.
        String digits = field.name.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) return 0;
        try {
            return Math.floorMod(Math.round(Integer.parseInt(digits) / 90f), 4);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static int findConstructor(List<AbstractInsnNode> code, int start, String owner,
                                       String descriptor, int distance) {
        for (int i = start + 1; i < Math.min(code.size(), start + distance); i++) {
            if (code.get(i) instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESPECIAL
                    && call.owner.equals(owner) && call.name.equals("<init>")
                    && call.desc.equals(descriptor)) return i;
        }
        return -1;
    }

    private static String stringTemplateBefore(List<AbstractInsnNode> code, int at, int distance) {
        for (int i = at - 1; i >= Math.max(0, at - distance); i--) {
            AbstractInsnNode insn = code.get(i);
            if (insn instanceof InvokeDynamicInsnNode indy && "makeConcatWithConstants".equals(indy.name)
                    && indy.bsmArgs != null && indy.bsmArgs.length > 0
                    && indy.bsmArgs[0] instanceof String recipe) {
                return resolveLiteralConcatLocals(code, i, indy,
                        concatTemplate(recipe, indy.bsmArgs));
            }
            if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof String value
                    && looksResourcePath(value)) return value;
        }
        return null;
    }

    private static String concatTemplate(String recipe, Object[] constants) {
        StringBuilder out = new StringBuilder();
        int dynamic = 0, constant = 1;
        for (int i = 0; i < recipe.length(); i++) {
            char ch = recipe.charAt(i);
            if (ch == '\u0001') out.append("${").append(dynamic++).append('}');
            else if (ch == '\u0002' && constant < constants.length) out.append(constants[constant++]);
            else out.append(ch);
        }
        return out.toString();
    }

    /**
     * StringConcatFactory recipes only preserve the shape of dynamic operands. When one operand
     * is a local whose value came from a constant default (for example map.getOrDefault(x,
     * "log")), recover that literal so the server does not have to guess among similarly named
     * source textures. This is deliberately conservative: direct method-call results stay as
     * placeholders and therefore continue to drive variant discovery.
     */
    private static String resolveLiteralConcatLocals(List<AbstractInsnNode> code, int indyIndex,
                                                     InvokeDynamicInsnNode indy, String template) {
        Type[] args = Type.getArgumentTypes(indy.desc);
        int cursor = indyIndex;
        String out = template;
        for (int arg = args.length - 1; arg >= 0; arg--) {
            AbstractInsnNode producer = previous(code, cursor);
            if (producer == null) break;
            cursor = indexOf(code, producer);
            if (!(producer instanceof VarInsnNode var) || var.getOpcode() != Opcodes.ALOAD) continue;
            String literal = literalAssignedToLocal(code, cursor, var.var);
            if (literal != null) out = out.replace("${" + arg + "}", literal);
        }
        return out;
    }

    private static String literalAssignedToLocal(List<AbstractInsnNode> code, int before, int local) {
        // Generator loops often build several rectangle fills between the local initialization and
        // a later material constructor, so keep this window comfortably larger than one texture
        // recipe while still staying inside the same method body.
        for (int i = before - 1; i >= Math.max(0, before - 400); i--) {
            AbstractInsnNode insn = code.get(i);
            if (!(insn instanceof VarInsnNode store) || store.getOpcode() != Opcodes.ASTORE
                    || store.var != local) continue;
            for (int j = i - 1; j >= Math.max(0, i - 16); j--) {
                if (code.get(j) instanceof LdcInsnNode ldc && ldc.cst instanceof String value
                        && !looksResourcePath(value)) return value;
            }
            return null;
        }
        return null;
    }

    private static boolean looksResourcePath(String value) {
        return value != null && !value.isBlank() && (value.contains("/") || value.contains(":"));
    }

    private static String[] splitResource(String value, String defaultNamespace) {
        String normalized = value == null ? "" : value;
        int colon = normalized.indexOf(':');
        if (colon > 0 && normalized.substring(0, colon).matches("[a-z0-9_.-]+")) {
            return new String[]{normalized.substring(0, colon), normalized.substring(colon + 1)};
        }
        return new String[]{defaultNamespace, normalized};
    }

    private static String stripTextureAffixes(String path) {
        String out = path == null ? "" : path;
        if (out.startsWith("textures/")) out = out.substring("textures/".length());
        if (out.endsWith(".png")) out = out.substring(0, out.length() - 4);
        return out;
    }

    private static String ensureTexturePng(String path) {
        String out = path == null ? "" : path;
        if (!out.startsWith("textures/")) out = "textures/" + out;
        if (!out.endsWith(".png")) out += ".png";
        return out;
    }

    private static Integer intValue(AbstractInsnNode insn) {
        if (insn == null) return null;
        if (insn instanceof IntInsnNode value) return value.operand;
        if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof Number number) return number.intValue();
        return switch (insn.getOpcode()) {
            case Opcodes.ICONST_M1 -> -1;
            case Opcodes.ICONST_0 -> 0;
            case Opcodes.ICONST_1 -> 1;
            case Opcodes.ICONST_2 -> 2;
            case Opcodes.ICONST_3 -> 3;
            case Opcodes.ICONST_4 -> 4;
            case Opcodes.ICONST_5 -> 5;
            default -> null;
        };
    }

    private static AbstractInsnNode previous(List<AbstractInsnNode> code, int index) {
        for (int i = index - 1; i >= 0; i--) {
            AbstractInsnNode insn = code.get(i);
            if (insn.getOpcode() >= 0) return insn;
        }
        return null;
    }

    private static int indexOf(List<AbstractInsnNode> code, AbstractInsnNode target) {
        return target == null ? -1 : code.indexOf(target);
    }

    private static List<AbstractInsnNode> instructions(MethodNode method) {
        List<AbstractInsnNode> out = new ArrayList<>();
        for (AbstractInsnNode insn : method.instructions) out.add(insn);
        return out;
    }
}
