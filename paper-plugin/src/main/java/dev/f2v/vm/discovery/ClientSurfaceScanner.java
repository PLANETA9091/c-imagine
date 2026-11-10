package dev.f2v.vm.discovery;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.Handle;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.Interpreter;
import org.objectweb.asm.tree.analysis.SourceInterpreter;
import org.objectweb.asm.tree.analysis.SourceValue;
import org.objectweb.asm.tree.analysis.Value;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Static "client surface" scanner used by the compatibility VM. It never loads or executes a
 * discovered mod class. Instead it turns client bytecode into a declarative recipe describing
 * which parts can be represented by F2V's HUD, input mirror, state mirror or display proxies.
 *
 * <p>This distinction is intentional: arbitrary client mods may touch GLFW/OpenGL, the local
 * filesystem or external networking. A discovery result is therefore evidence for translation,
 * never permission to execute the original class on a dedicated server.</p>
 */
public final class ClientSurfaceScanner {

    // 19: scan-budget exhaustion is explicit compatibility data instead of silent omission.
    // Cached scans from older schemas cannot prove completeness and must therefore be discarded.
    public static final int SCHEMA = 82;
    // Per-class safety caps. Hitting one is a certification failure, not a correctness shortcut:
    // markScanTruncated records exactly which category overflowed so callers can reject partial
    // compatibility profiles instead of reporting them as supported.
    private static final int MAX_REFERENCES = 192;
    private static final int MAX_HOOKS = 128;
    private static final int MAX_KEYBINDS = 256;
    private static final int MAX_ACTIONS = 384;

    public enum Kind {
        SCREEN, HUD, OVERLAY, MAP, KEYBIND, INPUT, CLIENT_TICK, RENDERER, CONFIG, NETWORK, MIXIN
    }

    public enum Translation {
        HUD_SCENE, MAP_HUD, INPUT_BRIDGE, STATE_MIRROR, DISPLAY_PROXY, CAPTURE_RENDER,
        HOOK_MIRROR, CLIENT_SERVICE_FALLBACK, UNSUPPORTED
    }

    /** Compatibility contract used by audits/runtime fallbacks. Unsupported is always explicit. */
    public enum CompatibilityState {
        NATIVE_BRIDGE, VANILLA_REPRESENTATION, VISUAL_ONLY, UNSUPPORTED
    }

    /** Concrete behavior observed in bytecode after a client input is consumed. */
    public enum ActionType {
        OPEN_SCREEN, NAVIGATE_BACK, OPEN_URL, CLOSE_SCREEN, SEND_PACKET, WRITE_STATE, TOGGLE_COLLECTION,
        INVOKE_CLIENT_HELPER, EXTERNAL_NETWORK_IO, LAUNCH_PROCESS, LOAD_NATIVE_LIBRARY
    }

    /** Exact scalar state mutation recovered from a client callback. Null on an Action means the
     * scanner saw a field write but could not prove the value/operation and runtime must not guess. */
    public enum StateWriteKind {
        ASSIGN_BOOLEAN, TOGGLE_BOOLEAN, ASSIGN_INT, ADD_INT, WRAP_ADD_INT,
        ASSIGN_LONG, ASSIGN_FLOAT, ASSIGN_DOUBLE, ASSIGN_STRING, ASSIGN_INPUT_STRING,
        ASSIGN_STATIC_FIELD
    }

    /** Runtime-visible framework state of one exact translated widget. */
    public enum WidgetRuntimeState {
        VISIBLE, ENABLED
    }

    /** What concrete client input was observed to lead to an action. */
    public enum TriggerType {
        KEYBIND, WIDGET, SCREEN_CLOSE
    }

    public enum ScanIssueType {
        SCAN_TRUNCATED
    }

    /** Structured scanner failure retained in discovery-cache JSON and compatibility reports. */
    public static final class ScanIssue {
        public ScanIssueType type;
        public String category;
        public int limit;

        public ScanIssue() {}

        private ScanIssue(ScanIssueType type, String category, int limit) {
            this.type = type;
            this.category = category;
            this.limit = limit;
        }
    }

    /**
     * Anchor used by scanner-recovered widget coordinates. Keeping this provenance matters for
     * expressions such as {@code width / 2 - 100}: reducing that to a bare {@code -100} makes the
     * runtime mistake a centered control for a trailing-edge inset. LEGACY preserves old cached
     * profiles and deliberately retains the trailing-edge heuristic for expressions whose origin
     * could not be proven.
     */
    public enum WidgetCoordinateAnchor {
        LEGACY, START, CENTER, END
    }

    /** Declarative source for one exact packet constructor argument. Only sources that can be
     * reconstructed without executing client code belong here. */
    public enum PacketArgumentKind {
        BOOLEAN_LITERAL, INT_LITERAL, LONG_LITERAL, FLOAT_LITERAL, DOUBLE_LITERAL,
        STRING_LITERAL, UUID_LITERAL, STATIC_FIELD, SERVER_PLAYER, SERVER_PLAYER_ENTITY_ID, SERVER_PLAYER_UUID,
        MENU_BLOCK_POS, MENU_ENTITY_UUID,
        /** Intermediate scanner evidence only. A packet may read a source-screen field that the
         * same callback wrote immediately before sending. This kind is never runtime-replayable by
         * itself; it must be folded to an exact literal/static value before the Action is certified. */
        SCREEN_FIELD,
        /** Intermediate evidence for a scalar produced by an exact zero-arg getter on a
         * source-screen field. The getter itself is proven to be a simple backing-field read, so
         * runtime can materialize the value from payload-seeded virtual screen state without
         * executing arbitrary client code. */
        SCREEN_FIELD_GETTER
    }

    /** One scanner-proven packet constructor argument. Index and descriptor make the evidence
     * independent of field/parameter names and let both loader runtimes select the exact
     * constructor without guessing overloads. */
    public static final class PacketArgument {
        public int index = -1;
        public String descriptor;
        public PacketArgumentKind kind;
        public Boolean booleanValue;
        public Integer intValue;
        public Long longValue;
        public Double doubleValue;
        public String stringValue;
        /** Canonical textual UUID for UUID_LITERAL; kept textual so discovery-cache JSON stays
         * loader/version agnostic. */
        public String uuidValue;
        public String fieldOwner;
        public String fieldName;
        public String fieldDesc;
        /** For SCREEN_FIELD_GETTER, the exact simple getter and backing field read from the object
         * stored in fieldOwner/fieldName/fieldDesc. */
        public String accessorOwner;
        public String accessorName;
        public String accessorDesc;
        public String accessorFieldOwner;
        public String accessorFieldName;
        public String accessorFieldDesc;
        /** Optional scanner-proven DTO/source field copied into the accessor backing field by one
         * exact zero-argument converter. Runtime may use this only when the clientbound opening
         * handler is independently proven to invoke that converter. */
        public String mirrorSourceOwner;
        public String mirrorSourceFieldName;
        public String mirrorSourceFieldDesc;
        public String mirrorConverterOwner;
        public String mirrorConverterMethod;
        public String mirrorConverterDesc;
        /** Exact server menu member used to resolve a target-derived argument. The runtime reads
         * this member only from the player's current AbstractContainerMenu; arbitrary client
         * objects are never loaded or instantiated. */
        public String menuOwner;
        public String menuMemberName;
        public String menuMemberDesc;
        public boolean menuMemberMethod;
    }

    /** One authoritative server target recoverable from the player's current container menu.
     * Every menu-derived packet argument on a screen must agree on this exact member before the
     * target is exposed; ambiguous screens deliberately return no target evidence. */
    public record MenuTarget(PacketArgumentKind kind, String menuOwner, String memberName,
                             String memberDesc, boolean memberMethod, String targetType) {}

    /** One client-facing class discovered in a mod jar. Public fields keep discovery-cache JSON
     * stable and let both loader variants serialize the same shape without adapters. */
    public static final class ClassSurface {
        public String clazz;
        /** Direct bytecode superclass. Kept declarative so inherited screen widgets can be
         * reconstructed without loading any client class on the dedicated server. */
        public String superClass;
        public int clientScore;
        public boolean clientOnlyLikely;
        /** True when loader metadata proves this class belongs to the client distribution but
         * bytecode exposes no observable runtime surface that F2V must translate. These classes
         * remain in discovery/cache output for diagnostics, but must not be treated as unsupported
         * bridge work merely because the loader marked them client-only. */
        public boolean metadataOnly;
        /** True when the class is a client-only external runtime service (web/file/native helper)
         * with no independently discovered screen/input/render/map/hook/action contract. F2V cannot
         * execute that service on the dedicated server, but the service is ancillary to bridgeable
         * gameplay surfaces and must not make the whole mod look structurally unsupported. */
        public boolean runtimeServiceOnly;
        /** True when external I/O is used only to obtain visual data for a renderer/display surface
         * with no interactive, map, mixin, or action contract. F2V can safely keep the display proxy
         * and substitute an authored/placeholder visual instead of executing the remote fetch. */
        public boolean remoteVisualFallback;
        /** True when this class contains hard external runtime code, but none of that code is
         * reachable from a translated key/widget/hook. Preserve the evidence for diagnostics while
         * keeping unrelated setup/tooling from poisoning the declarative client surface. */
        public boolean ancillaryRuntimeEffects;
        /** Scanner-proven client-machine-only service that F2V deliberately does not execute.
         * The bridge keeps the surrounding vanilla/server representation and safely omits the
         * remote I/O/process/native side effect. User-triggered omissions are surfaced at runtime
         * instead of being silently treated as if the client service ran. */
        public boolean clientServiceFallback;
        public boolean directExecutionAllowed;
        public final Set<Kind> kinds = new LinkedHashSet<>();
        public final Set<Translation> translations = new LinkedHashSet<>();
        public final Set<String> keybinds = new LinkedHashSet<>();
        public final Set<String> clientReferences = new LinkedHashSet<>();
        public final Set<String> mixinTargets = new LinkedHashSet<>();
        public final Set<String> blockers = new LinkedHashSet<>();
        /** Exact methods that directly perform hard runtime effects. Method ids are name+descriptor
         * so discovery can compare them to hook/action provenance without loading the class. */
        public final Set<String> externalNetworkMethods = new LinkedHashSet<>();
        public final Set<String> processNativeMethods = new LinkedHashSet<>();
        /** False means at least one scanner safety budget was exhausted for this class. */
        public boolean scanComplete = true;
        public final List<ScanIssue> scanIssues = new ArrayList<>();
        public final List<Hook> hooks = new ArrayList<>();
        public final List<Action> actions = new ArrayList<>();
    }

    public static final class Hook {
        public String method;
        public String descriptor;
        /** True only when this hook came from an explicit Mixin injection annotation. Used to
         * discard server/common injection methods until the enclosing mixin is proven client-side. */
        public boolean mixinInjection;
        public final Set<Kind> kinds = new LinkedHashSet<>();
    }

    /** Keybind -> behavior edge recovered from the actual class code. */
    public static final class Action {
        public ActionType type;
        public TriggerType triggerType = TriggerType.KEYBIND;
        public String keybind;
        /** Owning screen for widget-triggered edges. Internal JVM class name. */
        public String sourceScreen;
        /** Zero-based construction order within the screen's widget-init method. */
        public int widgetOrdinal = -1;
        /** Captured/translated label evidence when recoverable; may be a translation key. */
        public String widgetLabel;
        /** Concrete widget class that owns the callback when recoverable. */
        public String widgetClass;
        /** Static visual passed to the widget constructor/factory when recoverable. The runtime
         * can resolve this through GuiSpriteBytecodeScanner and preserve native button art instead
         * of drawing generic compatibility chrome over a server-reconstructed screen. */
        public String widgetVisualOwner;
        public String widgetVisualField;
        /** Explicit initial widget state recovered from the screen's init bytecode. Null means the
         * screen leaves the framework default unchanged. These are deliberately separate from
         * callback behavior: a hidden/disabled widget still has a callback in bytecode, but must
         * not become a live compatibility control in the starting GUI state. */
        public Boolean widgetInitiallyVisible;
        public Boolean widgetInitiallyActive;
        /** Live server-safe predicates recovered from the screen's tick/update bytecode. Their
         * root is the authoritative server target, never the client Screen instance. */
        public RendererBytecodeScanner.BooleanPredicate widgetVisiblePredicate;
        public RendererBytecodeScanner.BooleanPredicate widgetActivePredicate;
        /** Widget bounds relative to the screen background. -1 means the expression was dynamic
         * only when the matching anchor is LEGACY; anchored offsets may legitimately be negative. */
        public int widgetX = -1;
        public int widgetY = -1;
        public int widgetWidth = -1;
        public int widgetHeight = -1;
        public WidgetCoordinateAnchor widgetXAnchor = WidgetCoordinateAnchor.LEGACY;
        public WidgetCoordinateAnchor widgetYAnchor = WidgetCoordinateAnchor.LEGACY;
        public String method;
        public String target;
        /** Scanner-proven WRITE_STATE operation. Bare field targets without one are diagnostic
         * evidence only and are explicitly unsupported by the compatibility contract. */
        public StateWriteKind stateWriteKind;
        public Boolean stateBooleanValue;
        public Integer stateIntValue;
        /** Positive modulus for WRAP_ADD_INT. Null for every other integer transition. */
        public Integer stateIntModulus;
        /** Exact field supplying a dynamic WRAP_ADD_INT modulus. For direct screen state this is
         * an int field; for a scanner-proven cyclic helper setter it may be the exact collection
         * field whose cardinality is the bound. Runtime still requires an authoritative value. */
        public String stateIntModulusTarget;
        /** Optional positive lower bound applied to the dynamic modulus, e.g. Math.max(1, pages). */
        public Integer stateIntModulusMinimum;
        /** When stateIntModulusTarget is a collection field, this is the exact source-Screen
         * object field proven to reach the helper's bound object through construction. Runtime can
         * join that object identity to earlier clientbound constructor facts without classloading. */
        public String stateIntModulusScreenObjectFieldTarget;
        public Long stateLongValue;
        /** Float and double exact constants share one JSON-safe numeric slot; target descriptor
         * keeps their JVM type distinct. */
        public Double stateDoubleValue;
        public String stateStringValue;
        public String stateStaticFieldOwner;
        public String stateStaticFieldName;
        public String stateStaticFieldDesc;
        /** Scanner-proven initial scalar value for relative virtual client-state operations. This
         * is populated only when the owning field is JVM-defaulted: no constructor/static
         * initializer path writes it. */
        public Boolean stateInitialBoolean;
        public Integer stateInitialInt;
        /** For constant PUTFIELD writes, the screen field that supplied the receiver object. This
         * lets translated callbacks update the exact target widget rather than assuming the clicked
         * widget and the mutated widget are the same instance. */
        public String stateReceiverFieldOwner;
        public String stateReceiverFieldName;
        /** True only when bytecode proves the PUTFIELD receiver is the widget instance that
         * triggered this action: either the self receiver of a widget click handler or the single
         * callback parameter assignable from that widget. This lets inherited framework fields
         * such as AbstractWidget.active/visible update the exact translated control without
         * guessing from field names or ordinal proximity. */
        public boolean stateReceiverIsTriggerWidget;
        /** True only when every scanner-visible read of this exact client field is confined to a
         * render-context method. Such state affects interpolation/chrome only; F2V may retain its
         * captured/server-rendered visual representation without executing the client mutation. */
        public boolean visualOnlyState;
        /** True only when a cross-object helper is proven to mutate a receiver object whose values
         * are observed exclusively by rendering. Runtime deliberately omits that client animation
         * helper while preserving the captured/F2V visual representation. */
        public boolean visualOnlyHelper;
        /** True only when scanner hierarchy data proves the receiver field is declared on the
         * source Screen itself or one of its superclasses. */
        public boolean stateReceiverOwnedByScreen;
        public int stateTargetWidgetOrdinal = -1;
        public String stateTargetWidgetClass;
        public boolean stateTargetWidgetInteractive;
        /** Constructor descriptor observed on this exact input branch when the action sends a packet. */
        public String constructorDesc;
        /** Constant boolean constructor argument observed on this exact branch, when recoverable. */
        public Boolean booleanArgument;
        /** Exact String constructor argument recovered from a scanner-proven named-hotkey path. */
        public String stringArgument;
        /** One scanner-proven static-field constructor argument. This covers packet constructors
         * whose semantic is an enum/singleton constant (for example a GUI type) without loading
         * the client class or guessing from names at runtime. */
        public int staticFieldArgumentIndex = -1;
        public String staticFieldArgumentOwner;
        public String staticFieldArgumentName;
        public String staticFieldArgumentDesc;
        /** Exact constructor arguments recovered for this packet edge. The legacy scalar fields
         * above remain populated for cache/report compatibility, while this list supports arbitrary
         * all-constant constructor arities without adding another field per JVM type. */
        public final List<PacketArgument> packetArguments = new ArrayList<>();
        /** Factory/constructor that produced the payload on this exact branch. */
        public String producerOwner;
        public String producerMethod;
        public String producerDesc;
        /** Exact zero-arg config accessor whose returned collection is mutated by a self-handled
         * widget. These fields are populated only for scanner-proven TOGGLE_COLLECTION actions. */
        public String collectionAccessorOwner;
        public String collectionAccessorMethod;
        public String collectionAccessorDesc;
        /** Static registry plus exact key lookup used to derive each repeated widget's value. */
        public String valueRegistryOwner;
        public String valueRegistryField;
        public String valueRegistryDesc;
        public String valueKeyOwner;
        public String valueKeyMethod;
        public String valueKeyDesc;
        public int confidence;
    }

    private static final class MethodFacts {
        boolean closesScreen;
        boolean navigatesBack;
        final Set<String> keys = new LinkedHashSet<>();
        final Set<String> screens = new LinkedHashSet<>();
        final Set<String> externalUrls = new LinkedHashSet<>();
        final Set<PacketFact> packets = new LinkedHashSet<>();
        final Set<StateMutation> stateWrites = new LinkedHashSet<>();
        final Set<CollectionMutation> collectionMutations = new LinkedHashSet<>();
        final Set<ClientHelperCall> clientHelpers = new LinkedHashSet<>();
        final Set<RuntimeLaunch> runtimeLaunches = new LinkedHashSet<>();
        final Set<String> localCalls = new LinkedHashSet<>();
    }

    /** Exact hard runtime side effect reached from an input callback. Discovery never executes it;
     * retaining the owner/name/descriptor makes the unsupported edge auditable and prevents a whole
     * interactive surface from being represented only by a coarse class-level blocker. */
    private record RuntimeLaunch(ActionType type, String target) {}

    private record PacketFact(String target, String constructorDesc, Boolean booleanArgument,
                              StaticFieldArgument staticFieldArgument, List<PacketArgument> arguments,
                              String producerOwner, String producerMethod, String producerDesc) {}

    private record StaticFieldArgument(int index, String owner, String name, String desc) {}

    record StateMutation(String target, StateWriteKind kind,
                         Boolean booleanValue, Integer intValue, Integer intModulus,
                         String intModulusTarget, Integer intModulusMinimum,
                         Long longValue, Double doubleValue, String stringValue,
                         String staticFieldOwner, String staticFieldName, String staticFieldDesc,
                         String receiverFieldOwner, String receiverFieldName) {
        StateMutation(String target, StateWriteKind kind,
                      Boolean booleanValue, Integer intValue,
                      String receiverFieldOwner, String receiverFieldName) {
            this(target, kind, booleanValue, intValue, null, null, null, null, null, null,
                    null, null, null, receiverFieldOwner, receiverFieldName);
        }

        StateMutation(String target, StateWriteKind kind,
                      Boolean booleanValue, Integer intValue, Integer intModulus,
                      String receiverFieldOwner, String receiverFieldName) {
            this(target, kind, booleanValue, intValue, intModulus, null, null,
                    null, null, null,
                    null, null, null, receiverFieldOwner, receiverFieldName);
        }

        StateMutation(String target, StateWriteKind kind,
                      Boolean booleanValue, Integer intValue, Integer intModulus,
                      String intModulusTarget, Integer intModulusMinimum,
                      String receiverFieldOwner, String receiverFieldName) {
            this(target, kind, booleanValue, intValue, intModulus,
                    intModulusTarget, intModulusMinimum, null, null, null,
                    null, null, null, receiverFieldOwner, receiverFieldName);
        }
    }

    private record StateReceiver(String owner, String name) {}

    /** Exact client-state literal that can be represented without loading the client class. */
    private record StateLiteral(StateWriteKind kind,
                                Boolean booleanValue, Integer intValue, Long longValue,
                                Double doubleValue, String stringValue,
                                String staticFieldOwner, String staticFieldName, String staticFieldDesc) {
        StateMutation mutation(String target, StateReceiver receiver) {
            return new StateMutation(target, kind, booleanValue, intValue, null, null, null,
                    longValue, doubleValue,
                    stringValue, staticFieldOwner, staticFieldName, staticFieldDesc,
                    receiver == null ? null : receiver.owner(), receiver == null ? null : receiver.name());
        }
    }

    private record ClientHelperCall(String owner, String method, String desc, boolean visualOnly) {
        String target() { return owner + '#' + method + desc; }
    }

    private record CollectionMutation(String accessorOwner, String accessorMethod, String accessorDesc,
                                      String valueFieldOwner, String valueFieldName, String valueFieldDesc,
                                      String operation) {}

    private record RegistrySource(String registryOwner, String registryField, String registryDesc,
                                  String keyOwner, String keyMethod, String keyDesc) {}

    private record WidgetRef(int ordinal, String widgetClass, String label,
                             int x, int y, int width, int height,
                             WidgetCoordinateAnchor xAnchor, WidgetCoordinateAnchor yAnchor,
                             String visualOwner, String visualField,
                             Boolean initiallyVisible, Boolean initiallyActive) {}

    private record WidgetInitialState(Boolean visible, Boolean active) {}

    private record WidgetLiveState(RendererBytecodeScanner.BooleanPredicate visible,
                                   RendererBytecodeScanner.BooleanPredicate active) {}

    private record WidgetLayout(int x, int y, int width, int height,
                                WidgetCoordinateAnchor xAnchor,
                                WidgetCoordinateAnchor yAnchor) {
        WidgetLayout(int x, int y, int width, int height) {
            this(x, y, width, height, WidgetCoordinateAnchor.LEGACY,
                    WidgetCoordinateAnchor.LEGACY);
        }

        static final WidgetLayout UNKNOWN = new WidgetLayout(-1, -1, -1, -1);
    }

    private record Region(AbstractInsnNode start, AbstractInsnNode endExclusive, int confidence) {}

    public record Summary(int mods, int clientOnlyMods, int classes, int keybinds,
                          int actions, int blocked) {}

    /** Exact client method target suitable for capture-client direct execution. */
    public record HookTarget(String owner, String method, String descriptor, Set<Kind> kinds) {}

    /**
     * Vanilla screen bases a modded screen may extend directly. Vanilla classes are normally absent
     * from the scanned {@code nodes} map (only mod classes are traversed), so the superclass walk in
     * {@link #isScreenType} terminates at the first vanilla ancestor it cannot resolve. Listing the
     * concrete vanilla bases -- not just the two abstract roots -- lets that walk terminate
     * positively instead of falling through to the weaker name heuristic.
     */
    private static final Set<String> SCREEN_ROOTS = Set.of(
            "net/minecraft/client/gui/screens/Screen",
            "net/minecraft/client/gui/screens/inventory/AbstractContainerScreen",
            "net/minecraft/client/gui/screens/inventory/EffectRenderingInventoryScreen",
            "net/minecraft/client/gui/screens/inventory/InventoryScreen",
            "net/minecraft/client/gui/screens/inventory/AbstractFurnaceScreen",
            "net/minecraft/client/gui/screens/inventory/AbstractSignEditScreen",
            "net/minecraft/client/gui/screens/inventory/ContainerScreen",
            "net/minecraft/client/gui/screens/inventory/CraftingScreen",
            "net/minecraft/client/gui/screens/inventory/MenuAccess",
            "net/minecraft/client/gui/screens/AbstractContainerScreen",
            "net/minecraft/client/gui/screens/OptionsSubScreen",
            "net/minecraft/client/gui/screens/ConfirmScreen",
            "net/minecraft/client/gui/screens/SimpleOptionsSubScreen",
            "net/minecraft/client/gui/screens/recipebook/RecipeBookComponent");

    /**
     * Path segments that mark a class as living in a client UI package. Deliberately excludes
     * widget/component packages: {@link #isScreenType} is also used as an *exclusion* test (a
     * screen is not a payload and not a widget target), so treating widgets as screens breaks
     * widget-geometry extraction.
     */
    private static final List<String> UI_PATH_TOKENS = List.of(
            "/gui/", "/screen/", "/screens/", "/ui/", "/menu/", "/menus/");

    /**
     * Class-name suffixes mods commonly use for *screen* types. Widget-ish suffixes (panel,
     * overlay, hud, widget) are intentionally absent for the same reason as above.
     */
    private static final List<String> UI_NAME_SUFFIXES = List.of(
            "screen", "gui", "window", "dialog");

    private ClientSurfaceScanner() {}

    /** Analyze one already-parsed class. ModScanner calls this from its existing ASM pass, so
     * client discovery adds no second jar traversal to pack generation. */
    public static ClassSurface inspect(ClassNode node, Map<String, ClassNode> nodes) {
        return inspect(node, nodes, discoverKeyFields(nodes));
    }

    static ClassSurface inspect(ClassNode node, Map<String, ClassNode> nodes,
                                Map<String, String> discoveredKeyFields) {
        if (node == null) return null;
        Map<String, String> keyFields = discoveredKeyFields == null ? Map.of() : discoveredKeyFields;
        LinkedHashSet<String> refs = new LinkedHashSet<>();
        collectClassReference(node.superName, refs);
        if (node.interfaces != null) node.interfaces.forEach(value -> collectClassReference(value, refs));
        LinkedHashSet<String> mixinTargets = new LinkedHashSet<>();
        collectAnnotationReferences(node.visibleAnnotations, refs, mixinTargets);
        collectAnnotationReferences(node.invisibleAnnotations, refs, mixinTargets);
        if (node.fields != null) {
            for (FieldNode field : node.fields) collectDescriptor(field.desc, refs);
        }

        boolean clientAnnotation = clientAnnotation(node.visibleAnnotations)
                || clientAnnotation(node.invisibleAnnotations);
        boolean clientEntrypoint = node.interfaces != null && node.interfaces.stream().anyMatch(value ->
                value != null && value.toLowerCase(Locale.ROOT).endsWith("/clientmodinitializer"));
        // Follow known ancestors, but also accept an unresolved GUI *Screen base from an embedded
        // client library. This is the same conservative test used for action targets and avoids
        // classifying network payloads that merely happen to end in "Screen".
        boolean screen = isScreenType(node.name, nodes);
        String lowerClass = node.name.toLowerCase(Locale.ROOT);
        boolean clientPath = lowerClass.contains("/client/") || lowerClass.endsWith("client")
                || lowerClass.contains("clientonly");

        ClassSurface surface = new ClassSurface();
        surface.clazz = node.name;
        surface.superClass = node.superName;
        surface.directExecutionAllowed = false;
        surface.mixinTargets.addAll(mixinTargets);

        String ownFieldPrefix = node.name + ".";
        for (Map.Entry<String, String> entry : keyFields.entrySet()) {
            if (entry.getKey().startsWith(ownFieldPrefix)) {
                addKeybind(surface, entry.getValue());
            }
        }
        Map<String, MethodFacts> methodFacts = new HashMap<>();

        for (MethodNode method : node.methods) {
            collectDescriptor(method.desc, refs);
            scanMethod(method, refs, surface);
            methodFacts.put(methodKey(method), analyzeBehavior(node, method, keyFields, nodes));
        }
        boolean hasClientReference = refs.stream().anyMatch(ClientSurfaceScanner::isClientReference);
        boolean clientMixinEvidence = !surface.mixinTargets.isEmpty()
                && (clientPath || clientAnnotation || clientEntrypoint || hasClientReference
                || surface.mixinTargets.stream().anyMatch(target -> isClientReference(target)
                || target.toLowerCase(Locale.ROOT).contains("/client/")));
        if (!clientMixinEvidence) {
            surface.hooks.removeIf(hook -> hook != null && hook.mixinInjection);
            surface.kinds.clear();
            for (Hook hook : surface.hooks) surface.kinds.addAll(hook.kinds);
        }
        if (!screen && interactiveScreenShape(node)
                && (clientPath || clientAnnotation || clientEntrypoint || hasClientReference)) {
            screen = true;
        }
        propagateLocalActions(methodFacts);
        attachReachableRuntimeEffectMethods(surface, methodFacts);
        traceBuilderKeyCallbacks(surface, node, keyFields, nodes);
        traceKeyBranches(surface, node, keyFields, nodes);
        emitActions(surface, methodFacts);
        if (screen) {
            traceWidgetActions(surface, node, nodes);
            traceScreenCloseActions(surface, node, methodFacts);
        }
        attachHistoricalModulusObjectSources(surface, node, nodes);
        attachDefaultScalarInitials(surface, node, nodes);
        for (Action action : surface.actions) {
            if (action.keybind != null) addKeybind(surface, action.keybind);
        }
        if (clientPath) surface.clientScore += 3;
        if (clientAnnotation) surface.clientScore += 6;
        if (clientEntrypoint) surface.clientScore += 6;
        if (screen) {
            surface.clientScore += 7;
            surface.kinds.add(Kind.SCREEN);
        }
        if (hasClientReference) surface.clientScore += 2;
        if (clientMixinEvidence) {
            surface.clientScore += 4;
            surface.kinds.add(Kind.MIXIN);
        }

        for (String ref : refs) classifyReference(ref, surface);
        classifyName(lowerClass, surface);
        if (!surface.keybinds.isEmpty()) surface.kinds.add(Kind.KEYBIND);

        // A class living under a /client/ package is not automatically a client *surface*.
        // Real mods keep many passive DTO/config/event/registry helpers there. Treating path
        // location alone as observable behavior used to turn all of those into UNSUPPORTED rows
        // and heavily inflated compatibility gaps. Retain only scanner-proven behavior, a hard
        // runtime side effect, or an incomplete scan that cannot safely be discarded.
        boolean observableClientBehavior = hasObservableClientBehavior(surface);
        boolean hardRuntimeEffect = hasHardRuntimeEffect(surface);
        boolean independentClientEvidence = clientPath || clientAnnotation || clientEntrypoint || screen
                || hasClientReference
                || surface.mixinTargets.stream().anyMatch(ClientSurfaceScanner::isClientReference);
        boolean clientHardRuntimeEffect = hardRuntimeEffect && independentClientEvidence;
        if (!observableClientBehavior && !clientHardRuntimeEffect
                && !clientAnnotation && !clientEntrypoint) return null;
        surface.clientOnlyLikely = clientAnnotation || clientEntrypoint || screen || clientPath
                || surface.clientScore >= 5;
        surface.metadataOnly = !observableClientBehavior && !clientHardRuntimeEffect
                && (clientAnnotation || clientEntrypoint);
        surface.runtimeServiceOnly = !observableClientBehavior && clientHardRuntimeEffect;
        surface.remoteVisualFallback = remoteVisualFallback(surface);
        surface.ancillaryRuntimeEffects = !surface.runtimeServiceOnly && !surface.remoteVisualFallback
                && hasHardRuntimeEffect(surface) && !hasCoreHardRuntimeBehavior(surface);
        surface.clientServiceFallback = !surface.runtimeServiceOnly && !surface.remoteVisualFallback
                && hasHardRuntimeEffect(surface) && hasCoreHardRuntimeBehavior(surface);
        chooseTranslations(surface);
        return surface;
    }

    private static boolean hasObservableClientBehavior(ClassSurface surface) {
        if (surface == null) return false;
        if (!surface.kinds.isEmpty() || !surface.actions.isEmpty() || !surface.hooks.isEmpty()
                || !surface.keybinds.isEmpty()) return true;
        return !surface.scanComplete;
    }

    private static boolean remoteVisualFallback(ClassSurface surface) {
        if (surface == null || !surface.blockers.contains("external_network_io")
                || surface.blockers.contains("process_or_native_loading")
                || !surface.kinds.contains(Kind.RENDERER)
                || !surface.actions.isEmpty()
                || !surface.keybinds.isEmpty() || !surface.mixinTargets.isEmpty()) return false;
        for (Kind kind : surface.kinds) {
            if (kind != Kind.RENDERER && kind != Kind.CLIENT_TICK) return false;
        }
        for (Hook hook : surface.hooks) {
            if (hook == null) continue;
            for (Kind kind : hook.kinds) {
                if (kind != Kind.RENDERER && kind != Kind.CLIENT_TICK) return false;
            }
        }
        return true;
    }

    private static boolean hasHardRuntimeEffect(ClassSurface surface) {
        return surface != null && (surface.blockers.contains("external_network_io")
                || surface.blockers.contains("process_or_native_loading"));
    }

    /** Whether a hard external effect is part of a translated interaction/hook instead of merely
     * co-located setup/tooling code. This is intentionally conservative for classes that publish
     * user actions: if the class both handles an interaction and owns hard external code, retain the
     * hard verdict unless bytecode has already reduced it to a visual fallback. */
    private static boolean hasCoreHardRuntimeBehavior(ClassSurface surface) {
        if (surface == null) return false;
        for (Action action : surface.actions) {
            if (action == null) continue;
            if (action.type == ActionType.EXTERNAL_NETWORK_IO
                    || action.type == ActionType.LAUNCH_PROCESS
                    || action.type == ActionType.LOAD_NATIVE_LIBRARY) return true;
        }
        if (!surface.actions.isEmpty() && hasHardRuntimeEffect(surface)) return true;
        for (Hook hook : surface.hooks) {
            if (hook == null || hook.method == null || hook.descriptor == null) continue;
            String id = methodKey(hook.method, hook.descriptor);
            if (surface.externalNetworkMethods.contains(id)
                    || surface.processNativeMethods.contains(id)) return true;
        }
        if (surface.clazz != null) {
            int slash = surface.clazz.lastIndexOf('/');
            String leaf = (slash < 0 ? surface.clazz : surface.clazz.substring(slash + 1))
                    .toLowerCase(Locale.ROOT);
            if (leaf.endsWith("action") && hasHardRuntimeEffect(surface)) return true;
        }
        return false;
    }

    /** Propagation above follows same-class helpers (including compiler-generated lambda methods).
     * Reflect that reachability back into the method provenance sets so a client hook that delegates
     * socket/native work to a local helper remains a hard surface instead of looking ancillary. */
    private static void attachReachableRuntimeEffectMethods(ClassSurface surface,
                                                            Map<String, MethodFacts> methodFacts) {
        if (surface == null || methodFacts == null) return;
        for (Map.Entry<String, MethodFacts> entry : methodFacts.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) continue;
            for (RuntimeLaunch effect : entry.getValue().runtimeLaunches) {
                if (effect == null || effect.type() == null) continue;
                if (effect.type() == ActionType.EXTERNAL_NETWORK_IO) {
                    surface.externalNetworkMethods.add(entry.getKey());
                } else if (effect.type() == ActionType.LAUNCH_PROCESS
                        || effect.type() == ActionType.LOAD_NATIVE_LIBRARY) {
                    surface.processNativeMethods.add(entry.getKey());
                }
            }
        }
    }

    /**
     * Fluent keybinding APIs often attach the action as an invokedynamic callback during static
     * registration instead of polling the KeyMapping later. Once {@link #discoverBuilderKeyFields}
     * has proven which final field owns which translation id, follow callback handles only inside
     * that exact builder chain and summarize their packet/screen behavior.
     */
    private static void traceBuilderKeyCallbacks(ClassSurface surface, ClassNode owner,
                                                 Map<String, String> keyFields,
                                                 Map<String, ClassNode> nodes) {
        if (surface == null || owner == null || owner.methods == null || keyFields == null
                || keyFields.isEmpty() || nodes == null) return;
        for (MethodNode method : owner.methods) {
            for (AbstractInsnNode instruction : method.instructions) {
                if (!(instruction instanceof FieldInsnNode store)
                        || (store.getOpcode() != Opcodes.PUTSTATIC && store.getOpcode() != Opcodes.PUTFIELD)
                        || !isKeyMappingDescriptor(store.desc)) continue;
                String key = keyFields.get(fieldKey(store.owner, store.name));
                if (key == null) continue;
                MethodInsnNode build = keyBuilderBefore(store);
                if (build == null) continue;
                AbstractInsnNode allocation = keyBuilderAllocationBefore(build);
                if (allocation == null) continue;

                for (AbstractInsnNode current = allocation.getNext(); current != null && current != build;
                     current = current.getNext()) {
                    if (!(current instanceof InvokeDynamicInsnNode indy)) continue;
                    for (Object arg : indy.bsmArgs) {
                        if (!(arg instanceof Handle handle) || !nodes.containsKey(handle.getOwner())) continue;
                        ClassNode callbackOwner = nodes.get(handle.getOwner());
                        MethodNode callback = findMethod(callbackOwner, handle.getName(), handle.getDesc());
                        if (callback == null) continue;
                        MethodFacts facts = collectBehaviorRange(callbackOwner.name,
                                callback.instructions.getFirst(), null, nodes,
                                new HashSet<>(), 0);
                        emitFactsForKey(surface, key,
                                handle.getOwner() + '#' + methodKey(handle.getName(), handle.getDesc()),
                                facts, 97);
                    }
                }
            }
        }
    }

    private static MethodInsnNode keyBuilderBefore(FieldInsnNode store) {
        int remaining = 24;
        for (AbstractInsnNode previous = store == null ? null : store.getPrevious();
             previous != null && remaining-- > 0; previous = previous.getPrevious()) {
            if (previous instanceof MethodInsnNode call && !"<init>".equals(call.name)
                    && producesKeyMapping(call)) return call;
            if (previous instanceof FieldInsnNode other
                    && (other.getOpcode() == Opcodes.PUTSTATIC || other.getOpcode() == Opcodes.PUTFIELD)
                    && isKeyMappingDescriptor(other.desc)) break;
        }
        return null;
    }

    private static AbstractInsnNode keyBuilderAllocationBefore(MethodInsnNode build) {
        if (build == null || build.owner == null) return null;
        int remaining = 160;
        for (AbstractInsnNode previous = build.getPrevious(); previous != null && remaining-- > 0;
             previous = previous.getPrevious()) {
            if (previous instanceof TypeInsnNode allocation && allocation.getOpcode() == Opcodes.NEW
                    && build.owner.equals(allocation.desc)) return allocation;
            if (previous instanceof MethodInsnNode other && other != build && producesKeyMapping(other)) break;
        }
        return null;
    }

    /**
     * Recovers fields such as {@code OPEN_MAP -> "key.mod.open_map"} from the KeyMapping
     * constructor and the following PUTSTATIC/PUTFIELD. This uses code shape rather than field or
     * class names, so it works for arbitrary mods and survives renamed source identifiers.
     */
    /** Build one key-field index for the whole archive. Handler classes commonly consume mappings
     * declared in a separate registration class (Xaero/Recruits both do this), so class-local
     * discovery loses the actual key -> behavior relationship. */
    static Map<String, String> discoverKeyFields(Map<String, ClassNode> nodes) {
        return discoverKeyFields(nodes, List.of());
    }

    /**
     * Same structural key-field discovery, with optional translation ids harvested from the owning
     * mod's language table. The extra ids are used only to resolve builder APIs whose description
     * is supplied through an enum/translation object rather than as a literal String; bytecode still
     * has to prove which builder field becomes the KeyMapping description.
     */
    static Map<String, String> discoverKeyFields(Map<String, ClassNode> nodes,
                                                 Collection<String> translationKeys) {
        Map<String, String> out = new HashMap<>();
        if (nodes == null) return out;
        for (ClassNode node : nodes.values()) {
            discoverKeyFields(node, out);
            discoverWrappedKeyFields(node, out);
        }
        if (translationKeys != null && !translationKeys.isEmpty()) {
            LinkedHashSet<String> candidates = new LinkedHashSet<>();
            for (String key : translationKeys) {
                if (key != null && !key.isBlank()) candidates.add(key);
            }
            if (!candidates.isEmpty()) {
                for (ClassNode node : nodes.values()) {
                    discoverBuilderKeyFields(node, nodes, candidates, out);
                }
            }
        }
        return out;
    }

    /**
     * Handles fluent key builders such as {@code new Builder().description(Lang.KEY).build()}.
     * The builder class itself proves which instance field supplies the first String argument of
     * the eventual KeyMapping constructor. We then follow only chain methods that write that field
     * and resolve their static translation object against the already-owned language ids.
     */
    private static void discoverBuilderKeyFields(ClassNode node, Map<String, ClassNode> nodes,
                                                 Set<String> translationKeys,
                                                 Map<String, String> out) {
        if (node == null || node.methods == null || nodes == null || translationKeys.isEmpty()) return;
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode instruction : method.instructions) {
                if (!(instruction instanceof MethodInsnNode call) || "<init>".equals(call.name)
                        || !producesKeyMapping(call)) continue;
                String key = chainedBuilderTranslationKey(call, nodes, translationKeys);
                if (key == null) continue;
                int remaining = 16;
                for (AbstractInsnNode next = instruction.getNext(); next != null && remaining-- > 0;
                     next = next.getNext()) {
                    if (next instanceof FieldInsnNode field
                            && (field.getOpcode() == Opcodes.PUTSTATIC
                            || field.getOpcode() == Opcodes.PUTFIELD)
                            && isKeyMappingDescriptor(field.desc)) {
                        out.put(fieldKey(field.owner, field.name), key);
                        break;
                    }
                    if (next instanceof MethodInsnNode other && producesKeyMapping(other)) break;
                }
            }
        }
    }

    private static String chainedBuilderTranslationKey(MethodInsnNode build,
                                                       Map<String, ClassNode> nodes,
                                                       Set<String> translationKeys) {
        if (build == null || build.owner == null) return null;
        ClassNode builder = nodes.get(build.owner);
        if (builder == null) return null;
        String descriptionField = keyBuilderDescriptionField(builder, build.name, build.desc, nodes);
        if (descriptionField == null) return null;

        int remaining = 128;
        for (AbstractInsnNode previous = build.getPrevious(); previous != null && remaining-- > 0;
             previous = previous.getPrevious()) {
            if (previous instanceof TypeInsnNode allocation && allocation.getOpcode() == Opcodes.NEW
                    && build.owner.equals(allocation.desc)) break;
            if (!(previous instanceof MethodInsnNode call) || !build.owner.equals(call.owner)) continue;
            if (!builderMethodWritesField(builder, call.name, call.desc, descriptionField,
                    new HashSet<>(), 0)) continue;
            String resolved = translationKeyArgument(call, nodes, translationKeys);
            if (resolved != null) return resolved;
        }
        return null;
    }

    /** First String field forwarded into the concrete KeyMapping/subclass constructor. */
    private static String keyBuilderDescriptionField(ClassNode builder, String factoryName,
                                                     String factoryDesc,
                                                     Map<String, ClassNode> nodes) {
        MethodNode factory = findMethod(builder, factoryName, factoryDesc);
        if (factory == null) return null;
        for (AbstractInsnNode instruction : factory.instructions) {
            if (!(instruction instanceof MethodInsnNode constructor)
                    || constructor.getOpcode() != Opcodes.INVOKESPECIAL
                    || !"<init>".equals(constructor.name)
                    || !isKeyMappingClass(constructor.owner, nodes)) continue;
            AbstractInsnNode allocation = null;
            int remaining = 96;
            for (AbstractInsnNode previous = constructor.getPrevious(); previous != null && remaining-- > 0;
                 previous = previous.getPrevious()) {
                if (previous instanceof TypeInsnNode type && type.getOpcode() == Opcodes.NEW
                        && constructor.owner.equals(type.desc)) {
                    allocation = previous;
                    break;
                }
            }
            if (allocation == null) continue;
            for (AbstractInsnNode current = allocation.getNext(); current != null && current != constructor;
                 current = current.getNext()) {
                if (current instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETFIELD
                        && builder.name.equals(field.owner) && "Ljava/lang/String;".equals(field.desc)) {
                    return field.name;
                }
            }
        }
        return null;
    }

    private static boolean builderMethodWritesField(ClassNode builder, String name, String desc,
                                                    String fieldName, Set<String> seen, int depth) {
        if (builder == null || name == null || desc == null || fieldName == null || depth > 4) return false;
        String id = methodKey(name, desc);
        if (!seen.add(id)) return false;
        MethodNode method = findMethod(builder, name, desc);
        if (method == null) return false;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof FieldInsnNode field && field.getOpcode() == Opcodes.PUTFIELD
                    && builder.name.equals(field.owner) && fieldName.equals(field.name)) return true;
            if (instruction instanceof MethodInsnNode call && builder.name.equals(call.owner)
                    && !"<init>".equals(call.name)
                    && builderMethodWritesField(builder, call.name, call.desc, fieldName, seen, depth + 1)) {
                return true;
            }
        }
        return false;
    }

    private static String translationKeyArgument(MethodInsnNode call, Map<String, ClassNode> nodes,
                                                 Set<String> translationKeys) {
        if (call == null || call.desc == null) return null;
        Type[] args;
        try {
            args = Type.getArgumentTypes(call.desc);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
        if (args.length != 1) return null;
        AbstractInsnNode source = previousReal(call.getPrevious());
        if (source instanceof LdcInsnNode ldc && ldc.cst instanceof String text) {
            if (translationKeys.contains(text)) return text;
            return looksLikeKeybind(text, true) ? text : null;
        }
        if (source instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETSTATIC) {
            return translationKeyFromStaticField(field, nodes, translationKeys);
        }
        if (source instanceof MethodInsnNode getter && Type.getArgumentTypes(getter.desc).length == 0) {
            AbstractInsnNode receiver = previousReal(getter.getPrevious());
            if (receiver instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETSTATIC) {
                return translationKeyFromStaticField(field, nodes, translationKeys);
            }
        }
        return null;
    }

    private static String translationKeyFromStaticField(FieldInsnNode source,
                                                        Map<String, ClassNode> nodes,
                                                        Set<String> translationKeys) {
        if (source == null || nodes == null) return null;
        ClassNode owner = nodes.get(source.owner);
        if (owner == null) return null;
        if (owner.fields != null) {
            for (FieldNode field : owner.fields) {
                if (source.name.equals(field.name) && field.value instanceof String text
                        && translationKeys.contains(text)) return text;
            }
        }
        for (MethodNode method : owner.methods) {
            if (!"<clinit>".equals(method.name)) continue;
            for (AbstractInsnNode instruction : method.instructions) {
                if (!(instruction instanceof FieldInsnNode store) || store.getOpcode() != Opcodes.PUTSTATIC
                        || !source.owner.equals(store.owner) || !source.name.equals(store.name)) continue;
                ArrayList<String> literals = new ArrayList<>();
                int remaining = 96;
                for (AbstractInsnNode previous = store.getPrevious(); previous != null && remaining-- > 0;
                     previous = previous.getPrevious()) {
                    if (previous instanceof LdcInsnNode ldc && ldc.cst instanceof String text
                            && !text.isBlank()) literals.add(text);
                    if (previous instanceof TypeInsnNode allocation && allocation.getOpcode() == Opcodes.NEW
                            && source.owner.equals(allocation.desc)) break;
                    if (previous instanceof FieldInsnNode other && other.getOpcode() == Opcodes.PUTSTATIC
                            && source.owner.equals(other.owner)) break;
                }
                String matched = bestTranslationKey(literals, translationKeys);
                if (matched != null) return matched;
            }
        }
        return null;
    }

    private static String bestTranslationKey(Collection<String> literals, Set<String> candidates) {
        if (literals == null || literals.isEmpty() || candidates == null || candidates.isEmpty()) return null;
        String best = null;
        int bestScore = 0;
        boolean tied = false;
        for (String candidate : candidates) {
            if (candidate == null || candidate.isBlank()) continue;
            String lowerCandidate = candidate.toLowerCase(Locale.ROOT);
            int score = 0;
            for (String literal : literals) {
                if (literal == null || literal.isBlank()) continue;
                if (candidate.equals(literal)) score += 100;
                String lower = literal.toLowerCase(Locale.ROOT);
                if (!lower.matches("[a-z0-9_.-]+")) continue;
                if (lowerCandidate.startsWith(lower + ".")) score += 20;
                if (lowerCandidate.endsWith("." + lower)) score += 40;
                else if (lowerCandidate.contains("." + lower + ".")) score += 10;
            }
            if (score > bestScore) {
                best = candidate;
                bestScore = score;
                tied = false;
            } else if (score > 0 && score == bestScore && !java.util.Objects.equals(best, candidate)) {
                tied = true;
            }
        }
        return bestScore >= 40 && !tied ? best : null;
    }

    private static AbstractInsnNode previousReal(AbstractInsnNode instruction) {
        AbstractInsnNode current = instruction;
        while (current != null && current.getOpcode() < 0) current = current.getPrevious();
        return current;
    }

    private static boolean isKeyMappingClass(String type, Map<String, ClassNode> nodes) {
        if (isKeyMappingType(type)) return true;
        HashSet<String> seen = new HashSet<>();
        String current = type;
        while (current != null && seen.add(current)) {
            ClassNode node = nodes == null ? null : nodes.get(current);
            if (node == null) return false;
            current = node.superName;
            if (isKeyMappingType(current)) return true;
        }
        return false;
    }

    private static void discoverKeyFields(ClassNode node, Map<String, String> out) {
        if (node == null || node.methods == null) return;
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode instruction : method.instructions) {
                if (!(instruction instanceof MethodInsnNode call)) continue;
                String key;
                if (call.getOpcode() == Opcodes.INVOKESPECIAL
                        && "<init>".equals(call.name) && isKeyMappingType(call.owner)) {
                    key = keyMappingTranslationKey(call);
                } else {
                    key = keyMappingFactoryTranslationKey(call);
                }
                if (key == null) continue;
                int remaining = 16;
                for (AbstractInsnNode next = instruction.getNext(); next != null && remaining-- > 0;
                     next = next.getNext()) {
                    if (next instanceof FieldInsnNode field
                            && (field.getOpcode() == Opcodes.PUTSTATIC
                            || field.getOpcode() == Opcodes.PUTFIELD)
                            && isKeyMappingDescriptor(field.desc)) {
                        out.put(fieldKey(field.owner, field.name), key);
                        break;
                    }
                    if (next instanceof MethodInsnNode other && producesKeyMapping(other)) break;
                }
            }
        }
    }

    /**
     * Some loader abstractions manufacture a KeyMapping through a static/platform factory instead
     * of invoking its constructor in the registration class. When the call's declared return type
     * is a KeyMapping and it accepts exactly one String argument, that String is unambiguously the
     * mapping's translation/description key. This keeps discovery structural and avoids guessing
     * from factory or field names.
     */
    private static String keyMappingFactoryTranslationKey(MethodInsnNode call) {
        if (call == null || !producesKeyMapping(call) || "<init>".equals(call.name)) return null;
        Type[] arguments;
        try {
            arguments = Type.getArgumentTypes(call.desc);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
        int strings = 0;
        for (Type argument : arguments) {
            if (argument.getSort() == Type.OBJECT
                    && "java/lang/String".equals(argument.getInternalName())) strings++;
        }
        if (strings != 1) return null;
        String key = nearestString(call, 24);
        return key == null || key.isBlank() || key.length() > 180 ? null : key;
    }

    private static boolean producesKeyMapping(MethodInsnNode call) {
        if (call == null || call.desc == null) return false;
        if (call.getOpcode() == Opcodes.INVOKESPECIAL
                && "<init>".equals(call.name) && isKeyMappingType(call.owner)) return true;
        try {
            Type result = Type.getReturnType(call.desc);
            return result.getSort() == Type.OBJECT && isKeyMappingType(result.getInternalName());
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    /**
     * Finds key mappings hidden behind wrappers such as Forge's Lazy&lt;KeyMapping&gt;. The actual
     * KeyMapping constructor lives in a supplier lambda, while the field consumed by the input
     * handler has descriptor Lazy rather than KeyMapping. Following the invokedynamic supplier
     * handle keeps this structural and also covers similar memoized/supplier wrappers.
     */
    private static void discoverWrappedKeyFields(ClassNode node, Map<String, String> out) {
        if (node == null || node.methods == null) return;
        Map<String, String> factoryKeys = new HashMap<>();
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode instruction : method.instructions) {
                if (!(instruction instanceof MethodInsnNode call)
                        || call.getOpcode() != Opcodes.INVOKESPECIAL
                        || !"<init>".equals(call.name) || !isKeyMappingType(call.owner)) continue;
                String key = keyMappingTranslationKey(call);
                if (key != null) {
                    factoryKeys.put(methodKey(method), key);
                    break;
                }
            }
        }
        if (factoryKeys.isEmpty()) return;

        for (MethodNode method : node.methods) {
            if (!"<clinit>".equals(method.name)) continue;
            for (AbstractInsnNode instruction : method.instructions) {
                if (!(instruction instanceof InvokeDynamicInsnNode indy)) continue;
                String key = null;
                for (Object arg : indy.bsmArgs) {
                    if (!(arg instanceof Handle handle) || !node.name.equals(handle.getOwner())) continue;
                    key = factoryKeys.get(methodKey(handle.getName(), handle.getDesc()));
                    if (key != null) break;
                }
                if (key == null) continue;
                int remaining = 16;
                for (AbstractInsnNode next = instruction.getNext(); next != null && remaining-- > 0;
                     next = next.getNext()) {
                    if (next instanceof FieldInsnNode field && field.getOpcode() == Opcodes.PUTSTATIC) {
                        out.put(fieldKey(field.owner, field.name), key);
                        break;
                    }
                    if (next instanceof InvokeDynamicInsnNode) break;
                }
            }
        }
    }

    /**
     * Reads the translation-key argument from one concrete KeyMapping constructor invocation.
     * KeyMapping constructors commonly also receive a category string after the action key, so a
     * backwards "nearest string" search can accidentally record the category. The JVM evaluates
     * constructor arguments in source order: the action/description string is the first String
     * pushed after the matching {@code NEW KeyMapping}. Restricting the scan to that construction
     * region also supports arbitrary mod translation keys such as {@code mod.tool.reset} without
     * requiring a naming heuristic like a {@code key.} prefix.
     */
    private static String keyMappingTranslationKey(MethodInsnNode constructor) {
        if (constructor == null || !"<init>".equals(constructor.name)
                || !isKeyMappingType(constructor.owner)) return null;
        AbstractInsnNode allocation = null;
        int remaining = 64;
        for (AbstractInsnNode previous = constructor.getPrevious(); previous != null && remaining-- > 0;
             previous = previous.getPrevious()) {
            if (previous instanceof TypeInsnNode type && type.getOpcode() == Opcodes.NEW
                    && constructor.owner.equals(type.desc)) {
                allocation = previous;
                break;
            }
            if (previous instanceof MethodInsnNode other && other != constructor
                    && other.getOpcode() == Opcodes.INVOKESPECIAL && "<init>".equals(other.name)
                    && isKeyMappingType(other.owner)) return null;
        }
        if (allocation == null) return null;
        for (AbstractInsnNode current = allocation.getNext(); current != null && current != constructor;
             current = current.getNext()) {
            if (!(current instanceof LdcInsnNode ldc) || !(ldc.cst instanceof String text)) continue;
            if (text.isBlank() || text.length() > 180) continue;
            return text;
        }
        return null;
    }

    private static MethodFacts analyzeBehavior(ClassNode owner, MethodNode method,
                                               Map<String, String> keyFields,
                                               Map<String, ClassNode> nodes) {
        MethodFacts facts = new MethodFacts();
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof FieldInsnNode field) {
                String key = keyFields.get(fieldKey(field.owner, field.name));
                if (key != null && (field.getOpcode() == Opcodes.GETSTATIC
                        || field.getOpcode() == Opcodes.GETFIELD)) facts.keys.add(key);
                if (key == null && field.owner.equals(owner.name)
                        && (field.getOpcode() == Opcodes.PUTSTATIC
                        || field.getOpcode() == Opcodes.PUTFIELD)
                        && !isKeyMappingDescriptor(field.desc)
                        && !widgetReferenceStateField(field, nodes)) {
                    facts.stateWrites.add(stateMutation(field, null, nodes));
                }
                continue;
            }
            if (!(instruction instanceof MethodInsnNode call)) continue;
            RuntimeLaunch runtimeLaunch = runtimeLaunch(call);
            if (runtimeLaunch != null) facts.runtimeLaunches.add(runtimeLaunch);
            if (externalNetworkCall(call)) {
                facts.runtimeLaunches.add(new RuntimeLaunch(ActionType.EXTERNAL_NETWORK_IO,
                        call.owner + '#' + call.name + call.desc));
            }
            String confirmedUrl = confirmedExternalUrlLiteral(call, null);
            if (confirmedUrl != null) facts.externalUrls.add(confirmedUrl);
            boolean directNetworkSend = isGameNetworkSend(call);
            PacketFact forwardedPacket = !directNetworkSend && forwardsNetworkSend(call, nodes)
                    ? producedForwardedPacketBefore(owner.name, call, nodes, null) : null;
            boolean forwardingSend = forwardedPacket != null;
            if (call.owner.equals(owner.name) && screenCloseLifecycle(call.name, call.desc)) {
                facts.closesScreen = true;
            } else if (call.owner.equals(owner.name) && !directNetworkSend && !forwardingSend) {
                facts.localCalls.add(methodKey(call.name, call.desc));
            }
            if (isSetScreen(call)) {
                facts.closesScreen |= previousOpcode(call) != null
                        && previousOpcode(call).getOpcode() == Opcodes.ACONST_NULL;
                String screen = producedTypeBefore(instruction, nodes, true);
                if (screen != null && !(confirmLinkScreen(screen) && !facts.externalUrls.isEmpty())) {
                    facts.screens.add(screen);
                }
            }
            if (directNetworkSend || forwardingSend) {
                PacketFact packet = forwardingSend
                        ? forwardedPacket
                        : producedPacketBefore(owner.name, instruction, nodes, null);
                if (packet != null) facts.packets.add(packet);
            }
        }
        return facts;
    }

    /** Propagate behavior through small same-class helpers (key handler -> openMapScreen()). */
    private static void propagateLocalActions(Map<String, MethodFacts> facts) {
        for (int round = 0; round < 6; round++) {
            boolean changed = false;
            for (MethodFacts caller : facts.values()) {
                for (String call : caller.localCalls) {
                    MethodFacts callee = facts.get(call);
                    if (callee == null) continue;
                    if (callee.closesScreen && !caller.closesScreen) {
                        caller.closesScreen = true;
                        changed = true;
                    }
                    changed |= caller.screens.addAll(callee.screens);
                    changed |= caller.externalUrls.addAll(callee.externalUrls);
                    changed |= caller.packets.addAll(callee.packets);
                    changed |= caller.stateWrites.addAll(callee.stateWrites);
                    changed |= caller.runtimeLaunches.addAll(callee.runtimeLaunches);
                }
            }
            if (!changed) return;
        }
    }

    /**
     * Associates actions with the branch controlled by each concrete key field. This is more
     * precise than method-wide correlation: a single input handler often checks many hotkeys and
     * opens a different screen for each one. The common bytecode shape is either
     * {@code GETSTATIC key; consumeClick(); IFEQ end}, a stored key state such as
     * {@code GETSTATIC key; isDown(); ISTORE n; ... ILOAD n; IFEQ end}, or
     * {@code ALOAD key; GETSTATIC wanted; IF_ACMPNE end}. We inspect only that fall-through block
     * and recursively summarize mod-local helper calls reached from it.
     */
    private static void traceKeyBranches(ClassSurface surface, ClassNode owner,
                                         Map<String, String> keyFields,
                                         Map<String, ClassNode> nodes) {
        if (owner == null || owner.methods == null || keyFields.isEmpty()) return;
        for (MethodNode method : owner.methods) {
            for (AbstractInsnNode instruction : method.instructions) {
                if (!(instruction instanceof FieldInsnNode field)
                        || (field.getOpcode() != Opcodes.GETSTATIC
                        && field.getOpcode() != Opcodes.GETFIELD)) continue;
                String key = keyFields.get(fieldKey(field.owner, field.name));
                if (key == null) continue;
                Region region = keyControlledRegion(field, keyFields);
                if (region == null || region.start() == null) continue;
                MethodFacts facts = collectBehaviorRange(owner.name, region.start(),
                        region.endExclusive(), nodes, new HashSet<>(), 0);
                emitFactsForKey(surface, key, methodKey(method), facts, region.confidence());
            }
        }
    }

    private static Region keyControlledRegion(FieldInsnNode keyField, Map<String, String> keyFields) {
        int remaining = 14;
        VarInsnNode storedBoolean = null;
        boolean keyBoolean = false;
        for (AbstractInsnNode next = keyField.getNext(); next != null && remaining-- > 0;
             next = next.getNext()) {
            if (next instanceof FieldInsnNode other && other != keyField
                    && keyFields.containsKey(fieldKey(other.owner, other.name))) return null;
            if (next instanceof MethodInsnNode call && isKeyMappingType(call.owner)) {
                try {
                    keyBoolean = Type.getArgumentTypes(call.desc).length == 0
                            && Type.getReturnType(call.desc).getSort() == Type.BOOLEAN;
                } catch (IllegalArgumentException ignored) {
                    keyBoolean = false;
                }
                continue;
            }
            if (keyBoolean && next instanceof VarInsnNode var && var.getOpcode() == Opcodes.ISTORE) {
                storedBoolean = var;
                break;
            }
            if (!(next instanceof JumpInsnNode jump)) continue;
            if (!isForwardTarget(jump)) return null;
            int opcode = jump.getOpcode();
            // These are the normal "condition failed -> skip action body" opcodes generated by
            // Java for key.isDown()/consumeClick() and object/field equality checks.
            if (opcode == Opcodes.IFEQ || opcode == Opcodes.IFNULL
                    || opcode == Opcodes.IF_ACMPNE || opcode == Opcodes.IF_ICMPNE) {
                return new Region(jump.getNext(), jump.label, 99);
            }
            return null;
        }
        return storedBoolean == null ? null : storedBooleanRegion(storedBoolean);
    }

    /**
     * Follows a boolean returned by one concrete key mapping after the compiler stores it in a
     * local. The local may be read for unrelated client state before it finally guards the action,
     * so only an overwrite terminates the search. Supporting only the common forward IFEQ shape is
     * deliberate: it proves that the fall-through region executes when the key is down without
     * having to infer arbitrary control-flow joins.
     */
    private static Region storedBooleanRegion(VarInsnNode store) {
        int local = store.var;
        int remaining = 256;
        for (AbstractInsnNode cursor = store.getNext(); cursor != null && remaining-- > 0;
             cursor = cursor.getNext()) {
            if (!(cursor instanceof VarInsnNode var) || var.var != local) continue;
            if (var.getOpcode() == Opcodes.ISTORE) return null;
            if (var.getOpcode() != Opcodes.ILOAD) continue;

            int lookahead = 6;
            for (AbstractInsnNode next = var.getNext(); next != null && lookahead-- > 0;
                 next = next.getNext()) {
                if (next.getOpcode() < 0) continue;
                if (!(next instanceof JumpInsnNode jump)) break;
                if (jump.getOpcode() != Opcodes.IFEQ || !isForwardTarget(jump)) break;
                return new Region(jump.getNext(), jump.label, 98);
            }
        }
        return null;
    }

    private static boolean isForwardTarget(JumpInsnNode jump) {
        int remaining = 768;
        for (AbstractInsnNode current = jump.getNext(); current != null && remaining-- > 0;
             current = current.getNext()) {
            if (current == jump.label) return true;
        }
        return false;
    }

    private static MethodFacts collectBehaviorRange(String ownerName, AbstractInsnNode start,
                                                    AbstractInsnNode endExclusive,
                                                    Map<String, ClassNode> nodes,
                                                    Set<String> callStack, int depth) {
        MethodFacts facts = new MethodFacts();
        if (start == null) return facts;
        for (AbstractInsnNode instruction = start;
             instruction != null && instruction != endExclusive;
             instruction = instruction.getNext()) {
            if (instruction instanceof FieldInsnNode field) {
                if ((field.getOpcode() == Opcodes.PUTSTATIC || field.getOpcode() == Opcodes.PUTFIELD)
                        && !isKeyMappingDescriptor(field.desc)
                        && !widgetReferenceStateField(field, nodes)) {
                    facts.stateWrites.add(stateMutation(field, start, nodes));
                }
                continue;
            }
            if (!(instruction instanceof MethodInsnNode call)) continue;
            RuntimeLaunch runtimeLaunch = runtimeLaunch(call);
            if (runtimeLaunch != null) facts.runtimeLaunches.add(runtimeLaunch);
            if (externalNetworkCall(call)) {
                facts.runtimeLaunches.add(new RuntimeLaunch(ActionType.EXTERNAL_NETWORK_IO,
                        call.owner + '#' + call.name + call.desc));
            }
            String confirmedUrl = confirmedExternalUrlLiteral(call, start);
            if (confirmedUrl != null) facts.externalUrls.add(confirmedUrl);
            CollectionMutation collectionMutation = collectionMutationBefore(call, start);
            if (collectionMutation != null) facts.collectionMutations.add(collectionMutation);
            boolean directNetworkSend = isGameNetworkSend(call);
            PacketFact forwardedPacket = !directNetworkSend && forwardsNetworkSend(call, nodes)
                    ? producedForwardedPacketBefore(ownerName, call, nodes, start) : null;
            boolean forwardingSend = forwardedPacket != null;
            if (isSetScreen(call)) {
                facts.closesScreen |= previousOpcode(call) != null
                        && previousOpcode(call).getOpcode() == Opcodes.ACONST_NULL;
                String screen = producedTypeBefore(instruction, nodes, true, start);
                if (screen != null && !(confirmLinkScreen(screen) && !facts.externalUrls.isEmpty())) {
                    facts.screens.add(screen);
                }
            }
            if (directNetworkSend || forwardingSend) {
                PacketFact packet = forwardingSend
                        ? forwardedPacket
                        : producedPacketBefore(ownerName, instruction, nodes, start);
                if (packet != null) facts.packets.add(packet);
            }
            if (screenCloseLifecycle(call.name, call.desc)
                    && (ownerName.equals(call.owner) || nodes != null && nodes.containsKey(call.owner))) {
                facts.closesScreen = true;
                // Lifecycle methods may commit buffered state. That packet belongs to the
                // SCREEN_CLOSE action recorded separately, not to the key/widget branch that
                // merely invoked onClose()/removed().
                continue;
            }
            // A send helper's own body may construct a framework envelope around the caller's
            // concrete payload. Once we recovered the caller argument above, do not recurse into
            // that helper and emit the envelope as a second semantic action. This applies both to
            // inferred forwarders such as send(Record) and explicit sendToServer(RootPacket)
            // multiloader shims.
            if (directNetworkSend || forwardingSend) continue;
            if (depth >= 4 || "<init>".equals(call.name) || nodes == null
                    || !nodes.containsKey(call.owner)) continue;
            List<StateMutation> directSetters = simpleSetterCallMutations(call, start, nodes);
            if (!directSetters.isEmpty()) {
                facts.stateWrites.addAll(directSetters);
                continue;
            }
            ResolvedMethod resolved = resolveMethodInHierarchy(
                    nodes.get(call.owner), call.name, call.desc, nodes);
            if (resolved == null) continue;
            String callId = resolved.owner() + "#" + methodKey(call.name, call.desc);
            if (!callStack.add(callId)) continue;
            MethodNode callee = resolved.method();
            if (callee != null) {
                StateMutation relativeReceiverMutation = screenHeldRelativeSetterMutation(
                        call, callee, start, nodes);
                if (relativeReceiverMutation != null) {
                    facts.stateWrites.add(relativeReceiverMutation);
                    callStack.remove(callId);
                    continue;
                }
                MethodFacts nested = collectBehaviorForBoundSwitchCall(resolved.owner(), callee,
                        call, start, nodes, callStack, depth + 1);
                if (nested == null) {
                    nested = collectBehaviorRange(resolved.owner(), callee.instructions.getFirst(),
                            null, nodes, callStack, depth + 1);
                }
                bindForwardedStateArguments(nested, ownerName, call, callee, start, nodes);
                bindForwardedPacketArguments(nested, call, callee, start);
                String forwardedUrl = forwardedConfirmedExternalUrl(call, callee, start);
                if (forwardedUrl != null) {
                    nested.externalUrls.add(forwardedUrl);
                    nested.screens.removeIf(ClientSurfaceScanner::confirmLinkScreen);
                }
                foldPacketScreenFieldArguments(nested, facts.stateWrites);
                if (ownerName.equals(call.owner)) {
                    StateMutation toggle = sameObjectBooleanToggleSetterMutation(
                            ownerName, call, callee, start, nodes);
                    if (toggle != null) {
                        LinkedHashSet<StateMutation> rebound = new LinkedHashSet<>();
                        for (StateMutation mutation : nested.stateWrites) {
                            rebound.add(mutation != null && mutation.kind() == null
                                    && java.util.Objects.equals(mutation.target(), toggle.target())
                                    ? toggle : mutation);
                        }
                        nested.stateWrites.clear();
                        nested.stateWrites.addAll(rebound);
                    }
                }
                boolean forwardedBack = forwardedNavigationBack(ownerName, call, resolved.owner(),
                        callee, nodes, start)
                        || zeroArgForwardedNavigationBack(resolved.owner(), callee, nodes);
                if (forwardedBack && navigationOnlyHelper(nested, nodes)) {
                    facts.navigatesBack = true;
                    callStack.remove(callId);
                    continue;
                }
                String forwardedScreen = forwardedNavigationScreen(call, resolved.owner(), callee,
                        nodes, start);
                if (forwardedScreen != null && navigationOnlyHelper(nested, nodes)) {
                    // Some client UI libraries wrap Minecraft#setScreen in a small navigation
                    // helper which retains the current Screen in a client-only back stack. The
                    // concrete destination exists only at the caller; once we recurse into the
                    // helper it has become a Screen parameter and the ordinary produced-type scan
                    // can no longer see it. Preserve that parameter flow structurally and expose
                    // the same OPEN_SCREEN edge the direct setScreen path would have produced.
                    // Navigation bookkeeping itself is represented by F2V's translated screen
                    // lifecycle and is never executed on the dedicated server.
                    facts.screens.add(forwardedScreen);
                    callStack.remove(callId);
                    continue;
                }
                if (ownerName.equals(call.owner)) {
                    // Same-object synthetic/local helpers are part of the callback's own state
                    // machine, so retain their precise scalar writes.
                    mergeFacts(facts, nested);
                } else {
                    StateReceiver receiver = zeroArgReceiverField(call, start);
                    if (receiver != null && pureScalarHelper(nested)) {
                        for (StateMutation mutation : nested.stateWrites) {
                            facts.stateWrites.add(new StateMutation(mutation.target(), mutation.kind(),
                                    mutation.booleanValue(), mutation.intValue(), mutation.intModulus(),
                                    mutation.intModulusTarget(), mutation.intModulusMinimum(),
                                    receiver.owner(), receiver.name()));
                        }
                    } else {
                        // Crossing into another object and flattening all of its internal fields makes
                        // one click look like dozens of independent state actions (builders, layout
                        // objects, serializers, etc.). Preserve externally meaningful behavior such as
                        // packets/navigation, but collapse packetless helper-owned state to one explicit
                        // unsupported helper edge instead of leaking implementation details.
                        mergeExternalFacts(facts, nested);
                        if (nested.packets.isEmpty() && nested.screens.isEmpty() && !nested.closesScreen
                                && nested.runtimeLaunches.isEmpty()
                                && (!nested.stateWrites.isEmpty() || !nested.collectionMutations.isEmpty()
                                || !nested.clientHelpers.isEmpty())) {
                            facts.clientHelpers.add(new ClientHelperCall(call.owner, call.name, call.desc,
                                    visualOnlyClientHelper(call, start, nodes)));
                        }
                    }
                }
            }
            callStack.remove(callId);
        }
        return facts;
    }

    /**
     * Specializes one helper call when an exact caller literal selects a switch on the matching
     * callee parameter. Without this, scanning a shared button dispatcher linearly attaches every
     * switch case to every source widget. The specialization is deliberately narrow: exactly one
     * eligible switch, a direct parameter ILOAD key, and a selected case that terminates with
     * return/throw or jumps to a common tail after all switch targets. Anything ambiguous falls
     * back to the ordinary conservative scan.
     */
    private static MethodFacts collectBehaviorForBoundSwitchCall(String ownerName, MethodNode callee,
                                                                 MethodInsnNode caller,
                                                                 AbstractInsnNode callerFloorInclusive,
                                                                 Map<String, ClassNode> nodes,
                                                                 Set<String> callStack, int depth) {
        if (ownerName == null || callee == null || caller == null || nodes == null
                || callee.instructions == null || callee.instructions.getFirst() == null) return null;
        Type[] arguments;
        try {
            arguments = Type.getArgumentTypes(caller.desc);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
        if (arguments.length == 0) return null;

        Map<Integer, Integer> literalByLocal = new HashMap<>();
        for (int index = 0; index < arguments.length; index++) {
            Type type = arguments[index];
            if (type.getSize() != 1 || !(type.getSort() == Type.BYTE || type.getSort() == Type.CHAR
                    || type.getSort() == Type.SHORT || type.getSort() == Type.INT)) continue;
            AbstractInsnNode supplied = directCategoryOneArgument(caller, arguments, index,
                    callerFloorInclusive);
            StateLiteral literal = stateLiteralThroughLocal(type, supplied, callerFloorInclusive, 0);
            if (literal == null || literal.kind() != StateWriteKind.ASSIGN_INT
                    || literal.intValue() == null) continue;
            int local = parameterLocal(callee, arguments, index);
            if (local >= 0) literalByLocal.put(local, literal.intValue());
        }
        if (literalByLocal.isEmpty()) return null;

        AbstractInsnNode selectedSwitch = null;
        AbstractInsnNode selectedLabel = null;
        Set<AbstractInsnNode> switchTargets = null;
        for (AbstractInsnNode instruction : callee.instructions) {
            AbstractInsnNode key = previousReal(instruction, null);
            if (!(key instanceof VarInsnNode load) || load.getOpcode() != Opcodes.ILOAD) continue;
            Integer literal = literalByLocal.get(load.var);
            if (literal == null) continue;
            AbstractInsnNode label;
            LinkedHashSet<AbstractInsnNode> targets = new LinkedHashSet<>();
            if (instruction instanceof TableSwitchInsnNode table) {
                label = literal >= table.min && literal <= table.max
                        ? table.labels.get(literal - table.min) : table.dflt;
                targets.addAll(table.labels);
                targets.add(table.dflt);
            } else if (instruction instanceof LookupSwitchInsnNode lookup) {
                int match = lookup.keys.indexOf(literal);
                label = match >= 0 ? lookup.labels.get(match) : lookup.dflt;
                targets.addAll(lookup.labels);
                targets.add(lookup.dflt);
            } else {
                continue;
            }
            if (selectedSwitch != null) return null;
            selectedSwitch = instruction;
            selectedLabel = label;
            switchTargets = targets;
        }
        if (selectedSwitch == null || selectedLabel == null || switchTargets == null) return null;

        AbstractInsnNode nextCase = null;
        for (AbstractInsnNode cursor = selectedLabel.getNext(); cursor != null; cursor = cursor.getNext()) {
            if (switchTargets.contains(cursor) && cursor != selectedLabel) {
                nextCase = cursor;
                break;
            }
        }

        AbstractInsnNode branchEnd = nextCase;
        AbstractInsnNode commonTail = null;
        if (nextCase != null) {
            AbstractInsnNode terminal = previousReal(nextCase, selectedLabel);
            if (terminal instanceof JumpInsnNode jump && jump.getOpcode() == Opcodes.GOTO) {
                int tailIndex = callee.instructions.indexOf(jump.label);
                int lastCaseIndex = -1;
                for (AbstractInsnNode target : switchTargets) {
                    // A common compiler shape uses the switch default label itself as the shared
                    // tail. Do not count that same label as a later case when proving that a
                    // selected case's GOTO exits beyond every real case body.
                    if (target == jump.label) continue;
                    lastCaseIndex = Math.max(lastCaseIndex, callee.instructions.indexOf(target));
                }
                if (tailIndex <= lastCaseIndex) return null;
                commonTail = jump.label;
            } else if (terminal == null || !terminalOpcode(terminal.getOpcode())) {
                // Falling through into another case means the selected case cannot be isolated.
                return null;
            }
        }

        MethodFacts specialized = new MethodFacts();
        mergeFacts(specialized, collectBehaviorRange(ownerName, callee.instructions.getFirst(),
                selectedSwitch, nodes, callStack, depth));
        mergeFacts(specialized, collectBehaviorRange(ownerName, selectedLabel.getNext(),
                branchEnd, nodes, callStack, depth));
        if (commonTail != null) {
            mergeFacts(specialized, collectBehaviorRange(ownerName, commonTail.getNext(),
                    null, nodes, callStack, depth));
        }
        return specialized;
    }

    private static boolean terminalOpcode(int opcode) {
        return opcode == Opcodes.RETURN || opcode == Opcodes.IRETURN || opcode == Opcodes.LRETURN
                || opcode == Opcodes.FRETURN || opcode == Opcodes.DRETURN || opcode == Opcodes.ARETURN
                || opcode == Opcodes.ATHROW;
    }

    /** Resolves a constructor-supplied parent Screen passed through a pure navigation wrapper.
     * The runtime class of the parent is deliberately not guessed; F2V restores the exact
     * translated screen frame it previously pushed for this player. */
    private static boolean forwardedNavigationBack(String callerOwner, MethodInsnNode caller,
                                                   String helperOwner, MethodNode helper,
                                                   Map<String, ClassNode> nodes,
                                                   AbstractInsnNode floorInclusive) {
        if (callerOwner == null || caller == null || helper == null || nodes == null) return false;
        Type[] args;
        try {
            args = Type.getArgumentTypes(caller.desc);
        } catch (IllegalArgumentException ignored) {
            return false;
        }
        if (args.length != 1 || !screenParameterType(args[0], nodes)
                || !screenParameterForwards(helperOwner, helper, 0, nodes, new HashSet<>(), 0)) {
            return false;
        }
        AbstractInsnNode supplied = directCategoryOneArgument(caller, args, 0, floorInclusive);
        if (!(supplied instanceof FieldInsnNode field) || field.getOpcode() != Opcodes.GETFIELD
                || !args[0].getDescriptor().equals(field.desc)) return false;
        AbstractInsnNode receiver = previousReal(field, floorInclusive);
        if (!(receiver instanceof VarInsnNode self) || self.getOpcode() != Opcodes.ALOAD || self.var != 0) {
            return false;
        }
        ClassNode screen = nodes.get(callerOwner);
        if (screen == null || !screenHierarchy(screen, nodes).contains(field.owner)) return false;
        return screenHierarchyFieldCopiedFromConstructorParameter(screen, field, field.desc, nodes);
    }

    /** Same navigation-back proof as {@link #forwardedNavigationBack(String, MethodInsnNode,
     * String, MethodNode, Map, AbstractInsnNode)}, for a zero-argument helper which reads the
     * constructor-supplied Screen from its own instance before forwarding it to setScreen. */
    private static boolean zeroArgForwardedNavigationBack(String helperOwner, MethodNode helper,
                                                          Map<String, ClassNode> nodes) {
        if (helperOwner == null || helper == null || nodes == null) return false;
        Type[] helperArgs;
        try {
            helperArgs = Type.getArgumentTypes(helper.desc);
        } catch (IllegalArgumentException ignored) {
            return false;
        }
        if (helperArgs.length != 0) return false;
        ClassNode helperClass = nodes.get(helperOwner);
        if (helperClass == null) return false;
        Set<String> hierarchy = screenHierarchy(helperClass, nodes);
        for (AbstractInsnNode instruction : helper.instructions) {
            if (!(instruction instanceof MethodInsnNode call) || "<init>".equals(call.name)) continue;
            Type[] callArgs;
            try {
                callArgs = Type.getArgumentTypes(call.desc);
            } catch (IllegalArgumentException ignored) {
                continue;
            }
            int screenArg = uniqueScreenParameter(callArgs, nodes);
            if (screenArg < 0) continue;
            AbstractInsnNode supplied = directCategoryOneArgument(call, callArgs, screenArg, null);
            while (supplied instanceof TypeInsnNode cast && cast.getOpcode() == Opcodes.CHECKCAST) {
                supplied = previousReal(supplied, null);
            }
            if (!(supplied instanceof FieldInsnNode field) || field.getOpcode() != Opcodes.GETFIELD
                    || !callArgs[screenArg].getDescriptor().equals(field.desc)
                    || !hierarchy.contains(field.owner)) continue;
            AbstractInsnNode receiver = previousReal(field, null);
            if (!(receiver instanceof VarInsnNode self) || self.getOpcode() != Opcodes.ALOAD
                    || self.var != 0) continue;
            ClassNode declaring = nodes.get(field.owner);
            if (declaring == null || !screenHierarchyFieldCopiedFromConstructorParameter(
                    helperClass, field, field.desc, nodes)) continue;
            if (isSetScreen(call)) return true;
            if (!nodes.containsKey(call.owner)) continue;
            ResolvedMethod resolved = resolveMethodInHierarchy(
                    nodes.get(call.owner), call.name, call.desc, nodes);
            if (resolved != null && resolved.method() != null
                    && screenParameterForwards(resolved.owner(), resolved.method(), screenArg,
                    nodes, new HashSet<>(), 0)) return true;
        }
        return false;
    }

    /** Resolves a concrete Screen passed to a pure client-navigation wrapper. The helper proof is
     * deliberately narrow: the public call must take exactly one Screen-like parameter, and that
     * exact parameter must flow through ordinary helper calls / a zero-SAM-argument lambda until it
     * reaches Minecraft#setScreen. Rich argument expressions or ambiguous parameter flow remain an
     * ordinary INVOKE_CLIENT_HELPER action. */
    private static String forwardedNavigationScreen(MethodInsnNode caller, String helperOwner,
                                                     MethodNode helper,
                                                     Map<String, ClassNode> nodes,
                                                     AbstractInsnNode floorInclusive) {
        if (caller == null || helper == null || nodes == null) return null;
        Type[] args;
        try {
            args = Type.getArgumentTypes(caller.desc);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
        if (args.length != 1 || !screenParameterType(args[0], nodes)
                || !screenParameterForwards(helperOwner, helper, 0, nodes,
                new HashSet<>(), 0)) return null;
        return producedTypeBefore(caller, nodes, true, floorInclusive);
    }

    /**
     * Recover the URL carried by Minecraft's vanilla external-link confirmation screen. This is a
     * framework primitive rather than a mod-specific rule: any client callback that constructs the
     * vanilla ConfirmLinkScreen with one exact String is representable by a vanilla clickable
     * OPEN_URL component on the compatibility client.
     */
    private static String confirmedExternalUrlLiteral(MethodInsnNode constructor,
                                                      AbstractInsnNode floorInclusive) {
        if (!confirmLinkConstructor(constructor)) return null;
        Type[] arguments;
        try {
            arguments = Type.getArgumentTypes(constructor.desc);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
        int stringArgument = uniqueStringArgument(arguments);
        if (stringArgument < 0) return null;
        AbstractInsnNode supplied = directCategoryOneArgument(constructor, arguments,
                stringArgument, floorInclusive);
        return supplied instanceof LdcInsnNode ldc && ldc.cst instanceof String text
                && !text.isBlank() ? text : null;
    }

    /**
     * Same proof as {@link #confirmedExternalUrlLiteral(MethodInsnNode, AbstractInsnNode)}, but for
     * a small local helper such as linkTo(String). The callee must pass that exact String parameter
     * unchanged into ConfirmLinkScreen and the caller must supply one exact literal. Arbitrary URL
     * expressions remain unsupported.
     */
    private static String forwardedConfirmedExternalUrl(MethodInsnNode caller, MethodNode callee,
                                                         AbstractInsnNode callerFloorInclusive) {
        if (caller == null || callee == null) return null;
        Type[] helperArguments;
        try {
            helperArguments = Type.getArgumentTypes(caller.desc);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
        for (Type argument : helperArguments) if (argument.getSize() != 1) return null;
        for (AbstractInsnNode instruction : callee.instructions) {
            if (!(instruction instanceof MethodInsnNode constructor)
                    || !confirmLinkConstructor(constructor)) continue;
            Type[] constructorArguments;
            try {
                constructorArguments = Type.getArgumentTypes(constructor.desc);
            } catch (IllegalArgumentException ignored) {
                continue;
            }
            int stringArgument = uniqueStringArgument(constructorArguments);
            if (stringArgument < 0) continue;
            AbstractInsnNode source = directCategoryOneArgument(constructor, constructorArguments,
                    stringArgument, null);
            if (!(source instanceof VarInsnNode load) || load.getOpcode() != Opcodes.ALOAD) continue;
            for (int parameter = 0; parameter < helperArguments.length; parameter++) {
                if (!"Ljava/lang/String;".equals(helperArguments[parameter].getDescriptor())
                        || parameterLocal(callee, helperArguments, parameter) != load.var) continue;
                AbstractInsnNode supplied = directCategoryOneArgument(caller, helperArguments,
                        parameter, callerFloorInclusive);
                if (supplied instanceof LdcInsnNode ldc && ldc.cst instanceof String text
                        && !text.isBlank()) return text;
            }
        }
        return null;
    }

    private static boolean confirmLinkConstructor(MethodInsnNode call) {
        return call != null && call.getOpcode() == Opcodes.INVOKESPECIAL
                && "<init>".equals(call.name)
                && "net/minecraft/client/gui/screens/ConfirmLinkScreen".equals(call.owner);
    }

    private static boolean confirmLinkScreen(String screen) {
        return screen != null && "net/minecraft/client/gui/screens/ConfirmLinkScreen"
                .equals(screen.replace('.', '/'));
    }

    private static int uniqueStringArgument(Type[] arguments) {
        if (arguments == null) return -1;
        int found = -1;
        for (int index = 0; index < arguments.length; index++) {
            if (!"Ljava/lang/String;".equals(arguments[index].getDescriptor())) continue;
            if (found >= 0) return -1;
            found = index;
        }
        return found;
    }

    private static AbstractInsnNode directCategoryOneArgument(AbstractInsnNode terminal,
                                                               Type[] arguments, int argument,
                                                               AbstractInsnNode floorInclusive) {
        if (terminal == null || arguments == null || argument < 0 || argument >= arguments.length) {
            return null;
        }
        for (Type type : arguments) if (type.getSize() != 1) return null;
        AbstractInsnNode cursor = terminal;
        for (int index = arguments.length - 1; index >= argument; index--) {
            cursor = previousReal(cursor, floorInclusive);
            if (cursor == null) return null;
        }
        return cursor;
    }

    private static boolean screenParameterForwards(String owner, MethodNode method, int parameter,
                                                   Map<String, ClassNode> nodes,
                                                   Set<String> visiting, int depth) {
        if (owner == null || method == null || nodes == null || depth > 8) return false;
        Type[] args;
        try {
            args = Type.getArgumentTypes(method.desc);
        } catch (IllegalArgumentException ignored) {
            return false;
        }
        if (parameter < 0 || parameter >= args.length || !screenParameterType(args[parameter], nodes)) {
            return false;
        }
        String visit = owner + '#' + method.name + method.desc + "@screen:" + parameter;
        if (!visiting.add(visit)) return false;
        try {
            int parameterLocal = parameterLocal(method, args, parameter);
            if (parameterLocal < 0) return false;
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call) {
                    if (isSetScreen(call)) {
                        Type[] callArgs;
                        try {
                            callArgs = Type.getArgumentTypes(call.desc);
                        } catch (IllegalArgumentException ignored) {
                            continue;
                        }
                        int screenArg = uniqueScreenParameter(callArgs, nodes);
                        if (screenArg >= 0 && directParameterArgument(call, callArgs, screenArg,
                                parameterLocal, null)) return true;
                    }
                    if ("<init>".equals(call.name) || !nodes.containsKey(call.owner)) continue;
                    ResolvedMethod resolved = resolveMethodInHierarchy(
                            nodes.get(call.owner), call.name, call.desc, nodes);
                    if (resolved == null || resolved.method() == null) continue;
                    Type[] callArgs;
                    try {
                        callArgs = Type.getArgumentTypes(call.desc);
                    } catch (IllegalArgumentException ignored) {
                        continue;
                    }
                    for (int index = 0; index < callArgs.length; index++) {
                        if (!screenParameterType(callArgs[index], nodes)
                                || !directParameterArgument(call, callArgs, index,
                                parameterLocal, null)) continue;
                        if (screenParameterForwards(resolved.owner(), resolved.method(), index,
                                nodes, visiting, depth + 1)) return true;
                    }
                } else if (instruction instanceof InvokeDynamicInsnNode indy
                        && indy.bsm != null
                        && "java/lang/invoke/LambdaMetafactory".equals(indy.bsm.getOwner())) {
                    Handle implementation = lambdaImplementation(indy);
                    if (implementation == null || !nodes.containsKey(implementation.getOwner())) continue;
                    MethodNode lambda = findMethod(nodes.get(implementation.getOwner()),
                            implementation.getName(), implementation.getDesc());
                    if (lambda == null) continue;
                    Type[] captured;
                    Type[] lambdaArgs;
                    try {
                        captured = Type.getArgumentTypes(indy.desc);
                        lambdaArgs = Type.getArgumentTypes(lambda.desc);
                    } catch (IllegalArgumentException ignored) {
                        continue;
                    }
                    // A captured-only Runnable/zero-argument SAM keeps capture index and lambda
                    // parameter index identical. Other SAM shapes require a richer binding model.
                    if (captured.length == 0 || captured.length != lambdaArgs.length) continue;
                    for (int index = 0; index < captured.length; index++) {
                        if (!screenParameterType(captured[index], nodes)
                                || !captured[index].equals(lambdaArgs[index])
                                || !directParameterArgument(indy, captured, index,
                                parameterLocal, null)) continue;
                        if (screenParameterForwards(implementation.getOwner(), lambda, index,
                                nodes, visiting, depth + 1)) return true;
                    }
                }
            }
            return false;
        } finally {
            visiting.remove(visit);
        }
    }

    private static int parameterLocal(MethodNode method, Type[] arguments, int parameter) {
        if (method == null || arguments == null || parameter < 0 || parameter >= arguments.length) return -1;
        int local = (method.access & Opcodes.ACC_STATIC) != 0 ? 0 : 1;
        for (int index = 0; index < parameter; index++) local += arguments[index].getSize();
        return local;
    }

    private static int uniqueScreenParameter(Type[] arguments, Map<String, ClassNode> nodes) {
        if (arguments == null) return -1;
        int found = -1;
        for (int index = 0; index < arguments.length; index++) {
            if (!screenParameterType(arguments[index], nodes)) continue;
            if (found >= 0) return -1;
            found = index;
        }
        return found;
    }

    private static boolean screenParameterType(Type type, Map<String, ClassNode> nodes) {
        return type != null && type.getSort() == Type.OBJECT
                && isScreenType(type.getInternalName(), nodes);
    }

    /** Straight-line, category-1 argument binding used only after the callee parameter itself was
     * proven to reach setScreen. This intentionally rejects computed/aliased expressions. */
    private static boolean directParameterArgument(AbstractInsnNode terminal, Type[] arguments,
                                                   int argument, int parameterLocal,
                                                   AbstractInsnNode floorInclusive) {
        if (terminal == null || arguments == null || argument < 0 || argument >= arguments.length) {
            return false;
        }
        for (Type type : arguments) if (type.getSize() != 1) return false;
        AbstractInsnNode cursor = terminal;
        for (int index = arguments.length - 1; index >= argument; index--) {
            cursor = previousReal(cursor, floorInclusive);
            if (cursor == null) return false;
        }
        return cursor instanceof VarInsnNode load && load.getOpcode() == Opcodes.ALOAD
                && load.var == parameterLocal;
    }

    private static boolean navigationOnlyHelper(MethodFacts facts, Map<String, ClassNode> nodes) {
        if (facts == null || !facts.packets.isEmpty() || facts.closesScreen
                || !facts.collectionMutations.isEmpty() || !facts.runtimeLaunches.isEmpty()) return false;
        for (ClientHelperCall helper : facts.clientHelpers) {
            if (!navigationForwardingHelper(helper, nodes)) return false;
        }
        for (StateMutation mutation : facts.stateWrites) {
            if (mutation == null || mutation.target() == null) return false;
            int colon = mutation.target().lastIndexOf(':');
            if (colon < 0 || colon + 1 >= mutation.target().length()) return false;
            Type type;
            try {
                type = Type.getType(mutation.target().substring(colon + 1));
            } catch (IllegalArgumentException ignored) {
                return false;
            }
            if (!screenParameterType(type, nodes) && !unreadStateMutation(mutation, nodes)) return false;
        }
        return true;
    }

    /** A helper-local bookkeeping write is unobservable when the exact JVM field is never read by
     * any indexed class. This is intentionally stronger than "not read by this helper": protected
     * screen fields may be consumed by subclasses, and those must remain semantic blockers. */
    private static boolean unreadStateMutation(StateMutation mutation, Map<String, ClassNode> nodes) {
        if (mutation == null || mutation.target() == null || nodes == null) return false;
        int colon = mutation.target().lastIndexOf(':');
        int dot = colon < 0 ? -1 : mutation.target().lastIndexOf('.', colon);
        if (dot <= 0 || colon <= dot + 1) return false;
        String owner = mutation.target().substring(0, dot).replace('.', '/');
        String name = mutation.target().substring(dot + 1, colon);
        String desc = mutation.target().substring(colon + 1);
        if (owner.isBlank() || name.isBlank() || desc.isBlank()) return false;
        for (ClassNode node : nodes.values()) {
            if (node == null || node.methods == null) continue;
            for (MethodNode method : node.methods) {
                if (method == null || method.instructions == null) continue;
                for (AbstractInsnNode instruction : method.instructions) {
                    if (instruction instanceof FieldInsnNode field
                            && (field.getOpcode() == Opcodes.GETFIELD
                            || field.getOpcode() == Opcodes.GETSTATIC)
                            && owner.equals(field.owner) && name.equals(field.name)
                            && desc.equals(field.desc)) return false;
                }
            }
        }
        return true;
    }

    private static boolean navigationForwardingHelper(ClientHelperCall helper,
                                                      Map<String, ClassNode> nodes) {
        if (helper == null || helper.owner() == null || helper.method() == null
                || helper.desc() == null || nodes == null) return false;
        Type[] arguments;
        try {
            arguments = Type.getArgumentTypes(helper.desc());
        } catch (IllegalArgumentException ignored) {
            return false;
        }
        if (arguments.length != 1 || !screenParameterType(arguments[0], nodes)) return false;
        ResolvedMethod resolved = resolveMethodInHierarchy(
                nodes.get(helper.owner()), helper.method(), helper.desc(), nodes);
        return resolved != null && resolved.method() != null
                && screenParameterForwards(resolved.owner(), resolved.method(), 0,
                nodes, new HashSet<>(), 0);
    }

    /**
     * Rebinds a helper's direct parameter-to-field assignments from the exact value supplied by
     * the caller. This is a general call-site data-flow rule, not a UI-specific convention:
     * {@code helper(2)} followed by {@code this.page = arg} remains the exact assignment of 2 even
     * when the helper also rebuilds local widgets or performs other client-only bookkeeping.
     *
     * <p>Only direct parameter loads into instance fields are eligible. Ambiguous producers,
     * control-flow-derived values, mismatched descriptors, or multiple disagreeing writes keep the
     * original unresolved mutation.</p>
     */
    private static void bindForwardedStateArguments(MethodFacts facts, String callerOwner,
                                                    MethodInsnNode caller, MethodNode callee,
                                                    AbstractInsnNode callerFloorInclusive,
                                                    Map<String, ClassNode> nodes) {
        if (facts == null || facts.stateWrites.isEmpty() || caller == null || callee == null
                || callerOwner == null || nodes == null) return;
        Type[] arguments;
        try {
            arguments = Type.getArgumentTypes(callee.desc);
            if (!java.util.Arrays.equals(arguments, Type.getArgumentTypes(caller.desc))) return;
        } catch (IllegalArgumentException ignored) {
            return;
        }
        if (arguments.length == 0) return;

        ClassNode sourceClass = nodes.get(callerOwner);
        MethodNode sourceMethod = methodContainingInstruction(sourceClass, caller);
        if (sourceClass == null || sourceMethod == null) return;
        BytecodeValueFlow flow = new BytecodeValueFlow(nodes);
        Map<Integer, StateLiteral> supplied = new HashMap<>();
        Map<Integer, Set<AbstractInsnNode>> suppliedProducers = new HashMap<>();
        for (int index = 0; index < arguments.length; index++) {
            StateLiteral exact = null;
            Set<AbstractInsnNode> producers = flow.argumentSources(sourceClass, sourceMethod, caller, index);
            if (producers.isEmpty()) continue;
            suppliedProducers.put(index, producers);
            boolean ambiguous = false;
            for (AbstractInsnNode producer : producers) {
                StateLiteral candidate = stateLiteralThroughLocal(
                        arguments[index], producer, callerFloorInclusive, 0);
                if (candidate == null) {
                    candidate = staticSingletonGetterLiteral(
                            arguments[index], producer, nodes, callerFloorInclusive);
                }
                if (candidate == null) {
                    ambiguous = true;
                    break;
                }
                if (exact == null) exact = candidate;
                else if (!exact.equals(candidate)) {
                    ambiguous = true;
                    break;
                }
            }
            if (!ambiguous && exact != null) supplied.put(index, exact);
        }
        if (supplied.isEmpty() && suppliedProducers.isEmpty()) return;

        Map<String, StateLiteral> exactTargets = new HashMap<>();
        Map<String, StateMutation> relativeTargets = new HashMap<>();
        Set<String> rejectedTargets = new HashSet<>();
        for (AbstractInsnNode instruction : callee.instructions) {
            if (!(instruction instanceof FieldInsnNode field) || field.getOpcode() != Opcodes.PUTFIELD) continue;
            String target = field.owner + "." + field.name + ':' + field.desc;
            AbstractInsnNode value = previousReal(field, null);
            int parameter = directParameterLoadIndex(callee, arguments, value);
            StateLiteral literal = parameter < 0 ? null : supplied.get(parameter);
            if (literal != null && stateLiteralFitsField(literal, field.desc)) {
                StateLiteral previous = exactTargets.putIfAbsent(target, literal);
                if (previous != null && !previous.equals(literal)) rejectedTargets.add(target);
                continue;
            }
            StateMutation relative = parameter < 0 ? null : relativeIntTransitionFromProducers(
                    target, field.desc, suppliedProducers.get(parameter), callerFloorInclusive,
                    sourceClass, sourceMethod, flow, nodes);
            if (relative != null) {
                StateMutation previous = relativeTargets.putIfAbsent(target, relative);
                if (previous != null && !previous.equals(relative)) rejectedTargets.add(target);
            }
        }
        // A generated/default wrapper may not write the field itself; it can pass one of its
        // scalar parameters unchanged through another small helper which performs the assignment.
        // Recover that parameter provenance recursively, then substitute the exact caller value.
        for (StateMutation mutation : facts.stateWrites) {
            if (mutation == null || mutation.kind() != null || mutation.target() == null
                    || rejectedTargets.contains(mutation.target())
                    || exactTargets.containsKey(mutation.target())) continue;
            Integer parameter = forwardedStateParameter(caller.owner, callee, mutation.target(),
                    nodes, new HashSet<>(), 0);
            StateLiteral literal = parameter == null ? null : supplied.get(parameter);
            if (literal != null && stateLiteralFitsField(literal,
                    parsedStateTarget(mutation.target()) == null ? ""
                            : parsedStateTarget(mutation.target()).desc())) {
                exactTargets.put(mutation.target(), literal);
                continue;
            }
            ParsedStateTarget parsed = parsedStateTarget(mutation.target());
            StateMutation relative = parameter == null || parsed == null ? null
                    : relativeIntTransitionFromProducers(mutation.target(), parsed.desc(),
                    suppliedProducers.get(parameter), callerFloorInclusive,
                    sourceClass, sourceMethod, flow, nodes);
            if (relative != null) {
                relativeTargets.put(mutation.target(), relative);
            }
        }
        if (exactTargets.isEmpty() && relativeTargets.isEmpty()) return;

        LinkedHashSet<StateMutation> rebound = new LinkedHashSet<>();
        for (StateMutation mutation : facts.stateWrites) {
            StateLiteral literal = mutation == null || mutation.kind() != null
                    || rejectedTargets.contains(mutation.target())
                    ? null : exactTargets.get(mutation.target());
            StateMutation relative = mutation == null || mutation.kind() != null
                    || rejectedTargets.contains(mutation.target())
                    ? null : relativeTargets.get(mutation.target());
            if (literal == null) {
                if (relative != null) {
                    rebound.add(new StateMutation(relative.target(), relative.kind(),
                            relative.booleanValue(), relative.intValue(), relative.intModulus(),
                            relative.intModulusTarget(), relative.intModulusMinimum(),
                            relative.longValue(), relative.doubleValue(), relative.stringValue(),
                            relative.staticFieldOwner(), relative.staticFieldName(), relative.staticFieldDesc(),
                            mutation.receiverFieldOwner(), mutation.receiverFieldName()));
                    continue;
                }
                rebound.add(mutation);
                continue;
            }
            rebound.add(new StateMutation(mutation.target(), literal.kind(),
                    literal.booleanValue(), literal.intValue(), null, null, null, literal.longValue(),
                    literal.doubleValue(), literal.stringValue(), literal.staticFieldOwner(),
                    literal.staticFieldName(), literal.staticFieldDesc(),
                    mutation.receiverFieldOwner(), mutation.receiverFieldName()));
        }
        facts.stateWrites.clear();
        facts.stateWrites.addAll(rebound);
    }

    /**
     * Reconstructs an integer transition supplied to a helper parameter only when the expression
     * reads the exact field that parameter ultimately writes. This is the relative counterpart to
     * literal helper rebinding above: wrappers are transparent, but a helper that transforms its
     * parameter internally is not eligible because {@link #forwardedStateParameter} cannot prove
     * unchanged flow to the target field.
     */
    private static StateMutation relativeIntTransitionFromProducers(
            String target, String descriptor, Set<AbstractInsnNode> producers,
            AbstractInsnNode floorInclusive, ClassNode sourceClass, MethodNode sourceMethod,
            BytecodeValueFlow flow, Map<String, ClassNode> nodes) {
        if (!"I".equals(descriptor) || target == null || producers == null || producers.isEmpty()) {
            return null;
        }
        ParsedStateTarget parsed = parsedStateTarget(target);
        if (parsed == null || !"I".equals(parsed.desc())) return null;
        StateMutation agreed = null;
        for (AbstractInsnNode producer : producers) {
            StateMutation candidate = relativeIntTransitionFromInstruction(
                    target, parsed, producer, floorInclusive, sourceClass, sourceMethod, flow, nodes);
            if (candidate == null) return null;
            if (agreed == null) agreed = candidate;
            else if (!agreed.equals(candidate)) return null;
        }
        return agreed;
    }

    private static StateMutation relativeIntTransitionFromInstruction(
            String target, ParsedStateTarget parsed, AbstractInsnNode producer,
            AbstractInsnNode floorInclusive, ClassNode sourceClass, MethodNode sourceMethod,
            BytecodeValueFlow flow, Map<String, ClassNode> nodes) {
        if (producer == null) return null;
        AbstractInsnNode operation = producer;
        Integer modulus = null;
        DynamicIntModulus dynamicModulus = null;
        if (operation.getOpcode() == Opcodes.IREM) {
            AbstractInsnNode modulusNode = previousReal(operation, floorInclusive);
            modulus = integerConstant(modulusNode);
            AbstractInsnNode modulusStart = modulusNode;
            if (modulus == null) {
                dynamicModulus = dynamicIntModulus(modulusNode, floorInclusive,
                        sourceClass, sourceMethod, flow, nodes);
                if (dynamicModulus == null) return null;
                modulusStart = dynamicModulus.expressionStart();
            } else if (modulus <= 0) {
                return null;
            }
            operation = previousReal(modulusStart, floorInclusive);
        }
        if (operation == null || (operation.getOpcode() != Opcodes.IADD
                && operation.getOpcode() != Opcodes.ISUB)) return null;
        AbstractInsnNode amountNode = previousReal(operation, floorInclusive);
        Integer amount = integerConstant(amountNode);
        AbstractInsnNode source = previousReal(amountNode, floorInclusive);
        if (amount == null || !sameParsedFieldRead(source, parsed)) return null;
        int delta = operation.getOpcode() == Opcodes.ISUB ? -amount : amount;
        return new StateMutation(target,
                modulus == null && dynamicModulus == null
                        ? StateWriteKind.ADD_INT : StateWriteKind.WRAP_ADD_INT,
                null, delta, modulus,
                dynamicModulus == null ? null : dynamicModulus.target(),
                dynamicModulus == null ? null : dynamicModulus.minimum(), null, null);
    }

    private record DynamicIntModulus(String target, Integer minimum,
                                     AbstractInsnNode expressionStart) {}

    /** Exact dynamic modulus source for a wrapped integer expression. Accepted forms are one
     * source-screen int field or Math.max(positiveLiteral, sourceScreenIntField), in either
     * argument order. The returned expression start lets the caller locate the preceding +/-
     * operation without guessing how many bytecode instructions the bound expression consumed. */
    private static DynamicIntModulus dynamicIntModulus(
            AbstractInsnNode producer, AbstractInsnNode floorInclusive,
            ClassNode sourceClass, MethodNode sourceMethod, BytecodeValueFlow flow,
            Map<String, ClassNode> nodes) {
        DynamicIntModulus direct = dynamicIntModulusField(producer, floorInclusive, sourceClass, nodes);
        if (direct != null) return direct;
        if (!(producer instanceof MethodInsnNode call) || call.getOpcode() != Opcodes.INVOKESTATIC
                || !"java/lang/Math".equals(call.owner) || !"max".equals(call.name)
                || !"(II)I".equals(call.desc) || sourceClass == null || sourceMethod == null
                || flow == null) return null;

        DynamicIntModulus field = null;
        Integer minimum = null;
        LinkedHashSet<AbstractInsnNode> starts = new LinkedHashSet<>();
        for (int argument = 0; argument < 2; argument++) {
            Set<AbstractInsnNode> sources = flow.argumentSources(sourceClass, sourceMethod, call, argument);
            if (sources.isEmpty()) return null;
            Integer agreedLiteral = null;
            DynamicIntModulus agreedField = null;
            for (AbstractInsnNode source : sources) {
                Integer literal = integerConstant(source);
                DynamicIntModulus candidateField = literal == null
                        ? dynamicIntModulusField(source, floorInclusive, sourceClass, nodes) : null;
                if (literal == null && candidateField == null) return null;
                if (literal != null) {
                    if (agreedField != null || agreedLiteral != null && !agreedLiteral.equals(literal)) return null;
                    agreedLiteral = literal;
                    starts.add(source);
                } else {
                    if (agreedLiteral != null || agreedField != null
                            && !java.util.Objects.equals(agreedField.target(), candidateField.target())) return null;
                    agreedField = candidateField;
                    starts.add(candidateField.expressionStart());
                }
            }
            if (agreedLiteral != null) {
                if (agreedLiteral <= 0 || minimum != null) return null;
                minimum = agreedLiteral;
            } else {
                if (agreedField == null || field != null) return null;
                field = agreedField;
            }
        }
        if (field == null || minimum == null) return null;
        AbstractInsnNode expressionStart = earliestInstruction(sourceMethod, starts);
        return expressionStart == null ? null
                : new DynamicIntModulus(field.target(), minimum, expressionStart);
    }

    private static DynamicIntModulus dynamicIntModulusField(
            AbstractInsnNode producer, AbstractInsnNode floorInclusive,
            ClassNode sourceClass, Map<String, ClassNode> nodes) {
        if (!(producer instanceof FieldInsnNode field) || field.getOpcode() != Opcodes.GETFIELD
                || !"I".equals(field.desc) || sourceClass == null) return null;
        AbstractInsnNode receiver = previousReal(field, floorInclusive);
        if (!(receiver instanceof VarInsnNode self) || self.getOpcode() != Opcodes.ALOAD || self.var != 0
                || !screenHierarchy(sourceClass, nodes).contains(field.owner)) return null;
        return new DynamicIntModulus(field.owner + "." + field.name + ':' + field.desc,
                null, receiver);
    }

    private static AbstractInsnNode earliestInstruction(MethodNode method,
                                                        Collection<AbstractInsnNode> candidates) {
        if (method == null || method.instructions == null || candidates == null || candidates.isEmpty()) {
            return null;
        }
        Set<AbstractInsnNode> wanted = new HashSet<>(candidates);
        for (AbstractInsnNode instruction : method.instructions) {
            if (wanted.contains(instruction)) return instruction;
        }
        return null;
    }

    private static boolean sameParsedFieldRead(AbstractInsnNode instruction, ParsedStateTarget parsed) {
        return parsed != null && instruction instanceof FieldInsnNode field
                && field.getOpcode() == Opcodes.GETFIELD
                && parsed.owner().equals(field.owner) && parsed.name().equals(field.name)
                && parsed.desc().equals(field.desc);
    }

    /** Returns the unique parameter of {@code method} whose unchanged value eventually reaches
     * the exact field target, possibly through additional forwarding helpers. Constants or
     * computed expressions inside the helper deliberately stop provenance: this routine exists to
     * remove wrapper noise, not to evaluate arbitrary client programs. */
    private static Integer forwardedStateParameter(String ownerName, MethodNode method, String target,
                                                   Map<String, ClassNode> nodes,
                                                   Set<String> visiting, int depth) {
        if (ownerName == null || method == null || target == null || nodes == null || depth > 5) {
            return null;
        }
        String visit = ownerName + '#' + methodKey(method) + "@state-param:" + target;
        if (!visiting.add(visit)) return null;
        try {
            Type[] arguments;
            try {
                arguments = Type.getArgumentTypes(method.desc);
            } catch (IllegalArgumentException ignored) {
                return null;
            }
            Integer agreed = null;
            boolean saw = false;
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof FieldInsnNode field && field.getOpcode() == Opcodes.PUTFIELD
                        && target.equals(field.owner + "." + field.name + ':' + field.desc)) {
                    int parameter = directParameterLoadIndex(method, arguments, previousReal(field, null));
                    if (parameter < 0) return null;
                    if (agreed != null && agreed != parameter) return null;
                    agreed = parameter;
                    saw = true;
                    continue;
                }
                if (!(instruction instanceof MethodInsnNode call) || "<init>".equals(call.name)
                        || !nodes.containsKey(call.owner)) continue;
                ResolvedMethod resolved = resolveMethodInHierarchy(
                        nodes.get(call.owner), call.name, call.desc, nodes);
                if (resolved == null || resolved.method() == null) continue;
                Integer nested = forwardedStateParameter(resolved.owner(), resolved.method(), target,
                        nodes, visiting, depth + 1);
                if (nested == null) continue;
                Type[] callArguments;
                try {
                    callArguments = Type.getArgumentTypes(call.desc);
                } catch (IllegalArgumentException ignored) {
                    return null;
                }
                if (nested < 0 || nested >= callArguments.length) return null;
                AbstractInsnNode supplied = directCategoryOneArgument(call, callArguments, nested, null);
                int parameter = directParameterLoadIndex(method, arguments, supplied);
                if (parameter < 0) return null;
                if (agreed != null && agreed != parameter) return null;
                agreed = parameter;
                saw = true;
            }
            return saw ? agreed : null;
        } finally {
            visiting.remove(visit);
        }
    }

    private static int directParameterLoadIndex(MethodNode method, Type[] arguments,
                                                AbstractInsnNode value) {
        if (!(value instanceof VarInsnNode load) || method == null || arguments == null) return -1;
        for (int index = 0; index < arguments.length; index++) {
            if (parameterLocal(method, arguments, index) != load.var) continue;
            int expected = switch (arguments[index].getSort()) {
                case Type.BOOLEAN, Type.BYTE, Type.CHAR, Type.SHORT, Type.INT -> Opcodes.ILOAD;
                case Type.FLOAT -> Opcodes.FLOAD;
                case Type.LONG -> Opcodes.LLOAD;
                case Type.DOUBLE -> Opcodes.DLOAD;
                case Type.ARRAY, Type.OBJECT -> Opcodes.ALOAD;
                default -> -1;
            };
            return load.getOpcode() == expected ? index : -1;
        }
        return -1;
    }

    /**
     * Carries scanner-proven caller literals through helper parameters when those parameters are
     * forwarded unchanged into a packet constructor. Only category-1 values represented by one
     * exact bytecode instruction are rebound here. This covers enum/singleton constants and boolean
     * literals without attempting to evaluate arbitrary client expressions.
     */
    private static void bindForwardedPacketArguments(MethodFacts facts, MethodInsnNode caller,
                                                     MethodNode callee,
                                                     AbstractInsnNode callerFloorInclusive) {
        if (facts == null || facts.packets.isEmpty() || caller == null || callee == null) return;
        Type[] helperArgs;
        try {
            helperArgs = Type.getArgumentTypes(caller.desc);
        } catch (IllegalArgumentException ignored) {
            return;
        }
        if (helperArgs.length == 0) return;
        // Keep this proof deliberately stack-simple. Long/double arguments consume two slots and
        // richer producer expressions need the packet-value IR rather than backward guessing.
        for (Type argument : helperArgs) {
            if (argument.getSize() != 1) return;
        }

        AbstractInsnNode[] supplied = new AbstractInsnNode[helperArgs.length];
        AbstractInsnNode cursor = caller;
        for (int i = helperArgs.length - 1; i >= 0; i--) {
            cursor = previousReal(cursor, callerFloorInclusive);
            if (cursor == null) return;
            supplied[i] = cursor;
        }

        int[] parameterLocals = new int[helperArgs.length];
        int local = (callee.access & Opcodes.ACC_STATIC) != 0 ? 0 : 1;
        for (int i = 0; i < helperArgs.length; i++) {
            parameterLocals[i] = local;
            local += helperArgs[i].getSize();
        }
        LinkedHashSet<PacketFact> rebound = new LinkedHashSet<>();
        for (PacketFact packet : facts.packets) {
            if (packet == null
                    || packet.constructorDesc() == null || packet.producerOwner() == null
                    || packet.producerMethod() == null || packet.producerDesc() == null
                    || !"<init>".equals(packet.producerMethod())) {
                rebound.add(packet);
                continue;
            }
            Type[] constructorArgs;
            try {
                constructorArgs = Type.getArgumentTypes(packet.constructorDesc());
            } catch (IllegalArgumentException ignored) {
                rebound.add(packet);
                continue;
            }
            if (constructorArgs.length != helperArgs.length) {
                rebound.add(packet);
                continue;
            }
            boolean sameShape = true;
            for (int i = 0; i < helperArgs.length; i++) {
                if (!helperArgs[i].equals(constructorArgs[i])) {
                    sameShape = false;
                    break;
                }
            }
            if (!sameShape) {
                rebound.add(packet);
                continue;
            }

            boolean forwarded = false;
            for (AbstractInsnNode instruction : callee.instructions) {
                if (!(instruction instanceof MethodInsnNode constructor)
                        || constructor.getOpcode() != Opcodes.INVOKESPECIAL
                        || !packet.producerOwner().equals(constructor.owner)
                        || !packet.producerMethod().equals(constructor.name)
                        || !packet.producerDesc().equals(constructor.desc)) continue;
                AbstractInsnNode value = constructor;
                boolean allForwarded = true;
                for (int i = helperArgs.length - 1; i >= 0; i--) {
                    value = previousReal(value, null);
                    int expectedOpcode = helperArgs[i].getSort() == Type.OBJECT
                            || helperArgs[i].getSort() == Type.ARRAY ? Opcodes.ALOAD : Opcodes.ILOAD;
                    if (!(value instanceof VarInsnNode load) || load.getOpcode() != expectedOpcode
                            || load.var != parameterLocals[i]) {
                        allForwarded = false;
                        break;
                    }
                }
                if (allForwarded) {
                    forwarded = true;
                    break;
                }
            }
            if (!forwarded) {
                rebound.add(packet);
                continue;
            }

            StaticFieldArgument staticField = packet.staticFieldArgument();
            Boolean booleanArgument = packet.booleanArgument();
            ArrayList<PacketArgument> arguments = new ArrayList<>(packet.arguments());
            for (int i = 0; i < helperArgs.length; i++) {
                Type argument = helperArgs[i];
                if (argument.getSort() == Type.BOOLEAN) {
                    Boolean literal = booleanConstant(supplied[i]);
                    if (literal != null) booleanArgument = literal;
                } else if ((argument.getSort() == Type.OBJECT || argument.getSort() == Type.ARRAY)
                        && supplied[i] instanceof FieldInsnNode field
                        && field.getOpcode() == Opcodes.GETSTATIC
                        && argument.getDescriptor().equals(field.desc)) {
                    staticField = new StaticFieldArgument(i, field.owner, field.name, field.desc);
                }
                PacketArgument exact = packetArgumentFromInstruction(i, argument, supplied[i],
                        null, null, callerFloorInclusive, helperArgs.length);
                if (exact != null) putPacketArgument(arguments, exact);
            }
            rebound.add(new PacketFact(packet.target(), packet.constructorDesc(), booleanArgument,
                    staticField, List.copyOf(arguments), packet.producerOwner(), packet.producerMethod(),
                    packet.producerDesc()));
        }
        facts.packets.clear();
        facts.packets.addAll(rebound);
    }

    /**
     * Replaces an intermediate SCREEN_FIELD packet expression with the exact value written by the
     * same interaction before the send helper was entered. Any ambiguity (multiple different
     * writes, an unresolved write, relative/toggle state) leaves SCREEN_FIELD intact, which is
     * deliberately non-replayable in the public compatibility contract.
     */
    private static void foldPacketScreenFieldArguments(MethodFacts packetFacts,
                                                       Set<StateMutation> callerWrites) {
        if (packetFacts == null || packetFacts.packets.isEmpty()
                || callerWrites == null || callerWrites.isEmpty()) return;
        LinkedHashSet<PacketFact> foldedPackets = new LinkedHashSet<>();
        for (PacketFact packet : packetFacts.packets) {
            if (packet == null || packet.arguments() == null || packet.arguments().isEmpty()) {
                foldedPackets.add(packet);
                continue;
            }
            ArrayList<PacketArgument> arguments = new ArrayList<>(packet.arguments());
            boolean changed = false;
            for (int i = 0; i < arguments.size(); i++) {
                PacketArgument argument = arguments.get(i);
                if (argument == null || argument.kind != PacketArgumentKind.SCREEN_FIELD
                        || argument.fieldOwner == null || argument.fieldName == null
                        || argument.fieldDesc == null) continue;
                String target = argument.fieldOwner + "." + argument.fieldName + ':' + argument.fieldDesc;
                StateMutation mutation = unambiguousExactWrite(callerWrites, target);
                PacketArgument replacement = packetArgumentFromStateMutation(argument, mutation);
                if (replacement == null) continue;
                arguments.set(i, replacement);
                changed = true;
            }
            foldedPackets.add(changed
                    ? new PacketFact(packet.target(), packet.constructorDesc(), packet.booleanArgument(),
                    packet.staticFieldArgument(), List.copyOf(arguments), packet.producerOwner(),
                    packet.producerMethod(), packet.producerDesc())
                    : packet);
        }
        packetFacts.packets.clear();
        packetFacts.packets.addAll(foldedPackets);
    }

    private static StateMutation unambiguousExactWrite(Set<StateMutation> writes, String target) {
        StateMutation exact = null;
        for (StateMutation write : writes) {
            if (write == null || !java.util.Objects.equals(target, write.target())) continue;
            if (write.kind() == null || write.kind() == StateWriteKind.TOGGLE_BOOLEAN
                    || write.kind() == StateWriteKind.ADD_INT
                    || write.kind() == StateWriteKind.WRAP_ADD_INT) return null;
            if (exact != null && !exact.equals(write)) return null;
            exact = write;
        }
        return exact;
    }

    private static PacketArgument packetArgumentFromStateMutation(PacketArgument template,
                                                                  StateMutation mutation) {
        if (template == null || mutation == null || mutation.kind() == null
                || template.descriptor == null) return null;
        PacketArgument out = new PacketArgument();
        out.index = template.index;
        out.descriptor = template.descriptor;
        switch (mutation.kind()) {
            case ASSIGN_BOOLEAN -> {
                if (!"Z".equals(out.descriptor) || mutation.booleanValue() == null) return null;
                out.kind = PacketArgumentKind.BOOLEAN_LITERAL;
                out.booleanValue = mutation.booleanValue();
            }
            case ASSIGN_INT -> {
                if (!("B".equals(out.descriptor) || "C".equals(out.descriptor)
                        || "S".equals(out.descriptor) || "I".equals(out.descriptor))
                        || mutation.intValue() == null) return null;
                out.kind = PacketArgumentKind.INT_LITERAL;
                out.intValue = mutation.intValue();
            }
            case ASSIGN_LONG -> {
                if (!"J".equals(out.descriptor) || mutation.longValue() == null) return null;
                out.kind = PacketArgumentKind.LONG_LITERAL;
                out.longValue = mutation.longValue();
            }
            case ASSIGN_FLOAT -> {
                if (!"F".equals(out.descriptor) || mutation.doubleValue() == null) return null;
                out.kind = PacketArgumentKind.FLOAT_LITERAL;
                out.doubleValue = mutation.doubleValue();
            }
            case ASSIGN_DOUBLE -> {
                if (!"D".equals(out.descriptor) || mutation.doubleValue() == null) return null;
                out.kind = PacketArgumentKind.DOUBLE_LITERAL;
                out.doubleValue = mutation.doubleValue();
            }
            case ASSIGN_STRING -> {
                if (!"Ljava/lang/String;".equals(out.descriptor) || mutation.stringValue() == null) return null;
                out.kind = PacketArgumentKind.STRING_LITERAL;
                out.stringValue = mutation.stringValue();
            }
            case ASSIGN_INPUT_STRING -> {
                // Runtime text is deliberately unresolved at scan time. It may later satisfy a
                // SCREEN_FIELD packet expression through the compatibility HUD's virtual state.
                return null;
            }
            case ASSIGN_STATIC_FIELD -> {
                if (mutation.staticFieldOwner() == null || mutation.staticFieldName() == null
                        || mutation.staticFieldDesc() == null
                        || !out.descriptor.equals(mutation.staticFieldDesc())) return null;
                out.kind = PacketArgumentKind.STATIC_FIELD;
                out.fieldOwner = mutation.staticFieldOwner();
                out.fieldName = mutation.staticFieldName();
                out.fieldDesc = mutation.staticFieldDesc();
            }
            case TOGGLE_BOOLEAN, ADD_INT, WRAP_ADD_INT -> {
                return null;
            }
        }
        return out;
    }

    /** Client helper whose complete observable bytecode effect is assigning exact literals to
     * fields on itself. The caller receiver must be a concrete Screen field. This summarizes
     * fluent setters and small multi-field setters without executing client code. */
    private static List<StateMutation> simpleSetterCallMutations(MethodInsnNode call,
                                                                 AbstractInsnNode floorInclusive,
                                                                 Map<String, ClassNode> nodes) {
        if (call == null || call.getOpcode() == Opcodes.INVOKESTATIC || nodes == null) return List.of();
        Type[] arguments;
        try {
            arguments = Type.getArgumentTypes(call.desc);
        } catch (IllegalArgumentException ignored) {
            return List.of();
        }
        if (arguments.length == 0 || arguments.length > 4) return List.of();

        // Reuse the same straight-line local-alias proof as direct field writes. Control-flow joins
        // remain unresolved, but compiler-generated STORE/LOAD temporaries no longer turn an exact
        // literal setter into an opaque client helper.
        StateLiteral[] callerValues = new StateLiteral[arguments.length];
        AbstractInsnNode cursor = previousReal(call, floorInclusive);
        for (int index = arguments.length - 1; index >= 0; index--) {
            StateLiteral value = stateLiteralThroughLocal(arguments[index], cursor,
                    floorInclusive, 0);
            if (value == null) return List.of();
            callerValues[index] = value;
            cursor = stateLiteralExpressionStart(arguments[index], cursor, floorInclusive);
            cursor = previousReal(cursor, floorInclusive);
        }
        StateReceiver callerReceiver = fieldReceiver(cursor);
        if (callerReceiver == null) return List.of();

        MethodNode method = findMethodInHierarchy(nodes.get(call.owner), call.name, call.desc, nodes);
        if (method == null || method.instructions == null) return List.of();

        Map<Integer, Integer> parameterByLocal = new HashMap<>();
        int local = 1; // instance receiver occupies slot zero
        for (int index = 0; index < arguments.length; index++) {
            parameterByLocal.put(local, index);
            local += arguments[index].getSize();
        }
        ArrayList<StateMutation> mutations = new ArrayList<>();
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode || instruction instanceof InvokeDynamicInsnNode
                    || instruction instanceof JumpInsnNode
                    || instruction.getType() == AbstractInsnNode.TABLESWITCH_INSN
                    || instruction.getType() == AbstractInsnNode.LOOKUPSWITCH_INSN) return List.of();
            int opcode = instruction.getOpcode();
            if (opcode == Opcodes.ATHROW || opcode == Opcodes.MONITORENTER || opcode == Opcodes.MONITOREXIT
                    || opcode == Opcodes.PUTSTATIC || opcode == Opcodes.IASTORE || opcode == Opcodes.LASTORE
                    || opcode == Opcodes.FASTORE || opcode == Opcodes.DASTORE || opcode == Opcodes.AASTORE
                    || opcode == Opcodes.BASTORE || opcode == Opcodes.CASTORE || opcode == Opcodes.SASTORE) {
                return List.of();
            }
            if (!(instruction instanceof FieldInsnNode field)
                    || (field.getOpcode() != Opcodes.PUTFIELD && field.getOpcode() != Opcodes.PUTSTATIC)) continue;
            if (field.getOpcode() != Opcodes.PUTFIELD || !call.owner.equals(field.owner)) return List.of();
            AbstractInsnNode value = previousReal(field, null);
            AbstractInsnNode receiver = previousReal(value, null);
            if (!(receiver instanceof VarInsnNode self) || self.getOpcode() != Opcodes.ALOAD
                    || self.var != 0) return List.of();

            StateLiteral literal = null;
            if (value instanceof VarInsnNode var && parameterByLocal.containsKey(var.var)) {
                int parameter = parameterByLocal.get(var.var);
                if (var.getOpcode() == loadOpcode(arguments[parameter])) literal = callerValues[parameter];
            }
            if (literal == null) literal = stateLiteral(Type.getType(field.desc), value);
            if (literal == null || !stateLiteralFitsField(literal, field.desc)) return List.of();
            mutations.add(literal.mutation(field.owner + "." + field.name + ':' + field.desc,
                    callerReceiver));
        }
        return mutations.isEmpty() ? List.of() : List.copyOf(mutations);
    }

    /** Earliest instruction of a straight-line literal/local-alias expression. This is used only
     * to continue walking backward to the receiver/preceding argument after the value itself has
     * already been proven by {@link #stateLiteralThroughLocal(Type, AbstractInsnNode, AbstractInsnNode, int)}. */
    private static AbstractInsnNode stateLiteralExpressionStart(Type expected,
                                                                AbstractInsnNode instruction,
                                                                AbstractInsnNode floorInclusive) {
        if (expected == null || instruction == null) return instruction;
        AbstractInsnNode cursor = instruction;
        int depth = 0;
        while (cursor instanceof VarInsnNode load && load.getOpcode() == loadOpcode(expected)
                && depth++ < 4) {
            int storeOpcode = storeOpcode(expected);
            int remaining = 128;
            AbstractInsnNode storeNode = null;
            for (AbstractInsnNode previous = cursor.getPrevious(); previous != null && remaining-- > 0;
                 previous = previous.getPrevious()) {
                if (previous == floorInclusive || previous instanceof JumpInsnNode
                        || previous.getType() == AbstractInsnNode.TABLESWITCH_INSN
                        || previous.getType() == AbstractInsnNode.LOOKUPSWITCH_INSN) break;
                if (previous instanceof VarInsnNode store && store.var == load.var) {
                    if (store.getOpcode() != storeOpcode) return cursor;
                    storeNode = store;
                    break;
                }
            }
            if (storeNode == null) break;
            AbstractInsnNode source = previousReal(storeNode, floorInclusive);
            if (source == null) break;
            cursor = source;
        }
        return cursor;
    }

    private static int loadOpcode(Type type) {
        if (type == null) return -1;
        return switch (type.getSort()) {
            case Type.BOOLEAN, Type.BYTE, Type.CHAR, Type.SHORT, Type.INT -> Opcodes.ILOAD;
            case Type.LONG -> Opcodes.LLOAD;
            case Type.FLOAT -> Opcodes.FLOAD;
            case Type.DOUBLE -> Opcodes.DLOAD;
            case Type.ARRAY, Type.OBJECT -> Opcodes.ALOAD;
            default -> -1;
        };
    }

    private static StateLiteral stateLiteral(Type expected, AbstractInsnNode instruction) {
        if (expected == null || instruction == null) return null;
        return switch (expected.getSort()) {
            case Type.BOOLEAN -> {
                Boolean value = booleanConstant(instruction);
                yield value == null ? null : new StateLiteral(StateWriteKind.ASSIGN_BOOLEAN,
                        value, null, null, null, null, null, null, null);
            }
            case Type.BYTE, Type.CHAR, Type.SHORT, Type.INT -> {
                Integer value = integerConstant(instruction);
                yield value == null ? null : new StateLiteral(StateWriteKind.ASSIGN_INT,
                        null, value, null, null, null, null, null, null);
            }
            case Type.LONG -> {
                Long value = longConstant(instruction);
                yield value == null ? null : new StateLiteral(StateWriteKind.ASSIGN_LONG,
                        null, null, value, null, null, null, null, null);
            }
            case Type.FLOAT -> {
                Double value = floatingConstant(instruction);
                yield value == null ? null : new StateLiteral(StateWriteKind.ASSIGN_FLOAT,
                        null, null, null, value, null, null, null, null);
            }
            case Type.DOUBLE -> {
                Double value = floatingConstant(instruction);
                yield value == null ? null : new StateLiteral(StateWriteKind.ASSIGN_DOUBLE,
                        null, null, null, value, null, null, null, null);
            }
            case Type.OBJECT, Type.ARRAY -> {
                if (instruction instanceof LdcInsnNode ldc && ldc.cst instanceof String text) {
                    yield new StateLiteral(StateWriteKind.ASSIGN_STRING,
                            null, null, null, null, text, null, null, null);
                }
                if (instruction instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETSTATIC) {
                    yield new StateLiteral(StateWriteKind.ASSIGN_STATIC_FIELD,
                            null, null, null, null, null, field.owner, field.name, field.desc);
                }
                yield null;
            }
            default -> null;
        };
    }

    private static boolean stateLiteralFitsField(StateLiteral value, String descriptor) {
        if (value == null || descriptor == null) return false;
        return switch (value.kind()) {
            case ASSIGN_BOOLEAN -> "Z".equals(descriptor);
            case ASSIGN_INT -> "I".equals(descriptor) || "B".equals(descriptor)
                    || "S".equals(descriptor) || "C".equals(descriptor);
            case ASSIGN_LONG -> "J".equals(descriptor);
            case ASSIGN_FLOAT -> "F".equals(descriptor);
            case ASSIGN_DOUBLE -> "D".equals(descriptor);
            case ASSIGN_STRING -> descriptor.startsWith("L") || descriptor.startsWith("[");
            case ASSIGN_INPUT_STRING -> false;
            case ASSIGN_STATIC_FIELD -> descriptor.startsWith("L") || descriptor.startsWith("[");
            case TOGGLE_BOOLEAN, ADD_INT, WRAP_ADD_INT -> false;
        };
    }

    private static StateReceiver zeroArgReceiverField(MethodInsnNode call, AbstractInsnNode floorInclusive) {
        if (call == null || call.getOpcode() == Opcodes.INVOKESTATIC) return null;
        try {
            if (Type.getArgumentTypes(call.desc).length != 0) return null;
        } catch (IllegalArgumentException ignored) {
            return null;
        }
        return fieldReceiver(previousReal(call, floorInclusive));
    }

    private static StateReceiver fieldReceiver(AbstractInsnNode instruction) {
        if (instruction instanceof FieldInsnNode field
                && (field.getOpcode() == Opcodes.GETFIELD || field.getOpcode() == Opcodes.GETSTATIC)) {
            return new StateReceiver(field.owner, field.name);
        }
        return null;
    }

    private static boolean pureScalarHelper(MethodFacts facts) {
        return facts != null && !facts.stateWrites.isEmpty()
                && facts.stateWrites.stream().allMatch(write -> write != null && write.kind() != null)
                && facts.packets.isEmpty() && facts.screens.isEmpty() && !facts.closesScreen
                && facts.collectionMutations.isEmpty() && facts.clientHelpers.isEmpty()
                && facts.runtimeLaunches.isEmpty();
    }

    /**
     * Proves that one cross-object call is client rendering/interpolation bookkeeping. The call
     * must operate on an exact field receiver, the helper method must be self-contained state
     * computation, and every read of that receiver field must either mutate the helper fluently or
     * observe a value from a render-context method. This keeps semantic config/model helpers red.
     */
    static boolean visualOnlyClientHelper(MethodInsnNode call, AbstractInsnNode floorInclusive,
                                          Map<String, ClassNode> nodes) {
        if (call == null || nodes == null) return false;
        if (helperWritesOnlyRenderState(call, nodes)) return true;
        if (call.getOpcode() == Opcodes.INVOKESTATIC) return false;
        FieldInsnNode receiver = simpleCallReceiverField(call, floorInclusive);
        if (receiver == null || receiver.getOpcode() != Opcodes.GETFIELD) return false;
        String helperType = objectInternalName(receiver.desc);
        if (helperType == null || !helperType.equals(call.owner)
                || !pureSelfStateMethod(call.owner, call.name, call.desc, nodes,
                new HashSet<>(), 0)) return false;
        return receiverFieldObservedOnlyByRendering(receiver, helperType, nodes);
    }

    /** Pure widget/chrome helper whose complete state footprint is render-only. This is stronger
     * than a "simple setter" check: every field write in the helper (including same-class helper
     * calls) must itself pass the render-only field proof, and any external void effect rejects it. */
    private static boolean helperWritesOnlyRenderState(MethodInsnNode call,
                                                       Map<String, ClassNode> nodes) {
        if (call == null || nodes == null) return false;
        ClassNode owner = nodes.get(call.owner);
        if (owner == null) return false;
        ResolvedMethod resolved = resolveMethodInHierarchy(owner, call.name, call.desc, nodes);
        if (resolved == null || resolved.method() == null) return false;
        return methodWritesOnlyRenderState(resolved.owner(), resolved.method(), nodes,
                new HashSet<>(), 0) > 0;
    }

    /** @return -1 when unsafe, otherwise the number of proven render-only field writes. */
    private static int methodWritesOnlyRenderState(String ownerName, MethodNode method,
                                                   Map<String, ClassNode> nodes,
                                                   Set<String> visiting, int depth) {
        if (ownerName == null || method == null || nodes == null || depth > 5) return -1;
        String id = ownerName + '#' + methodKey(method);
        if (!visiting.add(id)) return 0;
        try {
            Set<String> hierarchy = classHierarchyNames(nodes.get(ownerName), nodes);
            int writes = 0;
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof FieldInsnNode field) {
                    if (field.getOpcode() == Opcodes.PUTSTATIC) return -1;
                    if (field.getOpcode() == Opcodes.PUTFIELD) {
                        if (!hierarchy.contains(field.owner)) return -1;
                        String target = field.owner + '.' + field.name + ':' + field.desc;
                        StateMutation mutation = new StateMutation(
                                target, null, null, null, null, null);
                        if (!renderOnlyStateMutation(mutation, nodes)) return -1;
                        writes++;
                    }
                    continue;
                }
                if (!(instruction instanceof MethodInsnNode nested)) continue;
                if (isSetScreen(nested) || isGameNetworkSend(nested)) return -1;
                if (hierarchy.contains(nested.owner) && !"<init>".equals(nested.name)) {
                    ClassNode nestedOwner = nodes.get(nested.owner);
                    ResolvedMethod resolved = nestedOwner == null ? null
                            : resolveMethodInHierarchy(nestedOwner, nested.name, nested.desc, nodes);
                    if (resolved == null || resolved.method() == null) return -1;
                    int nestedWrites = methodWritesOnlyRenderState(resolved.owner(), resolved.method(),
                            nodes, visiting, depth + 1);
                    if (nestedWrites < 0) return -1;
                    writes += nestedWrites;
                    continue;
                }
                Type returned;
                try {
                    returned = Type.getReturnType(nested.desc);
                } catch (IllegalArgumentException ignored) {
                    return -1;
                }
                if (returned.getSort() == Type.VOID) return -1;
            }
            return writes;
        } finally {
            visiting.remove(id);
        }
    }

    /** Locates a field receiver only when every call argument is represented by one JVM value
     * instruction. Complex argument expressions stay unsupported rather than making stack guesses. */
    private static FieldInsnNode simpleCallReceiverField(MethodInsnNode call,
                                                         AbstractInsnNode floorInclusive) {
        if (call == null || call.desc == null) return null;
        Type[] arguments;
        try {
            arguments = Type.getArgumentTypes(call.desc);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
        AbstractInsnNode cursor = call;
        for (int index = arguments.length - 1; index >= 0; index--) {
            cursor = previousReal(cursor, floorInclusive);
            if (!singleInstructionCallArgument(cursor, arguments[index])) return null;
        }
        cursor = previousReal(cursor, floorInclusive);
        while (cursor instanceof TypeInsnNode cast && cast.getOpcode() == Opcodes.CHECKCAST) {
            cursor = previousReal(cursor, floorInclusive);
        }
        return cursor instanceof FieldInsnNode field
                && (field.getOpcode() == Opcodes.GETFIELD || field.getOpcode() == Opcodes.GETSTATIC)
                ? field : null;
    }

    private static boolean singleInstructionCallArgument(AbstractInsnNode instruction, Type type) {
        if (instruction == null || type == null) return false;
        if (instruction instanceof VarInsnNode) return true;
        if (instruction instanceof LdcInsnNode || instruction instanceof IntInsnNode) return true;
        if (instruction instanceof FieldInsnNode field) return field.getOpcode() == Opcodes.GETSTATIC;
        int opcode = instruction.getOpcode();
        return opcode == Opcodes.ACONST_NULL || opcode == Opcodes.ICONST_M1
                || opcode >= Opcodes.ICONST_0 && opcode <= Opcodes.ICONST_5
                || opcode == Opcodes.LCONST_0 || opcode == Opcodes.LCONST_1
                || opcode >= Opcodes.FCONST_0 && opcode <= Opcodes.FCONST_2
                || opcode == Opcodes.DCONST_0 || opcode == Opcodes.DCONST_1;
    }

    private static boolean receiverFieldObservedOnlyByRendering(FieldInsnNode receiver,
                                                                String helperType,
                                                                Map<String, ClassNode> nodes) {
        ClassNode owner = nodes.get(receiver.owner);
        if (owner == null || owner.methods == null) return false;
        boolean sawRead = false;
        for (MethodNode method : owner.methods) {
            if (method == null || method.instructions == null) continue;
            for (AbstractInsnNode instruction : method.instructions) {
                if (!(instruction instanceof FieldInsnNode field) || field.getOpcode() != Opcodes.GETFIELD
                        || !receiver.owner.equals(field.owner) || !receiver.name.equals(field.name)
                        || !receiver.desc.equals(field.desc)) continue;
                sawRead = true;
                MethodInsnNode use = helperCallAfter(field, helperType, 12);
                if (use == null || !pureSelfStateMethod(use.owner, use.name, use.desc, nodes,
                        new HashSet<>(), 0)) return false;
                Type returned;
                try {
                    returned = Type.getReturnType(use.desc);
                } catch (IllegalArgumentException ignored) {
                    return false;
                }
                if (returned.getSort() == Type.VOID) continue;
                if (returned.getSort() == Type.OBJECT && helperType.equals(returned.getInternalName())) {
                    continue;
                }
                if (!renderContextMethod(method)) return false;
            }
        }
        return sawRead;
    }

    private static MethodInsnNode helperCallAfter(FieldInsnNode receiver, String helperType, int limit) {
        for (AbstractInsnNode cursor = receiver == null ? null : receiver.getNext();
             cursor != null && limit-- > 0; cursor = cursor.getNext()) {
            if (cursor instanceof MethodInsnNode call) {
                return helperType.equals(call.owner) ? call : null;
            }
            if (cursor instanceof JumpInsnNode || cursor instanceof TableSwitchInsnNode
                    || cursor instanceof LookupSwitchInsnNode) return null;
        }
        return null;
    }

    /** Self-contained client-state computation: instance fields may change, but the helper cannot
     * send packets, navigate screens, mutate static/global state, or call external void effects. */
    private static boolean pureSelfStateMethod(String ownerName, String name, String desc,
                                               Map<String, ClassNode> nodes,
                                               Set<String> visiting, int depth) {
        if (ownerName == null || nodes == null || depth > 5) return false;
        ClassNode owner = nodes.get(ownerName);
        if (owner == null) return false;
        ResolvedMethod resolved = resolveMethodInHierarchy(owner, name, desc, nodes);
        if (resolved == null || resolved.method() == null) return false;
        String id = resolved.owner() + '#' + methodKey(resolved.method());
        if (!visiting.add(id)) return true;
        try {
            Set<String> hierarchy = classHierarchyNames(nodes.get(resolved.owner()), nodes);
            for (AbstractInsnNode instruction : resolved.method().instructions) {
                if (instruction instanceof FieldInsnNode field) {
                    if (field.getOpcode() == Opcodes.PUTSTATIC) return false;
                    if (field.getOpcode() == Opcodes.PUTFIELD && !hierarchy.contains(field.owner)) return false;
                    continue;
                }
                if (!(instruction instanceof MethodInsnNode call)) continue;
                if (isSetScreen(call) || isGameNetworkSend(call)) return false;
                if (hierarchy.contains(call.owner)) {
                    if ("<init>".equals(call.name)) continue;
                    if (!pureSelfStateMethod(call.owner, call.name, call.desc, nodes,
                            visiting, depth + 1)) return false;
                    continue;
                }
                Type returned;
                try {
                    returned = Type.getReturnType(call.desc);
                } catch (IllegalArgumentException ignored) {
                    return false;
                }
                if (returned.getSort() == Type.VOID) return false;
            }
            return true;
        } finally {
            visiting.remove(id);
        }
    }

    private static Set<String> classHierarchyNames(ClassNode owner, Map<String, ClassNode> nodes) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        ClassNode cursor = owner;
        int remaining = 24;
        while (cursor != null && remaining-- > 0 && out.add(cursor.name)) {
            cursor = cursor.superName == null || nodes == null ? null : nodes.get(cursor.superName);
        }
        return out;
    }

    private static boolean screenCloseLifecycle(String name, String descriptor) {
        return "()V".equals(descriptor) && ("onClose".equals(name) || "removed".equals(name)
                || "m_7379_".equals(name) || "m_7861_".equals(name));
    }

    private static MethodNode findMethod(ClassNode owner, String name, String desc) {
        if (owner == null || owner.methods == null) return null;
        for (MethodNode method : owner.methods) {
            if (method.name.equals(name) && method.desc.equals(desc)) return method;
        }
        return null;
    }

    private static void mergeFacts(MethodFacts target, MethodFacts source) {
        target.closesScreen |= source.closesScreen;
        target.navigatesBack |= source.navigatesBack;
        target.screens.addAll(source.screens);
        target.externalUrls.addAll(source.externalUrls);
        target.packets.addAll(source.packets);
        target.stateWrites.addAll(source.stateWrites);
        target.collectionMutations.addAll(source.collectionMutations);
        target.clientHelpers.addAll(source.clientHelpers);
        target.runtimeLaunches.addAll(source.runtimeLaunches);
    }

    private static void mergeExternalFacts(MethodFacts target, MethodFacts source) {
        target.closesScreen |= source.closesScreen;
        target.navigatesBack |= source.navigatesBack;
        target.screens.addAll(source.screens);
        target.externalUrls.addAll(source.externalUrls);
        target.packets.addAll(source.packets);
        target.runtimeLaunches.addAll(source.runtimeLaunches);
    }

    /** Attach JVM-default initial values only when every relevant initializer is proven not to
     * write the field. This is intentionally narrower than trying to evaluate constructors: a
     * single explicit/dynamic write makes the initial value unknown and relative replay stays red. */
    private static void attachDefaultScalarInitials(ClassSurface surface, ClassNode owner,
                                                    Map<String, ClassNode> nodes) {
        if (surface == null || owner == null || nodes == null) return;
        for (Action action : surface.actions) {
            if (action == null || action.type != ActionType.WRITE_STATE || action.target == null
                    || action.sourceScreen == null
                    || !owner.name.equals(action.sourceScreen.replace('.', '/'))) continue;
            ParsedStateTarget parsed = parsedStateTarget(action.target);
            if (parsed == null) continue;
            ClassNode stateOwner = owner.name.equals(parsed.owner()) ? owner : nodes.get(parsed.owner());
            if (stateOwner == null || stateOwner.fields == null) continue;
            // External helper state is eligible only when bytecode proved that the concrete helper
            // instance comes from a field on this source Screen. The one additional safe case is
            // state owned by the clicked widget class itself: runtime already isolates that state
            // by screen + widget ordinal + widget class, so its JVM default is equally exact.
            boolean widgetOwned = action.widgetClass != null
                    && parsed.owner().equals(action.widgetClass.replace('.', '/'));
            if (stateOwner != owner && !widgetOwned && (!action.stateReceiverOwnedByScreen
                    || action.stateReceiverFieldName == null)) continue;
            FieldNode field = null;
            for (FieldNode candidate : stateOwner.fields) {
                if (candidate != null && parsed.name().equals(candidate.name)
                        && parsed.desc().equals(candidate.desc)) {
                    field = candidate;
                    break;
                }
            }
            if (field == null || !("I".equals(field.desc) || "Z".equals(field.desc))) continue;
            Object initial = provenScalarInitial(stateOwner, field, nodes);
            if (initial instanceof Boolean value) action.stateInitialBoolean = value;
            if (initial instanceof Integer value) action.stateInitialInt = value;
        }
    }

    private record ParsedStateTarget(String owner, String name, String desc) {}

    private static ParsedStateTarget parsedStateTarget(String target) {
        if (target == null) return null;
        int colon = target.lastIndexOf(':');
        int dot = colon < 0 ? -1 : target.lastIndexOf('.', colon);
        if (dot <= 0 || colon <= dot + 1 || colon + 1 >= target.length()) return null;
        return new ParsedStateTarget(target.substring(0, dot).replace('.', '/'),
                target.substring(dot + 1, colon), target.substring(colon + 1));
    }

    /**
     * Proves that one exact client field is rendering/interpolation state rather than semantic UI
     * data. The proof is intentionally read-based: every scanner-visible read of the same JVM
     * field reference must occur in a method whose descriptor carries a render-context object.
     * A field with no reads is not accepted, and a single read from input/tick/network/config code
     * keeps the mutation semantic. This avoids field-name or mod-name allowlists.
     */
    static boolean renderOnlyStateMutation(StateMutation mutation, Map<String, ClassNode> nodes) {
        if (mutation == null || nodes == null || nodes.isEmpty()) return false;
        ParsedStateTarget parsed = parsedStateTarget(mutation.target());
        if (parsed == null) return false;
        // Replacing one vanilla GUI child reference is presentation composition, not semantic
        // application state. F2V represents that composition with its own captured/synthetic
        // controls, so the original client object must neither be constructed nor treated as a
        // gameplay blocker. Keep this deliberately limited to the two vanilla base contracts used
        // for screen children; mod-defined model/config objects still require the read-based proof
        // below.
        if ("Lnet/minecraft/client/gui/components/events/GuiEventListener;".equals(parsed.desc())
                || "Lnet/minecraft/client/gui/components/AbstractWidget;".equals(parsed.desc())) {
            return true;
        }
        return renderOnlyStateTarget(parsed, nodes, new HashSet<>(), 0);
    }

    /**
     * Read-only proof with one narrowly defined transitive case: a non-render read may feed the
     * value of another same-instance field directly, provided that destination field is itself
     * proven render-only. This covers interpolation/chrome pipelines such as
     * {@code currentGradient -> previousGradient -> render} without treating arbitrary helper
     * computation as visual state. Any call, branch, return, external receiver, or cycle rejects
     * the proof.
     */
    private static boolean renderOnlyStateTarget(ParsedStateTarget parsed,
                                                 Map<String, ClassNode> nodes,
                                                 Set<String> visiting, int depth) {
        if (parsed == null || nodes == null || depth > 8) return false;
        String identity = parsed.owner() + '.' + parsed.name() + ':' + parsed.desc();
        if (!visiting.add(identity)) return false;
        ClassNode owner = nodes.get(parsed.owner());
        if (owner == null || owner.methods == null) {
            visiting.remove(identity);
            return false;
        }
        try {
            boolean read = false;
            for (MethodNode method : owner.methods) {
                if (method == null || method.instructions == null) continue;
                for (AbstractInsnNode instruction : method.instructions) {
                    if (!(instruction instanceof FieldInsnNode field)
                            || (field.getOpcode() != Opcodes.GETFIELD
                            && field.getOpcode() != Opcodes.GETSTATIC)
                            || !parsed.owner().equals(field.owner)
                            || !parsed.name().equals(field.name)
                            || !parsed.desc().equals(field.desc)) continue;
                    read = true;
                    if (presentationContextMethod(method, nodes)) continue;
                    ParsedStateTarget forwarded = directSameInstanceFieldCopy(field, parsed, nodes);
                    if (forwarded == null || !renderOnlyStateTarget(
                            forwarded, nodes, visiting, depth + 1)) return false;
                }
            }
            return read;
        } finally {
            visiting.remove(identity);
        }
    }

    private static ParsedStateTarget directSameInstanceFieldCopy(FieldInsnNode read,
                                                                 ParsedStateTarget source,
                                                                 Map<String, ClassNode> nodes) {
        if (read == null || source == null || read.getOpcode() != Opcodes.GETFIELD) return null;
        AbstractInsnNode sink = nextOpcode(read.getNext());
        if (!(sink instanceof FieldInsnNode write) || write.getOpcode() != Opcodes.PUTFIELD) return null;
        Set<String> hierarchy = classHierarchyNames(nodes.get(source.owner()), nodes);
        if (!hierarchy.contains(write.owner)) return null;

        // JVM stack for `this.target = this.source`: the destination receiver is pushed before the
        // source receiver. Require both to be the same local; aliases/calls remain unsupported.
        AbstractInsnNode sourceReceiver = previousReal(read, null);
        AbstractInsnNode targetReceiver = previousReal(sourceReceiver, null);
        if (!(sourceReceiver instanceof VarInsnNode sourceLoad)
                || sourceLoad.getOpcode() != Opcodes.ALOAD
                || !(targetReceiver instanceof VarInsnNode targetLoad)
                || targetLoad.getOpcode() != Opcodes.ALOAD
                || sourceLoad.var != targetLoad.var) return null;
        return parsedStateTarget(write.owner + "." + write.name + ':' + write.desc);
    }

    /** Presentation-only context. In addition to true render methods, a method that directly
     * constructs translated GUI widgets may consume layout state without making that state
     * semantic. This is intentionally structural: arbitrary init-like names do not qualify, and
     * one read from a non-presentation method still rejects the field globally. */
    private static boolean presentationContextMethod(MethodNode method, Map<String, ClassNode> nodes) {
        return renderContextMethod(method) || constructsWidget(method, nodes);
    }

    /** Descriptor-only render-context proof. Names are deliberately ignored because obfuscation
     * and GUI frameworks rename render methods while retaining the framework value types. */
    private static boolean renderContextMethod(MethodNode method) {
        if (method == null || method.desc == null) return false;
        try {
            for (Type argument : Type.getArgumentTypes(method.desc)) {
                if (argument.getSort() != Type.OBJECT) continue;
                String type = argument.getInternalName();
                if ("net/minecraft/client/gui/GuiGraphics".equals(type)
                        || "com/mojang/blaze3d/vertex/PoseStack".equals(type)
                        || "net/minecraft/client/gui/Font".equals(type)) return true;
            }
        } catch (IllegalArgumentException ignored) {
            return false;
        }
        return false;
    }

    /** Package-private for regression tests. */
    static Object provenJvmDefault(ClassNode owner, FieldNode field, Map<String, ClassNode> nodes) {
        if (owner == null || field == null || !("I".equals(field.desc) || "Z".equals(field.desc))) {
            return null;
        }
        boolean isStatic = (field.access & Opcodes.ACC_STATIC) != 0;
        String initializer = isStatic ? "<clinit>" : "<init>";
        boolean sawInitializer = isStatic;
        String target = owner.name + "." + field.name + ":" + field.desc;
        if (owner.methods != null) {
            for (MethodNode method : owner.methods) {
                if (method == null || !initializer.equals(method.name)) continue;
                sawInitializer = true;
                Set<String> stack = new HashSet<>();
                stack.add(owner.name + "#" + methodKey(method));
                MethodFacts facts = collectBehaviorRange(owner.name, method.instructions.getFirst(),
                        null, nodes, stack, 0);
                if (facts.stateWrites.stream().anyMatch(write -> target.equals(write.target()))) {
                    return null;
                }
            }
        }
        // An instance classfile should have a constructor. If it does not, do not infer a value.
        if (!sawInitializer) return null;
        return "Z".equals(field.desc) ? Boolean.FALSE : Integer.valueOf(0);
    }

    /**
     * Stronger initial-value proof used by relative virtual state. JVM-default fields remain the
     * cheapest case, but javac/Kotlin frequently spell an equally deterministic initial value as
     * {@code this.page = 0} or {@code this.enabled = true} in every constructor. Accept that shape
     * only when every constructor reaches the same exact boolean/int literal. Constructor
     * delegation is followed recursively; dynamic writes, helper-owned writes, branches around the
     * field write, multiple writes, or disagreeing overloads keep the value unresolved.
     */
    static Object provenScalarInitial(ClassNode owner, FieldNode field, Map<String, ClassNode> nodes) {
        Object jvmDefault = provenJvmDefault(owner, field, nodes);
        if (jvmDefault != null) return jvmDefault;
        if (owner == null || field == null || nodes == null
                || (field.access & Opcodes.ACC_STATIC) != 0
                || !("I".equals(field.desc) || "Z".equals(field.desc))
                || owner.methods == null) return null;

        Object expected = null;
        boolean sawConstructor = false;
        for (MethodNode method : owner.methods) {
            if (method == null || !"<init>".equals(method.name)) continue;
            sawConstructor = true;
            ScalarInitialProof proof = constructorScalarInitial(owner, field, method, nodes,
                    new HashSet<>(), 0);
            if (!proof.proven()) return null;
            if (expected == null) expected = proof.value();
            else if (!java.util.Objects.equals(expected, proof.value())) return null;
        }
        return sawConstructor ? expected : null;
    }

    private record ScalarInitialProof(boolean proven, Object value) {
        static ScalarInitialProof unknown() { return new ScalarInitialProof(false, null); }
        static ScalarInitialProof exact(Object value) { return new ScalarInitialProof(true, value); }
    }

    private static ScalarInitialProof constructorScalarInitial(ClassNode owner, FieldNode field,
                                                               MethodNode constructor,
                                                               Map<String, ClassNode> nodes,
                                                               Set<String> visiting, int depth) {
        if (owner == null || field == null || constructor == null || nodes == null || depth > 8) {
            return ScalarInitialProof.unknown();
        }
        String id = owner.name + '#' + methodKey(constructor) + "@initial:" + field.name + ':' + field.desc;
        if (!visiting.add(id)) return ScalarInitialProof.unknown();
        try {
            String target = owner.name + "." + field.name + ':' + field.desc;
            FieldInsnNode directWrite = null;
            boolean hasControlFlow = false;
            for (AbstractInsnNode instruction : constructor.instructions) {
                if (instruction instanceof JumpInsnNode
                        || instruction.getType() == AbstractInsnNode.TABLESWITCH_INSN
                        || instruction.getType() == AbstractInsnNode.LOOKUPSWITCH_INSN) {
                    hasControlFlow = true;
                }
                if (instruction instanceof FieldInsnNode write
                        && write.getOpcode() == Opcodes.PUTFIELD
                        && owner.name.equals(write.owner)
                        && field.name.equals(write.name)
                        && field.desc.equals(write.desc)) {
                    if (directWrite != null) return ScalarInitialProof.unknown();
                    directWrite = write;
                }
            }

            Set<String> stack = new HashSet<>();
            stack.add(owner.name + "#" + methodKey(constructor));
            MethodFacts facts = collectBehaviorRange(owner.name, constructor.instructions.getFirst(),
                    null, nodes, stack, 0);
            long observedWrites = facts.stateWrites.stream()
                    .filter(write -> write != null && target.equals(write.target())).count();

            if (directWrite != null) {
                // A conditional constructor assignment is not a single initial value even if one
                // branch happens to contain a literal. Likewise, another helper write to the same
                // field would make ordering part of the proof, which this compact evaluator avoids.
                if (hasControlFlow || observedWrites != 1) return ScalarInitialProof.unknown();
                AbstractInsnNode value = previousReal(directWrite, null);
                AbstractInsnNode receiver = previousReal(value, null);
                if (!(receiver instanceof VarInsnNode self) || self.getOpcode() != Opcodes.ALOAD
                        || self.var != 0) return ScalarInitialProof.unknown();
                StateLiteral literal = stateLiteralThroughLocal(Type.getType(field.desc), value,
                        null, 0);
                if (literal == null) return ScalarInitialProof.unknown();
                if (literal.kind() == StateWriteKind.ASSIGN_BOOLEAN && literal.booleanValue() != null) {
                    return ScalarInitialProof.exact(literal.booleanValue());
                }
                if (literal.kind() == StateWriteKind.ASSIGN_INT && literal.intValue() != null) {
                    return ScalarInitialProof.exact(literal.intValue());
                }
                return ScalarInitialProof.unknown();
            }

            // A same-object helper wrote the field even though this constructor did not do so
            // directly. Do not infer a default or try to execute arbitrary initialization helpers.
            if (observedWrites != 0) return ScalarInitialProof.unknown();

            MethodInsnNode delegate = null;
            for (AbstractInsnNode instruction : constructor.instructions) {
                if (instruction instanceof MethodInsnNode call
                        && call.getOpcode() == Opcodes.INVOKESPECIAL
                        && "<init>".equals(call.name) && owner.name.equals(call.owner)) {
                    if (delegate != null) return ScalarInitialProof.unknown();
                    delegate = call;
                }
            }
            if (delegate != null) {
                MethodNode targetConstructor = findMethod(owner, "<init>", delegate.desc);
                if (targetConstructor == null) return ScalarInitialProof.unknown();
                return constructorScalarInitial(owner, field, targetConstructor, nodes, visiting, depth + 1);
            }
            // No write and no this(...) delegation leaves the JVM zero value intact.
            return ScalarInitialProof.exact("Z".equals(field.desc) ? Boolean.FALSE : Integer.valueOf(0));
        } finally {
            visiting.remove(id);
        }
    }

    /** Constant PUTFIELD receiver such as `this.cancelButton.active = false`. The receiver is
     * immediately below the constant value on the JVM stack, so the corresponding GETFIELD is
     * adjacent in normal javac/Kotlin bytecode. Dynamic receivers remain unknown. */
    private static StateReceiver constantStateReceiver(FieldInsnNode write, AbstractInsnNode value,
                                                       AbstractInsnNode floorInclusive) {
        if (write == null || write.getOpcode() != Opcodes.PUTFIELD || value == null) return null;
        AbstractInsnNode receiver = previousReal(value, floorInclusive);
        if (receiver instanceof FieldInsnNode field
                && (field.getOpcode() == Opcodes.GETFIELD || field.getOpcode() == Opcodes.GETSTATIC)) {
            return new StateReceiver(field.owner, field.name);
        }
        return null;
    }

    /**
     * Recovers only scalar writes whose operand stack proves the exact operation. A raw PUTFIELD is
     * still retained with a null kind so audits can report that client state exists without
     * pretending runtime knows how to reproduce a dynamic object/text/selection value.
     */
    static StateMutation stateMutation(FieldInsnNode field, AbstractInsnNode floorInclusive) {
        String target = field.owner + "." + field.name + ":" + field.desc;
        AbstractInsnNode value = previousReal(field, floorInclusive);
        Type fieldType;
        try {
            fieldType = Type.getType(field.desc);
        } catch (IllegalArgumentException ignored) {
            return new StateMutation(target, null, null, null, null, null);
        }
        StateLiteral literal = stateLiteralThroughLocal(fieldType, value, floorInclusive, 0);
        if (literal != null && stateLiteralFitsField(literal, field.desc)) {
            StateReceiver receiver = constantStateReceiver(field, value, floorInclusive);
            return literal.mutation(target, receiver);
        }
        if ("Z".equals(field.desc)) {
            if (value != null && value.getOpcode() == Opcodes.IXOR) {
                AbstractInsnNode one = previousReal(value, floorInclusive);
                AbstractInsnNode source = previousReal(one, floorInclusive);
                if (Integer.valueOf(1).equals(integerConstant(one)) && sameFieldRead(source, field)) {
                    return new StateMutation(target, StateWriteKind.TOGGLE_BOOLEAN, null, null, null, null);
                }
            }
            return new StateMutation(target, null, null, null, null, null);
        }
        if ("I".equals(field.desc)) {
            if (value != null && value.getOpcode() == Opcodes.IREM) {
                AbstractInsnNode modulusNode = previousReal(value, floorInclusive);
                Integer modulus = integerConstant(modulusNode);
                AbstractInsnNode operation = previousReal(modulusNode, floorInclusive);
                if (modulus != null && modulus > 0 && operation != null
                        && (operation.getOpcode() == Opcodes.IADD
                        || operation.getOpcode() == Opcodes.ISUB)) {
                    AbstractInsnNode amountNode = previousReal(operation, floorInclusive);
                    Integer amount = integerConstant(amountNode);
                    AbstractInsnNode source = previousReal(amountNode, floorInclusive);
                    if (amount != null && sameFieldRead(source, field)) {
                        int delta = operation.getOpcode() == Opcodes.ISUB ? -amount : amount;
                        return new StateMutation(target, StateWriteKind.WRAP_ADD_INT,
                                null, delta, modulus, null, null);
                    }
                }
            }
            if (value != null && (value.getOpcode() == Opcodes.IADD || value.getOpcode() == Opcodes.ISUB)) {
                AbstractInsnNode amountNode = previousReal(value, floorInclusive);
                Integer amount = integerConstant(amountNode);
                AbstractInsnNode source = previousReal(amountNode, floorInclusive);
                if (amount != null && sameFieldRead(source, field)) {
                    int delta = value.getOpcode() == Opcodes.ISUB ? -amount : amount;
                    return new StateMutation(target, StateWriteKind.ADD_INT, null, delta, null, null);
                }
            }
            return new StateMutation(target, null, null, null, null, null);
        }
        return new StateMutation(target, null, null, null, null, null);
    }

    /** Nodes-aware extension of {@link #stateMutation(FieldInsnNode, AbstractInsnNode)}. The base
     * decoder intentionally remains usable by focused tests and callers without a class graph;
     * production scanning can additionally reduce an exact static singleton/enum getter to the
     * constructor literal that initialized its backing field. */
    private static StateMutation stateMutation(FieldInsnNode field, AbstractInsnNode floorInclusive,
                                               Map<String, ClassNode> nodes) {
        StateMutation direct = stateMutation(field, floorInclusive);
        if (direct != null && direct.kind() != null || field == null || nodes == null) return direct;
        Type fieldType;
        try {
            fieldType = Type.getType(field.desc);
        } catch (IllegalArgumentException ignored) {
            return direct;
        }
        AbstractInsnNode value = previousReal(field, floorInclusive);
        StateLiteral literal = staticSingletonGetterLiteral(fieldType, value, nodes, floorInclusive);
        if (literal == null || !stateLiteralFitsField(literal, field.desc)) return direct;
        return literal.mutation(field.owner + "." + field.name + ':' + field.desc, null);
    }

    /**
     * Resolves a scalar getter invoked on an exact static singleton/enum instance when bytecode
     * proves both halves of the value:
     * <ol>
     *   <li>the getter is exactly {@code aload_0; getfield X; xreturn};</li>
     *   <li>that backing field is copied from one constructor parameter; and</li>
     *   <li>the exact static instance's constructor call supplies a literal for that parameter.</li>
     * </ol>
     * This is name-independent and does not execute the client class or assume enum ordinal=value.
     */
    private static StateLiteral staticSingletonGetterLiteral(Type expected,
                                                             AbstractInsnNode instruction,
                                                             Map<String, ClassNode> nodes,
                                                             AbstractInsnNode floorInclusive) {
        if (expected == null || !(instruction instanceof MethodInsnNode getter) || nodes == null) return null;
        Type[] getterArgs;
        Type returned;
        try {
            getterArgs = Type.getArgumentTypes(getter.desc);
            returned = Type.getReturnType(getter.desc);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
        if (getterArgs.length != 0 || !expected.equals(returned) || getter.getOpcode() == Opcodes.INVOKESTATIC) {
            return null;
        }
        AbstractInsnNode receiver = previousReal(getter, floorInclusive);
        if (!(receiver instanceof FieldInsnNode singleton) || singleton.getOpcode() != Opcodes.GETSTATIC) return null;
        String singletonType = objectInternalName(singleton.desc);
        if (singletonType == null || !classHierarchyNames(nodes.get(singletonType), nodes).contains(getter.owner)) {
            return null;
        }

        ResolvedMethod resolvedGetter = resolveMethodInHierarchy(nodes.get(getter.owner),
                getter.name, getter.desc, nodes);
        if (resolvedGetter == null || resolvedGetter.method() == null) return null;
        FieldInsnNode backing = simpleSelfGetterField(resolvedGetter.method(), expected.getDescriptor());
        if (backing == null) return null;

        ClassNode singletonClass = nodes.get(singletonType);
        if (singletonClass == null || singletonClass.methods == null) return null;
        MethodNode clinit = findMethod(singletonClass, "<clinit>", "()V");
        if (clinit == null) return null;
        for (AbstractInsnNode instructionInClinit : clinit.instructions) {
            if (!(instructionInClinit instanceof FieldInsnNode store)
                    || store.getOpcode() != Opcodes.PUTSTATIC
                    || !singleton.owner.equals(store.owner) || !singleton.name.equals(store.name)
                    || !singleton.desc.equals(store.desc)) continue;
            MethodInsnNode constructorCall = nearestSingletonConstructorBefore(store, singletonType, 48);
            if (constructorCall == null) return null;
            MethodNode constructor = findMethod(singletonClass, "<init>", constructorCall.desc);
            if (constructor == null) return null;
            Integer parameter = constructorParameterForField(constructor, backing);
            if (parameter == null) return null;
            Type[] constructorArgs;
            try {
                constructorArgs = Type.getArgumentTypes(constructorCall.desc);
            } catch (IllegalArgumentException ignored) {
                return null;
            }
            if (parameter < 0 || parameter >= constructorArgs.length
                    || !constructorArgs[parameter].equals(expected)) return null;
            AbstractInsnNode cursor = constructorCall;
            for (int index = constructorArgs.length - 1; index >= parameter; index--) {
                cursor = previousReal(cursor, null);
                if (cursor == null) return null;
                StateLiteral literal = stateLiteralThroughLocal(constructorArgs[index], cursor, null, 0);
                if (literal == null) return null;
                if (index == parameter) return literal;
                cursor = stateLiteralExpressionStart(constructorArgs[index], cursor, null);
            }
            return null;
        }
        return null;
    }

    private static FieldInsnNode simpleSelfGetterField(MethodNode method, String descriptor) {
        if (method == null || method.instructions == null || descriptor == null) return null;
        ArrayList<AbstractInsnNode> real = new ArrayList<>();
        for (AbstractInsnNode instruction : method.instructions) if (instruction.getOpcode() >= 0) real.add(instruction);
        if (real.size() != 3 || !(real.get(0) instanceof VarInsnNode self)
                || self.getOpcode() != Opcodes.ALOAD || self.var != 0
                || !(real.get(1) instanceof FieldInsnNode field) || field.getOpcode() != Opcodes.GETFIELD
                || !descriptor.equals(field.desc)) return null;
        int expectedReturn = switch (Type.getType(descriptor).getSort()) {
            case Type.BOOLEAN, Type.BYTE, Type.CHAR, Type.SHORT, Type.INT -> Opcodes.IRETURN;
            case Type.LONG -> Opcodes.LRETURN;
            case Type.FLOAT -> Opcodes.FRETURN;
            case Type.DOUBLE -> Opcodes.DRETURN;
            case Type.OBJECT, Type.ARRAY -> Opcodes.ARETURN;
            default -> -1;
        };
        return real.get(2).getOpcode() == expectedReturn ? field : null;
    }

    private record PayloadMirrorSource(String sourceOwner, String sourceFieldName,
                                       String sourceFieldDesc, String converterOwner,
                                       String converterMethod, String converterDesc) {}

    /**
     * Finds a unique zero-argument object converter that copies one source backing field into the
     * exact target backing field consumed by a packet argument. This lets runtime mirror an
     * already-decoded clientbound DTO scalar without constructing the client-side target object.
     *
     * <p>The converter must allocate exactly one target object, every return must resolve to that
     * allocation under ASM verifier provenance, and the target setter must itself be a trivial
     * one-field setter. The copied value must be a direct {@code this.sourceField} read. Multiple
     * disagreeing converters reject the relation.</p>
     */
    private static PayloadMirrorSource payloadMirrorSource(String targetOwner,
                                                           FieldInsnNode targetField,
                                                           Map<String, ClassNode> nodes) {
        if (targetOwner == null || targetField == null || nodes == null || nodes.isEmpty()) return null;
        PayloadMirrorSource agreed = null;
        for (ClassNode source : nodes.values()) {
            if (source == null || source.name == null || source.methods == null
                    || source.name.startsWith("net/minecraft/client/")
                    || source.name.contains("/client/")) continue;
            for (MethodNode converter : source.methods) {
                if (converter == null || converter.instructions == null
                        || (converter.access & Opcodes.ACC_STATIC) != 0) continue;
                Type[] args;
                Type returned;
                try {
                    args = Type.getArgumentTypes(converter.desc);
                    returned = Type.getReturnType(converter.desc);
                } catch (IllegalArgumentException ignored) {
                    continue;
                }
                if (args.length != 0 || returned.getSort() != Type.OBJECT
                        || !targetOwner.equals(returned.getInternalName())) continue;

                TypeInsnNode allocation = null;
                int allocations = 0;
                for (AbstractInsnNode instruction : converter.instructions) {
                    if (instruction instanceof TypeInsnNode type && type.getOpcode() == Opcodes.NEW
                            && targetOwner.equals(type.desc)) {
                        allocation = type;
                        allocations++;
                    }
                }
                if (allocations != 1 || allocation == null) continue;
                BytecodeValueFlow flow = new BytecodeValueFlow(nodes);
                boolean returnedAllocation = false;
                boolean badReturn = false;
                for (AbstractInsnNode instruction : converter.instructions) {
                    if (instruction.getOpcode() != Opcodes.ARETURN) continue;
                    Set<AbstractInsnNode> sources = flow.returnSources(source, converter, instruction);
                    if (sources.size() != 1 || !sources.contains(allocation)) {
                        badReturn = true;
                        break;
                    }
                    returnedAllocation = true;
                }
                if (badReturn || !returnedAllocation) continue;

                PayloadMirrorSource candidate = null;
                boolean ambiguous = false;
                for (AbstractInsnNode instruction : converter.instructions) {
                    if (!(instruction instanceof MethodInsnNode setter)
                            || setter.getOpcode() == Opcodes.INVOKESTATIC) continue;
                    ResolvedMethod resolved = resolveMethodInHierarchy(
                            nodes.get(setter.owner), setter.name, setter.desc, nodes);
                    if (resolved == null || resolved.method() == null) continue;
                    FieldInsnNode written = simpleScalarSetterField(
                            resolved.method(), targetField.desc);
                    if (written == null || !sameBackingField(written, targetField)) continue;
                    AbstractInsnNode value = previousReal(setter, null);
                    if (!(value instanceof FieldInsnNode sourceField)
                            || sourceField.getOpcode() != Opcodes.GETFIELD
                            || !targetField.desc.equals(sourceField.desc)) continue;
                    AbstractInsnNode self = previousReal(sourceField, null);
                    if (!(self instanceof VarInsnNode load) || load.getOpcode() != Opcodes.ALOAD
                            || load.var != 0 || !source.name.equals(sourceField.owner)) continue;
                    PayloadMirrorSource next = new PayloadMirrorSource(source.name,
                            sourceField.name, sourceField.desc, source.name,
                            converter.name, converter.desc);
                    if (candidate != null && !candidate.equals(next)) {
                        ambiguous = true;
                        break;
                    }
                    candidate = next;
                }
                if (ambiguous || candidate == null) continue;
                if (agreed != null && !agreed.equals(candidate)) return null;
                agreed = candidate;
            }
        }
        return agreed;
    }

    /**
     * Preserves the caller expression for the common encapsulated toggle shape
     * {@code this.setFlag(!this.flag())}. Recursing into the setter alone sees only
     * {@code this.flag = parameter} and would otherwise lose the exact operation. The proof is
     * deliberately strict: one boolean parameter, a one-field setter, a one-field getter for the
     * same field, the same local receiver for getter/setter, and an exact javac/Kotlin boolean
     * negation control-flow shape. Anything richer remains unresolved.
     */
    static StateMutation sameObjectBooleanToggleSetterMutation(String ownerName,
                                                               MethodInsnNode setterCall,
                                                               MethodNode setter,
                                                               AbstractInsnNode floorInclusive,
                                                               Map<String, ClassNode> nodes) {
        if (ownerName == null || setterCall == null || setter == null || nodes == null
                || setterCall.getOpcode() == Opcodes.INVOKESTATIC
                || !ownerName.equals(setterCall.owner)
                || !"(Z)V".equals(setterCall.desc)) return null;
        FieldInsnNode written = simpleBooleanSetterField(setter);
        if (written == null) return null;

        AbstractInsnNode beforeCall = previousReal(setterCall, floorInclusive);
        MethodInsnNode getter = null;
        VarInsnNode getterReceiver = null;
        VarInsnNode setterReceiver = null;

        // `receiver; receiver; getter(); iconst_1; ixor; setter(...)`
        if (beforeCall != null && beforeCall.getOpcode() == Opcodes.IXOR) {
            AbstractInsnNode one = previousReal(beforeCall, floorInclusive);
            AbstractInsnNode candidate = previousReal(one, floorInclusive);
            AbstractInsnNode getterLoad = previousReal(candidate, floorInclusive);
            AbstractInsnNode setterLoad = previousReal(getterLoad, floorInclusive);
            if (!Integer.valueOf(1).equals(integerConstant(one))
                    || !(candidate instanceof MethodInsnNode method)
                    || !(getterLoad instanceof VarInsnNode gl) || gl.getOpcode() != Opcodes.ALOAD
                    || !(setterLoad instanceof VarInsnNode sl) || sl.getOpcode() != Opcodes.ALOAD
                    || gl.var != sl.var || gl.var != 0) return null;
            getter = method;
            getterReceiver = gl;
            setterReceiver = sl;
        } else {
            // javac's branch form for `!getter()`:
            // receiver; receiver; getter(); IFNE false; ICONST_1; GOTO end;
            // false: ICONST_0; end: setter(...)
            AbstractInsnNode jumpValueNode = beforeCall;
            AbstractInsnNode skip = previousReal(jumpValueNode, floorInclusive);
            AbstractInsnNode fallValueNode = previousReal(skip, floorInclusive);
            AbstractInsnNode condition = previousReal(fallValueNode, floorInclusive);
            AbstractInsnNode candidate = previousReal(condition, floorInclusive);
            AbstractInsnNode getterLoad = previousReal(candidate, floorInclusive);
            AbstractInsnNode setterLoad = previousReal(getterLoad, floorInclusive);
            Integer jumpValue = integerConstant(jumpValueNode);
            Integer fallValue = integerConstant(fallValueNode);
            if (!(skip instanceof JumpInsnNode go) || go.getOpcode() != Opcodes.GOTO
                    || !(condition instanceof JumpInsnNode branch)
                    || !(candidate instanceof MethodInsnNode method)
                    || !(getterLoad instanceof VarInsnNode gl) || gl.getOpcode() != Opcodes.ALOAD
                    || !(setterLoad instanceof VarInsnNode sl) || sl.getOpcode() != Opcodes.ALOAD
                    || gl.var != sl.var || gl.var != 0
                    || nextOpcode(go.label) != setterCall
                    || nextOpcode(branch.label) != jumpValueNode) return null;
            boolean negated = branch.getOpcode() == Opcodes.IFNE
                    && Integer.valueOf(0).equals(jumpValue) && Integer.valueOf(1).equals(fallValue)
                    || branch.getOpcode() == Opcodes.IFEQ
                    && Integer.valueOf(1).equals(jumpValue) && Integer.valueOf(0).equals(fallValue);
            if (!negated) return null;
            getter = method;
            getterReceiver = gl;
            setterReceiver = sl;
        }
        if (getterReceiver == null || setterReceiver == null
                || !"()Z".equals(getter.desc) || getter.getOpcode() == Opcodes.INVOKESTATIC) return null;
        ResolvedMethod resolvedGetter = resolveMethodInHierarchy(
                nodes.get(getter.owner), getter.name, getter.desc, nodes);
        if (resolvedGetter == null || resolvedGetter.method() == null) return null;
        FieldInsnNode read = simpleSelfGetterField(resolvedGetter.method(), "Z");
        if (read == null || !written.owner.equals(read.owner) || !written.name.equals(read.name)
                || !written.desc.equals(read.desc)) return null;
        String target = written.owner + "." + written.name + ':' + written.desc;
        return new StateMutation(target, StateWriteKind.TOGGLE_BOOLEAN,
                null, null, null, null);
    }

    /**
     * Cross-object counterpart to {@link #sameObjectBooleanToggleSetterMutation}. Client screens
     * frequently keep a small config/model object in a field and express controls as
     * {@code this.config.setFlag(!this.config.flag())} or
     * {@code this.config.setIndex(this.config.index() + 1)}. Recursing into the setter loses the
     * caller expression, while treating the whole call as an opaque helper needlessly blocks a
     * state transition F2V can represent exactly.
     *
     * <p>The proof is deliberately narrow: one boolean/int setter parameter, a four-instruction
     * self setter, a three-instruction self getter for the exact same backing field, and two
     * identical GETFIELD receiver chains rooted in the same local. Initial-value certification is
     * still handled separately; without a proven helper-field initial value the resulting relative
     * action remains unsupported.</p>
     */
    static StateMutation screenHeldRelativeSetterMutation(MethodInsnNode setterCall,
                                                          MethodNode setter,
                                                          AbstractInsnNode floorInclusive,
                                                          Map<String, ClassNode> nodes) {
        if (setterCall == null || setter == null || nodes == null
                || setterCall.getOpcode() == Opcodes.INVOKESTATIC) return null;
        Type[] args;
        try {
            args = Type.getArgumentTypes(setterCall.desc);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
        if (args.length != 1 || !(args[0].getSort() == Type.BOOLEAN || args[0].getSort() == Type.INT)) {
            return null;
        }
        String desc = args[0].getDescriptor();
        FieldInsnNode written = simpleScalarSetterField(setter, desc);
        CyclicIntSetterProof cyclic = null;
        if (written == null && args[0].getSort() == Type.INT) {
            cyclic = canonicalCyclicIntSetter(setterCall.owner, setter, nodes);
            written = cyclic == null ? null : cyclic.written();
        }
        if (written == null) return null;

        if (args[0].getSort() == Type.BOOLEAN) {
            AbstractInsnNode beforeCall = previousReal(setterCall, floorInclusive);
            MethodInsnNode getter;
            StateReceiver receiver;
            StateReceiver pairedReceiver;
            if (beforeCall != null && beforeCall.getOpcode() == Opcodes.IXOR) {
                AbstractInsnNode one = previousReal(beforeCall, floorInclusive);
                AbstractInsnNode getterNode = previousReal(one, floorInclusive);
                if (!Integer.valueOf(1).equals(integerConstant(one))
                        || !(getterNode instanceof MethodInsnNode candidate)) return null;
                getter = candidate;
                receiver = pairedScreenFieldReceiver(getter, floorInclusive);
                pairedReceiver = receiver;
            } else {
                // javac branch form for !getter(), identical to the same-object proof except the
                // duplicated receiver is `screen.config` rather than local `this`.
                AbstractInsnNode jumpValueNode = beforeCall;
                AbstractInsnNode skip = previousReal(jumpValueNode, floorInclusive);
                AbstractInsnNode fallValueNode = previousReal(skip, floorInclusive);
                AbstractInsnNode condition = previousReal(fallValueNode, floorInclusive);
                AbstractInsnNode getterNode = previousReal(condition, floorInclusive);
                Integer jumpValue = integerConstant(jumpValueNode);
                Integer fallValue = integerConstant(fallValueNode);
                if (!(skip instanceof JumpInsnNode go) || go.getOpcode() != Opcodes.GOTO
                        || !(condition instanceof JumpInsnNode branch)
                        || !(getterNode instanceof MethodInsnNode candidate)
                        || nextOpcode(go.label) != setterCall
                        || nextOpcode(branch.label) != jumpValueNode) return null;
                boolean negated = branch.getOpcode() == Opcodes.IFNE
                        && Integer.valueOf(0).equals(jumpValue) && Integer.valueOf(1).equals(fallValue)
                        || branch.getOpcode() == Opcodes.IFEQ
                        && Integer.valueOf(1).equals(jumpValue) && Integer.valueOf(0).equals(fallValue);
                if (!negated) return null;
                getter = candidate;
                receiver = pairedScreenFieldReceiver(getter, floorInclusive);
                pairedReceiver = receiver;
            }
            if (receiver == null) receiver = screenFieldReceiverForCall(getter, floorInclusive, nodes);
            if (receiver == null || getter.getOpcode() == Opcodes.INVOKESTATIC
                    || !"()Z".equals(getter.desc)) return null;
            StateReceiver setterReceiver = screenFieldReceiverForCall(setterCall, floorInclusive, nodes);
            if (setterReceiver == null) setterReceiver = pairedReceiver;
            if (setterReceiver == null || !setterReceiver.equals(receiver)) return null;
            ResolvedMethod resolvedGetter = resolveMethodInHierarchy(
                    nodes.get(getter.owner), getter.name, getter.desc, nodes);
            if (resolvedGetter == null || resolvedGetter.method() == null) return null;
            FieldInsnNode read = simpleSelfGetterField(resolvedGetter.method(), "Z");
            if (!sameBackingField(written, read)) return null;
            return new StateMutation(written.owner + "." + written.name + ':' + written.desc,
                    StateWriteKind.TOGGLE_BOOLEAN, null, null, receiver.owner(), receiver.name());
        }

        AbstractInsnNode operation = previousReal(setterCall, floorInclusive);
        Integer modulus = null;
        DynamicIntModulus dynamicModulus = null;
        if (cyclic != null) {
            dynamicModulus = new DynamicIntModulus(cyclic.collectionFieldTarget(), null, setterCall);
        } else if (operation != null && operation.getOpcode() == Opcodes.IREM) {
            AbstractInsnNode modulusNode = previousReal(operation, floorInclusive);
            modulus = integerConstant(modulusNode);
            AbstractInsnNode modulusStart = modulusNode;
            if (modulus == null) {
                ClassNode callerClass = null;
                MethodNode callerMethod = null;
                for (ClassNode candidate : nodes.values()) {
                    MethodNode containing = methodContainingInstruction(candidate, setterCall);
                    if (containing == null) continue;
                    if (callerClass != null) return null;
                    callerClass = candidate;
                    callerMethod = containing;
                }
                if (callerClass == null || callerMethod == null) return null;
                dynamicModulus = dynamicIntModulus(modulusNode, floorInclusive,
                        callerClass, callerMethod, new BytecodeValueFlow(nodes), nodes);
                if (dynamicModulus == null) return null;
                modulusStart = dynamicModulus.expressionStart();
            } else if (modulus <= 0) {
                return null;
            }
            operation = previousReal(modulusStart, floorInclusive);
        }
        if (operation == null || (operation.getOpcode() != Opcodes.IADD
                && operation.getOpcode() != Opcodes.ISUB)) return null;
        AbstractInsnNode amountNode = previousReal(operation, floorInclusive);
        StateLiteral amountLiteral = stateLiteralThroughLocal(Type.INT_TYPE, amountNode,
                floorInclusive, 0);
        if (amountLiteral == null || amountLiteral.kind() != StateWriteKind.ASSIGN_INT
                || amountLiteral.intValue() == null) return null;
        AbstractInsnNode amountStart = stateLiteralExpressionStart(Type.INT_TYPE, amountNode, floorInclusive);
        AbstractInsnNode getterNode = previousReal(amountStart, floorInclusive);
        if (!(getterNode instanceof MethodInsnNode getter) || getter.getOpcode() == Opcodes.INVOKESTATIC
                || !"()I".equals(getter.desc)) return null;
        StateReceiver pairedReceiver = pairedScreenFieldReceiver(getter, floorInclusive);
        StateReceiver receiver = pairedReceiver;
        if (receiver == null) receiver = screenFieldReceiverForCall(getter, floorInclusive, nodes);
        StateReceiver setterReceiver = screenFieldReceiverForCall(setterCall, floorInclusive, nodes);
        if (setterReceiver == null) setterReceiver = pairedReceiver;
        if (receiver == null || setterReceiver == null || !receiver.equals(setterReceiver)) return null;
        ResolvedMethod resolvedGetter = resolveMethodInHierarchy(
                nodes.get(getter.owner), getter.name, getter.desc, nodes);
        if (resolvedGetter == null || resolvedGetter.method() == null) return null;
        FieldInsnNode read = simpleSelfGetterField(resolvedGetter.method(), "I");
        if (!sameBackingField(written, read)) return null;
        int delta = operation.getOpcode() == Opcodes.ISUB
                ? -amountLiteral.intValue() : amountLiteral.intValue();
        return new StateMutation(written.owner + "." + written.name + ':' + written.desc,
                modulus == null && dynamicModulus == null
                        ? StateWriteKind.ADD_INT : StateWriteKind.WRAP_ADD_INT,
                null, delta, modulus,
                dynamicModulus == null ? null : dynamicModulus.target(),
                dynamicModulus == null ? null : dynamicModulus.minimum(),
                receiver.owner(), receiver.name());
    }

    private record CyclicIntSetterProof(FieldInsnNode written, String collectionFieldTarget) {}

    /**
     * Exact branch form of a cyclic integer setter used by many paged widgets:
     * <pre>
     * if (value > 0 && value < values.size()) value
     * else if (value < 0) values.size() - 1
     * else 0
     * </pre>
     * The caller still has to prove it supplied current +/- a literal. This method proves only the
     * setter's normalization and the exact backing collection whose cardinality is the modulus.
     */
    private static CyclicIntSetterProof canonicalCyclicIntSetter(
            String ownerName, MethodNode setter, Map<String, ClassNode> nodes) {
        if (ownerName == null || setter == null || nodes == null || !"(I)V".equals(setter.desc)
                || setter.instructions == null) return null;
        AbstractInsnNode a0 = nextOpcode(setter.instructions.getFirst());
        AbstractInsnNode a1 = nextOpcode(a0 == null ? null : a0.getNext());
        AbstractInsnNode a2 = nextOpcode(a1 == null ? null : a1.getNext());
        AbstractInsnNode a3 = nextOpcode(a2 == null ? null : a2.getNext());
        AbstractInsnNode a4 = nextOpcode(a3 == null ? null : a3.getNext());
        AbstractInsnNode a5 = nextOpcode(a4 == null ? null : a4.getNext());
        AbstractInsnNode a6 = nextOpcode(a5 == null ? null : a5.getNext());
        AbstractInsnNode a7 = nextOpcode(a6 == null ? null : a6.getNext());
        AbstractInsnNode a8 = nextOpcode(a7 == null ? null : a7.getNext());
        AbstractInsnNode a9 = nextOpcode(a8 == null ? null : a8.getNext());
        AbstractInsnNode a10 = nextOpcode(a9 == null ? null : a9.getNext());
        if (!(a0 instanceof VarInsnNode self) || self.getOpcode() != Opcodes.ALOAD || self.var != 0
                || !(a1 instanceof VarInsnNode firstValue) || firstValue.getOpcode() != Opcodes.ILOAD
                || firstValue.var != 1 || !(a2 instanceof JumpInsnNode positive)
                || positive.getOpcode() != Opcodes.IFLE
                || !(a3 instanceof VarInsnNode compareValue) || compareValue.getOpcode() != Opcodes.ILOAD
                || compareValue.var != 1 || !(a4 instanceof VarInsnNode boundSelf)
                || boundSelf.getOpcode() != Opcodes.ALOAD || boundSelf.var != 0
                || !(a5 instanceof FieldInsnNode holder) || holder.getOpcode() != Opcodes.GETFIELD
                || !(a6 instanceof MethodInsnNode getter) || getter.getOpcode() == Opcodes.INVOKESTATIC
                || !(a7 instanceof MethodInsnNode size) || !collectionSizeCall(size)
                || !(a8 instanceof JumpInsnNode tooLarge) || tooLarge.getOpcode() != Opcodes.IF_ICMPGE
                || !(a9 instanceof VarInsnNode validValue) || validValue.getOpcode() != Opcodes.ILOAD
                || validValue.var != 1 || !(a10 instanceof JumpInsnNode validJump)
                || validJump.getOpcode() != Opcodes.GOTO || tooLarge.label != positive.label) return null;

        AbstractInsnNode b0 = nextOpcode(positive.label);
        AbstractInsnNode b1 = nextOpcode(b0 == null ? null : b0.getNext());
        AbstractInsnNode b2 = nextOpcode(b1 == null ? null : b1.getNext());
        AbstractInsnNode b3 = nextOpcode(b2 == null ? null : b2.getNext());
        AbstractInsnNode b4 = nextOpcode(b3 == null ? null : b3.getNext());
        AbstractInsnNode b5 = nextOpcode(b4 == null ? null : b4.getNext());
        AbstractInsnNode b6 = nextOpcode(b5 == null ? null : b5.getNext());
        AbstractInsnNode b7 = nextOpcode(b6 == null ? null : b6.getNext());
        AbstractInsnNode b8 = nextOpcode(b7 == null ? null : b7.getNext());
        if (!(b0 instanceof VarInsnNode fallbackValue) || fallbackValue.getOpcode() != Opcodes.ILOAD
                || fallbackValue.var != 1 || !(b1 instanceof JumpInsnNode nonNegative)
                || nonNegative.getOpcode() != Opcodes.IFGE
                || !(b2 instanceof VarInsnNode fallbackSelf) || fallbackSelf.getOpcode() != Opcodes.ALOAD
                || fallbackSelf.var != 0 || !(b3 instanceof FieldInsnNode fallbackHolder)
                || fallbackHolder.getOpcode() != Opcodes.GETFIELD
                || !(b4 instanceof MethodInsnNode fallbackGetter)
                || fallbackGetter.getOpcode() == Opcodes.INVOKESTATIC
                || !(b5 instanceof MethodInsnNode fallbackSize) || !collectionSizeCall(fallbackSize)
                || !Integer.valueOf(1).equals(integerConstant(b6)) || b7 == null
                || b7.getOpcode() != Opcodes.ISUB || !(b8 instanceof JumpInsnNode negativeJump)
                || negativeJump.getOpcode() != Opcodes.GOTO) return null;
        AbstractInsnNode zero = nextOpcode(nonNegative.label);
        if (!Integer.valueOf(0).equals(integerConstant(zero))) return null;
        AbstractInsnNode writeNode = nextOpcode(validJump.label);
        if (writeNode != nextOpcode(negativeJump.label)
                || writeNode != nextOpcode(zero.getNext())
                || !(writeNode instanceof FieldInsnNode written) || written.getOpcode() != Opcodes.PUTFIELD
                || !"I".equals(written.desc)) return null;

        if (!sameBackingField(holder, fallbackHolder)
                || !getter.owner.equals(fallbackGetter.owner) || !getter.name.equals(fallbackGetter.name)
                || !getter.desc.equals(fallbackGetter.desc)
                || !size.owner.equals(fallbackSize.owner) || !size.name.equals(fallbackSize.name)
                || !size.desc.equals(fallbackSize.desc)) return null;
        String collection = collectionBackingFieldTarget(holder, getter, nodes);
        if (collection == null || !cyclicSetterSuffixHasNoImmediateExternalEffect(
                ownerName, writeNode, nodes)) return null;
        return new CyclicIntSetterProof(written, collection);
    }

    private static boolean collectionSizeCall(MethodInsnNode call) {
        return call != null && "size".equals(call.name) && "()I".equals(call.desc)
                && ("java/util/Collection".equals(call.owner) || "java/util/List".equals(call.owner)
                || "java/util/Set".equals(call.owner) || "java/util/Map".equals(call.owner));
    }

    private static String collectionBackingFieldTarget(FieldInsnNode holder, MethodInsnNode getter,
                                                       Map<String, ClassNode> nodes) {
        if (holder == null || getter == null || nodes == null || holder.getOpcode() != Opcodes.GETFIELD) {
            return null;
        }
        String holderType = objectInternalName(holder.desc);
        if (holderType == null || !classHierarchyNames(nodes.get(holderType), nodes).contains(getter.owner)) {
            return null;
        }
        Type[] args;
        Type returned;
        try {
            args = Type.getArgumentTypes(getter.desc);
            returned = Type.getReturnType(getter.desc);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
        if (args.length != 0 || returned.getSort() != Type.OBJECT) return null;
        ResolvedMethod resolved = resolveMethodInHierarchy(nodes.get(getter.owner),
                getter.name, getter.desc, nodes);
        if (resolved == null || resolved.method() == null) return null;
        FieldInsnNode backing = simpleSelfGetterField(resolved.method(), returned.getDescriptor());
        return backing == null ? null : backing.owner + "." + backing.name + ':' + backing.desc;
    }

    /** Client-local rebuilds may be omitted only when the setter and its one optional same-object
     * suffix helper contain no immediate packet, navigation, close, or external-link effect. Other
     * local fields remain unavailable to future packet replay unless independently translated. */
    private static boolean cyclicSetterSuffixHasNoImmediateExternalEffect(
            String ownerName, AbstractInsnNode write, Map<String, ClassNode> nodes) {
        AbstractInsnNode next = nextOpcode(write == null ? null : write.getNext());
        if (next != null && next.getOpcode() == Opcodes.RETURN) return true;
        if (!(next instanceof VarInsnNode self) || self.getOpcode() != Opcodes.ALOAD || self.var != 0) {
            return false;
        }
        AbstractInsnNode callNode = nextOpcode(next.getNext());
        AbstractInsnNode end = nextOpcode(callNode == null ? null : callNode.getNext());
        if (!(callNode instanceof MethodInsnNode call) || !ownerName.equals(call.owner)
                || !"()V".equals(call.desc) || end == null || end.getOpcode() != Opcodes.RETURN
                || nextOpcode(end.getNext()) != null) return false;
        ResolvedMethod resolved = resolveMethodInHierarchy(nodes.get(call.owner), call.name, call.desc, nodes);
        if (resolved == null || resolved.method() == null) return false;
        String id = resolved.owner() + '#' + methodKey(resolved.method());
        HashSet<String> stack = new HashSet<>();
        stack.add(id);
        MethodFacts facts = collectBehaviorRange(resolved.owner(), resolved.method().instructions.getFirst(),
                null, nodes, stack, 0);
        return facts.packets.isEmpty() && facts.screens.isEmpty() && !facts.closesScreen
                && facts.externalUrls.isEmpty() && facts.runtimeLaunches.isEmpty();
    }

    /** Returns the screen-held receiver only for `base.field; base.field; getter()` with the same
     * local base and exact field reference twice. The first copy remains on the operand stack for
     * the setter while the second is consumed by the getter. */
    private static StateReceiver pairedScreenFieldReceiver(MethodInsnNode getter,
                                                           AbstractInsnNode floorInclusive) {
        if (getter == null) return null;
        AbstractInsnNode getterFieldNode = previousReal(getter, floorInclusive);
        AbstractInsnNode getterBaseNode = previousReal(getterFieldNode, floorInclusive);
        AbstractInsnNode setterFieldNode = previousReal(getterBaseNode, floorInclusive);
        AbstractInsnNode setterBaseNode = previousReal(setterFieldNode, floorInclusive);
        if (!(getterFieldNode instanceof FieldInsnNode getterField)
                || getterField.getOpcode() != Opcodes.GETFIELD
                || !(setterFieldNode instanceof FieldInsnNode setterField)
                || setterField.getOpcode() != Opcodes.GETFIELD
                || !getterField.owner.equals(setterField.owner)
                || !getterField.name.equals(setterField.name)
                || !getterField.desc.equals(setterField.desc)
                || !(getterBaseNode instanceof VarInsnNode getterBase)
                || getterBase.getOpcode() != Opcodes.ALOAD
                || !(setterBaseNode instanceof VarInsnNode setterBase)
                || setterBase.getOpcode() != Opcodes.ALOAD
                || getterBase.var != setterBase.var) return null;
        return new StateReceiver(getterField.owner, getterField.name);
    }

    /** Data-flow fallback for compiler locals/null guards around a screen-held helper receiver.
     * Null-only producers are ignored because that path cannot successfully invoke the helper;
     * every remaining producer must be the same exact GETFIELD rooted in local 0 of the concrete
     * source Screen. */
    private static StateReceiver screenFieldReceiverForCall(MethodInsnNode call,
                                                            AbstractInsnNode floorInclusive,
                                                            Map<String, ClassNode> nodes) {
        if (call == null || call.getOpcode() == Opcodes.INVOKESTATIC || nodes == null) return null;
        ClassNode callerClass = null;
        MethodNode callerMethod = null;
        for (ClassNode candidate : nodes.values()) {
            MethodNode containing = methodContainingInstruction(candidate, call);
            if (containing == null) continue;
            if (callerClass != null) return null;
            callerClass = candidate;
            callerMethod = containing;
        }
        if (callerClass == null || callerMethod == null) return null;
        BytecodeValueFlow flow = new BytecodeValueFlow(nodes);
        LinkedHashSet<FieldInsnNode> fields = new LinkedHashSet<>();
        for (AbstractInsnNode producer : flow.receiverSources(callerClass, callerMethod, call)) {
            if (producer != null && producer.getOpcode() == Opcodes.ACONST_NULL) continue;
            if (!(producer instanceof FieldInsnNode field) || field.getOpcode() != Opcodes.GETFIELD
                    || flow.fieldReceiverParameterLocal(callerClass, callerMethod, field) == null
                    || flow.fieldReceiverParameterLocal(callerClass, callerMethod, field) != 0
                    || !screenHierarchy(callerClass, nodes).contains(field.owner)) return null;
            fields.add(field);
        }
        if (fields.isEmpty()) return null;
        FieldInsnNode first = fields.iterator().next();
        for (FieldInsnNode field : fields) {
            if (!first.owner.equals(field.owner) || !first.name.equals(field.name)
                    || !first.desc.equals(field.desc)) return null;
        }
        return new StateReceiver(first.owner, first.name);
    }

    private static boolean sameBackingField(FieldInsnNode left, FieldInsnNode right) {
        return left != null && right != null && left.owner.equals(right.owner)
                && left.name.equals(right.name) && left.desc.equals(right.desc);
    }

    private static FieldInsnNode simpleScalarSetterField(MethodNode method, String descriptor) {
        if (method == null || descriptor == null || method.instructions == null) return null;
        Type[] args;
        try {
            args = Type.getArgumentTypes(method.desc);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
        if (args.length != 1 || !descriptor.equals(args[0].getDescriptor())
                || Type.getReturnType(method.desc).getSort() != Type.VOID) return null;
        ArrayList<AbstractInsnNode> real = new ArrayList<>();
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction.getOpcode() >= 0) real.add(instruction);
        }
        if (real.size() != 4 || !(real.get(0) instanceof VarInsnNode self)
                || self.getOpcode() != Opcodes.ALOAD || self.var != 0
                || !(real.get(1) instanceof VarInsnNode value)
                || value.getOpcode() != loadOpcode(args[0]) || value.var != 1
                || !(real.get(2) instanceof FieldInsnNode field)
                || field.getOpcode() != Opcodes.PUTFIELD || !descriptor.equals(field.desc)
                || real.get(3).getOpcode() != Opcodes.RETURN) return null;
        return field;
    }

    private static FieldInsnNode simpleBooleanSetterField(MethodNode method) {
        if (method == null || !"(Z)V".equals(method.desc) || method.instructions == null) return null;
        ArrayList<AbstractInsnNode> real = new ArrayList<>();
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction.getOpcode() >= 0) real.add(instruction);
        }
        if (real.size() != 4 || !(real.get(0) instanceof VarInsnNode self)
                || self.getOpcode() != Opcodes.ALOAD || self.var != 0
                || !(real.get(1) instanceof VarInsnNode value)
                || value.getOpcode() != Opcodes.ILOAD || value.var != 1
                || !(real.get(2) instanceof FieldInsnNode field)
                || field.getOpcode() != Opcodes.PUTFIELD || !"Z".equals(field.desc)
                || real.get(3).getOpcode() != Opcodes.RETURN) return null;
        return field;
    }

    private static MethodInsnNode nearestSingletonConstructorBefore(AbstractInsnNode store,
                                                                    String singletonType, int limit) {
        for (AbstractInsnNode cursor = store == null ? null : store.getPrevious();
             cursor != null && limit-- > 0; cursor = cursor.getPrevious()) {
            if (cursor instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESPECIAL
                    && "<init>".equals(call.name) && singletonType.equals(call.owner)) return call;
            if (cursor instanceof FieldInsnNode field && field.getOpcode() == Opcodes.PUTSTATIC) break;
        }
        return null;
    }

    /** Zero-based constructor parameter copied directly to one exact backing field. */
    private static Integer constructorParameterForField(MethodNode constructor, FieldInsnNode backing) {
        if (constructor == null || backing == null) return null;
        Type[] args;
        try {
            args = Type.getArgumentTypes(constructor.desc);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
        Map<Integer, Integer> parameterByLocal = new HashMap<>();
        int local = 1;
        for (int index = 0; index < args.length; index++) {
            parameterByLocal.put(local, index);
            local += args[index].getSize();
        }
        Integer result = null;
        for (AbstractInsnNode instruction : constructor.instructions) {
            if (!(instruction instanceof FieldInsnNode field) || field.getOpcode() != Opcodes.PUTFIELD
                    || !backing.owner.equals(field.owner) || !backing.name.equals(field.name)
                    || !backing.desc.equals(field.desc)) continue;
            AbstractInsnNode value = previousReal(field, null);
            AbstractInsnNode receiver = previousReal(value, null);
            if (!(value instanceof VarInsnNode load) || !(receiver instanceof VarInsnNode self)
                    || self.getOpcode() != Opcodes.ALOAD || self.var != 0) return null;
            Integer parameter = parameterByLocal.get(load.var);
            if (parameter == null || load.getOpcode() != loadOpcode(args[parameter])) return null;
            if (result != null && !result.equals(parameter)) return null;
            result = parameter;
        }
        return result;
    }

    /** Resolve an exact literal through a straight-line local alias. This deliberately stops at
     * control-flow boundaries: if a local can have more than one reaching definition, the value
     * remains unresolved rather than guessing which branch executed. */
    private static StateLiteral stateLiteralThroughLocal(Type expected, AbstractInsnNode instruction,
                                                         AbstractInsnNode floorInclusive, int depth) {
        if (expected == null || instruction == null || depth > 4) return null;
        StateLiteral direct = stateLiteral(expected, instruction);
        if (direct != null) return direct;
        if (!(instruction instanceof VarInsnNode load) || load.getOpcode() != loadOpcode(expected)) {
            return null;
        }
        int storeOpcode = storeOpcode(expected);
        if (storeOpcode < 0) return null;
        int remaining = 128;
        for (AbstractInsnNode cursor = instruction.getPrevious(); cursor != null && remaining-- > 0;
             cursor = cursor.getPrevious()) {
            if (cursor == floorInclusive) break;
            if (cursor instanceof JumpInsnNode
                    || cursor.getType() == AbstractInsnNode.TABLESWITCH_INSN
                    || cursor.getType() == AbstractInsnNode.LOOKUPSWITCH_INSN) return null;
            int opcode = cursor.getOpcode();
            if (opcode == Opcodes.IRETURN || opcode == Opcodes.LRETURN || opcode == Opcodes.FRETURN
                    || opcode == Opcodes.DRETURN || opcode == Opcodes.ARETURN || opcode == Opcodes.RETURN
                    || opcode == Opcodes.ATHROW) return null;
            if (!(cursor instanceof VarInsnNode store) || store.var != load.var) continue;
            if (store.getOpcode() != storeOpcode) return null;
            AbstractInsnNode source = previousReal(store, floorInclusive);
            return stateLiteralThroughLocal(expected, source, floorInclusive, depth + 1);
        }
        return null;
    }

    private static int storeOpcode(Type type) {
        if (type == null) return -1;
        return switch (type.getSort()) {
            case Type.BOOLEAN, Type.BYTE, Type.CHAR, Type.SHORT, Type.INT -> Opcodes.ISTORE;
            case Type.LONG -> Opcodes.LSTORE;
            case Type.FLOAT -> Opcodes.FSTORE;
            case Type.DOUBLE -> Opcodes.DSTORE;
            case Type.ARRAY, Type.OBJECT -> Opcodes.ASTORE;
            default -> -1;
        };
    }

    private static AbstractInsnNode previousReal(AbstractInsnNode instruction,
                                                 AbstractInsnNode floorInclusive) {
        for (AbstractInsnNode previous = instruction == null ? null : instruction.getPrevious();
             previous != null; previous = previous.getPrevious()) {
            if (previous == floorInclusive) return previous.getOpcode() >= 0 ? previous : null;
            if (previous.getOpcode() >= 0) return previous;
        }
        return null;
    }

    private static boolean sameFieldRead(AbstractInsnNode instruction, FieldInsnNode write) {
        return instruction instanceof FieldInsnNode read
                && (read.getOpcode() == Opcodes.GETFIELD || read.getOpcode() == Opcodes.GETSTATIC)
                && write.owner.equals(read.owner) && write.name.equals(read.name)
                && write.desc.equals(read.desc);
    }

    private static Integer integerConstant(AbstractInsnNode instruction) {
        if (instruction == null) return null;
        return switch (instruction.getOpcode()) {
            case Opcodes.ICONST_M1 -> -1;
            case Opcodes.ICONST_0 -> 0;
            case Opcodes.ICONST_1 -> 1;
            case Opcodes.ICONST_2 -> 2;
            case Opcodes.ICONST_3 -> 3;
            case Opcodes.ICONST_4 -> 4;
            case Opcodes.ICONST_5 -> 5;
            default -> {
                if (instruction instanceof IntInsnNode value
                        && (value.getOpcode() == Opcodes.BIPUSH || value.getOpcode() == Opcodes.SIPUSH)) {
                    yield value.operand;
                }
                if (instruction instanceof LdcInsnNode ldc && ldc.cst instanceof Integer value) yield value;
                yield null;
            }
        };
    }

    private static Long longConstant(AbstractInsnNode instruction) {
        if (instruction == null) return null;
        return switch (instruction.getOpcode()) {
            case Opcodes.LCONST_0 -> 0L;
            case Opcodes.LCONST_1 -> 1L;
            default -> instruction instanceof LdcInsnNode ldc && ldc.cst instanceof Long value
                    ? value : null;
        };
    }

    private static Double floatingConstant(AbstractInsnNode instruction) {
        if (instruction == null) return null;
        return switch (instruction.getOpcode()) {
            case Opcodes.FCONST_0, Opcodes.DCONST_0 -> 0.0;
            case Opcodes.FCONST_1, Opcodes.DCONST_1 -> 1.0;
            case Opcodes.FCONST_2 -> 2.0;
            default -> {
                if (instruction instanceof LdcInsnNode ldc && ldc.cst instanceof Float value) {
                    yield value.doubleValue();
                }
                if (instruction instanceof LdcInsnNode ldc && ldc.cst instanceof Double value) {
                    yield value;
                }
                yield null;
            }
        };
    }

    /** Recover the common client-widget shape {@code config.values().add/remove(widgetValue)}.
     * The runtime later replays only this narrow collection mutation; the original client class is
     * never loaded or executed on the server. */
    private static CollectionMutation collectionMutationBefore(MethodInsnNode mutation,
                                                               AbstractInsnNode floorInclusive) {
        if (mutation == null || mutation.name == null || mutation.desc == null) return null;
        String operation = mutation.name.toLowerCase(Locale.ROOT);
        if (!(operation.equals("add") || operation.equals("remove"))) return null;
        if (!mutation.desc.startsWith("(Ljava/lang/Object;)")
                && !mutation.desc.startsWith("(Ljava/lang/Object;I)")) return null;
        String owner = mutation.owner == null ? "" : mutation.owner.toLowerCase(Locale.ROOT);
        if (!(owner.startsWith("java/util/") || owner.contains("collection")
                || owner.contains("list") || owner.contains("set"))) return null;

        FieldInsnNode valueField = null;
        MethodInsnNode accessor = null;
        int remaining = 18;
        for (AbstractInsnNode previous = mutation.getPrevious(); previous != null && remaining-- > 0;
             previous = previous.getPrevious()) {
            if (previous instanceof FieldInsnNode field
                    && (field.getOpcode() == Opcodes.GETFIELD || field.getOpcode() == Opcodes.GETSTATIC)
                    && valueField == null) {
                valueField = field;
            } else if (previous instanceof MethodInsnNode call && accessor == null
                    && Type.getArgumentTypes(call.desc).length == 0
                    && collectionReturn(call.desc)) {
                accessor = call;
                break;
            }
            if (previous == floorInclusive) break;
        }
        if (valueField == null || accessor == null) return null;
        return new CollectionMutation(accessor.owner, accessor.name, accessor.desc,
                valueField.owner, valueField.name, valueField.desc, operation);
    }

    private static boolean collectionReturn(String descriptor) {
        try {
            Type result = Type.getReturnType(descriptor);
            if (result.getSort() != Type.OBJECT) return false;
            String type = result.getInternalName().toLowerCase(Locale.ROOT);
            return type.startsWith("java/util/") && (type.contains("collection")
                    || type.contains("list") || type.contains("set"));
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private static void emitFactsForKey(ClassSurface surface, String key, String method,
                                        MethodFacts facts, int confidence) {
        if (facts.closesScreen) {
            addAction(surface, ActionType.CLOSE_SCREEN, key, method,
                    surface.clazz == null ? "screen" : surface.clazz, confidence);
        }
        if (facts.navigatesBack) {
            addAction(surface, ActionType.NAVIGATE_BACK, key, method, "previous_screen", confidence);
        }
        for (String screen : facts.screens) {
            addAction(surface, ActionType.OPEN_SCREEN, key, method, screen, confidence);
            surface.kinds.add(Kind.SCREEN);
            if (mapSemantic(screen.toLowerCase(Locale.ROOT))) surface.kinds.add(Kind.MAP);
        }
        for (String url : facts.externalUrls) {
            addAction(surface, ActionType.OPEN_URL, key, method, url, confidence);
        }
        for (PacketFact packet : facts.packets) {
            addAction(surface, ActionType.SEND_PACKET, key, method, packet.target(), confidence,
                    packet.constructorDesc(), packet.booleanArgument(), packet.staticFieldArgument(), packet.arguments(),
                    packet.producerOwner(), packet.producerMethod(), packet.producerDesc());
            surface.kinds.add(Kind.NETWORK);
        }
        for (RuntimeLaunch launch : facts.runtimeLaunches) {
            addAction(surface, launch.type(), key, method, launch.target(), Math.min(confidence, 96));
        }
        if (facts.screens.isEmpty() && facts.externalUrls.isEmpty() && facts.packets.isEmpty()
                && facts.runtimeLaunches.isEmpty()) {
            for (ClientHelperCall helper : facts.clientHelpers) {
                addAction(surface, ActionType.INVOKE_CLIENT_HELPER, key, method,
                        helper.target(), Math.min(confidence, 80));
            }
            for (StateMutation mutation : facts.stateWrites) {
                addStateAction(surface, key, method, mutation, Math.min(confidence, 86));
            }
        }
    }

    private static void emitActions(ClassSurface surface, Map<String, MethodFacts> facts) {
        for (Map.Entry<String, MethodFacts> entry : facts.entrySet()) {
            MethodFacts method = entry.getValue();
            // Multi-key methods need branch tracing above; a method-wide cross product creates
            // false edges (open-map key -> settings screen, settings key -> map, etc.).
            if (method.keys.size() != 1) continue;
            int confidence = 82;
            for (String key : method.keys) {
                emitFactsForKey(surface, key, entry.getKey(), method, confidence);
            }
        }
    }

    /**
     * Several client screens intentionally buffer edits and send one authoritative packet from
     * Screen.removed()/onClose(). A widget-only graph otherwise makes those screens look local-only
     * even though their state is committed when the screen closes. Record that lifecycle edge
     * separately so runtime translators can decide whether they have enough state to reproduce it.
     */
    private static void traceScreenCloseActions(ClassSurface surface, ClassNode owner,
                                                Map<String, MethodFacts> facts) {
        if (surface == null || owner == null || facts == null) return;
        for (Map.Entry<String, MethodFacts> entry : facts.entrySet()) {
            String methodKey = entry.getKey();
            int descriptorAt = methodKey.indexOf('(');
            String name = descriptorAt < 0 ? methodKey : methodKey.substring(0, descriptorAt);
            String desc = descriptorAt < 0 ? "" : methodKey.substring(descriptorAt);
            if (!("removed".equals(name) || "onClose".equals(name)
                    || "m_7861_".equals(name) || "m_7379_".equals(name)) || !"()V".equals(desc)) continue;
            MethodFacts close = entry.getValue();
            if (close == null || close.packets.isEmpty()) continue;
            for (PacketFact packet : close.packets) {
                addLifecyclePacketAction(surface, owner.name, methodKey, packet, 99);
            }
        }
    }

    private static void addLifecyclePacketAction(ClassSurface surface, String sourceScreen,
                                                 String method, PacketFact packet, int confidence) {
        if (packet == null || packet.target() == null) return;
        for (Action existing : surface.actions) {
            if (existing.triggerType == TriggerType.SCREEN_CLOSE
                    && existing.type == ActionType.SEND_PACKET
                    && java.util.Objects.equals(existing.sourceScreen, sourceScreen)
                    && java.util.Objects.equals(existing.target, packet.target())
                    && java.util.Objects.equals(existing.constructorDesc, packet.constructorDesc())
                    && sameStaticFieldArgument(existing, packet.staticFieldArgument())
                    && samePacketArguments(existing.packetArguments, packet.arguments())
                    && java.util.Objects.equals(existing.producerOwner, packet.producerOwner())
                    && java.util.Objects.equals(existing.producerMethod, packet.producerMethod())
                    && java.util.Objects.equals(existing.producerDesc, packet.producerDesc())) {
                existing.confidence = Math.max(existing.confidence, confidence);
                return;
            }
        }
        if (!reserveAction(surface)) return;
        Action action = new Action();
        action.type = ActionType.SEND_PACKET;
        action.triggerType = TriggerType.SCREEN_CLOSE;
        action.sourceScreen = sourceScreen;
        action.method = method;
        action.target = packet.target();
        action.constructorDesc = packet.constructorDesc();
        action.booleanArgument = packet.booleanArgument();
        applyStaticFieldArgument(action, packet.staticFieldArgument());
        applyPacketArguments(action, packet.arguments());
        action.producerOwner = packet.producerOwner();
        action.producerMethod = packet.producerMethod();
        action.producerDesc = packet.producerDesc();
        action.confidence = confidence;
        surface.actions.add(action);
        surface.kinds.add(Kind.NETWORK);
    }

    private static void addAction(ClassSurface surface, ActionType type, String key, String method,
                                  String target, int confidence) {
        addAction(surface, type, key, method, target, confidence,
                null, null, null, List.of(), null, null, null);
    }

    private static void addStateAction(ClassSurface surface, String key, String method,
                                       StateMutation mutation, int confidence) {
        if (surface == null || key == null || mutation == null || mutation.target() == null) return;
        for (Action existing : surface.actions) {
            if (existing.type == ActionType.WRITE_STATE && key.equals(existing.keybind)
                    && mutation.target().equals(existing.target)
                    && existing.stateWriteKind == mutation.kind()
                    && java.util.Objects.equals(existing.stateBooleanValue, mutation.booleanValue())
                    && java.util.Objects.equals(existing.stateIntValue, mutation.intValue())
                    && java.util.Objects.equals(existing.stateIntModulus, mutation.intModulus())
                    && java.util.Objects.equals(existing.stateIntModulusTarget, mutation.intModulusTarget())
                    && java.util.Objects.equals(existing.stateIntModulusMinimum, mutation.intModulusMinimum())
                    && java.util.Objects.equals(existing.stateLongValue, mutation.longValue())
                    && java.util.Objects.equals(existing.stateDoubleValue, mutation.doubleValue())
                    && java.util.Objects.equals(existing.stateStringValue, mutation.stringValue())
                    && java.util.Objects.equals(existing.stateStaticFieldOwner, mutation.staticFieldOwner())
                    && java.util.Objects.equals(existing.stateStaticFieldName, mutation.staticFieldName())
                    && java.util.Objects.equals(existing.stateStaticFieldDesc, mutation.staticFieldDesc())) {
                existing.confidence = Math.max(existing.confidence, confidence);
                return;
            }
        }
        if (!reserveAction(surface)) return;
        Action action = new Action();
        action.type = ActionType.WRITE_STATE;
        action.keybind = key;
        action.method = method;
        action.target = mutation.target();
        action.stateWriteKind = mutation.kind();
        action.stateBooleanValue = mutation.booleanValue();
        action.stateIntValue = mutation.intValue();
        action.stateIntModulus = mutation.intModulus();
        action.stateIntModulusTarget = mutation.intModulusTarget();
        action.stateIntModulusMinimum = mutation.intModulusMinimum();
        action.stateLongValue = mutation.longValue();
        action.stateDoubleValue = mutation.doubleValue();
        action.stateStringValue = mutation.stringValue();
        action.stateStaticFieldOwner = mutation.staticFieldOwner();
        action.stateStaticFieldName = mutation.staticFieldName();
        action.stateStaticFieldDesc = mutation.staticFieldDesc();
        action.stateReceiverFieldOwner = mutation.receiverFieldOwner();
        action.stateReceiverFieldName = mutation.receiverFieldName();
        action.confidence = confidence;
        surface.actions.add(action);
    }

    private static void addAction(ClassSurface surface, ActionType type, String key, String method,
                                  String target, int confidence, String constructorDesc,
                                  Boolean booleanArgument, StaticFieldArgument staticFieldArgument,
                                  List<PacketArgument> packetArguments, String producerOwner,
                                  String producerMethod, String producerDesc) {
        if (key == null || target == null) return;
        for (Action existing : surface.actions) {
            if (existing.type == type && key.equals(existing.keybind) && target.equals(existing.target)
                    && java.util.Objects.equals(existing.constructorDesc, constructorDesc)
                    && java.util.Objects.equals(existing.booleanArgument, booleanArgument)
                    && sameStaticFieldArgument(existing, staticFieldArgument)
                    && samePacketArguments(existing.packetArguments, packetArguments)
                    && java.util.Objects.equals(existing.producerOwner, producerOwner)
                    && java.util.Objects.equals(existing.producerMethod, producerMethod)
                    && java.util.Objects.equals(existing.producerDesc, producerDesc)) {
                existing.confidence = Math.max(existing.confidence, confidence);
                return;
            }
        }
        if (!reserveAction(surface)) return;
        Action action = new Action();
        action.type = type;
        action.keybind = key;
        action.method = method;
        action.target = target;
        action.constructorDesc = constructorDesc;
        action.booleanArgument = booleanArgument;
        applyStaticFieldArgument(action, staticFieldArgument);
        applyPacketArguments(action, packetArguments);
        action.producerOwner = producerOwner;
        action.producerMethod = producerMethod;
        action.producerDesc = producerDesc;
        action.confidence = confidence;
        surface.actions.add(action);
    }

    private static boolean sameStaticFieldArgument(Action action, StaticFieldArgument argument) {
        if (action == null) return argument == null;
        if (argument == null) {
            return action.staticFieldArgumentIndex < 0 && action.staticFieldArgumentOwner == null
                    && action.staticFieldArgumentName == null && action.staticFieldArgumentDesc == null;
        }
        return action.staticFieldArgumentIndex == argument.index()
                && java.util.Objects.equals(action.staticFieldArgumentOwner, argument.owner())
                && java.util.Objects.equals(action.staticFieldArgumentName, argument.name())
                && java.util.Objects.equals(action.staticFieldArgumentDesc, argument.desc());
    }

    private static void applyStaticFieldArgument(Action action, StaticFieldArgument argument) {
        if (action == null || argument == null) return;
        action.staticFieldArgumentIndex = argument.index();
        action.staticFieldArgumentOwner = argument.owner();
        action.staticFieldArgumentName = argument.name();
        action.staticFieldArgumentDesc = argument.desc();
    }

    private static boolean samePacketArguments(List<PacketArgument> left, List<PacketArgument> right) {
        if (left == null || left.isEmpty()) return right == null || right.isEmpty();
        if (right == null || left.size() != right.size()) return false;
        for (int i = 0; i < left.size(); i++) {
            PacketArgument a = left.get(i);
            PacketArgument b = right.get(i);
            if (a == b) continue;
            if (a == null || b == null || a.index != b.index || a.kind != b.kind
                    || !java.util.Objects.equals(a.descriptor, b.descriptor)
                    || !java.util.Objects.equals(a.booleanValue, b.booleanValue)
                    || !java.util.Objects.equals(a.intValue, b.intValue)
                    || !java.util.Objects.equals(a.longValue, b.longValue)
                    || !java.util.Objects.equals(a.doubleValue, b.doubleValue)
                    || !java.util.Objects.equals(a.stringValue, b.stringValue)
                    || !java.util.Objects.equals(a.uuidValue, b.uuidValue)
                    || !java.util.Objects.equals(a.fieldOwner, b.fieldOwner)
                    || !java.util.Objects.equals(a.fieldName, b.fieldName)
                    || !java.util.Objects.equals(a.fieldDesc, b.fieldDesc)
                    || !java.util.Objects.equals(a.accessorOwner, b.accessorOwner)
                    || !java.util.Objects.equals(a.accessorName, b.accessorName)
                    || !java.util.Objects.equals(a.accessorDesc, b.accessorDesc)
                    || !java.util.Objects.equals(a.accessorFieldOwner, b.accessorFieldOwner)
                    || !java.util.Objects.equals(a.accessorFieldName, b.accessorFieldName)
                    || !java.util.Objects.equals(a.accessorFieldDesc, b.accessorFieldDesc)
                    || !java.util.Objects.equals(a.mirrorSourceOwner, b.mirrorSourceOwner)
                    || !java.util.Objects.equals(a.mirrorSourceFieldName, b.mirrorSourceFieldName)
                    || !java.util.Objects.equals(a.mirrorSourceFieldDesc, b.mirrorSourceFieldDesc)
                    || !java.util.Objects.equals(a.mirrorConverterOwner, b.mirrorConverterOwner)
                    || !java.util.Objects.equals(a.mirrorConverterMethod, b.mirrorConverterMethod)
                    || !java.util.Objects.equals(a.mirrorConverterDesc, b.mirrorConverterDesc)
                    || !java.util.Objects.equals(a.menuOwner, b.menuOwner)
                    || !java.util.Objects.equals(a.menuMemberName, b.menuMemberName)
                    || !java.util.Objects.equals(a.menuMemberDesc, b.menuMemberDesc)
                    || a.menuMemberMethod != b.menuMemberMethod) return false;
        }
        return true;
    }

    private static void applyPacketArguments(Action action, List<PacketArgument> arguments) {
        if (action == null || arguments == null || arguments.isEmpty()) return;
        action.packetArguments.clear();
        action.packetArguments.addAll(arguments);
    }

    /**
     * Recovers nested client-screen navigation from real widget callbacks. Modern Minecraft/mod
     * screens usually construct a Button (or compatible widget) with an invokedynamic lambda for
     * OnPress; the bootstrap arguments contain the exact synthetic method implementing that click.
     * Following that handle is substantially more reliable than guessing from button/screen names.
     *
     * <p>The resulting edge keeps widget construction order and label evidence. Recorder captures
     * already persist widgets in {@code Screen.children()} order, so the runtime can pair the
     * bytecode action with the exact captured rectangle even when the label is dynamic or blank.</p>
     */
    private static void traceWidgetActions(ClassSurface surface, ClassNode owner,
                                           Map<String, ClassNode> nodes) {
        if (surface == null || owner == null || owner.methods == null) return;
        Map<String, String> componentFields = discoverComponentFields(owner);
        UiKnowledge uiKnowledge = discoverUiKnowledge(owner, nodes);
        Map<String, Map<Integer, UiValue>> uiParameterValues =
                discoverUiParameterValues(owner, nodes, uiKnowledge);
        Map<String, Handle> lambdaFields = discoverLambdaFields(owner);
        Map<String, WidgetRef> allWidgetFields = new HashMap<>();
        for (MethodNode method : owner.methods) {
            if (!looksLikeWidgetInit(method) && !constructsWidget(method, nodes)) continue;
            Map<AbstractInsnNode, WidgetLayout> layouts = widgetLayouts(
                    owner, method, nodes, uiKnowledge,
                    uiParameterValues.getOrDefault(methodKey(owner.name, method.name, method.desc), Map.of()));
            Map<String, WidgetRef> widgetFields = new HashMap<>();
            Map<AbstractInsnNode, WidgetRef> widgetOrigins = new IdentityHashMap<>();
            Map<Integer, Handle> lambdaLocals = lambdaLocals(method);
            int ordinal = 0;
            for (AbstractInsnNode instruction : method.instructions) {
                if (!(instruction instanceof MethodInsnNode call)) continue;
                boolean constructor = call.getOpcode() == Opcodes.INVOKESPECIAL
                        && "<init>".equals(call.name)
                        && clickableWidgetConstructor(call, nodes);
                boolean factory = !constructor && looksLikeWidgetFactory(call, nodes);
                if (!constructor && !factory) continue;
                Handle implementation = widgetCallbackHandleBefore(
                        instruction, call, lambdaLocals, lambdaFields, nodes,
                        factory ? 72 : 48);
                String label = widgetLabelBefore(instruction, componentFields, 36);
                int widgetOrdinal = ordinal++;
                WidgetLayout layout = layouts.getOrDefault(instruction, WidgetLayout.UNKNOWN);
                // A custom widget constructor may receive a preconfigured builder rather than
                // x/y/w/h directly. The builder's fluent pos/size calls are still part of this
                // widget's construction chain, so merge them for constructors as well as terminal
                // factories. Ownership boundaries in mergePrecedingLayouts prevent borrowing a
                // previous widget's geometry.
                if (constructor || factory) {
                    layout = mergePrecedingLayouts(instruction, layout, layouts, nodes, 48);
                }
                String widgetClass = constructor ? call.owner : returnedObject(call.desc);
                FieldInsnNode visual = widgetVisualBefore(instruction, 28);
                FieldInsnNode store = storedWidgetFieldAfter(instruction, 10);
                WidgetInitialState initialState = widgetInitialState(method, store);
                WidgetRef widget = new WidgetRef(widgetOrdinal, widgetClass, label,
                        layout.x(), layout.y(), layout.width(), layout.height(),
                        layout.xAnchor(), layout.yAnchor(),
                        visual == null ? null : visual.owner,
                        visual == null ? null : visual.name,
                        initialState.visible(), initialState.active());
                widgetOrigins.put(instruction, widget);
                if (store != null) {
                    widgetFields.put(fieldKey(store.owner, store.name), widget);
                    allWidgetFields.put(fieldKey(store.owner, store.name), widget);
                }
                if (implementation == null) {
                    emitSelfHandledWidgetFacts(surface, owner.name, widget, nodes, 97);
                    continue;
                }
                if (nodes == null || !nodes.containsKey(implementation.getOwner())) {
                    continue;
                }
                emitLambdaWidgetFacts(surface, owner.name, widget, implementation, nodes, 98);
            }

            // Many client UI libraries construct a widget first and attach its callback later via
            // methods such as withCallback(Runnable) or calling(Consumer). Track the widget field
            // and lambda local rather than requiring the callback to be a constructor argument.
            for (AbstractInsnNode instruction : method.instructions) {
                if (!(instruction instanceof MethodInsnNode call) || !callbackRegistration(call, nodes)) continue;
                WidgetRef widget = referencedWidgetBefore(instruction, call.owner,
                        widgetFields, widgetOrigins, nodes, 28);
                if (widget == null) continue;
                Handle implementation = callbackHandleBefore(instruction, lambdaLocals,
                        lambdaFields, nodes, 20);
                if (implementation == null) continue;
                emitLambdaWidgetFacts(surface, owner.name, widget, implementation, nodes, 99);
            }
        }
        attachStateReceiverOwnership(surface, owner, nodes);
        attachStateTargetWidgets(surface, allWidgetFields);
        attachLiveWidgetStatePredicates(surface, owner, nodes, allWidgetFields);
    }

    private static void attachStateReceiverOwnership(ClassSurface surface, ClassNode owner,
                                                     Map<String, ClassNode> nodes) {
        if (surface == null || owner == null) return;
        Set<String> hierarchy = screenHierarchy(owner, nodes);
        for (Action action : surface.actions) {
            if (action == null || action.type != ActionType.WRITE_STATE
                    || action.triggerType != TriggerType.WIDGET
                    || action.sourceScreen == null || action.stateReceiverFieldOwner == null
                    || action.stateReceiverFieldName == null
                    || !owner.name.equals(action.sourceScreen.replace('.', '/'))) continue;
            action.stateReceiverOwnedByScreen = hierarchy.contains(
                    action.stateReceiverFieldOwner.replace('.', '/'));
        }
    }

    /**
     * Joins a cyclic helper state's collection bound back to the exact authoritative object field
     * on the source Screen. The proof follows the helper constructor, then the Screen's concrete
     * helper construction site; matching by descriptor alone is never sufficient.
     */
    private static void attachHistoricalModulusObjectSources(ClassSurface surface, ClassNode screen,
                                                              Map<String, ClassNode> nodes) {
        if (surface == null || screen == null || nodes == null) return;
        for (Action action : surface.actions) {
            if (action == null || action.type != ActionType.WRITE_STATE
                    || action.triggerType != TriggerType.WIDGET
                    || action.stateWriteKind != StateWriteKind.WRAP_ADD_INT
                    || action.stateIntModulus != null || action.stateIntModulusTarget == null
                    || action.stateReceiverFieldOwner == null || action.stateReceiverFieldName == null
                    || !action.stateReceiverOwnedByScreen
                    || action.sourceScreen == null
                    || !screen.name.equals(action.sourceScreen.replace('.', '/'))) continue;
            ParsedStateTarget modulus = parsedStateTarget(action.stateIntModulusTarget);
            if (modulus == null || objectInternalName(modulus.desc()) == null) continue;
            FieldNode helperField = exactHierarchyField(screen, action.stateReceiverFieldOwner,
                    action.stateReceiverFieldName, nodes);
            if (helperField == null) continue;
            String helperType = objectInternalName(helperField.desc);
            if (helperType == null) continue;
            String source = uniqueScreenObjectFieldForHelperBound(screen, helperField, helperType,
                    modulus.owner(), nodes);
            if (source != null) action.stateIntModulusScreenObjectFieldTarget = source;
        }
    }

    private static FieldNode exactHierarchyField(ClassNode owner, String fieldOwner, String fieldName,
                                                 Map<String, ClassNode> nodes) {
        if (owner == null || fieldOwner == null || fieldName == null || nodes == null) return null;
        String wantedOwner = fieldOwner.replace('.', '/');
        if (!screenHierarchy(owner, nodes).contains(wantedOwner)) return null;
        ClassNode declaring = nodes.get(wantedOwner);
        if (declaring == null || declaring.fields == null) return null;
        FieldNode found = null;
        for (FieldNode field : declaring.fields) {
            if (field == null || !fieldName.equals(field.name) || (field.access & Opcodes.ACC_STATIC) != 0) {
                continue;
            }
            if (found != null) return null;
            found = field;
        }
        return found;
    }

    private static String uniqueScreenObjectFieldForHelperBound(
            ClassNode screen, FieldNode helperField, String helperType, String boundObjectType,
            Map<String, ClassNode> nodes) {
        ClassNode helper = nodes.get(helperType);
        if (screen == null || helper == null || helperField == null || boundObjectType == null) return null;
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        for (MethodNode method : screen.methods) {
            if (method == null || method.instructions == null) continue;
            BytecodeValueFlow flow = new BytecodeValueFlow(nodes);
            for (AbstractInsnNode instruction : method.instructions) {
                if (!(instruction instanceof FieldInsnNode store) || store.getOpcode() != Opcodes.PUTFIELD
                        || !helperField.name.equals(store.name) || !helperField.desc.equals(store.desc)
                        || !screenHierarchy(screen, nodes).contains(store.owner)) continue;
                Integer storeReceiver = flow.putFieldReceiverParameterLocal(screen, method, store);
                if (storeReceiver == null || storeReceiver != 0) continue;
                Set<AbstractInsnNode> values = flow.putFieldValueSources(screen, method, store);
                if (values.size() != 1 || !(values.iterator().next() instanceof TypeInsnNode allocation)
                        || allocation.getOpcode() != Opcodes.NEW || !helperType.equals(allocation.desc)) continue;
                for (AbstractInsnNode nested : method.instructions) {
                    if (!(nested instanceof MethodInsnNode constructor)
                            || constructor.getOpcode() != Opcodes.INVOKESPECIAL
                            || !"<init>".equals(constructor.name) || !helperType.equals(constructor.owner)
                            || !flow.receiverSources(screen, method, constructor).contains(allocation)) continue;
                    Integer boundParameter = helperBoundObjectParameter(helper, constructor.desc,
                            boundObjectType, nodes);
                    if (boundParameter == null) continue;
                    String supplied = exactScreenObjectFieldArgument(screen, method, constructor,
                            boundParameter, boundObjectType, flow, nodes);
                    if (supplied != null) candidates.add(supplied);
                }
            }
        }
        return candidates.size() == 1 ? candidates.iterator().next() : null;
    }

    /** Unique constructor parameter copied directly into a field of the exact bound-object type. */
    private static Integer helperBoundObjectParameter(ClassNode helper, String constructorDesc,
                                                      String boundObjectType,
                                                      Map<String, ClassNode> nodes) {
        if (helper == null || constructorDesc == null || boundObjectType == null) return null;
        MethodNode constructor = findMethod(helper, "<init>", constructorDesc);
        if (constructor == null) return null;
        String descriptor = 'L' + boundObjectType + ';';
        BytecodeValueFlow flow = new BytecodeValueFlow(nodes);
        Integer found = null;
        for (AbstractInsnNode instruction : constructor.instructions) {
            if (!(instruction instanceof FieldInsnNode field) || field.getOpcode() != Opcodes.PUTFIELD
                    || !descriptor.equals(field.desc)) continue;
            Integer receiver = flow.putFieldReceiverParameterLocal(helper, constructor, field);
            Integer value = flow.putFieldValueParameterLocal(helper, constructor, field);
            if (receiver == null || receiver != 0 || value == null) return null;
            int parameter = constructorParameterIndexAtLocal(constructor, value);
            if (parameter < 0) return null;
            if (found != null && found != parameter) return null;
            found = parameter;
        }
        return found;
    }

    private static int constructorParameterIndexAtLocal(MethodNode constructor, int wantedLocal) {
        if (constructor == null || constructor.desc == null || wantedLocal <= 0) return -1;
        Type[] arguments;
        try {
            arguments = Type.getArgumentTypes(constructor.desc);
        } catch (IllegalArgumentException ignored) {
            return -1;
        }
        for (int index = 0; index < arguments.length; index++) {
            if (parameterLocal(constructor, arguments, index) == wantedLocal) return index;
        }
        return -1;
    }

    private static String exactScreenObjectFieldArgument(
            ClassNode screen, MethodNode method, MethodInsnNode constructor, int argument,
            String boundObjectType, BytecodeValueFlow flow, Map<String, ClassNode> nodes) {
        if (screen == null || method == null || constructor == null || flow == null
                || argument < 0 || boundObjectType == null) return null;
        LinkedHashSet<FieldInsnNode> fields = new LinkedHashSet<>();
        for (AbstractInsnNode producer : flow.argumentSources(screen, method, constructor, argument)) {
            if (producer != null && producer.getOpcode() == Opcodes.ACONST_NULL) continue;
            if (!(producer instanceof FieldInsnNode field) || field.getOpcode() != Opcodes.GETFIELD
                    || !('L' + boundObjectType + ';').equals(field.desc)) return null;
            Integer receiver = flow.fieldReceiverParameterLocal(screen, method, field);
            if (receiver == null || receiver != 0 || !screenHierarchy(screen, nodes).contains(field.owner)) {
                return null;
            }
            fields.add(field);
        }
        if (fields.isEmpty()) return null;
        FieldInsnNode first = fields.iterator().next();
        for (FieldInsnNode field : fields) {
            if (!sameBackingField(first, field)) return null;
        }
        return first.owner + "." + first.name + ':' + first.desc;
    }

    static Set<String> screenHierarchy(ClassNode owner, Map<String, ClassNode> nodes) {
        if (owner == null) return Set.of();
        LinkedHashSet<String> hierarchy = new LinkedHashSet<>();
        ClassNode cursor = owner;
        int remaining = 32;
        while (cursor != null && remaining-- > 0) {
            if (!hierarchy.add(cursor.name)) break;
            String parent = cursor.superName;
            if (parent == null || parent.isBlank()) break;
            ClassNode parentNode = nodes == null ? null : nodes.get(parent);
            if (parentNode == null) {
                hierarchy.add(parent);
                break;
            }
            cursor = parentNode;
        }
        return Set.copyOf(hierarchy);
    }

    private static void attachStateTargetWidgets(ClassSurface surface,
                                                 Map<String, WidgetRef> widgetFields) {
        if (surface == null || widgetFields == null || widgetFields.isEmpty()) return;
        for (Action action : surface.actions) {
            if (action == null || action.type != ActionType.WRITE_STATE
                    || action.triggerType != TriggerType.WIDGET
                    || action.stateReceiverFieldOwner == null || action.stateReceiverFieldName == null) continue;
            WidgetRef target = widgetFields.get(fieldKey(
                    action.stateReceiverFieldOwner, action.stateReceiverFieldName));
            if (target == null || target.widgetClass() == null) continue;
            action.stateTargetWidgetOrdinal = target.ordinal();
            action.stateTargetWidgetClass = target.widgetClass();
            action.stateTargetWidgetInteractive = surface.actions.stream().anyMatch(candidate ->
                    candidate != null && candidate.triggerType == TriggerType.WIDGET
                            && candidate.widgetOrdinal == target.ordinal()
                            && java.util.Objects.equals(candidate.widgetClass, target.widgetClass()));
        }
    }

    private static boolean constructsWidget(MethodNode method, Map<String, ClassNode> nodes) {
        if (method == null || method.instructions == null) return false;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKESPECIAL
                    && "<init>".equals(call.name)
                    && clickableWidgetConstructor(call, nodes)) return true;
        }
        return false;
    }

    private static boolean clickableWidgetConstructor(MethodInsnNode call,
                                                      Map<String, ClassNode> nodes) {
        return looksLikeClickableWidgetConstructor(call)
                || call != null && isWidgetType(call.owner, nodes);
    }

    private static void emitLambdaWidgetFacts(ClassSurface surface, String sourceScreen,
                                              WidgetRef widget, Handle implementation,
                                              Map<String, ClassNode> nodes, int confidence) {
        if (widget == null || implementation == null || nodes == null) return;
        // Method references to Screen::onClose have no lambda body in the mod jar. Minecraft's
        // client classes are deliberately absent from the server scanner classpath.
        if ("net/minecraft/client/gui/screens/Screen".equals(implementation.getOwner())
                && "()V".equals(implementation.getDesc())
                && ("onClose".equals(implementation.getName())
                || "m_7379_".equals(implementation.getName()))) {
            MethodFacts facts = new MethodFacts();
            facts.closesScreen = true;
            emitFactsForWidget(surface, sourceScreen, widget.widgetClass(), widget.ordinal(),
                    widget.label(), widget.x(), widget.y(), widget.width(), widget.height(),
                    methodKey(implementation.getName(), implementation.getDesc()), facts, nodes, confidence);
            attachWidgetMetadata(surface, sourceScreen, widget);
            return;
        }
        if (!nodes.containsKey(implementation.getOwner())) return;
        ClassNode callbackOwner = nodes.get(implementation.getOwner());
        MethodNode callbackMethod = findMethodInHierarchy(callbackOwner,
                implementation.getName(), implementation.getDesc(), nodes);
        if (callbackMethod == null) return;
        Set<String> callStack = new HashSet<>();
        callStack.add(implementation.getOwner() + "#"
                + methodKey(implementation.getName(), implementation.getDesc()));
        MethodFacts facts = collectBehaviorRange(implementation.getOwner(),
                callbackMethod.instructions.getFirst(), null, nodes, callStack, 0);
        promoteBooleanToggleCallbackState(facts, callbackMethod, widget.widgetClass(), nodes);
        promoteStringInputCallbackState(facts, implementation.getOwner(), callbackMethod,
                widget.widgetClass(), nodes);
        emitFactsForWidget(surface, sourceScreen, widget.widgetClass(), widget.ordinal(),
                widget.label(), widget.x(), widget.y(), widget.width(), widget.height(),
                methodKey(callbackMethod), facts, nodes, confidence);
        attachTriggerWidgetReceiverState(surface, sourceScreen, widget, callbackMethod, nodes, false);
        attachWidgetMetadata(surface, sourceScreen, widget);
    }

    /** Promote an unresolved boolean field assignment when the callback parameter is proven to be
     * the post-click state of a toggle widget. The proof is entirely structural: the widget's own
     * bytecode must perform a parent state transition and then pass a boolean getter result to a
     * Consumer. No widget/mod names participate. */
    private static void promoteBooleanToggleCallbackState(MethodFacts facts, MethodNode callback,
                                                          String widgetClass,
                                                          Map<String, ClassNode> nodes) {
        if (facts == null || callback == null || facts.stateWrites.isEmpty()
                || !widgetBooleanCallbackIsToggle(widgetClass, nodes)) return;
        Integer booleanLocal = singleBooleanCallbackLocal(callback);
        if (booleanLocal == null) return;
        HashMap<String, StateMutation> replacements = new HashMap<>();
        for (AbstractInsnNode instruction : callback.instructions) {
            if (!(instruction instanceof FieldInsnNode field)
                    || (field.getOpcode() != Opcodes.PUTFIELD && field.getOpcode() != Opcodes.PUTSTATIC)
                    || !"Z".equals(field.desc)) continue;
            AbstractInsnNode value = previousReal(field, null);
            VarInsnNode source = booleanSourceLocal(value);
            if (source == null || source.var != booleanLocal) continue;
            String target = field.owner + "." + field.name + ':' + field.desc;
            StateReceiver receiver = field.getOpcode() == Opcodes.PUTFIELD
                    ? fieldReceiver(previousReal(source, null)) : null;
            replacements.put(target, new StateMutation(target, StateWriteKind.TOGGLE_BOOLEAN,
                    null, null, receiver == null ? null : receiver.owner(),
                    receiver == null ? null : receiver.name()));
        }
        if (replacements.isEmpty()) return;
        LinkedHashSet<StateMutation> promoted = new LinkedHashSet<>();
        for (StateMutation mutation : facts.stateWrites) {
            StateMutation replacement = mutation != null && mutation.kind() == null
                    ? replacements.get(mutation.target()) : null;
            promoted.add(replacement == null ? mutation : replacement);
        }
        facts.stateWrites.clear();
        facts.stateWrites.addAll(promoted);
    }

    private static VarInsnNode booleanSourceLocal(AbstractInsnNode value) {
        if (value instanceof VarInsnNode primitive && primitive.getOpcode() == Opcodes.ILOAD) {
            return primitive;
        }
        if (value instanceof MethodInsnNode unbox && "java/lang/Boolean".equals(unbox.owner)
                && "booleanValue".equals(unbox.name) && "()Z".equals(unbox.desc)) {
            AbstractInsnNode source = previousReal(unbox, null);
            if (source instanceof VarInsnNode boxed && boxed.getOpcode() == Opcodes.ALOAD) return boxed;
        }
        return null;
    }

    private static Integer singleBooleanCallbackLocal(MethodNode method) {
        if (method == null || method.desc == null) return null;
        Type[] arguments;
        try {
            arguments = Type.getArgumentTypes(method.desc);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
        int local = (method.access & Opcodes.ACC_STATIC) != 0 ? 0 : 1;
        Integer result = null;
        for (Type argument : arguments) {
            boolean booleanLike = argument.getSort() == Type.BOOLEAN
                    || argument.getSort() == Type.OBJECT
                    && "java/lang/Boolean".equals(argument.getInternalName());
            if (booleanLike) {
                if (result != null) return null;
                result = local;
            }
            local += argument.getSize();
        }
        return result;
    }

    /**
     * Promotes an unresolved source-screen String assignment when bytecode proves the value is the
     * unique String parameter delivered to a text-input widget callback. The value itself remains
     * runtime input -- discovery records only its provenance, never a guessed/default String.
     */
    private static void promoteStringInputCallbackState(MethodFacts facts, String callbackOwner,
                                                        MethodNode callback,
                                                        String widgetClass,
                                                        Map<String, ClassNode> nodes) {
        if (facts == null || callback == null || facts.stateWrites.isEmpty()
                || !textInputWidgetType(widgetClass, nodes)) return;
        Integer stringLocal = singleStringCallbackLocal(callback);
        HashMap<String, StateMutation> replacements = new HashMap<>();
        if (stringLocal != null) {
            for (AbstractInsnNode instruction : callback.instructions) {
                if (!(instruction instanceof FieldInsnNode field)
                        || field.getOpcode() != Opcodes.PUTFIELD
                        || !"Ljava/lang/String;".equals(field.desc)) continue;
                AbstractInsnNode value = previousReal(field, null);
                if (!(value instanceof VarInsnNode load) || load.getOpcode() != Opcodes.ALOAD
                        || load.var != stringLocal) continue;
                AbstractInsnNode receiverNode = previousReal(load, null);
                StateReceiver receiver = receiverNode instanceof FieldInsnNode receiverField
                        && receiverField.getOpcode() == Opcodes.GETFIELD
                        ? new StateReceiver(receiverField.owner, receiverField.name) : null;
                String target = field.owner + "." + field.name + ':' + field.desc;
                replacements.put(target, new StateMutation(target, StateWriteKind.ASSIGN_INPUT_STRING,
                        null, null, receiver == null ? null : receiver.owner(),
                        receiver == null ? null : receiver.name()));
            }
        }
        // Some responder callbacks ignore the delivered String and immediately call a same-screen
        // helper which reads the bound text widget's value. This is semantically the same runtime
        // input, but only when the getter receiver is a concrete text-input field and the helper
        // chain stays on `this`. Do not promote arbitrary String-returning methods.
        for (String target : textInputDerivedStateTargets(callbackOwner, callback, nodes,
                new HashSet<>(), 0)) {
            replacements.putIfAbsent(target, new StateMutation(target,
                    StateWriteKind.ASSIGN_INPUT_STRING, null, null, null, null));
        }
        if (replacements.isEmpty()) return;
        LinkedHashSet<StateMutation> promoted = new LinkedHashSet<>();
        for (StateMutation mutation : facts.stateWrites) {
            StateMutation replacement = mutation != null && mutation.kind() == null
                    ? replacements.get(mutation.target()) : null;
            promoted.add(replacement == null ? mutation : replacement);
        }
        facts.stateWrites.clear();
        facts.stateWrites.addAll(promoted);
    }

    private static Set<String> textInputDerivedStateTargets(String ownerName, MethodNode method,
                                                            Map<String, ClassNode> nodes,
                                                            Set<String> visiting, int depth) {
        if (ownerName == null || method == null || nodes == null || depth > 4) return Set.of();
        String id = ownerName + '#' + methodKey(method) + "@text-input";
        if (!visiting.add(id)) return Set.of();
        LinkedHashSet<String> targets = new LinkedHashSet<>();
        try {
            Set<String> hierarchy = classHierarchyNames(nodes.get(ownerName), nodes);
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof FieldInsnNode field
                        && field.getOpcode() == Opcodes.PUTFIELD
                        && "Ljava/lang/String;".equals(field.desc)) {
                    AbstractInsnNode value = previousReal(field, null);
                    if (value instanceof MethodInsnNode getter
                            && getter.getOpcode() != Opcodes.INVOKESTATIC
                            && "()Ljava/lang/String;".equals(getter.desc)) {
                        AbstractInsnNode receiver = previousReal(getter, null);
                        if (receiver instanceof FieldInsnNode input
                                && input.getOpcode() == Opcodes.GETFIELD
                                && objectInternalName(input.desc) != null
                                && textInputWidgetType(objectInternalName(input.desc), nodes)) {
                            targets.add(field.owner + "." + field.name + ':' + field.desc);
                        }
                    }
                    continue;
                }
                if (!(instruction instanceof MethodInsnNode call) || "<init>".equals(call.name)
                        || !hierarchy.contains(call.owner)) continue;
                Type[] arguments;
                try {
                    arguments = Type.getArgumentTypes(call.desc);
                } catch (IllegalArgumentException ignored) {
                    continue;
                }
                if (arguments.length != 0) continue;
                AbstractInsnNode receiver = previousReal(call, null);
                if (!(receiver instanceof VarInsnNode load) || load.getOpcode() != Opcodes.ALOAD
                        || load.var != 0) continue;
                ResolvedMethod resolved = resolveMethodInHierarchy(nodes.get(call.owner),
                        call.name, call.desc, nodes);
                if (resolved == null || resolved.method() == null) continue;
                targets.addAll(textInputDerivedStateTargets(resolved.owner(), resolved.method(), nodes,
                        visiting, depth + 1));
            }
            return Set.copyOf(targets);
        } finally {
            visiting.remove(id);
        }
    }

    private static Integer singleStringCallbackLocal(MethodNode method) {
        if (method == null || method.desc == null) return null;
        Type[] arguments;
        try {
            arguments = Type.getArgumentTypes(method.desc);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
        int local = (method.access & Opcodes.ACC_STATIC) != 0 ? 0 : 1;
        Integer result = null;
        for (Type argument : arguments) {
            boolean string = argument.getSort() == Type.OBJECT
                    && "java/lang/String".equals(argument.getInternalName());
            if (string) {
                if (result != null) return null;
                result = local;
            }
            local += argument.getSize();
        }
        return result;
    }

    /** Framework-independent text-input hint. This deliberately uses only the widget hierarchy,
     * never a mod id or owning screen, and mirrors the runtime's existing captured-text classifier. */
    private static boolean textInputWidgetType(String widgetClass, Map<String, ClassNode> nodes) {
        String current = widgetClass;
        int remaining = 16;
        while (current != null && remaining-- > 0) {
            String simple = current.substring(current.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
            if (simple.contains("editbox") || simple.contains("textfield")
                    || simple.contains("textinput")) return true;
            ClassNode node = nodes == null ? null : nodes.get(current);
            current = node == null ? null : node.superName;
        }
        return false;
    }

    static boolean widgetBooleanCallbackIsToggle(String widgetClass, Map<String, ClassNode> nodes) {
        if (widgetClass == null || nodes == null) return false;
        ClassNode widget = nodes.get(widgetClass);
        if (widget == null || widget.methods == null) return false;
        for (MethodNode method : widget.methods) {
            if (method == null || !"()V".equals(method.desc)
                    || (method.access & Opcodes.ACC_STATIC) != 0) continue;
            boolean parentTransition = false;
            boolean booleanValue = false;
            boolean callback = false;
            for (AbstractInsnNode instruction : method.instructions) {
                if (!(instruction instanceof MethodInsnNode call)) continue;
                if (call.getOpcode() == Opcodes.INVOKESPECIAL && "()V".equals(call.desc)
                        && !widget.name.equals(call.owner)) {
                    parentTransition = true;
                }
                if ("java/lang/Boolean".equals(call.owner) && "valueOf".equals(call.name)
                        && "(Z)Ljava/lang/Boolean;".equals(call.desc)) {
                    AbstractInsnNode source = previousReal(call, null);
                    booleanValue = source instanceof MethodInsnNode getter
                            && getter.getOpcode() != Opcodes.INVOKESTATIC
                            && "()Z".equals(getter.desc);
                }
                if (booleanValue && "java/util/function/Consumer".equals(call.owner)
                        && "accept".equals(call.name) && "(Ljava/lang/Object;)V".equals(call.desc)) {
                    callback = true;
                }
            }
            if (parentTransition && callback) return true;
        }
        return false;
    }

    /** Custom widgets often own their click behavior directly instead of receiving a lambda in the
     * screen constructor. Follow the widget's concrete click override and summarize its bytecode
     * exactly the same way as a lambda callback. */
    private static void emitSelfHandledWidgetFacts(ClassSurface surface, String sourceScreen,
                                                   WidgetRef widget, Map<String, ClassNode> nodes,
                                                   int confidence) {
        if (widget == null || nodes == null || widget.widgetClass() == null) return;
        ClassNode widgetNode = nodes.get(widget.widgetClass());
        if (widgetNode == null || widgetNode.methods == null) return;
        for (MethodNode method : widgetNode.methods) {
            if (!clickHandlerShape(method)) continue;
            Set<String> callStack = new HashSet<>();
            callStack.add(widgetNode.name + "#" + methodKey(method));
            MethodFacts facts = collectBehaviorRange(widgetNode.name,
                    method.instructions.getFirst(), null, nodes, callStack, 0);
            if (facts.screens.isEmpty() && facts.packets.isEmpty() && facts.stateWrites.isEmpty()
                    && facts.collectionMutations.isEmpty() && facts.clientHelpers.isEmpty()
                    && facts.runtimeLaunches.isEmpty()) continue;
            emitFactsForWidget(surface, sourceScreen, widget.widgetClass(), widget.ordinal(),
                    widget.label(), widget.x(), widget.y(), widget.width(), widget.height(),
                    methodKey(method), facts, nodes, confidence);
            attachTriggerWidgetReceiverState(surface, sourceScreen, widget, method, nodes, true);
            attachWidgetMetadata(surface, sourceScreen, widget);
        }
    }

    /**
     * Associates framework/superclass state writes with the widget that actually triggered the
     * callback. Lambda implementations may capture arbitrary objects before the SAM argument, so
     * this does not assume "last ALOAD" or a particular parameter index: the callback must expose
     * exactly one reference parameter compatible with the scanned widget hierarchy and the
     * PUTFIELD receiver must be that exact local. Self-handled widget methods instead require
     * ALOAD 0. No client object is instantiated or reflected at runtime.
     */
    private static void attachTriggerWidgetReceiverState(ClassSurface surface, String sourceScreen,
                                                         WidgetRef widget, MethodNode method,
                                                         Map<String, ClassNode> nodes,
                                                         boolean selfHandled) {
        if (surface == null || widget == null || widget.widgetClass() == null || method == null
                || method.instructions == null) return;
        Integer receiverLocal = selfHandled ? Integer.valueOf(0) : uniqueWidgetCallbackParameterLocal(
                method, widget.widgetClass(), nodes);
        if (receiverLocal == null) return;
        String methodId = methodKey(method);
        for (AbstractInsnNode instruction : method.instructions) {
            if (!(instruction instanceof FieldInsnNode field) || field.getOpcode() != Opcodes.PUTFIELD) continue;
            ParsedStateTarget target = parsedStateTarget(field.owner + "." + field.name + ':' + field.desc);
            if (target == null || !fieldOwnerCompatibleWithWidget(field.owner, widget.widgetClass(), nodes)) continue;
            AbstractInsnNode value = previousReal(field, null);
            if (value == null) continue;
            AbstractInsnNode receiver = previousReal(value, null);
            while (receiver instanceof TypeInsnNode cast && cast.getOpcode() == Opcodes.CHECKCAST) {
                receiver = previousReal(receiver, null);
            }
            if (!(receiver instanceof VarInsnNode load) || load.getOpcode() != Opcodes.ALOAD
                    || load.var != receiverLocal) continue;
            String targetText = field.owner + "." + field.name + ':' + field.desc;
            for (Action action : surface.actions) {
                if (action == null || action.type != ActionType.WRITE_STATE
                        || action.triggerType != TriggerType.WIDGET
                        || action.widgetOrdinal != widget.ordinal()
                        || !java.util.Objects.equals(action.sourceScreen, sourceScreen)
                        || !java.util.Objects.equals(action.method, methodId)
                        || !java.util.Objects.equals(action.target, targetText)) continue;
                action.stateReceiverIsTriggerWidget = true;
                action.stateTargetWidgetOrdinal = widget.ordinal();
                action.stateTargetWidgetClass = widget.widgetClass();
                action.stateTargetWidgetInteractive = true;
            }
        }
    }

    private static Integer uniqueWidgetCallbackParameterLocal(MethodNode method, String widgetClass,
                                                              Map<String, ClassNode> nodes) {
        Type[] arguments;
        try {
            arguments = Type.getArgumentTypes(method.desc);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
        int local = (method.access & Opcodes.ACC_STATIC) != 0 ? 0 : 1;
        Integer candidate = null;
        for (Type argument : arguments) {
            if (argument.getSort() == Type.OBJECT
                    && fieldOwnerCompatibleWithWidget(argument.getInternalName(), widgetClass, nodes)) {
                if (candidate != null) return null;
                candidate = local;
            }
            local += argument.getSize();
        }
        return candidate;
    }

    private static boolean fieldOwnerCompatibleWithWidget(String owner, String widgetClass,
                                                          Map<String, ClassNode> nodes) {
        if (owner == null || widgetClass == null) return false;
        if (owner.equals(widgetClass)) return true;
        ClassNode cursor = nodes == null ? null : nodes.get(widgetClass);
        Set<String> seen = new HashSet<>();
        int remaining = 32;
        while (cursor != null && remaining-- > 0 && seen.add(cursor.name)) {
            if (owner.equals(cursor.name) || owner.equals(cursor.superName)) return true;
            cursor = cursor.superName == null || nodes == null ? null : nodes.get(cursor.superName);
        }
        return false;
    }

    private static FieldInsnNode widgetVisualBefore(AbstractInsnNode instruction, int steps) {
        int remaining = steps;
        for (AbstractInsnNode previous = instruction == null ? null : instruction.getPrevious();
             previous != null && remaining-- > 0; previous = previous.getPrevious()) {
            if (!(previous instanceof FieldInsnNode field) || field.getOpcode() != Opcodes.GETSTATIC) {
                continue;
            }
            try {
                Type type = Type.getType(field.desc);
                if (type.getSort() != Type.OBJECT) continue;
            } catch (IllegalArgumentException ignored) {
                continue;
            }
            return field;
        }
        return null;
    }

    private static void attachWidgetMetadata(ClassSurface surface, String sourceScreen,
                                             WidgetRef widget) {
        if (surface == null || widget == null) return;
        for (Action action : surface.actions) {
            if (action == null || action.triggerType != TriggerType.WIDGET
                    || action.widgetOrdinal != widget.ordinal()
                    || !java.util.Objects.equals(action.sourceScreen, sourceScreen)) continue;
            if (widget.visualOwner() != null && widget.visualField() != null) {
                action.widgetVisualOwner = widget.visualOwner();
                action.widgetVisualField = widget.visualField();
            }
            action.widgetInitiallyVisible = widget.initiallyVisible();
            action.widgetInitiallyActive = widget.initiallyActive();
            action.widgetXAnchor = widget.xAnchor();
            action.widgetYAnchor = widget.yAnchor();
        }
    }

    /**
     * Recover direct framework-state assignments made after a widget is stored in a screen field.
     * This intentionally looks only at the screen init method and the exact widget field receiver,
     * so later tick/render state transitions are not flattened into the starting state. A common
     * screen pattern is `button = new ...; button.active = false; button.visible = false;`.
     */
    private static WidgetInitialState widgetInitialState(MethodNode method, FieldInsnNode widgetStore) {
        if (method == null || widgetStore == null) return new WidgetInitialState(null, null);
        Boolean visible = null;
        Boolean active = null;
        int remaining = 160;
        for (AbstractInsnNode cursor = widgetStore.getNext(); cursor != null && remaining-- > 0;
             cursor = cursor.getNext()) {
            if (!(cursor instanceof FieldInsnNode load) || load.getOpcode() != Opcodes.GETFIELD
                    || !java.util.Objects.equals(load.owner, widgetStore.owner)
                    || !java.util.Objects.equals(load.name, widgetStore.name)) continue;
            AbstractInsnNode value = nextOpcode(load.getNext());
            if (value == null) continue;
            AbstractInsnNode sink = nextOpcode(value.getNext());
            if (sink instanceof TypeInsnNode cast && cast.getOpcode() == Opcodes.CHECKCAST) {
                sink = nextOpcode(sink.getNext());
            }
            if (!(sink instanceof FieldInsnNode flag) || flag.getOpcode() != Opcodes.PUTFIELD
                    || !"Z".equals(flag.desc)) continue;
            Boolean constant = booleanConstant(value);
            if (constant == null) continue;
            WidgetStateField stateField = widgetStateField(flag);
            if (visible == null && stateField == WidgetStateField.VISIBLE) {
                visible = constant;
            } else if (active == null && stateField == WidgetStateField.ACTIVE) {
                active = constant;
            }
            if (visible != null && active != null) break;
        }
        return new WidgetInitialState(visible, active);
    }

    public enum WidgetStateField { VISIBLE, ACTIVE, UNKNOWN }

    /** Production Forge/NeoForge jars reference inherited AbstractWidget state through SRG field
     * aliases even when the mod itself was authored against named mappings. Keep those framework
     * aliases beside the semantic-name checks so this remains a generic widget scanner rather than
     * a per-mod exception. */
    private static WidgetStateField widgetStateField(FieldInsnNode field) {
        if (field == null || !"Z".equals(field.desc)) return WidgetStateField.UNKNOWN;
        return widgetStateFieldName(field.name);
    }

    private static WidgetStateField widgetStateFieldName(String fieldName) {
        String name = fieldName == null ? "" : fieldName.toLowerCase(Locale.ROOT);
        if (name.equals("visible") || name.contains("visible") || name.equals("f_93624_")) {
            return WidgetStateField.VISIBLE;
        }
        if (name.equals("active") || name.contains("active") || name.equals("enabled")
                || name.contains("enabled") || name.equals("f_93623_")) {
            return WidgetStateField.ACTIVE;
        }
        return WidgetStateField.UNKNOWN;
    }

    /** Semantic framework property for a scanner-proven widget state write. The target widget must
     * already have exact receiver provenance; callers must not infer that the clicked control and
     * the mutated control are the same instance. */
    public static WidgetStateField widgetStateField(Action action) {
        if (action == null || action.type != ActionType.WRITE_STATE || action.target == null
                || action.stateTargetWidgetOrdinal < 0) return WidgetStateField.UNKNOWN;
        ParsedStateTarget target = parsedStateTarget(action.target);
        if (target == null || !"Z".equals(target.desc())) return WidgetStateField.UNKNOWN;
        FieldInsnNode synthetic = new FieldInsnNode(Opcodes.PUTFIELD,
                target.owner(), target.name(), target.desc());
        return widgetStateField(synthetic);
    }

    /** Exact framework-state mutation of a screen-owned translated widget. The receiver field and
     * target ordinal are scanner-proven; callers must not assume the clicked widget is the target. */
    public static WidgetRuntimeState widgetRuntimeState(Action action) {
        if (action == null || action.type != ActionType.WRITE_STATE
                || action.triggerType != TriggerType.WIDGET
                || action.stateWriteKind != StateWriteKind.ASSIGN_BOOLEAN
                || action.stateBooleanValue == null || action.target == null
                || (!action.stateReceiverIsTriggerWidget
                && (action.stateReceiverFieldOwner == null || action.stateReceiverFieldName == null))
                || action.stateTargetWidgetOrdinal < 0 || action.stateTargetWidgetClass == null
                || !action.stateTargetWidgetInteractive) return null;
        int colon = action.target.lastIndexOf(':');
        int dot = colon < 0 ? -1 : action.target.lastIndexOf('.', colon);
        if (dot <= 0 || colon <= dot + 1 || !"Z".equals(action.target.substring(colon + 1))) return null;
        WidgetStateField state = widgetStateFieldName(action.target.substring(dot + 1, colon));
        return state == WidgetStateField.VISIBLE ? WidgetRuntimeState.VISIBLE
                : state == WidgetStateField.ACTIVE ? WidgetRuntimeState.ENABLED : null;
    }

    private static AbstractInsnNode nextOpcode(AbstractInsnNode instruction) {
        for (AbstractInsnNode cursor = instruction; cursor != null; cursor = cursor.getNext()) {
            if (cursor.getOpcode() >= 0) return cursor;
        }
        return null;
    }

    private static Boolean booleanConstant(AbstractInsnNode instruction) {
        if (instruction == null) return null;
        return switch (instruction.getOpcode()) {
            case Opcodes.ICONST_0 -> Boolean.FALSE;
            case Opcodes.ICONST_1 -> Boolean.TRUE;
            default -> null;
        };
    }

    /**
     * Decode state-specific widget visibility/enabled rules from ordinary screen tick/update code.
     * Only paths rooted in the screen's authoritative BlockEntity/Entity field survive into the
     * predicate. Client-only caches, animation counters, Minecraft singletons and renderer objects
     * deliberately resolve to null and therefore cannot become server execution dependencies.
     */
    private static void attachLiveWidgetStatePredicates(ClassSurface surface, ClassNode owner,
                                                        Map<String, ClassNode> nodes,
                                                        Map<String, WidgetRef> widgetFields) {
        if (surface == null || owner == null || owner.methods == null || widgetFields == null
                || widgetFields.isEmpty()) return;

        Map<String, WidgetStateAccumulator> states = new HashMap<>();
        for (Map.Entry<String, WidgetRef> entry : widgetFields.entrySet()) {
            states.put(entry.getKey(), new WidgetStateAccumulator(entry.getValue()));
        }

        for (MethodNode method : widgetStateUpdateMethods(owner, nodes, widgetFields.keySet())) {
            if (method == null || method.instructions == null) continue;
            for (AbstractInsnNode instruction : method.instructions) {
                if (!(instruction instanceof FieldInsnNode stateWrite)
                        || stateWrite.getOpcode() != Opcodes.PUTFIELD || !"Z".equals(stateWrite.desc)) {
                    continue;
                }
                WidgetStateField stateField = widgetStateField(stateWrite);
                if (stateField == WidgetStateField.UNKNOWN) continue;
                FieldInsnNode widgetLoad = widgetReceiverBeforeStateWrite(
                        stateWrite, widgetFields.keySet(), 96);
                if (widgetLoad == null) continue;
                WidgetStateAccumulator accumulator = states.get(fieldKey(widgetLoad.owner, widgetLoad.name));
                if (accumulator == null) continue;

                RendererBytecodeScanner.BooleanPredicate value = screenBooleanValueAtSink(
                        owner, method, stateWrite, nodes, 0, new HashSet<>());
                if (value == null) continue;
                RendererBytecodeScanner.BooleanPredicate guard = screenGuardingPredicate(
                        owner, method, widgetLoad, nodes, 0, new HashSet<>());
                accumulator.accept(stateField, value, guard);
            }
        }

        for (Map.Entry<String, WidgetRef> entry : widgetFields.entrySet()) {
            WidgetRef widget = entry.getValue();
            WidgetStateAccumulator accumulator = states.get(entry.getKey());
            if (widget == null || accumulator == null) continue;
            WidgetLiveState live = accumulator.result();
            if (live.visible() == null && live.active() == null) continue;
            for (Action action : surface.actions) {
                if (action == null || action.triggerType != TriggerType.WIDGET
                        || action.widgetOrdinal != widget.ordinal()
                        || !java.util.Objects.equals(action.sourceScreen, owner.name)) continue;
                action.widgetVisiblePredicate = live.visible();
                action.widgetActivePredicate = live.active();
            }
        }
    }

    private static final class WidgetStateAccumulator {
        private RendererBytecodeScanner.BooleanPredicate visible;
        private RendererBytecodeScanner.BooleanPredicate active;
        private boolean visibleEvidence;
        private boolean activeEvidence;

        WidgetStateAccumulator(WidgetRef widget) {
            // AbstractWidget defaults both flags to true. Explicit init assignments override that.
            this.visible = widgetConstant(widget != null
                    && Boolean.FALSE.equals(widget.initiallyVisible()) ? false : true);
            this.active = widgetConstant(widget != null
                    && Boolean.FALSE.equals(widget.initiallyActive()) ? false : true);
        }

        void accept(WidgetStateField field, RendererBytecodeScanner.BooleanPredicate value,
                    RendererBytecodeScanner.BooleanPredicate guard) {
            if (field == WidgetStateField.VISIBLE) {
                PredicateUpdate next = updateState(visible, visibleEvidence, value, guard);
                visible = next.value();
                visibleEvidence = next.evidence();
            } else if (field == WidgetStateField.ACTIVE) {
                PredicateUpdate next = updateState(active, activeEvidence, value, guard);
                active = next.value();
                activeEvidence = next.evidence();
            }
        }

        WidgetLiveState result() {
            return new WidgetLiveState(visibleEvidence ? visible : null,
                    activeEvidence ? active : null);
        }
    }

    private record PredicateUpdate(RendererBytecodeScanner.BooleanPredicate value, boolean evidence) {}

    /** A guarded assignment is an ordinary state override: `state = guard ? value : state`.
     * Unresolved client-only transition constants (animation teardown is a common example) must not
     * erase a server-derived live rule, so constants without a server guard are only accepted until
     * stronger evidence has been found. Dynamic server-rooted values remain authoritative. */
    private static PredicateUpdate updateState(RendererBytecodeScanner.BooleanPredicate current,
                                               boolean evidence,
                                               RendererBytecodeScanner.BooleanPredicate value,
                                               RendererBytecodeScanner.BooleanPredicate guard) {
        if (value == null) return new PredicateUpdate(current, evidence);
        if (guard != null) {
            return new PredicateUpdate(widgetChoose(guard, value, current), true);
        }
        if (!widgetIsConstant(value) || !evidence) {
            return new PredicateUpdate(value, true);
        }
        return new PredicateUpdate(current, evidence);
    }

    /** Prefer tick/update call graphs. If names are fully obfuscated, fall back to methods which
     * actually write one of the discovered widget fields; this remains structural rather than
     * mod-specific. */
    private static Set<MethodNode> widgetStateUpdateMethods(ClassNode owner,
                                                            Map<String, ClassNode> nodes,
                                                            Set<String> widgetFields) {
        LinkedHashSet<MethodNode> selected = new LinkedHashSet<>();
        ArrayList<MethodNode> queue = new ArrayList<>();
        for (MethodNode method : owner.methods) {
            if (method == null || method.instructions == null || "<init>".equals(method.name)
                    || "<clinit>".equals(method.name) || looksLikeWidgetInit(method)) continue;
            String name = method.name == null ? "" : method.name.toLowerCase(Locale.ROOT);
            if (name.contains("tick") || name.contains("update") || name.contains("refresh")) {
                if (selected.add(method)) queue.add(method);
            }
        }
        for (int index = 0; index < queue.size() && index < 64; index++) {
            MethodNode method = queue.get(index);
            for (AbstractInsnNode instruction : method.instructions) {
                if (!(instruction instanceof MethodInsnNode call) || "<init>".equals(call.name)) continue;
                MethodNode local = findMethodInHierarchy(owner, call.name, call.desc, nodes);
                if (local != null && selected.add(local)) queue.add(local);
            }
        }
        boolean writes = selected.stream().anyMatch(method -> methodWritesWidgetState(method, widgetFields));
        if (!writes) {
            selected.clear();
            for (MethodNode method : owner.methods) {
                if (method == null || method.instructions == null || "<init>".equals(method.name)
                        || "<clinit>".equals(method.name) || looksLikeWidgetInit(method)) continue;
                if (methodWritesWidgetState(method, widgetFields)) selected.add(method);
            }
        }
        return selected;
    }

    private static boolean methodWritesWidgetState(MethodNode method, Set<String> widgetFields) {
        if (method == null || method.instructions == null || widgetFields == null || widgetFields.isEmpty()) {
            return false;
        }
        for (AbstractInsnNode instruction : method.instructions) {
            if (!(instruction instanceof FieldInsnNode stateWrite)
                    || stateWrite.getOpcode() != Opcodes.PUTFIELD || !"Z".equals(stateWrite.desc)
                    || widgetStateField(stateWrite) == WidgetStateField.UNKNOWN) continue;
            if (widgetReceiverBeforeStateWrite(stateWrite, widgetFields, 96) != null) return true;
        }
        return false;
    }

    private static FieldInsnNode widgetReceiverBeforeStateWrite(FieldInsnNode stateWrite,
                                                                Set<String> widgetFields,
                                                                int limit) {
        for (AbstractInsnNode cursor = previousOpcode(stateWrite); cursor != null && limit-- > 0;
             cursor = previousOpcode(cursor)) {
            if (cursor instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETFIELD
                    && widgetFields.contains(fieldKey(field.owner, field.name))) return field;
        }
        return null;
    }

    /** Decode a boolean value on the stack immediately before a widget boolean PUTFIELD. */
    private static RendererBytecodeScanner.BooleanPredicate screenBooleanValueAtSink(
            ClassNode screen, MethodNode method, FieldInsnNode sink, Map<String, ClassNode> nodes,
            int depth, Set<String> visiting) {
        if (screen == null || method == null || sink == null || depth > 10) return null;
        RendererBytecodeScanner.BooleanPredicate merged = screenMergedBooleanBeforeSink(
                screen, method, sink, nodes, depth + 1, visiting);
        if (merged != null) return merged;
        return screenBooleanValuePredicate(screen, method, previousOpcode(sink), nodes,
                depth + 1, visiting);
    }

    /** javac lowers `a && b && c` assigned to a field to several conditional jumps into one false
     * constant, with the true constant jumping directly to the PUTFIELD. Recover that merge rather
     * than seeing only the final ICONST_0. The inverse shape recovers ordinary OR expressions. */
    private static RendererBytecodeScanner.BooleanPredicate screenMergedBooleanBeforeSink(
            ClassNode screen, MethodNode method, FieldInsnNode sink, Map<String, ClassNode> nodes,
            int depth, Set<String> visiting) {
        AbstractInsnNode alternateInsn = previousOpcode(sink);
        Boolean alternate = booleanConstant(alternateInsn);
        if (alternate == null) return null;
        int sinkIndex = method.instructions.indexOf(sink);
        int alternateIndex = method.instructions.indexOf(alternateInsn);
        JumpInsnNode join = null;
        int scanned = 0;
        for (AbstractInsnNode cursor = previousOpcode(alternateInsn); cursor != null && scanned++ < 96;
             cursor = previousOpcode(cursor)) {
            if (!(cursor instanceof JumpInsnNode jump) || jump.getOpcode() != Opcodes.GOTO) continue;
            if (firstOpcodeIndex(method, method.instructions.indexOf(jump.label)) == sinkIndex) {
                join = jump;
                break;
            }
        }
        if (join == null) return null;
        Boolean fall = booleanConstant(previousOpcode(join));
        if (fall == null) return null;
        if (fall.equals(alternate)) return widgetConstant(fall);

        int fallIndex = method.instructions.indexOf(previousOpcode(join));
        ArrayList<RendererBytecodeScanner.BooleanPredicate> trueBranches = new ArrayList<>();
        ArrayList<RendererBytecodeScanner.BooleanPredicate> falseBranches = new ArrayList<>();
        boolean unresolvedFalseBranch = false;
        int joinIndex = method.instructions.indexOf(join);
        int start = Math.max(0, joinIndex - 120);
        for (int i = start; i < joinIndex; i++) {
            AbstractInsnNode instruction = method.instructions.get(i);
            if (!(instruction instanceof JumpInsnNode branch) || !screenBooleanJump(branch.getOpcode())) continue;
            int target = firstOpcodeIndex(method, method.instructions.indexOf(branch.label));
            Boolean targetValue;
            if (target == alternateIndex) targetValue = alternate;
            else if (target == fallIndex) targetValue = fall;
            else continue;
            RendererBytecodeScanner.BooleanPredicate taken = screenJumpTakenPredicate(
                    screen, method, branch, nodes, depth + 1, visiting);
            if (taken == null) {
                if (!targetValue) unresolvedFalseBranch = true;
                continue;
            }
            (targetValue ? trueBranches : falseBranches).add(taken);
        }
        if (trueBranches.isEmpty() && falseBranches.isEmpty()) return null;

        // javac's merged boolean block has one ordinary fall-through constant plus zero or more
        // short-circuit jumps to either constant. Reconstruct both sides: false-target branches
        // gate a true fall-through, while true-target branches are independent early successes.
        // If reaching the true fall-through also depends on a client-only/unresolved false branch,
        // do not turn that unknown path into unconditional true. Keep the server-provable early
        // true branches as a conservative lower bound instead (for example count > 0 || cache != null).
        RendererBytecodeScanner.BooleanPredicate result = widgetConstant(fall && !unresolvedFalseBranch);
        if (fall && !unresolvedFalseBranch) {
            for (RendererBytecodeScanner.BooleanPredicate condition : falseBranches) {
                result = widgetCombine(RendererBytecodeScanner.BooleanOp.AND,
                        result, widgetNegate(condition));
            }
        }
        for (RendererBytecodeScanner.BooleanPredicate condition : trueBranches) {
            result = widgetCombine(RendererBytecodeScanner.BooleanOp.OR, result, condition);
        }
        return result;
    }

    private static RendererBytecodeScanner.BooleanPredicate screenBooleanValuePredicate(
            ClassNode screen, MethodNode method, AbstractInsnNode producer, Map<String, ClassNode> nodes,
            int depth, Set<String> visiting) {
        if (producer == null || depth > 12) return null;
        Boolean constant = booleanConstant(producer);
        if (constant != null) return widgetConstant(constant);
        if (producer instanceof TypeInsnNode cast && cast.getOpcode() == Opcodes.CHECKCAST) {
            return screenBooleanValuePredicate(screen, method, previousOpcode(cast), nodes,
                    depth + 1, visiting);
        }
        if (producer instanceof VarInsnNode load && load.getOpcode() == Opcodes.ILOAD) {
            AbstractInsnNode stored = localValueProducer(method, load, Opcodes.ISTORE);
            return stored == null ? null : screenBooleanValuePredicate(screen, method, stored, nodes,
                    depth + 1, visiting);
        }
        if (producer instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETFIELD
                && "Z".equals(field.desc)) {
            String receiver = screenObjectPathBefore(screen, method, field, nodes, depth + 1);
            return receiver == null ? null : widgetSource(appendWidgetStep(receiver,
                    "F#" + field.owner + '#' + field.name + '#' + field.desc));
        }
        if (producer instanceof MethodInsnNode call && booleanReturnDescriptor(call.desc)) {
            if (call.getOpcode() != Opcodes.INVOKESTATIC
                    && Type.getArgumentTypes(call.desc).length == 0) {
                String receiver = screenObjectPathBefore(screen, method, call, nodes, depth + 1);
                if (receiver != null) {
                    return widgetSource(appendWidgetStep(receiver,
                            "M#" + call.owner + '#' + call.name + '#' + call.desc));
                }
                if (screenThisReceiver(method, call)) {
                    MethodNode helper = findMethodInHierarchy(screen, call.name, call.desc, nodes);
                    if (helper != null) {
                        return decodeScreenBooleanMethod(screen, helper, nodes, depth + 1, visiting);
                    }
                }
            }
        }
        return null;
    }

    private static RendererBytecodeScanner.BooleanPredicate decodeScreenBooleanMethod(
            ClassNode screen, MethodNode method, Map<String, ClassNode> nodes, int depth,
            Set<String> visiting) {
        if (screen == null || method == null || method.instructions == null || depth > 12) return null;
        String key = screen.name + '#' + method.name + method.desc + '@' + System.identityHashCode(method);
        if (!visiting.add(key)) return null;
        try {
            return screenBooleanFlow(screen, method, firstOpcodeIndex(method, 0), null,
                    nodes, depth + 1, visiting, new HashSet<>());
        } finally {
            visiting.remove(key);
        }
    }

    private static RendererBytecodeScanner.BooleanPredicate screenBooleanFlow(
            ClassNode screen, MethodNode method, int start,
            RendererBytecodeScanner.BooleanPredicate pending, Map<String, ClassNode> nodes,
            int depth, Set<String> visiting, Set<Integer> path) {
        if (start < 0 || start >= method.instructions.size() || depth > 20 || !path.add(start)) return null;
        try {
            RendererBytecodeScanner.BooleanPredicate value = pending;
            for (int i = start; i < method.instructions.size(); i++) {
                AbstractInsnNode instruction = method.instructions.get(i);
                int opcode = instruction.getOpcode();
                if (opcode < 0) continue;
                if (opcode == Opcodes.ICONST_0 || opcode == Opcodes.ICONST_1
                        || instruction instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETFIELD
                        && "Z".equals(field.desc)
                        || instruction instanceof MethodInsnNode call && booleanReturnDescriptor(call.desc)
                        || instruction instanceof VarInsnNode load && load.getOpcode() == Opcodes.ILOAD) {
                    RendererBytecodeScanner.BooleanPredicate decoded = screenBooleanValuePredicate(
                            screen, method, instruction, nodes, depth + 1, visiting);
                    if (decoded != null) value = decoded;
                }
                if (instruction instanceof JumpInsnNode jump) {
                    if (opcode == Opcodes.GOTO) {
                        return screenBooleanFlow(screen, method,
                                firstOpcodeIndex(method, method.instructions.indexOf(jump.label)), value,
                                nodes, depth + 1, visiting, new HashSet<>(path));
                    }
                    if (screenBooleanJump(opcode)) {
                        RendererBytecodeScanner.BooleanPredicate condition = screenJumpTakenPredicate(
                                screen, method, jump, nodes, depth + 1, visiting);
                        if (condition == null) return null;
                        int destination = firstOpcodeIndex(method, method.instructions.indexOf(jump.label));
                        int fallthrough = firstOpcodeIndex(method, i + 1);
                        RendererBytecodeScanner.BooleanPredicate taken = screenBooleanFlow(
                                screen, method, destination, null, nodes, depth + 1,
                                visiting, new HashSet<>(path));
                        RendererBytecodeScanner.BooleanPredicate fall = screenBooleanFlow(
                                screen, method, fallthrough, null, nodes, depth + 1,
                                visiting, new HashSet<>(path));
                        return widgetChoose(condition, taken, fall);
                    }
                }
                if (opcode == Opcodes.IRETURN) {
                    return value != null ? value : screenBooleanValuePredicate(
                            screen, method, previousOpcode(instruction), nodes, depth + 1, visiting);
                }
                if (opcode == Opcodes.RETURN || opcode == Opcodes.ATHROW) return null;
            }
            return null;
        } finally {
            path.remove(start);
        }
    }

    /** Predicate for executing the fall-through region which contains target. */
    /** Exact normal-control-flow condition for reaching one draw. Unlike the widget-update
     * heuristic, this composes all enclosing guards and both if/else paths. Unknown branches may
     * only be discarded when both successors have the identical result. No client class runs. */
    static RendererBytecodeScanner.BooleanPredicate screenDrawPredicate(
            ClassNode screen, MethodNode method, AbstractInsnNode target, Map<String, ClassNode> nodes) {
        if (screen == null || method == null || target == null || method.instructions.size() > 8192
                || method.tryCatchBlocks != null && !method.tryCatchBlocks.isEmpty()) return null;
        return screenDrawFlow(screen, method, target, nodes, 0, new HashMap<>(), new HashSet<>(),
                new int[]{4096}, 0);
    }

    private static RendererBytecodeScanner.BooleanPredicate screenDrawFlow(
            ClassNode screen, MethodNode method, AbstractInsnNode target, Map<String, ClassNode> nodes,
            int start, Map<Integer, RendererBytecodeScanner.BooleanPredicate> memo,
            Set<Integer> visiting, int[] budget, int depth) {
        if (start < 0 || start >= method.instructions.size() || depth > 128) return null;
        if (memo.containsKey(start)) return memo.get(start);
        if (!visiting.add(start)) return null; // loops need a stateful interpreter
        RendererBytecodeScanner.BooleanPredicate result = null;
        try {
            for (int i = start; i < method.instructions.size() && budget[0]-- > 0; i++) {
                AbstractInsnNode instruction = method.instructions.get(i);
                if (instruction == target) { result = widgetConstant(true); break; }
                int opcode = instruction.getOpcode();
                if (opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN || opcode == Opcodes.ATHROW) {
                    result = widgetConstant(false); break;
                }
                if (instruction instanceof JumpInsnNode jump) {
                    var taken = screenDrawFlow(screen, method, target, nodes,
                            method.instructions.indexOf(jump.label), memo, visiting, budget, depth + 1);
                    if (opcode == Opcodes.GOTO) { result = taken; break; }
                    if (opcode == Opcodes.JSR) break;
                    var fall = screenDrawFlow(screen, method, target, nodes,
                            i + 1, memo, visiting, budget, depth + 1);
                    if (taken != null && fall != null && taken.toString().equals(fall.toString())) {
                        result = taken;
                    } else {
                        var condition = screenJumpTakenPredicate(screen, method, jump, nodes, 0, new HashSet<>());
                        result = widgetChoose(condition, taken, fall);
                    }
                    break;
                }
                if (instruction instanceof org.objectweb.asm.tree.TableSwitchInsnNode
                        || instruction instanceof org.objectweb.asm.tree.LookupSwitchInsnNode
                        || opcode == Opcodes.RET) break;
            }
            memo.put(start, result);
            return result;
        } finally { visiting.remove(start); }
    }

    private static RendererBytecodeScanner.BooleanPredicate screenGuardingPredicate(
            ClassNode screen, MethodNode method, AbstractInsnNode target, Map<String, ClassNode> nodes,
            int depth, Set<String> visiting) {
        if (screen == null || method == null || target == null || depth > 10) return null;
        int targetIndex = method.instructions.indexOf(target);
        JumpInsnNode best = null;
        int bestSpan = Integer.MAX_VALUE;
        for (AbstractInsnNode instruction : method.instructions) {
            if (!(instruction instanceof JumpInsnNode jump) || !screenBooleanJump(jump.getOpcode())) continue;
            int branch = method.instructions.indexOf(jump);
            int destination = firstOpcodeIndex(method, method.instructions.indexOf(jump.label));
            if (branch < 0 || branch >= targetIndex || destination <= targetIndex) continue;
            int span = destination - branch;
            if (span < bestSpan) {
                best = jump;
                bestSpan = span;
            }
        }
        if (best == null) return null;
        RendererBytecodeScanner.BooleanPredicate taken = screenJumpTakenPredicate(
                screen, method, best, nodes, depth + 1, visiting);
        return taken == null ? null : widgetNegate(taken);
    }

    private static RendererBytecodeScanner.BooleanPredicate screenJumpTakenPredicate(
            ClassNode screen, MethodNode method, JumpInsnNode jump, Map<String, ClassNode> nodes,
            int depth, Set<String> visiting) {
        if (jump == null || depth > 14) return null;
        AbstractInsnNode producer = previousOpcode(jump);
        return switch (jump.getOpcode()) {
            case Opcodes.IFEQ -> widgetNegate(screenBooleanValuePredicate(
                    screen, method, producer, nodes, depth + 1, visiting));
            case Opcodes.IFNE -> screenBooleanValuePredicate(
                    screen, method, producer, nodes, depth + 1, visiting);
            case Opcodes.IFNULL -> widgetNegate(screenObjectNotNullPredicate(
                    screen, method, producer, nodes, depth + 1, visiting));
            case Opcodes.IFNONNULL -> screenObjectNotNullPredicate(
                    screen, method, producer, nodes, depth + 1, visiting);
            case Opcodes.IFGT -> screenNumericZeroPredicate(
                    screen, method, producer, nodes, "GT", depth + 1);
            case Opcodes.IFGE -> screenNumericZeroPredicate(
                    screen, method, producer, nodes, "GE", depth + 1);
            case Opcodes.IFLT -> screenNumericZeroPredicate(
                    screen, method, producer, nodes, "LT", depth + 1);
            case Opcodes.IFLE -> screenNumericZeroPredicate(
                    screen, method, producer, nodes, "LE", depth + 1);
            default -> null;
        };
    }

    private static boolean screenBooleanJump(int opcode) {
        return opcode == Opcodes.IFEQ || opcode == Opcodes.IFNE
                || opcode == Opcodes.IFNULL || opcode == Opcodes.IFNONNULL
                || opcode == Opcodes.IFGT || opcode == Opcodes.IFGE
                || opcode == Opcodes.IFLT || opcode == Opcodes.IFLE;
    }

    /** JVM unary integer comparisons consume one numeric value and compare it with zero. Keep the
     * exact server-rooted access path so runtime can evaluate the same condition without loading
     * the client screen class. */
    private static RendererBytecodeScanner.BooleanPredicate screenNumericZeroPredicate(
            ClassNode screen, MethodNode method, AbstractInsnNode producer, Map<String, ClassNode> nodes,
            String comparison, int depth) {
        if (producer == null || comparison == null || depth > 14) return null;
        if (producer instanceof VarInsnNode load && load.getOpcode() == Opcodes.ILOAD) {
            AbstractInsnNode stored = localValueProducer(method, load, Opcodes.ISTORE);
            return stored == null ? null : screenNumericZeroPredicate(
                    screen, method, stored, nodes, comparison, depth + 1);
        }
        String source = screenObjectPath(screen, method, producer, nodes, depth + 1);
        if (source == null || source.isBlank()) return null;
        RendererBytecodeScanner.BooleanPredicate predicate =
                new RendererBytecodeScanner.BooleanPredicate();
        predicate.op = RendererBytecodeScanner.BooleanOp.NUMERIC_COMPARE;
        predicate.source = source;
        predicate.comparison = comparison;
        predicate.threshold = 0.0;
        return predicate;
    }

    private static RendererBytecodeScanner.BooleanPredicate screenObjectNotNullPredicate(
            ClassNode screen, MethodNode method, AbstractInsnNode producer, Map<String, ClassNode> nodes,
            int depth, Set<String> visiting) {
        if (producer == null || depth > 14) return null;
        if (producer.getOpcode() == Opcodes.ACONST_NULL) return widgetConstant(false);
        if (producer instanceof TypeInsnNode cast && cast.getOpcode() == Opcodes.CHECKCAST) {
            return screenObjectNotNullPredicate(screen, method, previousOpcode(cast), nodes,
                    depth + 1, visiting);
        }
        if (producer instanceof VarInsnNode load && load.getOpcode() == Opcodes.ALOAD) {
            AbstractInsnNode stored = localValueProducer(method, load, Opcodes.ASTORE);
            return stored == null ? null : screenObjectNotNullPredicate(
                    screen, method, stored, nodes, depth + 1, visiting);
        }
        String path = screenObjectPath(screen, method, producer, nodes, depth + 1);
        if (path != null && !path.isBlank()) return widgetNotNull(path);
        if (producer instanceof MethodInsnNode call && call.getOpcode() != Opcodes.INVOKESTATIC
                && Type.getArgumentTypes(call.desc).length == 0
                && objectReturnDescriptor(call.desc) && screenThisReceiver(method, call)) {
            MethodNode helper = findMethodInHierarchy(screen, call.name, call.desc, nodes);
            if (helper != null) return screenObjectPresenceFromMethod(
                    screen, helper, nodes, depth + 1, visiting);
        }
        return null;
    }

    /** Object helpers such as getImminent() often gate a client lookup with a server field:
     * `if (be.uuid == null) return null; return clientMap.get(be.uuid)`. For server UI state the
     * authoritative presence bit is the server-rooted guard, not the client map lookup. */
    private static RendererBytecodeScanner.BooleanPredicate screenObjectPresenceFromMethod(
            ClassNode screen, MethodNode method, Map<String, ClassNode> nodes, int depth,
            Set<String> visiting) {
        if (method == null || method.instructions == null || depth > 14) return null;
        String key = "object:" + screen.name + '#' + method.name + method.desc
                + '@' + System.identityHashCode(method);
        if (!visiting.add(key)) return null;
        try {
            for (AbstractInsnNode instruction : method.instructions) {
                if (!(instruction instanceof JumpInsnNode jump)
                        || jump.getOpcode() != Opcodes.IFNULL && jump.getOpcode() != Opcodes.IFNONNULL) {
                    continue;
                }
                RendererBytecodeScanner.BooleanPredicate taken = screenJumpTakenPredicate(
                        screen, method, jump, nodes, depth + 1, visiting);
                if (taken == null) continue;
                int branch = method.instructions.indexOf(jump);
                int destination = firstOpcodeIndex(method, method.instructions.indexOf(jump.label));
                int fallthrough = firstOpcodeIndex(method, branch + 1);
                boolean takenNull = branchStartsWithNull(method, destination, 8);
                boolean fallNull = branchStartsWithNull(method, fallthrough, 8);
                if (fallNull && !takenNull) return taken;
                if (takenNull && !fallNull) return widgetNegate(taken);
            }
            return null;
        } finally {
            visiting.remove(key);
        }
    }

    private static boolean branchStartsWithNull(MethodNode method, int start, int limit) {
        if (method == null || start < 0) return false;
        int seen = 0;
        for (int i = start; i < method.instructions.size() && seen++ < limit; i++) {
            AbstractInsnNode instruction = method.instructions.get(i);
            if (instruction.getOpcode() < 0) continue;
            if (instruction.getOpcode() == Opcodes.ACONST_NULL) return true;
            if (instruction.getOpcode() == Opcodes.ARETURN || instruction.getOpcode() == Opcodes.GOTO) break;
        }
        return false;
    }

    /** Build a server-target-rooted F#/M# path. A Screen field whose type is a BlockEntity/Entity is
     * treated as the root and deliberately omitted from the path because runtime already receives
     * that live server object. */
    private static String screenObjectPathBefore(ClassNode screen, MethodNode method,
                                                 AbstractInsnNode consumer,
                                                 Map<String, ClassNode> nodes, int depth) {
        return screenObjectPath(screen, method, previousOpcode(consumer), nodes, depth + 1);
    }

    private static String screenObjectPath(ClassNode screen, MethodNode method,
                                           AbstractInsnNode producer,
                                           Map<String, ClassNode> nodes, int depth) {
        if (screen == null || method == null || producer == null || depth > 12) return null;
        if (producer instanceof TypeInsnNode cast && cast.getOpcode() == Opcodes.CHECKCAST) {
            return screenObjectPath(screen, method, previousOpcode(cast), nodes, depth + 1);
        }
        if (producer instanceof VarInsnNode load && load.getOpcode() == Opcodes.ALOAD) {
            if (load.var == 0) return null; // the Screen itself is never a runtime predicate root
            AbstractInsnNode stored = localValueProducer(method, load, Opcodes.ASTORE);
            return stored == null ? null : screenObjectPath(screen, method, stored, nodes, depth + 1);
        }
        if (producer instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETFIELD) {
            AbstractInsnNode receiverInsn = previousOpcode(field);
            if (receiverInsn instanceof VarInsnNode load && load.getOpcode() == Opcodes.ALOAD
                    && load.var == 0 && serverTargetRootField(field, nodes)) {
                return "";
            }
            String receiver = screenObjectPath(screen, method, receiverInsn, nodes, depth + 1);
            if (receiver == null) return null;
            return appendWidgetStep(receiver,
                    "F#" + field.owner + '#' + field.name + '#' + field.desc);
        }
        if (producer instanceof MethodInsnNode call && call.getOpcode() != Opcodes.INVOKESTATIC
                && Type.getArgumentTypes(call.desc).length == 0 && objectReturnDescriptor(call.desc)) {
            String receiver = screenObjectPathBefore(screen, method, call, nodes, depth + 1);
            if (receiver == null) return null;
            return appendWidgetStep(receiver,
                    "M#" + call.owner + '#' + call.name + '#' + call.desc);
        }
        return null;
    }

    private static boolean serverTargetRootField(FieldInsnNode field, Map<String, ClassNode> nodes) {
        if (field == null || field.desc == null) return false;
        String type;
        try {
            Type descriptor = Type.getType(field.desc);
            if (descriptor.getSort() != Type.OBJECT) return false;
            type = descriptor.getInternalName();
        } catch (IllegalArgumentException ignored) {
            return false;
        }
        String lowerName = field.name == null ? "" : field.name.toLowerCase(Locale.ROOT);
        if (lowerName.contains("blockentity") || lowerName.contains("tileentity")) return true;
        return likelyServerTargetType(type, nodes, new HashSet<>(), 0);
    }

    private static boolean likelyServerTargetType(String type, Map<String, ClassNode> nodes,
                                                  Set<String> visiting, int depth) {
        if (type == null || depth > 12 || !visiting.add(type)) return false;
        if (type.equals("net/minecraft/world/level/block/entity/BlockEntity")
                || type.equals("net/minecraft/world/entity/Entity")
                || type.endsWith("BlockEntity") || type.endsWith("TileEntity")) return true;
        ClassNode node = nodes == null ? null : nodes.get(type);
        if (node == null) return false;
        if (likelyServerTargetType(node.superName, nodes, visiting, depth + 1)) return true;
        if (node.interfaces != null) {
            for (String iface : node.interfaces) {
                if (likelyServerTargetType(iface, nodes, visiting, depth + 1)) return true;
            }
        }
        return false;
    }

    private static boolean screenThisReceiver(MethodNode method, MethodInsnNode call) {
        if (method == null || call == null || call.getOpcode() == Opcodes.INVOKESTATIC
                || Type.getArgumentTypes(call.desc).length != 0) return false;
        AbstractInsnNode receiver = previousOpcode(call);
        return receiver instanceof VarInsnNode load && load.getOpcode() == Opcodes.ALOAD && load.var == 0;
    }

    private static MethodNode findMethodInHierarchy(ClassNode concrete, String name, String desc,
                                                    Map<String, ClassNode> nodes) {
        ResolvedMethod resolved = resolveMethodInHierarchy(concrete, name, desc, nodes);
        return resolved == null ? null : resolved.method();
    }

    private record ResolvedMethod(String owner, MethodNode method) {}

    /** Resolve both the bytecode body and the class that actually declares it. Recursive client
     * behavior scanning needs the declaring owner: invokevirtual constant-pool owners are often a
     * concrete Screen even when the implementation lives several superclasses up. */
    private static ResolvedMethod resolveMethodInHierarchy(ClassNode concrete, String name, String desc,
                                                           Map<String, ClassNode> nodes) {
        for (ClassNode current = concrete; current != null; current = nodes == null
                ? null : nodes.get(current.superName)) {
            MethodNode method = findMethod(current, name, desc);
            if (method != null) return new ResolvedMethod(current.name, method);
        }
        return null;
    }

    private static boolean booleanReturnDescriptor(String descriptor) {
        try {
            return descriptor != null && Type.getReturnType(descriptor).getSort() == Type.BOOLEAN;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private static boolean objectReturnDescriptor(String descriptor) {
        try {
            if (descriptor == null) return false;
            int sort = Type.getReturnType(descriptor).getSort();
            return sort == Type.OBJECT || sort == Type.ARRAY;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private static AbstractInsnNode previousOpcode(AbstractInsnNode instruction) {
        for (AbstractInsnNode cursor = instruction == null ? null : instruction.getPrevious();
             cursor != null; cursor = cursor.getPrevious()) {
            if (cursor.getOpcode() >= 0) return cursor;
        }
        return null;
    }

    private static AbstractInsnNode localValueProducer(MethodNode method, VarInsnNode load,
                                                       int storeOpcode) {
        if (method == null || load == null) return null;
        for (AbstractInsnNode cursor = previousOpcode(load); cursor != null; cursor = previousOpcode(cursor)) {
            if (cursor instanceof VarInsnNode store && store.getOpcode() == storeOpcode
                    && store.var == load.var) return previousOpcode(store);
        }
        return null;
    }

    private static int firstOpcodeIndex(MethodNode method, int from) {
        if (method == null) return -1;
        for (int i = Math.max(0, from); i < method.instructions.size(); i++) {
            if (method.instructions.get(i).getOpcode() >= 0) return i;
        }
        return -1;
    }

    private static String appendWidgetStep(String path, String step) {
        if (path == null || step == null) return null;
        return path.isBlank() ? step : path + '>' + step;
    }

    private static RendererBytecodeScanner.BooleanPredicate widgetConstant(boolean value) {
        RendererBytecodeScanner.BooleanPredicate predicate = new RendererBytecodeScanner.BooleanPredicate();
        predicate.op = RendererBytecodeScanner.BooleanOp.CONSTANT;
        predicate.constant = value;
        return predicate;
    }

    private static RendererBytecodeScanner.BooleanPredicate widgetSource(String source) {
        if (source == null || source.isBlank()) return null;
        RendererBytecodeScanner.BooleanPredicate predicate = new RendererBytecodeScanner.BooleanPredicate();
        predicate.op = RendererBytecodeScanner.BooleanOp.SOURCE;
        predicate.source = source;
        return predicate;
    }

    private static RendererBytecodeScanner.BooleanPredicate widgetNotNull(String source) {
        if (source == null || source.isBlank()) return null;
        RendererBytecodeScanner.BooleanPredicate predicate = new RendererBytecodeScanner.BooleanPredicate();
        predicate.op = RendererBytecodeScanner.BooleanOp.NOT_NULL;
        predicate.source = source;
        return predicate;
    }

    private static RendererBytecodeScanner.BooleanPredicate widgetNegate(
            RendererBytecodeScanner.BooleanPredicate value) {
        if (value == null) return null;
        if (value.op == RendererBytecodeScanner.BooleanOp.CONSTANT) {
            return widgetConstant(!value.constant);
        }
        if (value.op == RendererBytecodeScanner.BooleanOp.NOT
                && value.terms != null && value.terms.size() == 1) return value.terms.get(0);
        RendererBytecodeScanner.BooleanPredicate predicate = new RendererBytecodeScanner.BooleanPredicate();
        predicate.op = RendererBytecodeScanner.BooleanOp.NOT;
        predicate.terms = new ArrayList<>(List.of(value));
        return predicate;
    }

    private static RendererBytecodeScanner.BooleanPredicate widgetCombine(
            RendererBytecodeScanner.BooleanOp op,
            RendererBytecodeScanner.BooleanPredicate first,
            RendererBytecodeScanner.BooleanPredicate second) {
        if (first == null) return second;
        if (second == null) return first;
        if (op == RendererBytecodeScanner.BooleanOp.AND) {
            if (widgetIsConstant(first, false) || widgetIsConstant(second, false)) return widgetConstant(false);
            if (widgetIsConstant(first, true)) return second;
            if (widgetIsConstant(second, true)) return first;
        } else if (op == RendererBytecodeScanner.BooleanOp.OR) {
            if (widgetIsConstant(first, true) || widgetIsConstant(second, true)) return widgetConstant(true);
            if (widgetIsConstant(first, false)) return second;
            if (widgetIsConstant(second, false)) return first;
        }
        RendererBytecodeScanner.BooleanPredicate predicate = new RendererBytecodeScanner.BooleanPredicate();
        predicate.op = op;
        predicate.terms = new ArrayList<>();
        if (first.op == op && first.terms != null) predicate.terms.addAll(first.terms);
        else predicate.terms.add(first);
        if (second.op == op && second.terms != null) predicate.terms.addAll(second.terms);
        else predicate.terms.add(second);
        return predicate;
    }

    private static RendererBytecodeScanner.BooleanPredicate widgetChoose(
            RendererBytecodeScanner.BooleanPredicate condition,
            RendererBytecodeScanner.BooleanPredicate taken,
            RendererBytecodeScanner.BooleanPredicate fallthrough) {
        if (condition == null || taken == null || fallthrough == null) return null;
        if (widgetIsConstant(taken, true) && widgetIsConstant(fallthrough, false)) return condition;
        if (widgetIsConstant(taken, false) && widgetIsConstant(fallthrough, true)) {
            return widgetNegate(condition);
        }
        if (widgetIsConstant(taken, true)) {
            return widgetCombine(RendererBytecodeScanner.BooleanOp.OR, condition, fallthrough);
        }
        if (widgetIsConstant(taken, false)) {
            return widgetCombine(RendererBytecodeScanner.BooleanOp.AND,
                    widgetNegate(condition), fallthrough);
        }
        if (widgetIsConstant(fallthrough, true)) {
            return widgetCombine(RendererBytecodeScanner.BooleanOp.OR,
                    widgetNegate(condition), taken);
        }
        if (widgetIsConstant(fallthrough, false)) {
            return widgetCombine(RendererBytecodeScanner.BooleanOp.AND, condition, taken);
        }
        return widgetCombine(RendererBytecodeScanner.BooleanOp.OR,
                widgetCombine(RendererBytecodeScanner.BooleanOp.AND, condition, taken),
                widgetCombine(RendererBytecodeScanner.BooleanOp.AND,
                        widgetNegate(condition), fallthrough));
    }

    private static boolean widgetIsConstant(RendererBytecodeScanner.BooleanPredicate predicate) {
        return predicate != null && predicate.op == RendererBytecodeScanner.BooleanOp.CONSTANT;
    }

    private static boolean widgetIsConstant(RendererBytecodeScanner.BooleanPredicate predicate,
                                            boolean value) {
        return widgetIsConstant(predicate) && predicate.constant == value;
    }

    private static boolean clickHandlerShape(MethodNode method) {
        if (method == null || method.instructions == null || (method.access & Opcodes.ACC_STATIC) != 0) {
            return false;
        }
        String desc = method.desc == null ? "" : method.desc;
        String name = method.name == null ? "" : method.name.toLowerCase(Locale.ROOT);
        if ("(DD)V".equals(desc) || "(DDI)Z".equals(desc)) return true;
        return "()V".equals(desc) && (name.contains("click") || name.contains("press"));
    }

    /**
     * Symbolically evaluates the integer arguments used to construct widgets. This is deliberately
     * bytecode based: it understands constants and additions to dynamic gui-left/gui-top values,
     * so production-obfuscated screens still yield background-relative hit bounds without ever
     * loading a client class.
     */
    private static Map<AbstractInsnNode, WidgetLayout> widgetLayouts(
            ClassNode owner, MethodNode method, Map<String, ClassNode> nodes,
            UiKnowledge uiKnowledge, Map<Integer, UiValue> parameterValues) {
        Map<AbstractInsnNode, WidgetLayout> out = new java.util.IdentityHashMap<>();
        if (owner == null || method == null || method.instructions == null) return out;
        try {
            Frame<UiValue>[] frames = new Analyzer<>(new UiInterpreter(nodes, uiKnowledge, parameterValues))
                    .analyze(owner.name, method);
            int index = 0;
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode ctor
                        && ctor.getOpcode() == Opcodes.INVOKESPECIAL
                        && "<init>".equals(ctor.name)
                        && clickableWidgetConstructor(ctor, nodes)) {
                    WidgetLayout direct = layoutAt(frames[index], ctor.desc);
                    if (direct.width() <= 0 || direct.height() <= 0) {
                        int[] inherited = constructorDimensions(ctor.owner, ctor.desc, nodes,
                                new HashSet<>(), 0);
                        direct = new WidgetLayout(direct.x(), direct.y(),
                                direct.width() > 0 ? direct.width() : inherited[0],
                                direct.height() > 0 ? direct.height() : inherited[1],
                                direct.xAnchor(), direct.yAnchor());
                    }
                    out.put(instruction, direct);
                } else if (instruction instanceof MethodInsnNode call
                        && (looksLikeWidgetFactory(call, nodes)
                        || layoutMutationKind(call, nodes) != LayoutMutationKind.NONE)) {
                    WidgetLayout direct = layoutForCall(frames[index], call, nodes);
                    if (looksLikeWidgetFactory(call, nodes)
                            && (direct.width() <= 0 || direct.height() <= 0)) {
                        int[] dimensions = factoryDimensions(call, nodes, new HashSet<>(), 0);
                        direct = new WidgetLayout(direct.x(), direct.y(),
                                direct.width() > 0 ? direct.width() : dimensions[0],
                                direct.height() > 0 ? direct.height() : dimensions[1],
                                direct.xAnchor(), direct.yAnchor());
                    }
                    out.put(instruction, direct);
                }
                index++;
            }
            applyPostConstructionLayouts(owner, method, nodes, frames, out);
        } catch (AnalyzerException | RuntimeException ignored) {
        }
        return out;
    }

    /**
     * Apply layout setters that run after a widget is created. UI frameworks frequently construct a
     * control with placeholder coordinates, register/store it, then call setPosition/setSize on the
     * local or field. Tracking the exact JVM local/field alias keeps this name-independent and, more
     * importantly, prevents a nearby setter for another control from being borrowed by proximity.
     */
    private static void applyPostConstructionLayouts(
            ClassNode owner, MethodNode method, Map<String, ClassNode> nodes,
            Frame<UiValue>[] uiFrames, Map<AbstractInsnNode, WidgetLayout> layouts) {
        if (owner == null || method == null || method.instructions == null || layouts.isEmpty()) return;
        try {
            Frame<SourceValue>[] sourceFrames = new Analyzer<>(new SourceInterpreter())
                    .analyze(owner.name, method);
            java.util.IdentityHashMap<AbstractInsnNode, Integer> indices = new java.util.IdentityHashMap<>();
            int cursor = 0;
            for (AbstractInsnNode instruction : method.instructions) indices.put(instruction, cursor++);

            Map<Integer, AbstractInsnNode> locals = new HashMap<>();
            Set<Integer> localConflicts = new HashSet<>();
            Map<String, AbstractInsnNode> fields = new HashMap<>();
            Set<String> fieldConflicts = new HashSet<>();
            for (AbstractInsnNode terminal : List.copyOf(layouts.keySet())) {
                if (!(terminal instanceof MethodInsnNode call) || !widgetTerminal(call, nodes)) continue;
                VarInsnNode local = storedWidgetLocalAfter(terminal, nodes, 16);
                if (local != null && !localConflicts.contains(local.var)) {
                    AbstractInsnNode previous = locals.putIfAbsent(local.var, terminal);
                    if (previous != null && previous != terminal) {
                        locals.remove(local.var);
                        localConflicts.add(local.var);
                    }
                }
                FieldInsnNode field = storedWidgetFieldAfter(terminal, 16);
                if (field != null) {
                    String key = fieldKey(field.owner, field.name);
                    if (!fieldConflicts.contains(key)) {
                        AbstractInsnNode previous = fields.putIfAbsent(key, terminal);
                        if (previous != null && previous != terminal) {
                            fields.remove(key);
                            fieldConflicts.add(key);
                        }
                    }
                }
            }

            int index = 0;
            for (AbstractInsnNode instruction : method.instructions) {
                if (!(instruction instanceof MethodInsnNode call)) {
                    index++;
                    continue;
                }
                LayoutMutationKind kind = layoutMutationKind(call, nodes);
                if (kind == LayoutMutationKind.NONE || call.getOpcode() == Opcodes.INVOKESTATIC) {
                    index++;
                    continue;
                }
                Frame<SourceValue> sourceFrame = sourceFrames[index];
                AbstractInsnNode terminal = widgetReceiverTerminal(
                        sourceFrame, call, sourceFrames, indices, locals, fields, layouts,
                        new HashSet<>(), 0);
                Integer terminalIndex = terminal == null ? null : indices.get(terminal);
                if (terminalIndex == null || terminalIndex >= index || !layouts.containsKey(terminal)) {
                    index++;
                    continue;
                }
                WidgetLayout mutation = layoutForCall(uiFrames[index], call, nodes);
                layouts.put(terminal, applyLayoutMutation(layouts.get(terminal), mutation, kind));
                index++;
            }
        } catch (AnalyzerException | RuntimeException ignored) {
        }
    }

    private static boolean widgetTerminal(MethodInsnNode call, Map<String, ClassNode> nodes) {
        return call != null && (call.getOpcode() == Opcodes.INVOKESPECIAL && "<init>".equals(call.name)
                && clickableWidgetConstructor(call, nodes) || looksLikeWidgetFactory(call, nodes));
    }

    private static VarInsnNode storedWidgetLocalAfter(AbstractInsnNode instruction,
                                                      Map<String, ClassNode> nodes, int limit) {
        for (AbstractInsnNode next = instruction == null ? null : instruction.getNext();
             next != null && limit-- > 0; next = next.getNext()) {
            if (next instanceof VarInsnNode var && var.getOpcode() == Opcodes.ASTORE) return var;
            if (next instanceof MethodInsnNode call && next != instruction && widgetTerminal(call, nodes)) break;
        }
        return null;
    }

    private static AbstractInsnNode widgetReceiverTerminal(
            Frame<SourceValue> frame, MethodInsnNode call, Frame<SourceValue>[] frames,
            java.util.IdentityHashMap<AbstractInsnNode, Integer> indices,
            Map<Integer, AbstractInsnNode> locals, Map<String, AbstractInsnNode> fields,
            Map<AbstractInsnNode, WidgetLayout> layouts, Set<AbstractInsnNode> visiting, int depth) {
        if (frame == null || call == null || depth > 8) return null;
        Type[] arguments;
        try {
            arguments = Type.getArgumentTypes(call.desc);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
        int receiver = frame.getStackSize() - arguments.length - 1;
        if (receiver < 0) return null;
        return widgetSourceTerminal(frame.getStack(receiver), frames, indices, locals, fields,
                layouts, visiting, depth);
    }

    private static AbstractInsnNode widgetSourceTerminal(
            SourceValue source, Frame<SourceValue>[] frames,
            java.util.IdentityHashMap<AbstractInsnNode, Integer> indices,
            Map<Integer, AbstractInsnNode> locals, Map<String, AbstractInsnNode> fields,
            Map<AbstractInsnNode, WidgetLayout> layouts, Set<AbstractInsnNode> visiting, int depth) {
        if (source == null || source.insns == null || source.insns.size() != 1 || depth > 8) return null;
        AbstractInsnNode producer = source.insns.iterator().next();
        if (!visiting.add(producer)) return null;
        try {
            if (layouts.containsKey(producer)) return producer;
            if (producer instanceof VarInsnNode var && var.getOpcode() == Opcodes.ALOAD) {
                return locals.get(var.var);
            }
            if (producer instanceof FieldInsnNode field
                    && (field.getOpcode() == Opcodes.GETFIELD || field.getOpcode() == Opcodes.GETSTATIC)) {
                return fields.get(fieldKey(field.owner, field.name));
            }
            // CHECKCAST and stack-copy operations can sit between an exact local/field and the
            // setter receiver. Follow their single operand through the already-computed source frame.
            int opcode = producer.getOpcode();
            if (opcode == Opcodes.CHECKCAST || opcode == Opcodes.DUP || opcode == Opcodes.DUP_X1
                    || opcode == Opcodes.DUP_X2 || opcode == Opcodes.DUP2 || opcode == Opcodes.DUP2_X1
                    || opcode == Opcodes.DUP2_X2) {
                Integer at = indices.get(producer);
                Frame<SourceValue> before = at == null || at < 0 || at >= frames.length ? null : frames[at];
                if (before != null && before.getStackSize() > 0) {
                    return widgetSourceTerminal(before.getStack(before.getStackSize() - 1), frames,
                            indices, locals, fields, layouts, visiting, depth + 1);
                }
            }
            return null;
        } finally {
            visiting.remove(producer);
        }
    }

    private static WidgetLayout applyLayoutMutation(WidgetLayout current, WidgetLayout mutation,
                                                    LayoutMutationKind kind) {
        WidgetLayout base = current == null ? WidgetLayout.UNKNOWN : current;
        WidgetLayout next = mutation == null ? WidgetLayout.UNKNOWN : mutation;
        return switch (kind) {
            case BOUNDS -> next;
            case POSITION -> new WidgetLayout(next.x(), next.y(), base.width(), base.height(),
                    next.xAnchor(), next.yAnchor());
            case SIZE -> new WidgetLayout(base.x(), base.y(), next.width(), next.height(),
                    base.xAnchor(), base.yAnchor());
            case X -> new WidgetLayout(next.x(), base.y(), base.width(), base.height(),
                    next.xAnchor(), base.yAnchor());
            case Y -> new WidgetLayout(base.x(), next.y(), base.width(), base.height(),
                    base.xAnchor(), next.yAnchor());
            case WIDTH -> new WidgetLayout(base.x(), base.y(), next.width(), base.height(),
                    base.xAnchor(), base.yAnchor());
            case HEIGHT -> new WidgetLayout(base.x(), base.y(), base.width(), next.height(),
                    base.xAnchor(), base.yAnchor());
            case NONE -> base;
        };
    }

    private static WidgetLayout layoutAt(Frame<UiValue> frame, String descriptor) {
        if (frame == null || descriptor == null) return WidgetLayout.UNKNOWN;
        Type[] arguments;
        try {
            arguments = Type.getArgumentTypes(descriptor);
        } catch (IllegalArgumentException ignored) {
            return WidgetLayout.UNKNOWN;
        }
        int first = frame.getStackSize() - arguments.length;
        if (first < 0) return WidgetLayout.UNKNOWN;
        ArrayList<UiValue> integers = new ArrayList<>();
        for (int i = 0; i < arguments.length; i++) {
            if (arguments[i].getSort() == Type.INT) integers.add(frame.getStack(first + i));
        }
        if (integers.size() < 4) {
            UiCoordinate x = integers.size() > 0 ? coordinate(integers.get(0), true)
                    : UiCoordinate.UNKNOWN;
            UiCoordinate y = integers.size() > 1 ? coordinate(integers.get(1), false)
                    : UiCoordinate.UNKNOWN;
            return new WidgetLayout(x.value(), y.value(), -1, -1, x.anchor(), y.anchor());
        }
        // Legacy/custom widgets frequently prepend an integer id to the familiar x/y/w/h
        // arguments. Considering only the first four made the id become X and shifted every
        // hitbox. Rank each consecutive quartet, preferring usable dimensions and the later
        // quartet on a tie (the standard id,x,y,w,h shape).
        WidgetLayout best = WidgetLayout.UNKNOWN;
        int bestScore = Integer.MIN_VALUE;
        for (int start = 0; start + 3 < integers.size(); start++) {
            UiCoordinate x = coordinate(integers.get(start), true);
            UiCoordinate y = coordinate(integers.get(start + 1), false);
            int width = dimension(integers.get(start + 2));
            int height = dimension(integers.get(start + 3));
            int score = (x.known() ? 4 : 0) + (y.known() ? 4 : 0)
                    + (width > 0 && width <= 2048 ? 12 : 0)
                    + (height > 0 && height <= 1024 ? 12 : 0) + start;
            if (score >= bestScore) {
                bestScore = score;
                best = new WidgetLayout(x.value(), y.value(), width, height,
                        x.anchor(), y.anchor());
            }
        }
        return best;
    }

    private static WidgetLayout layoutForCall(Frame<UiValue> frame, MethodInsnNode call,
                                              Map<String, ClassNode> nodes) {
        WidgetLayout direct = layoutAt(frame, call.desc);
        LayoutMutationKind kind = layoutMutationKind(call, nodes);
        if (kind == LayoutMutationKind.NONE || kind == LayoutMutationKind.BOUNDS) return direct;
        List<UiValue> integers = integerArguments(frame, call.desc);
        if (integers.isEmpty()) return WidgetLayout.UNKNOWN;
        UiCoordinate firstX = coordinate(integers.get(0), true);
        UiCoordinate firstY = coordinate(integers.get(0), false);
        int firstDimension = dimension(integers.get(0));
        if (kind == LayoutMutationKind.X) {
            return new WidgetLayout(firstX.value(), -1, -1, -1,
                    firstX.anchor(), WidgetCoordinateAnchor.LEGACY);
        }
        if (kind == LayoutMutationKind.Y) {
            return new WidgetLayout(-1, firstY.value(), -1, -1,
                    WidgetCoordinateAnchor.LEGACY, firstY.anchor());
        }
        if (kind == LayoutMutationKind.WIDTH) {
            return new WidgetLayout(-1, -1, firstDimension, -1);
        }
        if (kind == LayoutMutationKind.HEIGHT) {
            return new WidgetLayout(-1, -1, -1, firstDimension);
        }
        if (integers.size() < 2) return WidgetLayout.UNKNOWN;
        if (kind == LayoutMutationKind.SIZE) {
            return new WidgetLayout(-1, -1,
                    dimension(integers.get(0)), dimension(integers.get(1)));
        }
        UiCoordinate x = coordinate(integers.get(0), true);
        UiCoordinate y = coordinate(integers.get(1), false);
        return new WidgetLayout(x.value(), y.value(), -1, -1, x.anchor(), y.anchor());
    }

    private static List<UiValue> integerArguments(Frame<UiValue> frame, String descriptor) {
        if (frame == null || descriptor == null) return List.of();
        Type[] arguments;
        try {
            arguments = Type.getArgumentTypes(descriptor);
        } catch (IllegalArgumentException ignored) {
            return List.of();
        }
        int first = frame.getStackSize() - arguments.length;
        if (first < 0) return List.of();
        ArrayList<UiValue> integers = new ArrayList<>();
        for (int i = 0; i < arguments.length; i++) {
            if (arguments[i].getSort() == Type.INT) integers.add(frame.getStack(first + i));
        }
        return integers;
    }

    private static WidgetLayout mergePrecedingLayouts(AbstractInsnNode instruction,
                                                       WidgetLayout initial,
                                                       Map<AbstractInsnNode, WidgetLayout> layouts,
                                                       Map<String, ClassNode> nodes,
                                                       int limit) {
        int x = initial.x(), y = initial.y(), width = initial.width(), height = initial.height();
        WidgetCoordinateAnchor xAnchor = initial.xAnchor(), yAnchor = initial.yAnchor();
        for (AbstractInsnNode previous = instruction == null ? null : instruction.getPrevious();
             previous != null && limit-- > 0; previous = previous.getPrevious()) {
            // Do not borrow bounds from a previously completed widget. Fluent builders often
            // create several controls in one init method and the old unbounded walk made later
            // buttons inherit the rectangle of an earlier one.
            if (previous instanceof MethodInsnNode call
                    && ((call.getOpcode() == Opcodes.INVOKESPECIAL && "<init>".equals(call.name)
                    && clickableWidgetConstructor(call, nodes))
                    || looksLikeWidgetFactory(call, nodes))) break;
            WidgetLayout candidate = layouts.get(previous);
            if (candidate == null) continue;
            if (!coordinateKnown(x, xAnchor) && coordinateKnown(candidate.x(), candidate.xAnchor())) {
                x = candidate.x();
                xAnchor = candidate.xAnchor();
            }
            if (!coordinateKnown(y, yAnchor) && coordinateKnown(candidate.y(), candidate.yAnchor())) {
                y = candidate.y();
                yAnchor = candidate.yAnchor();
            }
            if (width <= 0 && candidate.width() > 0) width = candidate.width();
            if (height <= 0 && candidate.height() > 0) height = candidate.height();
            if (coordinateKnown(x, xAnchor) && coordinateKnown(y, yAnchor)
                    && width > 0 && height > 0) break;
        }
        return new WidgetLayout(x, y, width, height, xAnchor, yAnchor);
    }

    private static int[] constructorDimensions(String owner, String descriptor,
                                               Map<String, ClassNode> nodes,
                                               Set<String> visited, int depth) {
        return constructorDimensions(owner, descriptor, nodes, visited, depth, Map.of());
    }

    private static int[] constructorDimensions(String owner, String descriptor,
                                               Map<String, ClassNode> nodes,
                                               Set<String> visited, int depth,
                                               Map<Integer, UiValue> parameterValues) {
        if (owner == null || descriptor == null || nodes == null || depth > 5
                || !visited.add(owner + descriptor)) return new int[]{-1, -1};
        ClassNode clazz = nodes.get(owner);
        MethodNode constructor = findMethod(clazz, "<init>", descriptor);
        if (constructor == null || constructor.instructions == null) return new int[]{-1, -1};
        try {
            Frame<UiValue>[] frames = new Analyzer<>(new UiInterpreter(
                    nodes, UiKnowledge.EMPTY, parameterValues))
                    .analyze(owner, constructor);
            Map<AbstractInsnNode, WidgetLayout> localLayouts = new java.util.IdentityHashMap<>();
            int knownWidth = -1;
            int knownHeight = -1;
            int index = 0;
            for (AbstractInsnNode instruction : constructor.instructions) {
                if (instruction instanceof MethodInsnNode call) {
                    LayoutMutationKind mutation = layoutMutationKind(call, nodes);
                    if (mutation != LayoutMutationKind.NONE) {
                        localLayouts.put(instruction, layoutForCall(frames[index], call, nodes));
                    }
                    if (call.getOpcode() != Opcodes.INVOKESPECIAL || !"<init>".equals(call.name)) {
                        index++;
                        continue;
                    }
                    // Only this(...)/super(...) defines this widget's inherited dimensions. A
                    // constructor can allocate arbitrary helper objects too; mining those NEW
                    // expressions for width/height would silently attach unrelated geometry.
                    if (!call.owner.equals(clazz.name) && !call.owner.equals(clazz.superName)) {
                        index++;
                        continue;
                    }
                    WidgetLayout layout = layoutAt(frames[index], call.desc);
                    layout = mergePrecedingLayouts(instruction, layout, localLayouts, nodes, 48);
                    if (layout.width() > 0) knownWidth = layout.width();
                    if (layout.height() > 0) knownHeight = layout.height();
                    if (knownWidth > 0 && knownHeight > 0) return new int[]{knownWidth, knownHeight};
                    Map<Integer, UiValue> nestedParameters = constructorArgumentBindings(
                            frames[index], call.desc);
                    int[] nested = constructorDimensions(call.owner, call.desc, nodes,
                            visited, depth + 1, nestedParameters);
                    if (knownWidth <= 0 && nested[0] > 0) knownWidth = nested[0];
                    if (knownHeight <= 0 && nested[1] > 0) knownHeight = nested[1];
                    if (knownWidth > 0 && knownHeight > 0) return new int[]{knownWidth, knownHeight};
                }
                index++;
            }
            if (knownWidth > 0 || knownHeight > 0) return new int[]{knownWidth, knownHeight};
        } catch (AnalyzerException | RuntimeException ignored) {
        }
        return new int[]{-1, -1};
    }

    /** Bind concrete/symbolic constructor arguments to the callee's JVM local slots. This lets a
     * subclass constructor forward fixed dimensions through one or more superclass constructors
     * without losing them at each invokespecial boundary. */
    private static Map<Integer, UiValue> constructorArgumentBindings(Frame<UiValue> frame,
                                                                     String descriptor) {
        if (frame == null || descriptor == null) return Map.of();
        Type[] arguments;
        try {
            arguments = Type.getArgumentTypes(descriptor);
        } catch (IllegalArgumentException ignored) {
            return Map.of();
        }
        int first = frame.getStackSize() - arguments.length;
        if (first < 0) return Map.of();
        Map<Integer, UiValue> bindings = new HashMap<>();
        int local = 1; // local zero is the constructor receiver
        for (int i = 0; i < arguments.length; i++) {
            UiValue value = frame.getStack(first + i);
            if (arguments[i].getSort() == Type.INT && value != null
                    && value.basis() != UiBasis.UNKNOWN) {
                bindings.put(local, value);
            }
            local += arguments[i].getSize();
        }
        return bindings.isEmpty() ? Map.of() : Map.copyOf(bindings);
    }

    /** Recover dimensions supplied inside a widget-producing factory. Factory call sites often
     * carry only x/y while the factory itself appends a fixed width/height before invoking the
     * concrete widget constructor. Every candidate construction must agree; data-dependent or
     * polymorphic sizes intentionally remain unknown. */
    private static int[] factoryDimensions(MethodInsnNode factory, Map<String, ClassNode> nodes,
                                           Set<String> visiting, int depth) {
        if (factory == null || nodes == null || depth > 5) return new int[]{-1, -1};
        String key = methodKey(factory.owner, factory.name, factory.desc);
        if (!visiting.add(key)) return new int[]{-1, -1};
        try {
            UiMethodTarget target = uiMethodTarget(factory, nodes);
            if (target == null || target.method().instructions == null
                    || target.method().instructions.size() > 4_000) return new int[]{-1, -1};
            Frame<UiValue>[] frames;
            try {
                frames = new Analyzer<>(new UiInterpreter(nodes, UiKnowledge.EMPTY, Map.of()))
                        .analyze(target.owner(), target.method());
            } catch (AnalyzerException | RuntimeException ignored) {
                return new int[]{-1, -1};
            }
            Integer width = null, height = null;
            boolean found = false;
            int index = 0;
            for (AbstractInsnNode instruction : target.method().instructions) {
                if (instruction instanceof MethodInsnNode call
                        && call.getOpcode() == Opcodes.INVOKESPECIAL
                        && "<init>".equals(call.name) && isWidgetType(call.owner, nodes)) {
                    WidgetLayout layout = layoutAt(frames[index], call.desc);
                    int candidateWidth = layout.width();
                    int candidateHeight = layout.height();
                    if (candidateWidth <= 0 || candidateHeight <= 0) {
                        int[] inherited = constructorDimensions(call.owner, call.desc, nodes,
                                new HashSet<>(), 0,
                                constructorArgumentBindings(frames[index], call.desc));
                        if (candidateWidth <= 0) candidateWidth = inherited[0];
                        if (candidateHeight <= 0) candidateHeight = inherited[1];
                    }
                    if (candidateWidth > 0 || candidateHeight > 0) {
                        found = true;
                        width = agreeDimension(width, candidateWidth);
                        height = agreeDimension(height, candidateHeight);
                    }
                } else if (instruction instanceof MethodInsnNode nested
                        && isWidgetType(returnedObject(nested.desc), nodes)
                        && methodProducesFreshWidget(nested, nodes, new HashSet<>(), 0)) {
                    int[] candidate = factoryDimensions(nested, nodes, visiting, depth + 1);
                    if (candidate[0] > 0 || candidate[1] > 0) {
                        found = true;
                        width = agreeDimension(width, candidate[0]);
                        height = agreeDimension(height, candidate[1]);
                    }
                }
                index++;
            }
            return found ? new int[]{width == null ? -1 : width, height == null ? -1 : height}
                    : new int[]{-1, -1};
        } finally {
            visiting.remove(key);
        }
    }

    private static Integer agreeDimension(Integer previous, int candidate) {
        if (candidate <= 0) return previous;
        if (previous == null) return candidate;
        return previous == candidate ? previous : -1;
    }

    private record UiCoordinate(int value, WidgetCoordinateAnchor anchor) {
        static final UiCoordinate UNKNOWN = new UiCoordinate(-1, WidgetCoordinateAnchor.LEGACY);

        boolean known() { return coordinateKnown(value, anchor); }
    }

    private enum UiBasis {
        UNKNOWN, CONSTANT, LEGACY,
        START_X, START_Y, SIZE_X, SIZE_Y, CENTER_X, CENTER_Y, END_X, END_Y
    }

    private record UiValue(int size, UiBasis basis, int offset) implements Value {
        static UiValue unknown(int size) { return new UiValue(size, UiBasis.UNKNOWN, 0); }
        static UiValue integer(int value) { return new UiValue(1, UiBasis.CONSTANT, value); }
        static UiValue legacy(int offset) { return new UiValue(1, UiBasis.LEGACY, offset); }
        static UiValue anchored(UiBasis basis, int offset) { return new UiValue(1, basis, offset); }
        boolean constant() { return basis == UiBasis.CONSTANT; }
        boolean symbolic() { return basis != UiBasis.UNKNOWN && basis != UiBasis.LEGACY; }
        @Override public int getSize() { return size; }
    }

    private record UiKnowledge(Map<String, UiValue> fields, Map<String, UiValue> methods) {
        static final UiKnowledge EMPTY = new UiKnowledge(Map.of(), Map.of());

        UiKnowledge {
            fields = fields == null || fields.isEmpty() ? Map.of() : Map.copyOf(fields);
            methods = methods == null || methods.isEmpty() ? Map.of() : Map.copyOf(methods);
        }
    }

    private static UiCoordinate coordinate(UiValue value, boolean xAxis) {
        if (value == null) return UiCoordinate.UNKNOWN;
        return switch (value.basis()) {
            case CONSTANT -> new UiCoordinate(value.offset(), WidgetCoordinateAnchor.START);
            case LEGACY -> new UiCoordinate(value.offset(), WidgetCoordinateAnchor.LEGACY);
            case START_X -> xAxis
                    ? new UiCoordinate(value.offset(), WidgetCoordinateAnchor.START) : UiCoordinate.UNKNOWN;
            case START_Y -> !xAxis
                    ? new UiCoordinate(value.offset(), WidgetCoordinateAnchor.START) : UiCoordinate.UNKNOWN;
            case CENTER_X -> xAxis
                    ? new UiCoordinate(value.offset(), WidgetCoordinateAnchor.CENTER) : UiCoordinate.UNKNOWN;
            case CENTER_Y -> !xAxis
                    ? new UiCoordinate(value.offset(), WidgetCoordinateAnchor.CENTER) : UiCoordinate.UNKNOWN;
            case SIZE_X, END_X -> xAxis
                    ? new UiCoordinate(value.offset(), WidgetCoordinateAnchor.END) : UiCoordinate.UNKNOWN;
            case SIZE_Y, END_Y -> !xAxis
                    ? new UiCoordinate(value.offset(), WidgetCoordinateAnchor.END) : UiCoordinate.UNKNOWN;
            default -> UiCoordinate.UNKNOWN;
        };
    }

    private static int dimension(UiValue value) {
        return value != null && value.constant() && value.offset() > 0 ? value.offset() : -1;
    }

    /** True when a coordinate carries enough provenance to distinguish a real -1 offset from the
     * legacy unknown sentinel. */
    public static boolean coordinateKnown(int coordinate, WidgetCoordinateAnchor anchor) {
        WidgetCoordinateAnchor resolved = anchor == null ? WidgetCoordinateAnchor.LEGACY : anchor;
        return resolved != WidgetCoordinateAnchor.LEGACY || coordinate != -1;
    }

    /** Resolve scanner-local coordinate provenance against the source panel extent. */
    public static int resolveWidgetCoordinate(int coordinate, WidgetCoordinateAnchor anchor,
                                              int surfaceExtent, int widgetExtent) {
        WidgetCoordinateAnchor resolved = anchor == null ? WidgetCoordinateAnchor.LEGACY : anchor;
        if (resolved == WidgetCoordinateAnchor.START || surfaceExtent <= 0) return coordinate;
        if (resolved == WidgetCoordinateAnchor.CENTER) return surfaceExtent / 2 + coordinate;
        if (resolved == WidgetCoordinateAnchor.END) return surfaceExtent + coordinate;
        if (coordinate >= 0 || coordinate == -1 || widgetExtent <= 0) return coordinate;
        int trailingInset = -coordinate;
        if (trailingInset < widgetExtent || trailingInset >= surfaceExtent) return coordinate;
        return surfaceExtent - trailingInset;
    }

    /**
     * Canonical logical viewport for a Screen whose true dimensions are only known at runtime.
     * START-anchored controls can prove a larger minimum. CENTER/END-anchored controls preserve a
     * stable viewport center instead of expanding/recentering the screen around sidecar widgets.
     * This is scanner-owned so audits and every runtime consumer use the same generic fallback.
     */
    public static int inferredSurfaceExtent(List<Action> actions, boolean xAxis) {
        int extent = xAxis ? 256 : 180;
        boolean viewportRelative = false;
        if (actions == null) return extent;
        for (Action action : actions) {
            if (action == null) continue;
            WidgetCoordinateAnchor anchor = xAxis ? action.widgetXAnchor : action.widgetYAnchor;
            int coordinate = xAxis ? action.widgetX : action.widgetY;
            int widgetExtent = xAxis ? action.widgetWidth : action.widgetHeight;
            if (!coordinateKnown(coordinate, anchor)) continue;
            if (anchor == WidgetCoordinateAnchor.CENTER || anchor == WidgetCoordinateAnchor.END) {
                viewportRelative = true;
                continue;
            }
            if (widgetExtent > 0 && coordinate >= 0) {
                extent = Math.max(extent, coordinate + widgetExtent);
            }
        }
        if (viewportRelative && xAxis) extent = Math.max(extent, 320);
        return extent;
    }

    /**
     * Recover screen-layout fields that cache expressions such as {@code width / 2} before widgets
     * are constructed. This is intentionally restricted to methods that actually build widgets;
     * arbitrary gameplay integer fields are never promoted into UI coordinate provenance.
     */
    private static UiKnowledge discoverUiKnowledge(
            ClassNode owner, Map<String, ClassNode> nodes) {
        if (owner == null || nodes == null || nodes.isEmpty()) return UiKnowledge.EMPTY;
        ArrayList<ClassNode> hierarchy = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String current = owner.name; current != null && seen.add(current); ) {
            ClassNode node = nodes.get(current);
            if (node == null) break;
            hierarchy.add(node);
            current = node.superName;
        }
        Map<String, UiValue> fields = new HashMap<>();
        Map<String, UiValue> methods = new HashMap<>();
        Set<String> fieldConflicts = new HashSet<>();
        Set<String> methodConflicts = new HashSet<>();
        for (int round = 0; round < 6; round++) {
            boolean changed = false;
            UiKnowledge knowledge = new UiKnowledge(fields, methods);
            UiInterpreter interpreter = new UiInterpreter(nodes, knowledge, Map.of());
            for (ClassNode clazz : hierarchy) {
                if (clazz.methods == null) continue;
                for (MethodNode method : clazz.methods) {
                    if (method == null || method.instructions == null
                            || method.instructions.size() > 12_000) continue;
                    boolean layoutMethod = layoutFieldMethod(method, nodes);
                    boolean summarizeReturn = zeroArgumentIntMethod(method);
                    if (!layoutMethod && !summarizeReturn) continue;
                    try {
                        Frame<UiValue>[] frames = new Analyzer<>(interpreter).analyze(clazz.name, method);
                        int index = 0;
                        for (AbstractInsnNode instruction : method.instructions) {
                            if (layoutMethod && instruction instanceof FieldInsnNode field
                                    && (field.getOpcode() == Opcodes.PUTFIELD
                                    || field.getOpcode() == Opcodes.PUTSTATIC)
                                    && "I".equals(field.desc)
                                    && !fieldConflicts.contains(fieldKey(field.owner, field.name))) {
                                Frame<UiValue> frame = frames[index];
                                UiValue value = frame == null || frame.getStackSize() == 0 ? null
                                        : frame.getStack(frame.getStackSize() - 1);
                                if (value != null && value.symbolic()) {
                                    String key = fieldKey(field.owner, field.name);
                                    UiValue previous = fields.get(key);
                                    if (previous == null) {
                                        fields.put(key, value);
                                        changed = true;
                                    } else if (!previous.equals(value)) {
                                        fields.remove(key);
                                        fieldConflicts.add(key);
                                        changed = true;
                                    }
                                }
                            }
                            index++;
                        }
                        if (summarizeReturn) {
                            UiValue value = commonIntReturn(method, frames);
                            String key = methodKey(clazz.name, method.name, method.desc);
                            if (value != null && value.basis() != UiBasis.UNKNOWN
                                    && value.basis() != UiBasis.LEGACY
                                    && !methodConflicts.contains(key)) {
                                UiValue previous = methods.get(key);
                                if (previous == null) {
                                    methods.put(key, value);
                                    changed = true;
                                } else if (!previous.equals(value)) {
                                    methods.remove(key);
                                    methodConflicts.add(key);
                                    changed = true;
                                }
                            }
                        }
                    } catch (AnalyzerException | RuntimeException ignored) {
                    }
                }
            }
            if (!changed) break;
        }
        return fields.isEmpty() && methods.isEmpty() ? UiKnowledge.EMPTY
                : new UiKnowledge(fields, methods);
    }

    private record UiMethodTarget(String owner, MethodNode method) {}
    private record UiParameterKey(String method, int local) {}

    /**
     * Propagate integer layout arguments into same-screen helper methods. This covers the common
     * pattern where init computes viewport-relative coordinates and delegates widget construction
     * to addButton(x, y, width, height). Bindings are accepted only when every discovered call site
     * agrees, so a reusable helper invoked at several different positions stays unknown rather than
     * being frozen to one arbitrary call.
     */
    private static Map<String, Map<Integer, UiValue>> discoverUiParameterValues(
            ClassNode owner, Map<String, ClassNode> nodes, UiKnowledge knowledge) {
        if (owner == null || nodes == null || nodes.isEmpty()) return Map.of();
        ArrayList<ClassNode> hierarchy = new ArrayList<>();
        Set<String> hierarchyNames = new HashSet<>();
        for (String current = owner.name; current != null && hierarchyNames.add(current); ) {
            ClassNode node = nodes.get(current);
            if (node == null) break;
            hierarchy.add(node);
            current = node.superName;
        }

        Map<String, Map<Integer, UiValue>> known = Map.of();
        for (int round = 0; round < 6; round++) {
            Map<UiParameterKey, UiValue> candidates = new HashMap<>();
            Set<UiParameterKey> invalid = new HashSet<>();
            for (ClassNode clazz : hierarchy) {
                if (clazz.methods == null) continue;
                for (MethodNode method : clazz.methods) {
                    if (method == null || method.instructions == null
                            || method.instructions.size() > 12_000) continue;
                    Map<Integer, UiValue> bindings = known.getOrDefault(
                            methodKey(clazz.name, method.name, method.desc), Map.of());
                    try {
                        Frame<UiValue>[] frames = new Analyzer<>(
                                new UiInterpreter(nodes, knowledge, bindings)).analyze(clazz.name, method);
                        int index = 0;
                        for (AbstractInsnNode instruction : method.instructions) {
                            if (!(instruction instanceof MethodInsnNode call)) {
                                index++;
                                continue;
                            }
                            UiMethodTarget target = uiMethodTarget(call, nodes);
                            if (target == null || !hierarchyNames.contains(target.owner())) {
                                index++;
                                continue;
                            }
                            Type[] arguments;
                            try {
                                arguments = Type.getArgumentTypes(call.desc);
                            } catch (IllegalArgumentException ignored) {
                                index++;
                                continue;
                            }
                            Frame<UiValue> frame = index < frames.length ? frames[index] : null;
                            int first = frame == null ? -1 : frame.getStackSize() - arguments.length;
                            if (first < 0) {
                                index++;
                                continue;
                            }
                            int local = (target.method().access & Opcodes.ACC_STATIC) != 0 ? 0 : 1;
                            String targetKey = methodKey(target.owner(), target.method().name, target.method().desc);
                            for (int argument = 0; argument < arguments.length; argument++) {
                                Type type = arguments[argument];
                                if (type.getSort() == Type.INT) {
                                    UiParameterKey key = new UiParameterKey(targetKey, local);
                                    UiValue value = frame.getStack(first + argument);
                                    if (value == null || value.basis() == UiBasis.UNKNOWN
                                            || value.basis() == UiBasis.LEGACY) {
                                        invalid.add(key);
                                        candidates.remove(key);
                                    } else if (!invalid.contains(key)) {
                                        UiValue previous = candidates.putIfAbsent(key, value);
                                        if (previous != null && !previous.equals(value)) {
                                            invalid.add(key);
                                            candidates.remove(key);
                                        }
                                    }
                                }
                                local += type.getSize();
                            }
                            index++;
                        }
                    } catch (AnalyzerException | RuntimeException ignored) {
                    }
                }
            }

            Map<String, Map<Integer, UiValue>> nextMutable = new HashMap<>();
            for (Map.Entry<UiParameterKey, UiValue> entry : candidates.entrySet()) {
                if (invalid.contains(entry.getKey())) continue;
                nextMutable.computeIfAbsent(entry.getKey().method(), ignored -> new HashMap<>())
                        .put(entry.getKey().local(), entry.getValue());
            }
            Map<String, Map<Integer, UiValue>> next = new HashMap<>();
            for (Map.Entry<String, Map<Integer, UiValue>> entry : nextMutable.entrySet()) {
                next.put(entry.getKey(), Map.copyOf(entry.getValue()));
            }
            next = next.isEmpty() ? Map.of() : Map.copyOf(next);
            if (next.equals(known)) return next;
            known = next;
        }
        return known;
    }

    private static UiMethodTarget uiMethodTarget(MethodInsnNode call, Map<String, ClassNode> nodes) {
        if (call == null || nodes == null) return null;
        Set<String> seen = new HashSet<>();
        for (String current = call.owner; current != null && seen.add(current); ) {
            ClassNode node = nodes.get(current);
            if (node == null) return null;
            MethodNode method = findMethod(node, call.name, call.desc);
            if (method != null) return new UiMethodTarget(current, method);
            current = node.superName;
        }
        return null;
    }

    private static boolean zeroArgumentIntMethod(MethodNode method) {
        if (method == null || method.desc == null || method.instructions == null) return false;
        try {
            return Type.getArgumentTypes(method.desc).length == 0
                    && Type.getReturnType(method.desc) == Type.INT_TYPE;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private static UiValue commonIntReturn(MethodNode method, Frame<UiValue>[] frames) {
        if (method == null || frames == null) return null;
        UiValue common = null;
        int index = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction.getOpcode() == Opcodes.IRETURN) {
                Frame<UiValue> frame = index < frames.length ? frames[index] : null;
                if (frame == null || frame.getStackSize() == 0) return null;
                UiValue value = frame.getStack(frame.getStackSize() - 1);
                if (value == null || value.basis() == UiBasis.UNKNOWN
                        || value.basis() == UiBasis.LEGACY) return null;
                if (common == null) common = value;
                else if (!common.equals(value)) return null;
            }
            index++;
        }
        return common;
    }

    private static boolean layoutFieldMethod(MethodNode method, Map<String, ClassNode> nodes) {
        if (looksLikeWidgetInit(method) || constructsWidget(method, nodes)) return true;
        String name = method.name == null ? "" : method.name.toLowerCase(Locale.ROOT);
        return name.contains("setbutton") || name.contains("buildwidget")
                || name.contains("setupwidget") || name.contains("layoutwidget");
    }

    /** Minimal safe integer interpreter; all object/float/control-flow values remain opaque. */
    private static final class UiInterpreter extends Interpreter<UiValue> {
        private final Map<String, ClassNode> nodes;
        private final UiKnowledge knowledge;
        private final Map<Integer, UiValue> parameterValues;

        UiInterpreter(Map<String, ClassNode> nodes, UiKnowledge knowledge,
                      Map<Integer, UiValue> parameterValues) {
            super(Opcodes.ASM9);
            this.nodes = nodes == null ? Map.of() : nodes;
            this.knowledge = knowledge == null ? UiKnowledge.EMPTY : knowledge;
            this.parameterValues = parameterValues == null ? Map.of() : parameterValues;
        }

        @Override public UiValue newValue(Type type) {
            if (type == null) return UiValue.unknown(1);
            if (type == Type.VOID_TYPE) return null;
            return UiValue.unknown(type.getSize());
        }

        @Override public UiValue newParameterValue(boolean isInstanceMethod, int local, Type type) {
            UiValue bound = parameterValues.get(local);
            return bound != null ? bound : newValue(type);
        }

        @Override public UiValue newOperation(AbstractInsnNode instruction) {
            int opcode = instruction.getOpcode();
            if (opcode >= Opcodes.ICONST_M1 && opcode <= Opcodes.ICONST_5) {
                return UiValue.integer(opcode - Opcodes.ICONST_0);
            }
            if (instruction instanceof IntInsnNode value
                    && (opcode == Opcodes.BIPUSH || opcode == Opcodes.SIPUSH)) {
                return UiValue.integer(value.operand);
            }
            if (instruction instanceof LdcInsnNode ldc && ldc.cst instanceof Integer value) {
                return UiValue.integer(value);
            }
            if (instruction instanceof FieldInsnNode field && opcode == Opcodes.GETSTATIC) {
                return valueForField(field);
            }
            if (opcode == Opcodes.LCONST_0 || opcode == Opcodes.LCONST_1
                    || opcode == Opcodes.DCONST_0 || opcode == Opcodes.DCONST_1) {
                return UiValue.unknown(2);
            }
            return UiValue.unknown(1);
        }

        @Override public UiValue copyOperation(AbstractInsnNode instruction, UiValue value) {
            return value;
        }

        @Override public UiValue unaryOperation(AbstractInsnNode instruction, UiValue value) {
            int opcode = instruction.getOpcode();
            if (instruction instanceof FieldInsnNode field && opcode == Opcodes.GETFIELD) {
                return valueForField(field);
            }
            if (opcode == Opcodes.INEG) {
                if (value != null && value.constant()) return UiValue.integer(-value.offset());
                if (value != null && value.basis() == UiBasis.LEGACY) return UiValue.legacy(-value.offset());
            }
            if (opcode == Opcodes.I2L || opcode == Opcodes.I2D || opcode == Opcodes.F2L
                    || opcode == Opcodes.F2D || opcode == Opcodes.LNEG || opcode == Opcodes.DNEG) {
                return UiValue.unknown(2);
            }
            if (opcode == Opcodes.PUTSTATIC || opcode == Opcodes.PUTFIELD
                    || opcode == Opcodes.IFEQ || opcode == Opcodes.IFNE
                    || opcode == Opcodes.IFLT || opcode == Opcodes.IFGE
                    || opcode == Opcodes.IFGT || opcode == Opcodes.IFLE
                    || opcode == Opcodes.IFNULL || opcode == Opcodes.IFNONNULL
                    || opcode == Opcodes.ATHROW || opcode == Opcodes.MONITORENTER
                    || opcode == Opcodes.MONITOREXIT) return null;
            return value == null ? UiValue.unknown(1) : UiValue.unknown(value.size());
        }

        @Override public UiValue binaryOperation(AbstractInsnNode instruction,
                                                 UiValue left, UiValue right) {
            int opcode = instruction.getOpcode();
            if (opcode == Opcodes.IADD || opcode == Opcodes.ISUB) {
                int sign = opcode == Opcodes.IADD ? 1 : -1;
                if (left != null && right != null) {
                    if (left.constant() && right.constant()) {
                        return UiValue.integer(left.offset() + sign * right.offset());
                    }
                    if (right.constant()) {
                        if (left.basis() != UiBasis.UNKNOWN) {
                            return new UiValue(1, left.basis(), left.offset() + sign * right.offset());
                        }
                        return UiValue.legacy(sign * right.offset());
                    }
                    if (opcode == Opcodes.IADD && left.constant()) {
                        if (right.basis() != UiBasis.UNKNOWN) {
                            return new UiValue(1, right.basis(), right.offset() + left.offset());
                        }
                        return UiValue.legacy(left.offset());
                    }
                    if (opcode == Opcodes.IADD) {
                        UiBasis combined = combineBasis(left.basis(), right.basis());
                        if (combined != UiBasis.UNKNOWN) {
                            return UiValue.anchored(combined, left.offset() + right.offset());
                        }
                    }
                }
            }
            if (opcode == Opcodes.IDIV && left != null && right != null
                    && right.constant() && right.offset() == 2) {
                if (left.constant()) return UiValue.integer(left.offset() / 2);
                UiBasis centered = centerBasis(left.basis());
                if (centered != UiBasis.UNKNOWN) {
                    return UiValue.anchored(centered, left.offset() / 2);
                }
            }
            if ((opcode == Opcodes.ISHR || opcode == Opcodes.IUSHR) && left != null && right != null
                    && right.constant() && right.offset() == 1) {
                if (left.constant()) return UiValue.integer(left.offset() >> 1);
                UiBasis centered = centerBasis(left.basis());
                if (centered != UiBasis.UNKNOWN) {
                    return UiValue.anchored(centered, left.offset() / 2);
                }
            }
            if (opcode == Opcodes.IMUL && left != null && right != null) {
                if (left.constant() && right.constant()) {
                    return UiValue.integer(left.offset() * right.offset());
                }
                // Multiplication by one is common after generic scale plumbing and is exactly
                // affine. Other multipliers would require retaining a basis coefficient, so leave
                // them unknown rather than manufacturing a coordinate.
                if (right.constant() && right.offset() == 1) return left;
                if (left.constant() && left.offset() == 1) return right;
            }
            if (opcode == Opcodes.LALOAD || opcode == Opcodes.DALOAD
                    || opcode == Opcodes.LADD || opcode == Opcodes.DADD
                    || opcode == Opcodes.LSUB || opcode == Opcodes.DSUB
                    || opcode == Opcodes.LMUL || opcode == Opcodes.DMUL
                    || opcode == Opcodes.LDIV || opcode == Opcodes.DDIV
                    || opcode == Opcodes.LREM || opcode == Opcodes.DREM) {
                return UiValue.unknown(2);
            }
            return UiValue.unknown(1);
        }

        @Override public UiValue ternaryOperation(AbstractInsnNode instruction,
                                                  UiValue first, UiValue second, UiValue third) {
            return null;
        }

        @Override public UiValue naryOperation(AbstractInsnNode instruction,
                                               List<? extends UiValue> values) {
            String descriptor = instruction instanceof MethodInsnNode call ? call.desc
                    : instruction instanceof InvokeDynamicInsnNode indy ? indy.desc : null;
            if (descriptor == null) return UiValue.unknown(1);
            Type result = Type.getReturnType(descriptor);
            if (instruction instanceof MethodInsnNode call && result == Type.INT_TYPE) {
                UiValue summarized = summarizedMethod(call);
                if (summarized.basis() != UiBasis.UNKNOWN) return summarized;
                if (Type.getArgumentTypes(call.desc).length == 0) {
                    UiValue accessor = valueForAccessor(call);
                    if (accessor.basis() != UiBasis.UNKNOWN) return accessor;
                }
            }
            return result == Type.VOID_TYPE ? null : UiValue.unknown(result.getSize());
        }

        @Override public void returnOperation(AbstractInsnNode instruction,
                                              UiValue value, UiValue expected) {}

        @Override public UiValue merge(UiValue first, UiValue second) {
            if (first == second) return first;
            if (first != null && first.equals(second)) return first;
            return UiValue.unknown(Math.max(first == null ? 1 : first.size(),
                    second == null ? 1 : second.size()));
        }

        private UiValue valueForField(FieldInsnNode field) {
            if (field == null) return UiValue.unknown(1);
            UiValue cached = knowledge.fields().get(fieldKey(field.owner, field.name));
            if (cached != null) return cached;
            ClassNode owner = nodes.get(field.owner);
            if (owner != null && owner.fields != null) {
                for (FieldNode candidate : owner.fields) {
                    if (field.name.equals(candidate.name) && "I".equals(candidate.desc)
                            && candidate.value instanceof Integer value) return UiValue.integer(value);
                }
            }
            if ("I".equals(field.desc) && isScreenType(field.owner, nodes)) {
                String name = field.name == null ? "" : field.name.toLowerCase(Locale.ROOT);
                if (name.contains("guileft") || name.contains("leftpos") || name.equals("f_97735_")) {
                    return UiValue.anchored(UiBasis.START_X, 0);
                }
                if (name.contains("guitop") || name.contains("toppos") || name.equals("f_97736_")) {
                    return UiValue.anchored(UiBasis.START_Y, 0);
                }
                if (name.contains("imagewidth") || name.equals("xsize") || name.equals("f_97726_")) {
                    return UiValue.anchored(UiBasis.SIZE_X, 0);
                }
                if (name.contains("imageheight") || name.equals("ysize") || name.equals("f_97727_")) {
                    return UiValue.anchored(UiBasis.SIZE_Y, 0);
                }
                if (name.equals("width") || name.equals("f_96543_")) {
                    return UiValue.anchored(UiBasis.SIZE_X, 0);
                }
                if (name.equals("height") || name.equals("f_96544_")) {
                    return UiValue.anchored(UiBasis.SIZE_Y, 0);
                }
            }
            return valueForDescriptor(field.desc);
        }

        private UiValue summarizedMethod(MethodInsnNode call) {
            if (call == null) return UiValue.unknown(1);
            String current = call.owner;
            Set<String> seen = new HashSet<>();
            while (current != null && seen.add(current)) {
                UiValue value = knowledge.methods().get(methodKey(current, call.name, call.desc));
                if (value != null) return value;
                ClassNode node = nodes.get(current);
                current = node == null ? null : node.superName;
            }
            return UiValue.unknown(1);
        }

        private UiValue valueForAccessor(MethodInsnNode call) {
            if (call == null) return UiValue.unknown(1);
            boolean screenOwner = isScreenType(call.owner, nodes);
            boolean guiExtentOwner = !screenOwner && guiDimensionAccessorOwner(call.owner);
            if (!screenOwner && !guiExtentOwner) return UiValue.unknown(1);
            String name = call.name == null ? "" : call.name.toLowerCase(Locale.ROOT);
            if (screenOwner && (name.contains("guileft") || name.contains("leftpos")
                    || name.equals("getguileft"))) {
                return UiValue.anchored(UiBasis.START_X, 0);
            }
            if (screenOwner && (name.contains("guitop") || name.contains("toppos")
                    || name.equals("getguitop"))) {
                return UiValue.anchored(UiBasis.START_Y, 0);
            }
            if (name.contains("imagewidth") || name.contains("xsize") || name.equals("getwidth")) {
                return UiValue.anchored(UiBasis.SIZE_X, 0);
            }
            if (name.contains("imageheight") || name.contains("ysize") || name.equals("getheight")) {
                return UiValue.anchored(UiBasis.SIZE_Y, 0);
            }
            return UiValue.unknown(1);
        }

        /**
         * GUI libraries often keep a texture/panel object on the screen and derive widget
         * coordinates from {@code panel.getWidth()/getHeight()} rather than Screen.imageWidth.
         * Treat that pair as the screen-local extent only when the owner is structurally a GUI
         * drawable: it declares both integer accessors and a render method that consumes
         * GuiGraphics. This recovers trailing-edge anchoring without naming a mod or GUI library,
         * while avoiding arbitrary gameplay objects that happen to expose getWidth().
         */
        private boolean guiDimensionAccessorOwner(String ownerName) {
            ClassNode owner = ownerName == null ? null : nodes.get(ownerName);
            if (owner == null || owner.methods == null) return false;
            boolean width = false;
            boolean height = false;
            boolean guiRender = false;
            for (MethodNode method : owner.methods) {
                String name = method.name == null ? "" : method.name.toLowerCase(Locale.ROOT);
                if ("()I".equals(method.desc) && name.equals("getwidth")) width = true;
                if ("()I".equals(method.desc) && name.equals("getheight")) height = true;
                if (method.desc != null && method.desc.contains("Lnet/minecraft/client/gui/GuiGraphics;")) {
                    try {
                        guiRender |= Type.getReturnType(method.desc) == Type.VOID_TYPE;
                    } catch (IllegalArgumentException ignored) {
                    }
                }
            }
            return width && height && guiRender;
        }

        private static UiBasis combineBasis(UiBasis first, UiBasis second) {
            if (first == UiBasis.START_X && second == UiBasis.SIZE_X
                    || first == UiBasis.SIZE_X && second == UiBasis.START_X) return UiBasis.END_X;
            if (first == UiBasis.START_Y && second == UiBasis.SIZE_Y
                    || first == UiBasis.SIZE_Y && second == UiBasis.START_Y) return UiBasis.END_Y;
            // Container screens commonly center controls with
            // guiLeft + (imageWidth - widgetWidth) / 2. START represents the panel origin while
            // CENTER represents half of that same panel extent, so their sum is still panel-center
            // relative. Preserve that provenance instead of collapsing the final coordinate to
            // unknown just because both symbolic terms are present.
            if (first == UiBasis.START_X && second == UiBasis.CENTER_X
                    || first == UiBasis.CENTER_X && second == UiBasis.START_X) return UiBasis.CENTER_X;
            if (first == UiBasis.START_Y && second == UiBasis.CENTER_Y
                    || first == UiBasis.CENTER_Y && second == UiBasis.START_Y) return UiBasis.CENTER_Y;
            return UiBasis.UNKNOWN;
        }

        private static UiBasis centerBasis(UiBasis basis) {
            if (basis == UiBasis.SIZE_X) return UiBasis.CENTER_X;
            if (basis == UiBasis.SIZE_Y) return UiBasis.CENTER_Y;
            return UiBasis.UNKNOWN;
        }

        private static UiValue valueForDescriptor(String descriptor) {
            try {
                Type type = Type.getType(descriptor);
                return UiValue.unknown(type.getSize());
            } catch (IllegalArgumentException ignored) {
                return UiValue.unknown(1);
            }
        }
    }

    private static boolean looksLikeWidgetInit(MethodNode method) {
        if (method == null || method.instructions == null) return false;
        String name = method.name == null ? "" : method.name.toLowerCase(Locale.ROOT);
        if (name.equals("init") || name.contains("initwidgets") || name.contains("addwidgets")) return true;
        // Obfuscated production names cannot be trusted. Presence of a clickable widget callback
        // is the semantic signal and still keeps this analysis scoped to screen subclasses.
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof InvokeDynamicInsnNode indy
                    && lambdaImplementation(indy) != null
                    && Type.getReturnType(indy.desc).getInternalName() != null) return true;
        }
        return false;
    }

    private static boolean looksLikeClickableWidgetConstructor(MethodInsnNode ctor) {
        if (ctor == null || ctor.desc == null) return false;
        String owner = ctor.owner == null ? "" : ctor.owner.toLowerCase(Locale.ROOT);
        String desc = ctor.desc.toLowerCase(Locale.ROOT);
        return desc.contains("button$onpress") || desc.contains("pressablewidget")
                || desc.contains("onpress") || (owner.contains("button")
                && (desc.contains("function") || desc.contains("consumer")))
                || owner.contains("button") || owner.contains("scrollinput")
                || owner.contains("checkbox") || owner.contains("toggle")
                || owner.contains("editbox") || owner.contains("textfield")
                || owner.contains("textfield");
    }

    /** Vanilla and many UI libraries construct widgets through fluent builders instead of NEW.
     * Treat a returned widget type plus a callback/terminal factory as the structural signal. */
    private static boolean looksLikeWidgetFactory(MethodInsnNode call,
                                                  Map<String, ClassNode> nodes) {
        if (call == null || call.desc == null || "<init>".equals(call.name)) return false;
        String returned = returnedObject(call.desc);
        if (!isWidgetType(returned, nodes)) return false;
        String lowerType = returned.toLowerCase(Locale.ROOT);
        if (lowerType.endsWith("$builder") || lowerType.endsWith("builder")) return false;
        String name = call.name == null ? "" : call.name.toLowerCase(Locale.ROOT);
        if (name.contains("addrenderable") || name.contains("addwidget")
                || name.contains("register")) return false;

        // A callback/configuration method invoked on an existing widget commonly returns `this`
        // (sometimes widened to an abstract widget superclass) to keep a fluent chain going. It is
        // not a second control. Treat widget->widget instance calls as factories only when their
        // bytecode proves that they actually allocate/return a fresh widget. This avoids duplicate
        // geometry-less controls while still allowing genuine copy/clone-style widget methods.
        if (call.getOpcode() != Opcodes.INVOKESTATIC && isWidgetType(call.owner, nodes)
                && !builderLikeType(call.owner, nodes)) {
            return methodProducesFreshWidget(call, nodes, new HashSet<>(), 0);
        }

        String descriptor = call.desc.toLowerCase(Locale.ROOT);
        if (descriptor.contains("java/lang/runnable")
                || descriptor.contains("java/util/function/")
                || descriptor.contains("onpress") || name.contains("build")
                || name.contains("create") || name.equals("of") || name.startsWith("m_")) return true;
        String owner = call.owner == null ? "" : call.owner.toLowerCase(Locale.ROOT);
        if (owner.endsWith("builder") || owner.contains("$builder") || owner.endsWith("factory")) return true;
        return methodProducesFreshWidget(call, nodes, new HashSet<>(), 0);
    }

    private static boolean methodProducesFreshWidget(MethodInsnNode call, Map<String, ClassNode> nodes,
                                                     Set<String> visiting, int depth) {
        if (call == null || nodes == null || depth > 4) return false;
        String key = methodKey(call.owner, call.name, call.desc);
        if (!visiting.add(key)) return false;
        try {
            UiMethodTarget target = uiMethodTarget(call, nodes);
            if (target == null || target.method().instructions == null
                    || target.method().instructions.size() > 4_000) return false;
            for (AbstractInsnNode instruction : target.method().instructions) {
                if (instruction instanceof TypeInsnNode type && type.getOpcode() == Opcodes.NEW
                        && isWidgetType(type.desc, nodes)) return true;
                if (!(instruction instanceof MethodInsnNode nested)) continue;
                if (nested.getOpcode() == Opcodes.INVOKESPECIAL && "<init>".equals(nested.name)
                        && isWidgetType(nested.owner, nodes)) return true;
                String returned = returnedObject(nested.desc);
                if (isWidgetType(returned, nodes)
                        && methodProducesFreshWidget(nested, nodes, visiting, depth + 1)) return true;
            }
            return false;
        } finally {
            visiting.remove(key);
        }
    }

    /** Assigning a freshly-created widget object into a screen field is UI graph bookkeeping,
     * not semantic client state. The translated scene already owns the control identity; its live
     * active/visible/scalar fields are scanned independently and remain semantic actions. */
    static boolean widgetReferenceStateField(FieldInsnNode field, Map<String, ClassNode> nodes) {
        if (field == null || field.desc == null) return false;
        String type = objectInternalName(field.desc);
        return type != null && isWidgetType(type, nodes);
    }

    private static boolean isWidgetType(String type, Map<String, ClassNode> nodes) {
        if (type == null) return false;
        if (isScreenType(type, nodes)) return false;
        Set<String> seen = new HashSet<>();
        String current = type;
        while (current != null && seen.add(current)) {
            String lower = current.toLowerCase(Locale.ROOT);
            if (lower.endsWith("/abstractwidget") || lower.endsWith("/abstractbutton")
                    || lower.contains("button") || lower.contains("checkbox")
                    || lower.contains("slider") || lower.contains("scrollinput")
                    || lower.contains("editbox") || lower.contains("textfield")
                    || lower.contains("toggle")) return true;
            ClassNode node = nodes == null ? null : nodes.get(current);
            current = node == null ? null : node.superName;
        }
        return structurallyClickableComponent(type, nodes);
    }

    /** Name-independent fallback for custom GUI frameworks. A component is treated as a widget
     * when its hierarchy participates in Minecraft's GUI-event contract and owns a concrete click
     * handler. Screen classes are excluded above so navigation helpers are not mistaken for child
     * controls merely because Screen itself handles mouse input. */
    private static boolean structurallyClickableComponent(String type, Map<String, ClassNode> nodes) {
        if (type == null || nodes == null || nodes.isEmpty()) return false;
        ArrayList<String> queue = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        queue.add(type);
        boolean eventContract = false;
        boolean clickHandler = false;
        for (int index = 0; index < queue.size() && index < 48; index++) {
            String current = queue.get(index);
            if (current == null || !seen.add(current)) continue;
            String lower = current.toLowerCase(Locale.ROOT);
            if (lower.endsWith("/guieventlistener") || lower.endsWith("/eventlistener")
                    || lower.endsWith("/narratableentry") || lower.endsWith("/renderable")) {
                eventContract = true;
            }
            ClassNode node = nodes.get(current);
            if (node == null) continue;
            if (node.methods != null && node.methods.stream().anyMatch(ClientSurfaceScanner::clickHandlerShape)) {
                clickHandler = true;
            }
            if (node.superName != null) queue.add(node.superName);
            if (node.interfaces != null) queue.addAll(node.interfaces);
            if (eventContract && clickHandler) return true;
        }
        return eventContract && clickHandler;
    }

    private enum LayoutMutationKind {
        NONE, BOUNDS, POSITION, SIZE, X, Y, WIDTH, HEIGHT
    }

    private static LayoutMutationKind layoutMutationKind(MethodInsnNode call,
                                                         Map<String, ClassNode> nodes) {
        if (call == null || call.desc == null) return LayoutMutationKind.NONE;
        int integers = 0;
        try {
            for (Type argument : Type.getArgumentTypes(call.desc)) {
                if (argument.getSort() == Type.INT) integers++;
            }
        } catch (IllegalArgumentException ignored) {
            return LayoutMutationKind.NONE;
        }
        if (integers >= 4) return LayoutMutationKind.BOUNDS;
        if (integers == 0) return LayoutMutationKind.NONE;
        String name = call.name == null ? "" : call.name.toLowerCase(Locale.ROOT);
        if (integers == 2) {
            if (name.contains("size") || name.contains("dimension")) return LayoutMutationKind.SIZE;
            if (name.contains("bound")) return LayoutMutationKind.BOUNDS;
            if (name.contains("position") || name.contains("location") || name.equals("pos")
                    || name.equals("move") || name.contains("origin")) return LayoutMutationKind.POSITION;
        } else if (integers == 1) {
            if (name.contains("width") || name.equals("setw") || name.equals("w")) {
                return LayoutMutationKind.WIDTH;
            }
            if (name.contains("height") || name.equals("seth") || name.equals("h")) {
                return LayoutMutationKind.HEIGHT;
            }
            if (name.equals("x") || name.equals("setx") || name.endsWith("xposition")) {
                return LayoutMutationKind.X;
            }
            if (name.equals("y") || name.equals("sety") || name.endsWith("yposition")) {
                return LayoutMutationKind.Y;
            }
        }
        return structuralLayoutMutation(call, nodes, new HashSet<>(), 0);
    }

    private static LayoutMutationKind structuralLayoutMutation(MethodInsnNode call,
                                                               Map<String, ClassNode> nodes,
                                                               Set<String> visiting, int depth) {
        if (call == null || nodes == null || depth > 4) return LayoutMutationKind.NONE;
        String key = methodKey(call.owner, call.name, call.desc);
        if (!visiting.add(key)) return LayoutMutationKind.NONE;
        try {
            UiMethodTarget target = uiMethodTarget(call, nodes);
            if (target == null || target.method().instructions == null
                    || target.method().instructions.size() > 1_500) return LayoutMutationKind.NONE;
            Type[] arguments;
            try {
                arguments = Type.getArgumentTypes(target.method().desc);
            } catch (IllegalArgumentException ignored) {
                return LayoutMutationKind.NONE;
            }
            int integerCount = 0;
            for (Type argument : arguments) if (argument.getSort() == Type.INT) integerCount++;
            if (integerCount == 0 || integerCount > 2) return LayoutMutationKind.NONE;

            java.util.EnumSet<LayoutMutationKind> writes = java.util.EnumSet.noneOf(LayoutMutationKind.class);
            for (AbstractInsnNode instruction : target.method().instructions) {
                if (instruction instanceof FieldInsnNode field && field.getOpcode() == Opcodes.PUTFIELD
                        && "I".equals(field.desc)) {
                    LayoutMutationKind kind = layoutFieldKind(field.name);
                    if (kind != LayoutMutationKind.NONE) writes.add(kind);
                } else if (instruction instanceof MethodInsnNode nested) {
                    LayoutMutationKind nestedKind = structuralLayoutMutation(
                            nested, nodes, visiting, depth + 1);
                    if (nestedKind != LayoutMutationKind.NONE) writes.add(nestedKind);
                }
            }
            if (integerCount == 1 && writes.size() == 1) return writes.iterator().next();
            if (integerCount == 2) {
                if (writes.contains(LayoutMutationKind.X) && writes.contains(LayoutMutationKind.Y)) {
                    return LayoutMutationKind.POSITION;
                }
                if (writes.contains(LayoutMutationKind.WIDTH) && writes.contains(LayoutMutationKind.HEIGHT)) {
                    return LayoutMutationKind.SIZE;
                }
            }
            return LayoutMutationKind.NONE;
        } finally {
            visiting.remove(key);
        }
    }

    private static LayoutMutationKind layoutFieldKind(String fieldName) {
        String name = fieldName == null ? "" : fieldName.toLowerCase(Locale.ROOT);
        if (name.equals("x") || name.equals("left") || name.equals("f_93620_")) return LayoutMutationKind.X;
        if (name.equals("y") || name.equals("top") || name.equals("f_93621_")) return LayoutMutationKind.Y;
        if (name.equals("width") || name.equals("f_93618_")) return LayoutMutationKind.WIDTH;
        if (name.equals("height") || name.equals("f_93619_")) return LayoutMutationKind.HEIGHT;
        return LayoutMutationKind.NONE;
    }

    private static FieldInsnNode storedWidgetFieldAfter(AbstractInsnNode instruction, int limit) {
        for (AbstractInsnNode next = instruction == null ? null : instruction.getNext();
             next != null && limit-- > 0; next = next.getNext()) {
            if (next instanceof FieldInsnNode field
                    && (field.getOpcode() == Opcodes.PUTFIELD || field.getOpcode() == Opcodes.PUTSTATIC)) {
                return field;
            }
            if (next instanceof MethodInsnNode call && "<init>".equals(call.name)) break;
        }
        return null;
    }

    private static boolean callbackRegistration(MethodInsnNode call, Map<String, ClassNode> nodes) {
        if (call == null || call.desc == null || "<init>".equals(call.name)) return false;
        String name = call.name == null ? "" : call.name.toLowerCase(Locale.ROOT);
        if (!acceptsCallbackArgument(call, nodes)) return false;
        if (isWidgetType(call.owner, nodes) || isWidgetType(returnedObject(call.desc), nodes)
                || builderLikeType(call.owner, nodes)) return true;
        return name.contains("callback") || name.contains("calling") || name.contains("onpress")
                || name.contains("listener") || name.startsWith("set") || name.startsWith("with");
    }

    /** Constructor/factory callback ownership must be proven by its descriptor, not proximity.
     * Otherwise a callback-less widget immediately before a real button can borrow the button's
     * invokedynamic implementation. Besides JDK functional interfaces, accept a classpath-visible
     * single-abstract-method interface structurally. */
    private static boolean acceptsCallbackArgument(MethodInsnNode call, Map<String, ClassNode> nodes) {
        if (call == null || call.desc == null) return false;
        Type[] arguments;
        try {
            arguments = Type.getArgumentTypes(call.desc);
        } catch (IllegalArgumentException ignored) {
            return false;
        }
        for (Type argument : arguments) {
            if (argument.getSort() != Type.OBJECT) continue;
            String type = argument.getInternalName();
            String lower = type.toLowerCase(Locale.ROOT);
            // Suppliers provide presentation/data lazily (tooltip text, labels, values); invoking
            // them is not a user action. Treating a Supplier passed to a widget setter as another
            // click callback creates phantom interactions beside the widget's real Runnable/Consumer.
            if (jdkSupplierType(type)) continue;
            if ("java/lang/Runnable".equals(type) || type.startsWith("java/util/function/")
                    || lower.contains("button$onpress") || lower.endsWith("/onpress")) return true;
            ClassNode node = nodes == null ? null : nodes.get(type);
            if (node == null || (node.access & Opcodes.ACC_INTERFACE) == 0 || node.methods == null) continue;
            int abstractMethods = 0;
            for (MethodNode method : node.methods) {
                if (method == null || (method.access & Opcodes.ACC_ABSTRACT) == 0
                        || (method.access & Opcodes.ACC_STATIC) != 0) continue;
                if (++abstractMethods > 1) break;
            }
            if (abstractMethods == 1) return true;
        }
        return false;
    }

    private static boolean jdkSupplierType(String type) {
        if (type == null || !type.startsWith("java/util/function/")) return false;
        String simple = type.substring(type.lastIndexOf('/') + 1);
        return simple.endsWith("Supplier");
    }

    private static boolean builderLikeType(String type, Map<String, ClassNode> nodes) {
        if (type == null) return false;
        String lower = type.toLowerCase(Locale.ROOT);
        if (lower.endsWith("builder") || lower.contains("$builder") || lower.endsWith("factory")) return true;
        ClassNode node = nodes == null ? null : nodes.get(type);
        if (node == null || node.methods == null) return false;
        for (MethodNode method : node.methods) {
            if (method == null || method.desc == null) continue;
            String returned = returnedObject(method.desc);
            if (!isWidgetType(returned, nodes)) continue;
            // Fluent widgets commonly return `this`, or widen `this` to an abstract widget base,
            // from configuration/callback setters. That does not make the widget itself a builder.
            // A builder/factory structurally produces a *different* widget type. Keeping this
            // hierarchy distinction prevents self-returning mutators from being scanned twice while
            // still recognizing anonymous/non-obviously-named builder classes.
            if (sameTypeHierarchy(type, returned, nodes)) continue;
            return true;
        }
        return false;
    }

    private static boolean sameTypeHierarchy(String first, String second,
                                             Map<String, ClassNode> nodes) {
        if (first == null || second == null) return false;
        if (first.equals(second)) return true;
        return inheritsType(first, second, nodes, new HashSet<>(), 0)
                || inheritsType(second, first, nodes, new HashSet<>(), 0);
    }

    private static boolean inheritsType(String child, String parent,
                                        Map<String, ClassNode> nodes,
                                        Set<String> visiting, int depth) {
        if (child == null || parent == null || depth > 32 || !visiting.add(child)) return false;
        if (child.equals(parent)) return true;
        ClassNode node = nodes == null ? null : nodes.get(child);
        if (node == null) return false;
        if (parent.equals(node.superName)) return true;
        if (node.interfaces != null && node.interfaces.contains(parent)) return true;
        if (node.superName != null
                && inheritsType(node.superName, parent, nodes, visiting, depth + 1)) return true;
        if (node.interfaces != null) {
            for (String iface : node.interfaces) {
                if (inheritsType(iface, parent, nodes, visiting, depth + 1)) return true;
            }
        }
        return false;
    }

    private static Map<Integer, Handle> lambdaLocals(MethodNode method) {
        Map<Integer, Handle> out = new HashMap<>();
        if (method == null || method.instructions == null) return out;
        for (AbstractInsnNode instruction : method.instructions) {
            if (!(instruction instanceof InvokeDynamicInsnNode indy)) continue;
            Handle implementation = lambdaImplementation(indy);
            if (implementation == null) continue;
            int remaining = 3;
            for (AbstractInsnNode next = instruction.getNext(); next != null && remaining-- > 0;
                 next = next.getNext()) {
                if (next instanceof VarInsnNode var && var.getOpcode() == Opcodes.ASTORE) {
                    out.put(var.var, implementation);
                    break;
                }
                if (next.getOpcode() >= 0) break;
            }
        }
        return out;
    }

    private static Map<String, Handle> discoverLambdaFields(ClassNode owner) {
        if (owner == null || owner.methods == null) return Map.of();
        Map<String, Handle> result = new HashMap<>();
        Set<String> conflicts = new HashSet<>();
        for (MethodNode method : owner.methods) {
            if (method == null || method.instructions == null) continue;
            for (AbstractInsnNode instruction : method.instructions) {
                if (!(instruction instanceof InvokeDynamicInsnNode indy)) continue;
                Handle handle = lambdaImplementation(indy);
                if (handle == null) continue;
                int remaining = 5;
                for (AbstractInsnNode next = instruction.getNext(); next != null && remaining-- > 0;
                     next = next.getNext()) {
                    if (next instanceof FieldInsnNode field
                            && (field.getOpcode() == Opcodes.PUTFIELD || field.getOpcode() == Opcodes.PUTSTATIC)) {
                        String key = fieldKey(field.owner, field.name);
                        if (conflicts.contains(key)) break;
                        Handle previous = result.putIfAbsent(key, handle);
                        if (previous != null && !previous.equals(handle)) {
                            result.remove(key);
                            conflicts.add(key);
                        }
                        break;
                    }
                    if (next instanceof InvokeDynamicInsnNode || next instanceof MethodInsnNode) break;
                }
            }
        }
        return result.isEmpty() ? Map.of() : Map.copyOf(result);
    }

    /** Resolve the receiver of a fluent callback-registration call without borrowing an unrelated
     * nearby widget. Registrations frequently happen before the widget is stored to its screen
     * field (for example ctor().titled(...).calling(callback).setState(...)), so field-only
     * matching loses real callbacks. Conversely, a proximity-only field search can attach that
     * callback to a preceding Label/Button that merely supplied display chrome. Prefer an exact
     * compatible field read, otherwise the nearest compatible widget construction/factory in the
     * same straight-line fluent chain. */
    private static WidgetRef referencedWidgetBefore(AbstractInsnNode instruction,
                                                    String receiverType,
                                                    Map<String, WidgetRef> widgetFields,
                                                    Map<AbstractInsnNode, WidgetRef> widgetOrigins,
                                                    Map<String, ClassNode> nodes,
                                                    int limit) {
        if ((widgetFields == null || widgetFields.isEmpty())
                && (widgetOrigins == null || widgetOrigins.isEmpty())) return null;
        for (AbstractInsnNode previous = instruction == null ? null : instruction.getPrevious();
             previous != null && limit-- > 0; previous = previous.getPrevious()) {
            if (previous instanceof FieldInsnNode field
                    && (field.getOpcode() == Opcodes.GETFIELD || field.getOpcode() == Opcodes.GETSTATIC)) {
                WidgetRef ref = widgetFields == null ? null : widgetFields.get(fieldKey(field.owner, field.name));
                if (ref != null && assignableTo(ref.widgetClass(), receiverType, nodes)) return ref;
            }
            WidgetRef origin = widgetOrigins == null ? null : widgetOrigins.get(previous);
            if (origin != null) {
                return assignableTo(origin.widgetClass(), receiverType, nodes) ? origin : null;
            }
            if (previous instanceof MethodInsnNode call) {
                // Calls returning the same receiver family are fluent stages, not ownership
                // boundaries. A different widget construction/factory is a hard boundary: walking
                // past it would reintroduce proximity-based cross-widget callback borrowing.
                String returned = returnedObject(call.desc);
                if (returned != null && assignableTo(returned, receiverType, nodes)) continue;
                if ((call.getOpcode() == Opcodes.INVOKESPECIAL && "<init>".equals(call.name))
                        || looksLikeWidgetFactory(call, nodes)) break;
            }
        }
        return null;
    }

    private static Handle callbackHandleBefore(AbstractInsnNode instruction,
                                               Map<Integer, Handle> lambdaLocals,
                                               Map<String, Handle> lambdaFields,
                                               Map<String, ClassNode> nodes, int limit) {
        for (AbstractInsnNode previous = instruction == null ? null : instruction.getPrevious();
             previous != null && limit-- > 0; previous = previous.getPrevious()) {
            if (previous instanceof InvokeDynamicInsnNode indy) {
                String functionalType = returnedObject(indy.desc);
                if (jdkSupplierType(functionalType)) continue;
                Handle handle = lambdaImplementation(indy);
                if (handle != null) return handle;
            }
            if (previous instanceof VarInsnNode var && var.getOpcode() == Opcodes.ALOAD) {
                Handle handle = lambdaLocals.get(var.var);
                if (handle != null) return handle;
            }
            if (previous instanceof FieldInsnNode field
                    && (field.getOpcode() == Opcodes.GETFIELD || field.getOpcode() == Opcodes.GETSTATIC)) {
                Handle handle = lambdaFields.get(fieldKey(field.owner, field.name));
                if (handle != null) return handle;
            }
            if (previous instanceof MethodInsnNode call) {
                // A callback registered on the previous widget is a hard ownership boundary.
                // Without this stop, a following callback-less constructor can accidentally borrow
                // that lambda (Create's station cancel button inherited tryAssemble this way).
                // Builder stages are different: their callback-bearing method belongs to the same
                // chain as the later terminal build(), so keep walking through builder returns.
                if (looksLikeWidgetFactory(call, nodes)) break;
                if (callbackRegistration(call, nodes)
                        && !builderLikeType(returnedObject(call.desc), nodes)) break;
                if (call.getOpcode() == Opcodes.INVOKESPECIAL && "<init>".equals(call.name)) break;
            }
        }
        return null;
    }

    /**
     * Resolves the callback owned by one concrete widget construction chain. Direct callback
     * constructors/factories are trivial. Builder-backed widgets need one extra proof because the
     * terminal {@code build()} call, or a custom widget constructor receiving the configured
     * builder, no longer carries the callback in its own descriptor.
     *
     * <p>We only follow backwards when the same unfinished chain contains a callback-bearing
     * registration whose result is builder-like. {@link #callbackHandleBefore} already stops at a
     * completed prior widget, so a callback from an earlier sibling control cannot leak forward.</p>
     */
    private static Handle widgetCallbackHandleBefore(
            AbstractInsnNode instruction, MethodInsnNode terminal,
            Map<Integer, Handle> lambdaLocals, Map<String, Handle> lambdaFields,
            Map<String, ClassNode> nodes, int limit) {
        if (terminal == null) return null;
        if (acceptsCallbackArgument(terminal, nodes)) {
            return callbackHandleBefore(instruction, lambdaLocals, lambdaFields, nodes, limit);
        }
        if (builderLikeType(terminal.owner, nodes)
                && isWidgetType(returnedObject(terminal.desc), nodes)) {
            return callbackHandleBefore(instruction, lambdaLocals, lambdaFields, nodes, limit);
        }
        if (!hasBuilderCallbackRegistrationBefore(instruction, nodes, limit)) return null;
        return callbackHandleBefore(instruction, lambdaLocals, lambdaFields, nodes, limit);
    }

    private static boolean hasBuilderCallbackRegistrationBefore(AbstractInsnNode instruction,
                                                                Map<String, ClassNode> nodes,
                                                                int limit) {
        for (AbstractInsnNode previous = instruction == null ? null : instruction.getPrevious();
             previous != null && limit-- > 0; previous = previous.getPrevious()) {
            if (!(previous instanceof MethodInsnNode call)) continue;
            if (callbackRegistration(call, nodes)
                    && builderLikeType(returnedObject(call.desc), nodes)) return true;
            if (looksLikeWidgetFactory(call, nodes)) return false;
            if (call.getOpcode() == Opcodes.INVOKESPECIAL && "<init>".equals(call.name)
                    && clickableWidgetConstructor(call, nodes)) return false;
        }
        return false;
    }

    private static Handle lambdaImplementation(InvokeDynamicInsnNode indy) {
        if (indy == null || indy.bsmArgs == null) return null;
        for (Object argument : indy.bsmArgs) {
            if (argument instanceof Handle handle) return handle;
        }
        return null;
    }

    /** Static Component fields are common labels; recover their translation/literal source. */
    private static Map<String, String> discoverComponentFields(ClassNode owner) {
        Map<String, String> out = new HashMap<>();
        if (owner == null || owner.methods == null) return out;
        for (MethodNode method : owner.methods) {
            if (!"<clinit>".equals(method.name)) continue;
            for (AbstractInsnNode instruction : method.instructions) {
                if (!(instruction instanceof FieldInsnNode field)
                        || field.getOpcode() != Opcodes.PUTSTATIC
                        || !field.owner.equals(owner.name)
                        || !looksLikeComponentDescriptor(field.desc)) continue;
                String text = nearestString(instruction, 18);
                if (text != null) out.put(fieldKey(field.owner, field.name), text);
            }
        }
        return out;
    }

    private static boolean looksLikeComponentDescriptor(String descriptor) {
        if (descriptor == null) return false;
        String lower = descriptor.toLowerCase(Locale.ROOT);
        return lower.contains("network/chat/component") || lower.contains("mutablecomponent");
    }

    private static String widgetLabelBefore(AbstractInsnNode instruction,
                                            Map<String, String> componentFields, int limit) {
        for (AbstractInsnNode previous = instruction == null ? null : instruction.getPrevious();
             previous != null && limit-- > 0; previous = previous.getPrevious()) {
            if (previous instanceof FieldInsnNode field) {
                String value = componentFields.get(fieldKey(field.owner, field.name));
                if (value != null) return value;
                // Icon-only controls frequently use a static sprite/enum field as their only
                // label evidence. Preserve that authored semantic for vanilla hover text even
                // when the screen never constructs a Component for the icon.
                if (field.getOpcode() == Opcodes.GETSTATIC && field.name != null
                        && !field.name.matches("f_\\d+_?")) {
                    String semantic = field.name.replaceFirst("^[iI]_", "")
                            .replace('_', ' ').trim();
                    if (semantic.length() >= 3) return semantic.toLowerCase(Locale.ROOT);
                }
            }
            if (previous instanceof LdcInsnNode ldc && ldc.cst instanceof String text
                    && !text.isBlank()) return text;
            if (previous instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESPECIAL
                    && "<init>".equals(call.name)) break;
        }
        return null;
    }

    private static String nearestString(AbstractInsnNode instruction, int limit) {
        for (AbstractInsnNode previous = instruction == null ? null : instruction.getPrevious();
             previous != null && limit-- > 0; previous = previous.getPrevious()) {
            if (previous instanceof LdcInsnNode ldc && ldc.cst instanceof String text
                    && !text.isBlank()) return text;
        }
        return null;
    }

    private static void emitFactsForWidget(ClassSurface surface, String sourceScreen,
                                           String widgetClass, int ordinal, String label,
                                           int x, int y, int width, int height,
                                           String method, MethodFacts facts,
                                           Map<String, ClassNode> nodes, int confidence) {
        if (facts.closesScreen) {
            addWidgetAction(surface, ActionType.CLOSE_SCREEN, sourceScreen, widgetClass,
                    ordinal, label, x, y, width, height, method, sourceScreen, confidence);
        }
        if (facts.navigatesBack) {
            addWidgetAction(surface, ActionType.NAVIGATE_BACK, sourceScreen, widgetClass,
                    ordinal, label, x, y, width, height, method, "previous_screen", confidence);
        }
        for (String screen : facts.screens) {
            addWidgetAction(surface, ActionType.OPEN_SCREEN, sourceScreen, widgetClass,
                    ordinal, label, x, y, width, height, method, screen, confidence);
        }
        for (String url : facts.externalUrls) {
            addWidgetAction(surface, ActionType.OPEN_URL, sourceScreen, widgetClass,
                    ordinal, label, x, y, width, height, method, url, confidence);
        }
        for (PacketFact packet : facts.packets) {
            addWidgetAction(surface, ActionType.SEND_PACKET, sourceScreen, widgetClass,
                    ordinal, label, x, y, width, height, method, packet.target(), confidence,
                    packet.constructorDesc(), packet.booleanArgument(), packet.staticFieldArgument(), packet.arguments(),
                    packet.producerOwner(), packet.producerMethod(), packet.producerDesc());
        }
        for (RuntimeLaunch launch : facts.runtimeLaunches) {
            addWidgetAction(surface, launch.type(), sourceScreen, widgetClass,
                    ordinal, label, x, y, width, height, method, launch.target(), Math.min(confidence, 96));
        }
        if (!facts.packets.isEmpty() && !facts.stateWrites.isEmpty()) {
            // A packet can read a source-screen scalar immediately after this same callback wrote
            // it. Publish only state writes that are exact dependencies of a SCREEN_FIELD packet
            // argument; unrelated callback bookkeeping remains collapsed as before. Runtime may
            // then materialize the packet transactionally from F2V's virtual screen state without
            // loading or reflecting the original client Screen.
            LinkedHashSet<String> packetFields = new LinkedHashSet<>();
            for (PacketFact packet : facts.packets) {
                if (packet == null || packet.arguments() == null) continue;
                for (PacketArgument argument : packet.arguments()) {
                    if (argument == null || argument.kind != PacketArgumentKind.SCREEN_FIELD
                            || argument.fieldOwner == null || argument.fieldName == null
                            || argument.fieldDesc == null) continue;
                    packetFields.add(argument.fieldOwner + "." + argument.fieldName + ':'
                            + argument.fieldDesc);
                }
            }
            if (!packetFields.isEmpty()) {
                for (StateMutation mutation : facts.stateWrites) {
                    if (mutation == null || mutation.kind() == null || mutation.target() == null
                            || !packetFields.contains(mutation.target())
                            || renderOnlyStateMutation(mutation, nodes)) continue;
                    addWidgetStateAction(surface, sourceScreen, widgetClass, ordinal, label,
                            x, y, width, height, method, mutation, false,
                            Math.min(confidence, 96));
                }
            }
        }
        if (!facts.navigatesBack && facts.screens.isEmpty()
                && facts.externalUrls.isEmpty() && facts.packets.isEmpty()
                && facts.runtimeLaunches.isEmpty()) {
            for (ClientHelperCall helper : facts.clientHelpers) {
                addWidgetHelperAction(surface, sourceScreen, widgetClass, ordinal, label,
                        x, y, width, height, method, helper, Math.min(confidence, 80));
            }
            emitCollectionWidgetFacts(surface, sourceScreen, widgetClass, ordinal, label,
                    x, y, width, height, method, facts, nodes, confidence);
            for (StateMutation mutation : facts.stateWrites) {
                addWidgetStateAction(surface, sourceScreen, widgetClass, ordinal, label,
                        x, y, width, height, method, mutation,
                        renderOnlyStateMutation(mutation, nodes), Math.min(confidence, 86));
            }
        }
    }



    private static void emitCollectionWidgetFacts(ClassSurface surface, String sourceScreen,
                                                  String widgetClass, int ordinal, String label,
                                                  int x, int y, int width, int height,
                                                  String method, MethodFacts facts,
                                                  Map<String, ClassNode> nodes, int confidence) {
        if (facts.collectionMutations.isEmpty()) return;
        Map<String, Set<String>> operations = new HashMap<>();
        Map<String, CollectionMutation> templates = new HashMap<>();
        for (CollectionMutation mutation : facts.collectionMutations) {
            String key = mutation.accessorOwner + '#' + mutation.accessorMethod + mutation.accessorDesc
                    + '|' + mutation.valueFieldOwner + '.' + mutation.valueFieldName
                    + ':' + mutation.valueFieldDesc;
            operations.computeIfAbsent(key, ignored -> new LinkedHashSet<>()).add(mutation.operation);
            templates.putIfAbsent(key, mutation);
        }
        for (Map.Entry<String, Set<String>> entry : operations.entrySet()) {
            if (!(entry.getValue().contains("add") && entry.getValue().contains("remove"))) continue;
            CollectionMutation mutation = templates.get(entry.getKey());
            RegistrySource source = registrySourceForField(widgetClass, mutation, nodes);
            if (source == null) continue;
            addWidgetCollectionAction(surface, sourceScreen, widgetClass, ordinal, label,
                    x, y, width, height, method, mutation, source, Math.min(confidence, 96));
        }
    }

    private static RegistrySource registrySourceForField(String widgetClass,
                                                         CollectionMutation mutation,
                                                         Map<String, ClassNode> nodes) {
        if (widgetClass == null || mutation == null || nodes == null
                || !widgetClass.equals(mutation.valueFieldOwner)) return null;
        ClassNode widget = nodes.get(widgetClass);
        if (widget == null || widget.methods == null) return null;
        for (MethodNode method : widget.methods) {
            if (!"<init>".equals(method.name)) continue;
            for (AbstractInsnNode instruction : method.instructions) {
                if (!(instruction instanceof FieldInsnNode store)
                        || store.getOpcode() != Opcodes.PUTFIELD
                        || !mutation.valueFieldOwner.equals(store.owner)
                        || !mutation.valueFieldName.equals(store.name)) continue;
                MethodInsnNode keyCall = null;
                FieldInsnNode registry = null;
                int remaining = 18;
                for (AbstractInsnNode previous = store.getPrevious(); previous != null && remaining-- > 0;
                     previous = previous.getPrevious()) {
                    if (keyCall == null && previous instanceof MethodInsnNode call) {
                        try {
                            if (Type.getReturnType(call.desc).getDescriptor().equals(store.desc)) keyCall = call;
                        } catch (IllegalArgumentException ignored) {
                        }
                    } else if (keyCall != null && previous instanceof FieldInsnNode field
                            && field.getOpcode() == Opcodes.GETSTATIC) {
                        registry = field;
                        break;
                    }
                }
                if (keyCall != null && registry != null) {
                    return new RegistrySource(registry.owner, registry.name, registry.desc,
                            keyCall.owner, keyCall.name, keyCall.desc);
                }
            }
        }
        return null;
    }

    private static void addWidgetCollectionAction(ClassSurface surface, String sourceScreen,
                                                  String widgetClass, int ordinal, String label,
                                                  int x, int y, int width, int height, String method,
                                                  CollectionMutation mutation, RegistrySource source,
                                                  int confidence) {
        String target = mutation.accessorOwner + "." + mutation.accessorMethod + ':'
                + mutation.accessorDesc;
        for (Action existing : surface.actions) {
            if (existing.type == ActionType.TOGGLE_COLLECTION
                    && java.util.Objects.equals(existing.sourceScreen, sourceScreen)
                    && java.util.Objects.equals(existing.widgetClass, widgetClass)
                    && java.util.Objects.equals(existing.target, target)
                    && java.util.Objects.equals(existing.valueRegistryOwner, source.registryOwner)
                    && java.util.Objects.equals(existing.valueRegistryField, source.registryField)) {
                existing.confidence = Math.max(existing.confidence, confidence);
                return;
            }
        }
        if (!reserveAction(surface)) return;
        Action action = new Action();
        action.type = ActionType.TOGGLE_COLLECTION;
        action.triggerType = TriggerType.WIDGET;
        action.sourceScreen = sourceScreen;
        action.widgetClass = widgetClass;
        action.widgetOrdinal = ordinal;
        action.widgetLabel = label;
        action.widgetX = x;
        action.widgetY = y;
        action.widgetWidth = width;
        action.widgetHeight = height;
        action.method = method;
        action.target = target;
        action.collectionAccessorOwner = mutation.accessorOwner;
        action.collectionAccessorMethod = mutation.accessorMethod;
        action.collectionAccessorDesc = mutation.accessorDesc;
        action.valueRegistryOwner = source.registryOwner;
        action.valueRegistryField = source.registryField;
        action.valueRegistryDesc = source.registryDesc;
        action.valueKeyOwner = source.keyOwner;
        action.valueKeyMethod = source.keyMethod;
        action.valueKeyDesc = source.keyDesc;
        action.confidence = confidence;
        surface.actions.add(action);
    }

    private static void addWidgetAction(ClassSurface surface, ActionType type, String sourceScreen,
                                        String widgetClass, int ordinal, String label, String method,
                                        String target, int confidence) {
        addWidgetAction(surface, type, sourceScreen, widgetClass, ordinal, label,
                -1, -1, -1, -1, method, target,
                confidence, null, null, null, List.of(), null, null, null);
    }

    private static void addWidgetHelperAction(ClassSurface surface, String sourceScreen,
                                              String widgetClass, int ordinal, String label,
                                              int x, int y, int width, int height, String method,
                                              ClientHelperCall helper, int confidence) {
        if (surface == null || helper == null || helper.target() == null) return;
        for (Action existing : surface.actions) {
            if (existing.triggerType == TriggerType.WIDGET
                    && existing.type == ActionType.INVOKE_CLIENT_HELPER
                    && java.util.Objects.equals(existing.sourceScreen, sourceScreen)
                    && existing.widgetOrdinal == ordinal
                    && java.util.Objects.equals(existing.target, helper.target())
                    && existing.visualOnlyHelper == helper.visualOnly()) {
                existing.confidence = Math.max(existing.confidence, confidence);
                return;
            }
        }
        if (!reserveAction(surface)) return;
        Action action = new Action();
        action.type = ActionType.INVOKE_CLIENT_HELPER;
        action.triggerType = TriggerType.WIDGET;
        action.sourceScreen = sourceScreen;
        action.widgetClass = widgetClass;
        action.widgetOrdinal = ordinal;
        action.widgetLabel = label;
        action.widgetX = x;
        action.widgetY = y;
        action.widgetWidth = width;
        action.widgetHeight = height;
        action.method = method;
        action.target = helper.target();
        action.visualOnlyHelper = helper.visualOnly();
        action.confidence = confidence;
        surface.actions.add(action);
    }

    private static void addWidgetAction(ClassSurface surface, ActionType type, String sourceScreen,
                                        String widgetClass, int ordinal, String label,
                                        int x, int y, int width, int height, String method,
                                        String target, int confidence) {
        addWidgetAction(surface, type, sourceScreen, widgetClass, ordinal, label,
                x, y, width, height, method, target, confidence,
                null, null, null, List.of(), null, null, null);
    }

    private static void addWidgetStateAction(ClassSurface surface, String sourceScreen,
                                             String widgetClass, int ordinal, String label,
                                             int x, int y, int width, int height, String method,
                                             StateMutation mutation, boolean visualOnlyState,
                                             int confidence) {
        if (surface == null || mutation == null || mutation.target() == null) return;
        for (Action existing : surface.actions) {
            if (existing.triggerType == TriggerType.WIDGET && existing.type == ActionType.WRITE_STATE
                    && java.util.Objects.equals(existing.sourceScreen, sourceScreen)
                    && existing.widgetOrdinal == ordinal
                    && mutation.target().equals(existing.target)
                    && existing.stateWriteKind == mutation.kind()
                    && java.util.Objects.equals(existing.stateBooleanValue, mutation.booleanValue())
                    && java.util.Objects.equals(existing.stateIntValue, mutation.intValue())
                    && java.util.Objects.equals(existing.stateIntModulus, mutation.intModulus())
                    && java.util.Objects.equals(existing.stateIntModulusTarget, mutation.intModulusTarget())
                    && java.util.Objects.equals(existing.stateIntModulusMinimum, mutation.intModulusMinimum())
                    && java.util.Objects.equals(existing.stateLongValue, mutation.longValue())
                    && java.util.Objects.equals(existing.stateDoubleValue, mutation.doubleValue())
                    && java.util.Objects.equals(existing.stateStringValue, mutation.stringValue())
                    && java.util.Objects.equals(existing.stateStaticFieldOwner, mutation.staticFieldOwner())
                    && java.util.Objects.equals(existing.stateStaticFieldName, mutation.staticFieldName())
                    && java.util.Objects.equals(existing.stateStaticFieldDesc, mutation.staticFieldDesc())
                    && existing.visualOnlyState == visualOnlyState) {
                existing.confidence = Math.max(existing.confidence, confidence);
                return;
            }
        }
        if (!reserveAction(surface)) return;
        Action action = new Action();
        action.type = ActionType.WRITE_STATE;
        action.triggerType = TriggerType.WIDGET;
        action.sourceScreen = sourceScreen;
        action.widgetClass = widgetClass;
        action.widgetOrdinal = ordinal;
        action.widgetLabel = label;
        action.widgetX = x;
        action.widgetY = y;
        action.widgetWidth = width;
        action.widgetHeight = height;
        action.method = method;
        action.target = mutation.target();
        action.stateWriteKind = mutation.kind();
        action.stateBooleanValue = mutation.booleanValue();
        action.stateIntValue = mutation.intValue();
        action.stateIntModulus = mutation.intModulus();
        action.stateIntModulusTarget = mutation.intModulusTarget();
        action.stateIntModulusMinimum = mutation.intModulusMinimum();
        action.stateLongValue = mutation.longValue();
        action.stateDoubleValue = mutation.doubleValue();
        action.stateStringValue = mutation.stringValue();
        action.stateStaticFieldOwner = mutation.staticFieldOwner();
        action.stateStaticFieldName = mutation.staticFieldName();
        action.stateStaticFieldDesc = mutation.staticFieldDesc();
        action.stateReceiverFieldOwner = mutation.receiverFieldOwner();
        action.stateReceiverFieldName = mutation.receiverFieldName();
        action.visualOnlyState = visualOnlyState;
        action.confidence = confidence;
        surface.actions.add(action);
    }

    private static void addWidgetAction(ClassSurface surface, ActionType type, String sourceScreen,
                                        String widgetClass, int ordinal, String label,
                                        int x, int y, int width, int height, String method,
                                        String target, int confidence, String constructorDesc,
                                        Boolean booleanArgument, StaticFieldArgument staticFieldArgument,
                                        List<PacketArgument> packetArguments, String producerOwner,
                                        String producerMethod, String producerDesc) {
        if (target == null) return;
        for (Action existing : surface.actions) {
            if (existing.triggerType == TriggerType.WIDGET && existing.type == type
                    && java.util.Objects.equals(existing.sourceScreen, sourceScreen)
                    && existing.widgetOrdinal == ordinal
                    && java.util.Objects.equals(existing.target, target)
                    && java.util.Objects.equals(existing.constructorDesc, constructorDesc)
                    && java.util.Objects.equals(existing.booleanArgument, booleanArgument)
                    && sameStaticFieldArgument(existing, staticFieldArgument)
                    && samePacketArguments(existing.packetArguments, packetArguments)
                    && java.util.Objects.equals(existing.producerOwner, producerOwner)
                    && java.util.Objects.equals(existing.producerMethod, producerMethod)
                    && java.util.Objects.equals(existing.producerDesc, producerDesc)) {
                existing.confidence = Math.max(existing.confidence, confidence);
                return;
            }
        }
        if (!reserveAction(surface)) return;
        Action action = new Action();
        action.type = type;
        action.triggerType = TriggerType.WIDGET;
        action.sourceScreen = sourceScreen;
        action.widgetClass = widgetClass;
        action.widgetOrdinal = ordinal;
        action.widgetLabel = label;
        action.widgetX = x;
        action.widgetY = y;
        action.widgetWidth = width;
        action.widgetHeight = height;
        action.method = method;
        action.target = target;
        action.constructorDesc = constructorDesc;
        action.booleanArgument = booleanArgument;
        applyStaticFieldArgument(action, staticFieldArgument);
        applyPacketArguments(action, packetArguments);
        action.producerOwner = producerOwner;
        action.producerMethod = producerMethod;
        action.producerDesc = producerDesc;
        action.confidence = confidence;
        surface.actions.add(action);
    }

    /**
     * Recovers the payload construction that feeds a network send. Keeping this branch-local is
     * important: one input method can construct the same packet class with different constants for
     * different keys (for example primary/secondary actions using a boolean constructor).
     */
    private static PacketFact producedPacketBefore(String sourceOwner, AbstractInsnNode instruction,
                                                   Map<String, ClassNode> nodes,
                                                   AbstractInsnNode floorInclusive) {
        int remaining = 48;
        for (AbstractInsnNode previous = instruction.getPrevious(); previous != null && remaining-- > 0;
             previous = previous.getPrevious()) {
            if (previous instanceof MethodInsnNode call) {
                if (call.getOpcode() == Opcodes.INVOKESPECIAL && "<init>".equals(call.name)
                        && isPayloadCandidate(call.owner, nodes)) {
                    return new PacketFact(call.owner, call.desc,
                            constantBooleanArgument(call, floorInclusive),
                            constantStaticFieldArgument(call, floorInclusive),
                            packetArgumentsBefore(sourceOwner, call, nodes, floorInclusive),
                            call.owner, call.name, call.desc);
                }
                String returned = returnedObject(call.desc);
                if (returned != null && isPayloadCandidate(returned, nodes)) {
                    return new PacketFact(returned, null, null, null, List.of(),
                            call.owner, call.name, call.desc);
                }
            }
            if (previous instanceof TypeInsnNode type && type.getOpcode() == Opcodes.NEW
                    && isPayloadCandidate(type.desc, nodes)) {
                return new PacketFact(type.desc, null, null, null, List.of(), null, null, null);
            }
            if (previous == floorInclusive) break;
        }
        return null;
    }

    /**
     * Recovers the exact value for the conservative one-reference constructor shape
     * {@code new Packet(SomeEnum.CONSTANT)}. The field descriptor must exactly match the declared
     * constructor parameter, so this cannot accidentally pick up a nearby unrelated static field.
     * Richer stack shapes remain declarative until F2V can prove every argument independently.
     */
    private static StaticFieldArgument constantStaticFieldArgument(MethodInsnNode constructor,
                                                                   AbstractInsnNode floorInclusive) {
        if (constructor == null || constructor.desc == null) return null;
        Type[] arguments;
        try {
            arguments = Type.getArgumentTypes(constructor.desc);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
        if (arguments.length == 1 && arguments[0].getSort() == Type.OBJECT) {
            String expected = arguments[0].getDescriptor();
            AbstractInsnNode value = previousReal(constructor, floorInclusive);
            return value instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETSTATIC
                    && expected.equals(field.desc)
                    ? new StaticFieldArgument(0, field.owner, field.name, field.desc) : null;
        }
        // Preserve the equally common packet shape new Packet(SomeEnum.VALUE, true). The final
        // boolean is independently proven by constantBooleanArgument; only then is the preceding
        // GETSTATIC unambiguously the first constructor argument.
        if (arguments.length == 2 && arguments[0].getSort() == Type.OBJECT
                && arguments[1].getSort() == Type.BOOLEAN) {
            AbstractInsnNode booleanValue = previousReal(constructor, floorInclusive);
            if (booleanConstant(booleanValue) == null) return null;
            AbstractInsnNode staticValue = previousReal(booleanValue, floorInclusive);
            if (staticValue instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETSTATIC
                    && arguments[0].getDescriptor().equals(field.desc)) {
                return new StaticFieldArgument(0, field.owner, field.name, field.desc);
            }
        }
        return null;
    }

    /** Exact trailing constructor arguments whose complete producer is one JVM instruction. The
     * scan stops at the first dynamic expression so an earlier constant can never be paired with a
     * later argument whose stack width/producer shape was not understood. */
    private static List<PacketArgument> packetArgumentsBefore(String sourceOwner,
                                                              MethodInsnNode constructor,
                                                              Map<String, ClassNode> nodes,
                                                              AbstractInsnNode floorInclusive) {
        if (constructor == null || constructor.desc == null) return List.of();
        Type[] arguments;
        try {
            arguments = Type.getArgumentTypes(constructor.desc);
        } catch (IllegalArgumentException ignored) {
            return List.of();
        }
        if (arguments.length == 0) return List.of();
        ArrayList<PacketArgument> out = new ArrayList<>();
        AbstractInsnNode cursor = constructor;
        for (int i = arguments.length - 1; i >= 0; i--) {
            cursor = previousReal(cursor, floorInclusive);
            if (cursor == null) break;
            PacketArgument argument = packetArgumentFromInstruction(i, arguments[i], cursor,
                    sourceOwner, nodes, floorInclusive, arguments.length);
            if (argument == null) break;
            out.add(argument);
            cursor = packetArgumentExpressionStart(argument, cursor, floorInclusive);
        }

        // The legacy stack walk above is intentionally conservative but compiler temporaries hide
        // otherwise exact sources: `UUID id = target.getUUID(); new Packet(id, ...)` appears as an
        // ALOAD next to the constructor. Ask ASM's verifier data-flow for the original producer of
        // each constructor argument and use the same semantic decoder on that producer. Ambiguous
        // control-flow merges are accepted only when every producer decodes to the identical fact.
        ClassNode sourceClass = sourceOwner == null || nodes == null ? null : nodes.get(sourceOwner);
        MethodNode sourceMethod = methodContainingInstruction(sourceClass, constructor);
        if (sourceClass != null && sourceMethod != null) {
            BytecodeValueFlow flow = new BytecodeValueFlow(nodes);
            for (int i = 0; i < arguments.length; i++) {
                Set<AbstractInsnNode> producers = flow.argumentSources(sourceClass, sourceMethod, constructor, i);
                PacketArgument exact = packetArgumentFromProducers(i, arguments[i], producers,
                        sourceOwner, nodes, floorInclusive, arguments.length);
                if (exact != null) putPacketArgument(out, exact);
            }
        }
        out.sort(java.util.Comparator.comparingInt(argument -> argument.index));
        return List.copyOf(out);
    }

    private static PacketArgument packetArgumentFromProducers(int index, Type type,
                                                              Set<AbstractInsnNode> producers,
                                                              String sourceOwner,
                                                              Map<String, ClassNode> nodes,
                                                              AbstractInsnNode floorInclusive,
                                                              int constructorArity) {
        if (producers == null || producers.isEmpty()) return null;
        PacketArgument resolved = null;
        for (AbstractInsnNode producer : producers) {
            PacketArgument candidate = packetArgumentFromInstruction(index, type, producer,
                    sourceOwner, nodes, floorInclusive, constructorArity);
            if (candidate == null) return null;
            if (resolved == null) {
                resolved = candidate;
            } else if (!samePacketArguments(List.of(resolved), List.of(candidate))) {
                return null;
            }
        }
        return resolved;
    }

    private static MethodNode methodContainingInstruction(ClassNode owner, AbstractInsnNode wanted) {
        if (owner == null || wanted == null || owner.methods == null) return null;
        for (MethodNode method : owner.methods) {
            if (method == null || method.instructions == null) continue;
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction == wanted) return method;
            }
        }
        return null;
    }

    private static PacketArgument screenFieldPacketArgument(int index, Type type,
                                                            AbstractInsnNode instruction,
                                                            String sourceOwner,
                                                            Map<String, ClassNode> nodes,
                                                            AbstractInsnNode floorInclusive) {
        if (index < 0 || type == null || sourceOwner == null || nodes == null
                || !(instruction instanceof FieldInsnNode field)
                || field.getOpcode() != Opcodes.GETFIELD
                || !type.getDescriptor().equals(field.desc)) return null;
        ClassNode source = nodes.get(sourceOwner);
        if (source == null || !screenHierarchy(source, nodes).contains(field.owner)) return null;
        AbstractInsnNode receiver = previousReal(field, floorInclusive);
        if (!(receiver instanceof VarInsnNode self) || self.getOpcode() != Opcodes.ALOAD || self.var != 0) {
            return null;
        }
        PacketArgument argument = new PacketArgument();
        argument.index = index;
        argument.descriptor = type.getDescriptor();
        argument.kind = PacketArgumentKind.SCREEN_FIELD;
        argument.fieldOwner = field.owner;
        argument.fieldName = field.name;
        argument.fieldDesc = field.desc;
        return argument;
    }

    /** Exact {@code this.screenField.simpleGetter()} packet argument. The getter must resolve to
     * the side-effect-free JVM shape {@code aload_0; getfield X; xreturn}; runtime therefore reads
     * the proven backing field from payload-seeded state instead of invoking a client helper. */
    private static PacketArgument screenFieldGetterPacketArgument(int index, Type type,
                                                                  AbstractInsnNode instruction,
                                                                  String sourceOwner,
                                                                  Map<String, ClassNode> nodes,
                                                                  AbstractInsnNode floorInclusive) {
        if (index < 0 || type == null || sourceOwner == null || nodes == null
                || !(instruction instanceof MethodInsnNode getter)
                || getter.getOpcode() == Opcodes.INVOKESTATIC) return null;
        Type[] arguments;
        Type returned;
        try {
            arguments = Type.getArgumentTypes(getter.desc);
            returned = Type.getReturnType(getter.desc);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
        if (arguments.length != 0 || !type.equals(returned)) return null;

        ClassNode source = nodes.get(sourceOwner);
        if (source == null) return null;

        LinkedHashSet<FieldInsnNode> receivers = new LinkedHashSet<>();
        AbstractInsnNode adjacent = previousReal(getter, floorInclusive);
        if (adjacent instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETFIELD) {
            receivers.add(field);
        } else {
            MethodNode sourceMethod = methodContainingInstruction(source, getter);
            if (sourceMethod == null) return null;
            BytecodeValueFlow flow = new BytecodeValueFlow(nodes);
            for (AbstractInsnNode producer : flow.receiverSources(source, sourceMethod, getter)) {
                // A null receiver can only terminate this path with an exception before the
                // packet constructor is reached. Ignore it, but keep every non-null alternative
                // and require those alternatives to agree exactly below.
                if (producer != null && producer.getOpcode() == Opcodes.ACONST_NULL) continue;
                if (!(producer instanceof FieldInsnNode field)
                        || field.getOpcode() != Opcodes.GETFIELD) return null;
                receivers.add(field);
            }
        }
        if (receivers.isEmpty()) return null;
        FieldInsnNode receiver = receivers.iterator().next();
        for (FieldInsnNode candidate : receivers) {
            if (!receiver.owner.equals(candidate.owner) || !receiver.name.equals(candidate.name)
                    || !receiver.desc.equals(candidate.desc)) return null;
        }
        if (!screenHierarchy(source, nodes).contains(receiver.owner)) return null;
        AbstractInsnNode selfNode = previousReal(receiver, floorInclusive);
        if (!(selfNode instanceof VarInsnNode self) || self.getOpcode() != Opcodes.ALOAD || self.var != 0) return null;

        ResolvedMethod resolved = resolveMethodInHierarchy(nodes.get(getter.owner),
                getter.name, getter.desc, nodes);
        if (resolved == null || resolved.method() == null) return null;
        FieldInsnNode backing = simpleSelfGetterField(resolved.method(), type.getDescriptor());
        if (backing == null) return null;

        PacketArgument argument = new PacketArgument();
        argument.index = index;
        argument.descriptor = type.getDescriptor();
        argument.kind = PacketArgumentKind.SCREEN_FIELD_GETTER;
        argument.fieldOwner = receiver.owner;
        argument.fieldName = receiver.name;
        argument.fieldDesc = receiver.desc;
        argument.accessorOwner = resolved.owner();
        argument.accessorName = getter.name;
        argument.accessorDesc = getter.desc;
        argument.accessorFieldOwner = backing.owner;
        argument.accessorFieldName = backing.name;
        argument.accessorFieldDesc = backing.desc;
        PayloadMirrorSource mirror = payloadMirrorSource(resolved.owner(), backing, nodes);
        if (mirror != null) {
            argument.mirrorSourceOwner = mirror.sourceOwner();
            argument.mirrorSourceFieldName = mirror.sourceFieldName();
            argument.mirrorSourceFieldDesc = mirror.sourceFieldDesc();
            argument.mirrorConverterOwner = mirror.converterOwner();
            argument.mirrorConverterMethod = mirror.converterMethod();
            argument.mirrorConverterDesc = mirror.converterDesc();
        }
        return argument;
    }

    private static PacketArgument packetArgumentFromInstruction(int index, Type type,
                                                                AbstractInsnNode instruction,
                                                                String sourceOwner,
                                                                Map<String, ClassNode> nodes,
                                                                AbstractInsnNode floorInclusive,
                                                                int constructorArity) {
        if (index < 0 || type == null || instruction == null) return null;
        PacketArgument screenGetter = screenFieldGetterPacketArgument(index, type, instruction,
                sourceOwner, nodes, floorInclusive);
        if (screenGetter != null) return screenGetter;
        PacketArgument argument = new PacketArgument();
        argument.index = index;
        argument.descriptor = type.getDescriptor();
        switch (type.getSort()) {
            case Type.BOOLEAN -> {
                Boolean value = booleanConstant(instruction);
                if (value == null && instruction instanceof FieldInsnNode field) {
                    StateMutation mutation = constantScreenFieldAssignmentBefore(
                            sourceOwner, field, nodes, floorInclusive);
                    if (mutation != null && mutation.kind() == StateWriteKind.ASSIGN_BOOLEAN) {
                        value = mutation.booleanValue();
                    }
                }
                if (value == null) {
                    PacketArgument field = screenFieldPacketArgument(index, type, instruction,
                            sourceOwner, nodes, floorInclusive);
                    if (field != null) return field;
                    return null;
                }
                argument.kind = PacketArgumentKind.BOOLEAN_LITERAL;
                argument.booleanValue = value;
            }
            case Type.BYTE, Type.CHAR, Type.SHORT, Type.INT -> {
                Integer value = integerConstant(instruction);
                if (value == null && instruction instanceof MethodInsnNode call
                        && serverPlayerEntityIdCall(call)) {
                    argument.kind = PacketArgumentKind.SERVER_PLAYER_ENTITY_ID;
                    break;
                }
                if (value == null && instruction instanceof FieldInsnNode field) {
                    StateMutation mutation = constantScreenFieldAssignmentBefore(
                            sourceOwner, field, nodes, floorInclusive);
                    if (mutation != null && mutation.kind() == StateWriteKind.ASSIGN_INT) {
                        value = mutation.intValue();
                    }
                }
                if (value == null) {
                    PacketArgument field = screenFieldPacketArgument(index, type, instruction,
                            sourceOwner, nodes, floorInclusive);
                    if (field != null) return field;
                    return null;
                }
                argument.kind = PacketArgumentKind.INT_LITERAL;
                argument.intValue = value;
            }
            case Type.LONG -> {
                Long value = longConstant(instruction);
                if (value == null) {
                    PacketArgument field = screenFieldPacketArgument(index, type, instruction,
                            sourceOwner, nodes, floorInclusive);
                    if (field != null) return field;
                    return null;
                }
                argument.kind = PacketArgumentKind.LONG_LITERAL;
                argument.longValue = value;
            }
            case Type.FLOAT, Type.DOUBLE -> {
                Double value = floatingConstant(instruction);
                if (value == null) {
                    PacketArgument field = screenFieldPacketArgument(index, type, instruction,
                            sourceOwner, nodes, floorInclusive);
                    if (field != null) return field;
                    return null;
                }
                argument.kind = type.getSort() == Type.FLOAT
                        ? PacketArgumentKind.FLOAT_LITERAL : PacketArgumentKind.DOUBLE_LITERAL;
                argument.doubleValue = value;
            }
            case Type.OBJECT -> {
                if ("java/lang/String".equals(type.getInternalName())
                        && instruction instanceof LdcInsnNode ldc && ldc.cst instanceof String value) {
                    argument.kind = PacketArgumentKind.STRING_LITERAL;
                    argument.stringValue = value;
                } else if ("net/minecraft/world/entity/player/Player".equals(type.getInternalName())
                        && serverPlayerValueBefore(sourceOwner, instruction, nodes, floorInclusive)) {
                    argument.kind = PacketArgumentKind.SERVER_PLAYER;
                } else if ("java/util/UUID".equals(type.getInternalName())
                        && instruction instanceof MethodInsnNode call
                        && "()Ljava/util/UUID;".equals(call.desc)
                        && playerUuidCall(call)
                        && (clientPlayerOwner(call.owner)
                        || serverPlayerValueBefore(sourceOwner,
                        previousReal(call, floorInclusive), nodes, floorInclusive))) {
                    argument.kind = PacketArgumentKind.SERVER_PLAYER_UUID;
                } else if ("java/util/UUID".equals(type.getInternalName())
                        && instruction instanceof MethodInsnNode call
                        && "()Ljava/util/UUID;".equals(call.desc)) {
                    MenuMemberSource source = menuEntitySourceBefore(sourceOwner, call, nodes, floorInclusive);
                    if (source == null) return null;
                    argument.kind = PacketArgumentKind.MENU_ENTITY_UUID;
                    argument.menuOwner = source.owner();
                    argument.menuMemberName = source.name();
                    argument.menuMemberDesc = source.desc();
                    argument.menuMemberMethod = source.method();
                } else if (constructorArity == 1
                        && "net/minecraft/core/BlockPos".equals(type.getInternalName())
                        && instruction instanceof MethodInsnNode call
                        && "()Lnet/minecraft/core/BlockPos;".equals(call.desc)) {
                    MenuMemberSource source = menuFieldSourceBefore(sourceOwner, call, nodes, floorInclusive);
                    if (source == null) return null;
                    argument.kind = PacketArgumentKind.MENU_BLOCK_POS;
                    argument.menuOwner = source.owner();
                    argument.menuMemberName = source.name();
                    argument.menuMemberDesc = source.desc();
                    argument.menuMemberMethod = source.method();
                } else if (instruction instanceof FieldInsnNode field
                        && field.getOpcode() == Opcodes.GETSTATIC
                        && type.getDescriptor().equals(field.desc)) {
                    argument.kind = PacketArgumentKind.STATIC_FIELD;
                    argument.fieldOwner = field.owner;
                    argument.fieldName = field.name;
                    argument.fieldDesc = field.desc;
                } else {
                    PacketArgument field = screenFieldPacketArgument(index, type, instruction,
                            sourceOwner, nodes, floorInclusive);
                    if (field != null) return field;
                    return null;
                }
            }
            case Type.ARRAY -> {
                if (instruction instanceof FieldInsnNode field
                        && field.getOpcode() == Opcodes.GETSTATIC
                        && type.getDescriptor().equals(field.desc)) {
                    argument.kind = PacketArgumentKind.STATIC_FIELD;
                    argument.fieldOwner = field.owner;
                    argument.fieldName = field.name;
                    argument.fieldDesc = field.desc;
                } else {
                    PacketArgument screenField = screenFieldPacketArgument(index, type, instruction,
                            sourceOwner, nodes, floorInclusive);
                    if (screenField != null) return screenField;
                    return null;
                }
            }
            default -> {
                return null;
            }
        }
        return argument;
    }

    /** Returns the first instruction belonging to a proven argument expression. The caller walks
     * backwards from here for the preceding constructor argument, so multi-instruction expressions
     * such as {@code this.target.getUUID()} never make the next argument start in the middle of the
     * target receiver chain. */
    private static AbstractInsnNode packetArgumentExpressionStart(PacketArgument argument,
                                                                  AbstractInsnNode terminal,
                                                                  AbstractInsnNode floorInclusive) {
        if (argument == null || terminal == null || argument.kind == null) return terminal;
        if (argument.kind == PacketArgumentKind.MENU_ENTITY_UUID
                || argument.kind == PacketArgumentKind.SERVER_PLAYER
                || argument.kind == PacketArgumentKind.SERVER_PLAYER_ENTITY_ID
                || argument.kind == PacketArgumentKind.SERVER_PLAYER_UUID
                || argument.kind == PacketArgumentKind.MENU_BLOCK_POS
                || argument.kind == PacketArgumentKind.SCREEN_FIELD
                || argument.kind == PacketArgumentKind.SCREEN_FIELD_GETTER) {
            AbstractInsnNode cursor = terminal;
            int remaining = 12;
            while (remaining-- > 0) {
                AbstractInsnNode previous = previousReal(cursor, floorInclusive);
                if (previous == null) break;
                cursor = previous;
                if (previous instanceof VarInsnNode load && load.getOpcode() == Opcodes.ALOAD) return previous;
                if (!(previous instanceof FieldInsnNode || previous instanceof TypeInsnNode
                        || previous instanceof MethodInsnNode)) break;
            }
            return terminal;
        }
        if ((argument.kind == PacketArgumentKind.BOOLEAN_LITERAL
                || argument.kind == PacketArgumentKind.INT_LITERAL)
                && terminal instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETFIELD) {
            AbstractInsnNode receiver = previousReal(field, floorInclusive);
            if (receiver instanceof VarInsnNode load && load.getOpcode() == Opcodes.ALOAD) return receiver;
        }
        return terminal;
    }

    private static boolean serverPlayerEntityIdCall(MethodInsnNode call) {
        if (call == null || !"()I".equals(call.desc) || !clientPlayerOwner(call.owner)) return false;
        return "getId".equals(call.name) || "m_19879_".equals(call.name);
    }

    private static boolean playerUuidCall(MethodInsnNode call) {
        if (call == null || !"()Ljava/util/UUID;".equals(call.desc)) return false;
        String owner = call.owner == null ? "" : call.owner.replace('.', '/');
        boolean playerOwner = clientPlayerOwner(owner)
                || "net/minecraft/world/entity/player/Player".equals(owner);
        return playerOwner && ("getUUID".equals(call.name) || "m_20148_".equals(call.name));
    }

    /** Proves that a packet Player argument denotes the local client player. Supported sources are
     * deliberately structural: Minecraft.player, or Inventory.player where the screen's Inventory
     * field is copied directly from an Inventory constructor parameter. Runtime substitutes the
     * authoritative ServerPlayer for that same connection. */
    private static boolean serverPlayerValueBefore(String sourceOwner, AbstractInsnNode terminal,
                                                   Map<String, ClassNode> nodes,
                                                   AbstractInsnNode floorInclusive) {
        if (!(terminal instanceof FieldInsnNode playerField)
                || playerField.getOpcode() != Opcodes.GETFIELD) return false;
        if ("net/minecraft/client/Minecraft".equals(playerField.owner)
                && "Lnet/minecraft/client/player/LocalPlayer;".equals(playerField.desc)) {
            AbstractInsnNode receiver = previousReal(playerField, floorInclusive);
            if (receiver instanceof MethodInsnNode call) {
                return "Lnet/minecraft/client/Minecraft;".equals(
                        Type.getReturnType(call.desc).getDescriptor());
            }
            if (receiver instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETFIELD
                    && "Lnet/minecraft/client/Minecraft;".equals(field.desc)
                    && sourceOwner != null && nodes != null) {
                ClassNode source = nodes.get(sourceOwner);
                return source != null && screenHierarchy(source, nodes).contains(field.owner);
            }
            return false;
        }
        if (!"net/minecraft/world/entity/player/Inventory".equals(playerField.owner)
                || !"Lnet/minecraft/world/entity/player/Player;".equals(playerField.desc)
                || sourceOwner == null || nodes == null) return false;
        AbstractInsnNode receiver = previousReal(playerField, floorInclusive);
        if (!(receiver instanceof FieldInsnNode inventoryField)
                || inventoryField.getOpcode() != Opcodes.GETFIELD
                || !"Lnet/minecraft/world/entity/player/Inventory;".equals(inventoryField.desc)) return false;
        ClassNode screen = nodes.get(sourceOwner);
        if (screen == null || !screenHierarchy(screen, nodes).contains(inventoryField.owner)) return false;
        return screenFieldCopiedFromConstructorParameter(screen, inventoryField,
                "Lnet/minecraft/world/entity/player/Inventory;");
    }

    private static boolean screenFieldCopiedFromConstructorParameter(ClassNode screen,
                                                                      FieldInsnNode read,
                                                                      String parameterDescriptor) {
        if (screen == null || read == null || parameterDescriptor == null) return false;
        boolean sawAssignment = false;
        for (MethodNode constructor : screen.methods) {
            if (!"<init>".equals(constructor.name)) continue;
            for (AbstractInsnNode instruction : constructor.instructions) {
                if (!(instruction instanceof FieldInsnNode store) || store.getOpcode() != Opcodes.PUTFIELD
                        || !store.owner.equals(read.owner) || !store.name.equals(read.name)
                        || !store.desc.equals(read.desc)) continue;
                sawAssignment = true;
                AbstractInsnNode value = previousReal(store, null);
                if (!(value instanceof VarInsnNode load) || load.getOpcode() != Opcodes.ALOAD) return false;
                Type parameter = constructorParameterAtLocal(constructor, load.var);
                if (parameter == null || !parameterDescriptor.equals(parameter.getDescriptor())) return false;
                AbstractInsnNode owner = previousReal(value, null);
                if (!(owner instanceof VarInsnNode thisLoad) || thisLoad.getOpcode() != Opcodes.ALOAD
                        || thisLoad.var != 0) return false;
            }
        }
        return sawAssignment;
    }

    /**
     * Extends the direct constructor-field proof through a screen superclass chain. This is used for
     * parent/back navigation fields commonly declared by a UI framework base screen: the concrete
     * screen must forward one unique parameter of the same descriptor unchanged into each direct
     * superclass constructor until the class that actually declares/stores the field is reached.
     * Constructor transforms, defaults, this(...) chains, ambiguous duplicate parameters, or a
     * missing class in the bytecode index all reject the proof rather than guessing the parent.
     */
    private static boolean screenHierarchyFieldCopiedFromConstructorParameter(
            ClassNode concrete, FieldInsnNode read, String parameterDescriptor,
            Map<String, ClassNode> nodes) {
        if (concrete == null || read == null || parameterDescriptor == null || nodes == null) return false;
        ClassNode declaring = null;
        ClassNode cursor = concrete;
        Set<String> seen = new HashSet<>();
        int remaining = 32;
        while (cursor != null && remaining-- > 0 && seen.add(cursor.name)) {
            if (cursor.fields != null && cursor.fields.stream().anyMatch(field ->
                    read.name.equals(field.name) && read.desc.equals(field.desc))) {
                declaring = cursor;
                break;
            }
            cursor = cursor.superName == null ? null : nodes.get(cursor.superName);
        }
        if (declaring == null) return false;
        FieldInsnNode declaredRead = new FieldInsnNode(Opcodes.GETFIELD,
                declaring.name, read.name, read.desc);
        if (!screenFieldCopiedFromConstructorParameter(declaring, declaredRead, parameterDescriptor)) {
            return false;
        }
        if (concrete.name.equals(declaring.name)) return true;

        cursor = concrete;
        seen.clear();
        remaining = 32;
        while (cursor != null && !cursor.name.equals(declaring.name)
                && remaining-- > 0 && seen.add(cursor.name)) {
            ClassNode parent = cursor.superName == null ? null : nodes.get(cursor.superName);
            if (parent == null || !allConstructorsForwardParameterToDirectSuper(
                    cursor, parent.name, parameterDescriptor)) return false;
            cursor = parent;
        }
        return cursor != null && cursor.name.equals(declaring.name);
    }

    private static boolean allConstructorsForwardParameterToDirectSuper(
            ClassNode owner, String superOwner, String parameterDescriptor) {
        if (owner == null || superOwner == null || parameterDescriptor == null
                || owner.methods == null) return false;
        boolean sawConstructor = false;
        for (MethodNode constructor : owner.methods) {
            if (!"<init>".equals(constructor.name)) continue;
            sawConstructor = true;
            Integer parameterLocal = uniqueParameterLocal(constructor, parameterDescriptor);
            if (parameterLocal == null) return false;
            MethodInsnNode superCall = null;
            for (AbstractInsnNode instruction : constructor.instructions) {
                if (!(instruction instanceof MethodInsnNode call)
                        || call.getOpcode() != Opcodes.INVOKESPECIAL
                        || !"<init>".equals(call.name) || !superOwner.equals(call.owner)) continue;
                if (superCall != null) return false;
                superCall = call;
            }
            if (superCall == null) return false;
            Type[] superArguments;
            try {
                superArguments = Type.getArgumentTypes(superCall.desc);
            } catch (IllegalArgumentException ignored) {
                return false;
            }
            int parameterIndex = -1;
            for (int index = 0; index < superArguments.length; index++) {
                if (!parameterDescriptor.equals(superArguments[index].getDescriptor())) continue;
                if (parameterIndex >= 0) return false;
                parameterIndex = index;
            }
            if (parameterIndex < 0) return false;
            AbstractInsnNode supplied = directCategoryOneArgument(
                    superCall, superArguments, parameterIndex, null);
            if (!(supplied instanceof VarInsnNode load) || load.getOpcode() != Opcodes.ALOAD
                    || load.var != parameterLocal) return false;
        }
        return sawConstructor;
    }

    private static Integer uniqueParameterLocal(MethodNode method, String descriptor) {
        if (method == null || method.desc == null || descriptor == null) return null;
        Type[] arguments;
        try {
            arguments = Type.getArgumentTypes(method.desc);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
        int local = (method.access & Opcodes.ACC_STATIC) != 0 ? 0 : 1;
        Integer match = null;
        for (Type argument : arguments) {
            if (descriptor.equals(argument.getDescriptor())) {
                if (match != null) return null;
                match = local;
            }
            local += argument.getSize();
        }
        return match;
    }

    /** Folds an exact scalar value written earlier in the same straight-line callback into the
     * packet argument. Crossing a branch/switch before finding the write loses certainty. */
    private static StateMutation constantScreenFieldAssignmentBefore(String sourceOwner,
                                                                     FieldInsnNode read,
                                                                     Map<String, ClassNode> nodes,
                                                                     AbstractInsnNode floorInclusive) {
        if (sourceOwner == null || read == null || read.getOpcode() != Opcodes.GETFIELD || nodes == null) return null;
        ClassNode source = nodes.get(sourceOwner);
        if (source == null || !screenHierarchy(source, nodes).contains(read.owner)) return null;
        AbstractInsnNode receiver = previousReal(read, floorInclusive);
        if (!(receiver instanceof VarInsnNode load) || load.getOpcode() != Opcodes.ALOAD || load.var != 0) return null;
        int remaining = 96;
        for (AbstractInsnNode cursor = previousReal(receiver, floorInclusive);
             cursor != null && remaining-- > 0; cursor = previousReal(cursor, floorInclusive)) {
            if (cursor instanceof JumpInsnNode || cursor instanceof TableSwitchInsnNode
                    || cursor instanceof LookupSwitchInsnNode) return null;
            if (!(cursor instanceof FieldInsnNode write) || write.getOpcode() != Opcodes.PUTFIELD
                    || !write.owner.equals(read.owner) || !write.name.equals(read.name)
                    || !write.desc.equals(read.desc)) continue;
            StateMutation mutation = stateMutation(write, floorInclusive, nodes);
            if (mutation == null) return null;
            return mutation.kind() == StateWriteKind.ASSIGN_BOOLEAN
                    || mutation.kind() == StateWriteKind.ASSIGN_INT ? mutation : null;
        }
        return null;
    }

    private record MenuMemberSource(String owner, String name, String desc, boolean method) {}

    /** Proves the narrow container-screen expression {@code this.menu -> concrete menu field ->
     * BlockEntity.getBlockPos()}. The menu field may be generic/Object at bytecode level; runtime
     * still requires its actual value to be a BlockEntity before replaying the argument. */
    private static MenuMemberSource menuFieldSourceBefore(String sourceOwner, MethodInsnNode terminal,
                                                           Map<String, ClassNode> nodes,
                                                           AbstractInsnNode floorInclusive) {
        if (sourceOwner == null || terminal == null || nodes == null) return null;
        AbstractInsnNode cursor = previousReal(terminal, floorInclusive);
        int remaining = 12;
        FieldInsnNode menuMember = null;
        boolean sawConcreteMenuCast = false;
        boolean sawBlockEntityCast = false;
        boolean sawScreenMenu = false;
        while (cursor != null && remaining-- > 0) {
            if (cursor instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETFIELD) {
                if (menuMember == null && isMenuType(field.owner, nodes)) {
                    menuMember = field;
                } else if (menuMember != null && isAbstractMenuDescriptor(field.desc)) {
                    ClassNode source = nodes.get(sourceOwner);
                    if (source == null || !screenHierarchy(source, nodes).contains(field.owner)) return null;
                    sawScreenMenu = true;
                    break;
                }
            } else if (cursor instanceof TypeInsnNode cast && cast.getOpcode() == Opcodes.CHECKCAST) {
                // A generic menu holder is commonly declared as Object/T. The exact CHECKCAST
                // between that field read and getBlockPos is the bytecode proof that runtime may
                // require the resolved server-menu value to be a BlockEntity.
                if (menuMember == null && isBlockEntityHierarchy(cast.desc, nodes)) {
                    sawBlockEntityCast = true;
                } else if (menuMember != null && isMenuType(cast.desc, nodes)) {
                    sawConcreteMenuCast = true;
                }
            } else if (cursor instanceof MethodInsnNode || cursor instanceof InvokeDynamicInsnNode) {
                return null;
            }
            if (cursor == floorInclusive) break;
            cursor = previousReal(cursor, floorInclusive);
        }
        boolean typedBlockEntity = menuMember != null
                && isBlockEntityHierarchy(objectInternalName(menuMember.desc), nodes);
        if (menuMember == null || !sawScreenMenu || !sawConcreteMenuCast
                || (!typedBlockEntity && !sawBlockEntityCast)) return null;
        return new MenuMemberSource(menuMember.owner, menuMember.name, menuMember.desc, false);
    }

    /** Proves a target identity copied from the server container menu into a screen field:
     * {@code <init>(Menu,..) { this.target = menu.target(); }} followed by
     * {@code this.target.getUUID()}. The menu accessor must itself be a trivial instance field
     * getter and its return type must derive from vanilla Entity. */
    private static MenuMemberSource menuEntitySourceBefore(String sourceOwner, MethodInsnNode terminal,
                                                            Map<String, ClassNode> nodes,
                                                            AbstractInsnNode floorInclusive) {
        if (sourceOwner == null || terminal == null || nodes == null) return null;
        ClassNode screen = nodes.get(sourceOwner);
        if (screen == null) return null;
        AbstractInsnNode receiver = previousReal(terminal, floorInclusive);
        if (!(receiver instanceof FieldInsnNode screenField) || screenField.getOpcode() != Opcodes.GETFIELD
                || !screenHierarchy(screen, nodes).contains(screenField.owner)) return null;
        String fieldType = objectInternalName(screenField.desc);
        if (!isEntityHierarchy(fieldType, nodes)) return null;

        MenuMemberSource agreed = null;
        boolean sawAssignment = false;
        for (MethodNode constructor : screen.methods) {
            if (!"<init>".equals(constructor.name)) continue;
            for (AbstractInsnNode instruction : constructor.instructions) {
                if (!(instruction instanceof FieldInsnNode store) || store.getOpcode() != Opcodes.PUTFIELD
                        || !store.owner.equals(screenField.owner) || !store.name.equals(screenField.name)
                        || !store.desc.equals(screenField.desc)) continue;
                sawAssignment = true;
                AbstractInsnNode value = previousReal(store, null);
                if (!(value instanceof MethodInsnNode accessor) || accessor.getOpcode() == Opcodes.INVOKESTATIC
                        || Type.getArgumentTypes(accessor.desc).length != 0
                        || !isMenuType(accessor.owner, nodes)
                        || !isEntityHierarchy(objectInternalName(Type.getReturnType(accessor.desc).getDescriptor()), nodes)) {
                    return null;
                }
                AbstractInsnNode accessorReceiver = previousReal(accessor, null);
                if (!(accessorReceiver instanceof VarInsnNode load) || load.getOpcode() != Opcodes.ALOAD) return null;
                Type parameter = constructorParameterAtLocal(constructor, load.var);
                if (parameter == null || parameter.getSort() != Type.OBJECT
                        || !isMenuType(parameter.getInternalName(), nodes)) return null;
                ResolvedMethod resolved = resolveMethodInHierarchy(nodes.get(accessor.owner),
                        accessor.name, accessor.desc, nodes);
                if (resolved == null || !trivialInstanceFieldGetter(resolved.method())) return null;
                MenuMemberSource source = new MenuMemberSource(
                        resolved.owner(), accessor.name, accessor.desc, true);
                if (agreed != null && !agreed.equals(source)) return null;
                agreed = source;
            }
        }
        return sawAssignment ? agreed : null;
    }

    private static Type constructorParameterAtLocal(MethodNode constructor, int wantedLocal) {
        if (constructor == null || constructor.desc == null || wantedLocal <= 0) return null;
        int local = 1;
        try {
            for (Type argument : Type.getArgumentTypes(constructor.desc)) {
                if (local == wantedLocal) return argument;
                local += argument.getSize();
            }
        } catch (IllegalArgumentException ignored) {
        }
        return null;
    }

    private static boolean trivialInstanceFieldGetter(MethodNode method) {
        if (method == null || (method.access & Opcodes.ACC_STATIC) != 0
                || Type.getArgumentTypes(method.desc).length != 0) return false;
        ArrayList<AbstractInsnNode> real = new ArrayList<>();
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction.getOpcode() >= 0) real.add(instruction);
        }
        if (real.size() != 3) return false;
        return real.get(0) instanceof VarInsnNode load && load.getOpcode() == Opcodes.ALOAD && load.var == 0
                && real.get(1) instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETFIELD
                && real.get(2).getOpcode() == Opcodes.ARETURN
                && Type.getReturnType(method.desc).getDescriptor().equals(field.desc);
    }

    private static String objectInternalName(String descriptor) {
        if (descriptor == null) return null;
        try {
            Type type = Type.getType(descriptor);
            return type.getSort() == Type.OBJECT ? type.getInternalName() : null;
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static boolean isEntityHierarchy(String type, Map<String, ClassNode> nodes) {
        if (type == null) return false;
        Set<String> vanillaRoots = Set.of(
                "net/minecraft/world/entity/Entity",
                "net/minecraft/world/entity/LivingEntity",
                "net/minecraft/world/entity/Mob",
                "net/minecraft/world/entity/PathfinderMob",
                "net/minecraft/world/entity/Animal",
                "net/minecraft/world/entity/AgeableMob",
                "net/minecraft/world/entity/TamableAnimal",
                "net/minecraft/world/entity/AmbientCreature",
                "net/minecraft/world/entity/Monster",
                "net/minecraft/world/entity/AbstractGolem",
                "net/minecraft/world/entity/WaterAnimal");
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        String current = type;
        while (current != null && seen.add(current)) {
            if (vanillaRoots.contains(current)) return true;
            ClassNode node = nodes == null ? null : nodes.get(current);
            if (node == null) return false;
            current = node.superName;
        }
        return false;
    }

    private static boolean isBlockEntityHierarchy(String type, Map<String, ClassNode> nodes) {
        if (type == null) return false;
        Set<String> vanillaRoots = Set.of(
                "net/minecraft/world/level/block/entity/BlockEntity",
                "net/minecraft/world/level/block/entity/BaseContainerBlockEntity",
                "net/minecraft/world/level/block/entity/RandomizableContainerBlockEntity");
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        String current = type;
        while (current != null && seen.add(current)) {
            if (vanillaRoots.contains(current)) return true;
            ClassNode node = nodes == null ? null : nodes.get(current);
            if (node == null) return false;
            current = node.superName;
        }
        return false;
    }

    private static boolean isAbstractMenuDescriptor(String descriptor) {
        return "Lnet/minecraft/world/inventory/AbstractContainerMenu;".equals(descriptor);
    }

    private static boolean isMenuType(String className, Map<String, ClassNode> nodes) {
        String current = className;
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        while (current != null && seen.add(current)) {
            if ("net/minecraft/world/inventory/AbstractContainerMenu".equals(current)) return true;
            ClassNode node = nodes.get(current);
            if (node == null) return false;
            current = node.superName;
        }
        return false;
    }

    private static boolean clientPlayerOwner(String owner) {
        if (owner == null) return false;
        String normalized = owner.replace('.', '/');
        if ("net/minecraft/client/player/LocalPlayer".equals(normalized)
                || "net/minecraft/client/player/AbstractClientPlayer".equals(normalized)) return true;
        return normalized.startsWith("net/minecraft/client/player/")
                && normalized.endsWith("Player");
    }

    private static void putPacketArgument(List<PacketArgument> arguments, PacketArgument replacement) {
        if (arguments == null || replacement == null || replacement.index < 0) return;
        arguments.removeIf(existing -> existing != null && existing.index == replacement.index);
        arguments.add(replacement);
        arguments.sort(java.util.Comparator.comparingInt(argument -> argument.index));
    }

    private static Boolean constantBooleanArgument(MethodInsnNode constructor,
                                                   AbstractInsnNode floorInclusive) {
        if (constructor == null || constructor.desc == null) return null;
        Type[] arguments;
        try {
            arguments = Type.getArgumentTypes(constructor.desc);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
        // The branch-local constant is safe to recover whenever boolean is the final constructor
        // argument. Earlier arguments may be UUIDs, positions, ids, etc.; JVM evaluation leaves
        // the final boolean push immediately before invokespecial just as it does for (Z)V.
        if (arguments.length == 0 || arguments[arguments.length - 1].getSort() != Type.BOOLEAN) {
            return null;
        }
        int remaining = 8;
        for (AbstractInsnNode previous = constructor.getPrevious(); previous != null && remaining-- > 0;
             previous = previous.getPrevious()) {
            if (previous == floorInclusive) break;
            int opcode = previous.getOpcode();
            if (opcode < 0) continue;
            if (opcode == Opcodes.ICONST_0) return Boolean.FALSE;
            if (opcode == Opcodes.ICONST_1) return Boolean.TRUE;
            if (previous instanceof IntInsnNode value
                    && (value.getOpcode() == Opcodes.BIPUSH || value.getOpcode() == Opcodes.SIPUSH)) {
                if (value.operand == 0) return Boolean.FALSE;
                if (value.operand == 1) return Boolean.TRUE;
            }
            if (previous instanceof LdcInsnNode ldc && ldc.cst instanceof Integer value) {
                if (value == 0) return Boolean.FALSE;
                if (value == 1) return Boolean.TRUE;
            }
            // The boolean value is the final constructor argument, so the first real instruction
            // before <init> is authoritative. If it is dynamic, do not guess from older constants.
            return null;
        }
        return null;
    }

    private static String producedTypeBefore(AbstractInsnNode instruction,
                                             Map<String, ClassNode> nodes, boolean screenOnly) {
        return producedTypeBefore(instruction, nodes, screenOnly, null);
    }

    private static String producedTypeBefore(AbstractInsnNode instruction,
                                             Map<String, ClassNode> nodes, boolean screenOnly,
                                             AbstractInsnNode floorInclusive) {
        int remaining = 48;
        for (AbstractInsnNode previous = instruction.getPrevious(); previous != null && remaining-- > 0;
             previous = previous.getPrevious()) {
            if (previous instanceof TypeInsnNode type && type.getOpcode() == Opcodes.NEW) {
                if (screenOnly) {
                    if (isScreenType(type.desc, nodes)) return type.desc;
                } else if (isPayloadCandidate(type.desc, nodes)) {
                    return type.desc;
                }
            }
            if (previous instanceof MethodInsnNode call) {
                String returned = returnedObject(call.desc);
                if (returned == null) continue;
                if (screenOnly ? isScreenType(returned, nodes) : isPayloadCandidate(returned, nodes)) {
                    return returned;
                }
            }
            if (previous == floorInclusive) break;
        }
        return null;
    }

    private static boolean isSetScreen(MethodInsnNode call) {
        return "net/minecraft/client/Minecraft".equals(call.owner)
                && call.desc.contains("Lnet/minecraft/client/gui/screens/Screen;")
                && call.desc.endsWith(")V");
    }

    private static boolean isGameNetworkSend(MethodInsnNode call) {
        String owner = call.owner.toLowerCase(Locale.ROOT);
        String name = call.name.toLowerCase(Locale.ROOT);
        if (name.contains("sendtoserver")) return true;
        // Client packet APIs frequently spell the same C2S boundary as sendPacketToServer,
        // sendMessageToServer, etc. Accept that name shape only when the method actually carries
        // a payload-like reference; this avoids promoting unrelated helpers such as
        // sendServerMetrics() into packet actions while remaining mapping/mod agnostic.
        if (name.startsWith("send") && name.contains("server") && payloadCarrierCall(call)) return true;
        if (owner.contains("clientplaynetworking") && name.contains("send")) return true;
        // SimpleChannel.send(PacketTarget, msg) and PacketDistributor.sendToPlayer/allPlayers are
        // server -> client APIs. Treating every method named "send" on those transports as C2S
        // published clientbound packets as interactive key actions (Aether's invisibility sync is
        // a representative real-world case). Their actual C2S entry point is sendToServer, already
        // handled above; ambiguous generic sends stay declarative rather than guessing direction.
        return owner.contains("clientpacketlistener")
                && (name.contains("send") || call.desc.contains("Lnet/minecraft/network/protocol/Packet;"));
    }

    /** True when this call is a small transport helper whose payload-like argument is forwarded to
     * a concrete game-network send. This lets discovery retain the semantic caller payload instead
     * of a framework-owned envelope constructed inside helpers such as send(Record). */
    private static boolean forwardsNetworkSend(MethodInsnNode call, Map<String, ClassNode> nodes) {
        if (call == null || nodes == null || isGameNetworkSend(call) || !payloadCarrierCall(call)) {
            return false;
        }
        ClassNode owner = nodes.get(call.owner);
        if (owner == null) return false;
        MethodNode method = findMethodInHierarchy(owner, call.name, call.desc, nodes);
        if (method == null) return false;
        return methodForwardsNetworkSend(owner.name, method, nodes, new HashSet<>(), 0);
    }

    private static boolean methodForwardsNetworkSend(String ownerName, MethodNode method,
                                                     Map<String, ClassNode> nodes,
                                                     Set<String> seen, int depth) {
        if (method == null || method.instructions == null || depth > 2) return false;
        String id = ownerName + '#' + methodKey(method);
        if (!seen.add(id)) return false;
        for (AbstractInsnNode instruction : method.instructions) {
            if (!(instruction instanceof MethodInsnNode nested)) continue;
            if (isGameNetworkSend(nested)) return true;
            if (depth >= 2 || "<init>".equals(nested.name) || !payloadCarrierCall(nested)) continue;
            ClassNode nestedOwner = nodes.get(nested.owner);
            if (nestedOwner == null) continue;
            MethodNode nestedMethod = findMethodInHierarchy(
                    nestedOwner, nested.name, nested.desc, nodes);
            if (nestedMethod != null && methodForwardsNetworkSend(
                    nestedOwner.name, nestedMethod, nodes, seen, depth + 1)) return true;
        }
        return false;
    }

    private static boolean payloadCarrierCall(MethodInsnNode call) {
        if (call == null || call.desc == null) return false;
        try {
            for (Type argument : Type.getArgumentTypes(call.desc)) {
                if (argument.getSort() != Type.OBJECT) continue;
                String type = argument.getInternalName();
                if ("java/lang/Record".equals(type)
                        || "net/minecraft/network/protocol/common/custom/CustomPacketPayload".equals(type)
                        || "net/minecraft/network/protocol/Packet".equals(type)) return true;
                String lower = type.toLowerCase(Locale.ROOT);
                int slash = lower.lastIndexOf('/');
                String simple = slash < 0 ? lower : lower.substring(slash + 1);
                if (simple.contains("packet") || simple.contains("payload")
                        || simple.contains("message")) return true;
            }
        } catch (IllegalArgumentException ignored) {
        }
        return false;
    }

    /** Recover the concrete caller value passed through a forwarding send helper. Unlike the
     * ordinary direct-send scan, this requires the candidate to be assignable to one of the
     * helper's payload-like parameters. That prevents the helper receiver (for example
     * OwoNetChannel.ClientHandle) from being mistaken for the payload when it is the nearest object
     * producer in bytecode. */
    private static PacketFact producedForwardedPacketBefore(String sourceOwner, MethodInsnNode call,
                                                            Map<String, ClassNode> nodes,
                                                            AbstractInsnNode floorInclusive) {
        if (call == null) return null;
        Set<String> carriers = payloadCarrierTypes(call.desc);
        if (carriers.isEmpty()) return null;
        int remaining = 64;
        for (AbstractInsnNode previous = call.getPrevious(); previous != null && remaining-- > 0;
             previous = previous.getPrevious()) {
            if (previous instanceof MethodInsnNode producer) {
                if (producer.getOpcode() == Opcodes.INVOKESPECIAL && "<init>".equals(producer.name)
                        && isPayloadCandidate(producer.owner, nodes)
                        && assignableToAny(producer.owner, carriers, nodes)) {
                    return new PacketFact(producer.owner, producer.desc,
                            constantBooleanArgument(producer, floorInclusive),
                            constantStaticFieldArgument(producer, floorInclusive),
                            packetArgumentsBefore(sourceOwner, producer, nodes, floorInclusive),
                            producer.owner, producer.name, producer.desc);
                }
                String returned = returnedObject(producer.desc);
                if (returned != null && isPayloadCandidate(returned, nodes)
                        && assignableToAny(returned, carriers, nodes)) {
                    return new PacketFact(returned, null, null, null, List.of(),
                            producer.owner, producer.name, producer.desc);
                }
            }
            if (previous instanceof TypeInsnNode type && type.getOpcode() == Opcodes.NEW
                    && isPayloadCandidate(type.desc, nodes)
                    && assignableToAny(type.desc, carriers, nodes)) {
                return new PacketFact(type.desc, null, null, null, List.of(), null, null, null);
            }
            if (previous == floorInclusive) break;
        }
        return null;
    }

    private static Set<String> payloadCarrierTypes(String descriptor) {
        if (descriptor == null) return Set.of();
        LinkedHashSet<String> out = new LinkedHashSet<>();
        try {
            for (Type argument : Type.getArgumentTypes(descriptor)) {
                if (argument.getSort() != Type.OBJECT) continue;
                String type = argument.getInternalName();
                String lower = type.toLowerCase(Locale.ROOT);
                int slash = lower.lastIndexOf('/');
                String simple = slash < 0 ? lower : lower.substring(slash + 1);
                if ("java/lang/Record".equals(type)
                        || "net/minecraft/network/protocol/common/custom/CustomPacketPayload".equals(type)
                        || "net/minecraft/network/protocol/Packet".equals(type)
                        || simple.contains("packet") || simple.contains("payload")
                        || simple.contains("message")) {
                    out.add(type);
                }
            }
        } catch (IllegalArgumentException ignored) {
        }
        return Set.copyOf(out);
    }

    private static boolean assignableToAny(String candidate, Set<String> targets,
                                           Map<String, ClassNode> nodes) {
        for (String target : targets) {
            if (assignableTo(candidate, target, nodes)) return true;
        }
        return false;
    }

    private static boolean assignableTo(String candidate, String target,
                                        Map<String, ClassNode> nodes) {
        if (candidate == null || target == null) return false;
        if (candidate.equals(target) || "java/lang/Object".equals(target)) return true;
        ArrayDeque<String> queue = new ArrayDeque<>();
        HashSet<String> seen = new HashSet<>();
        queue.add(candidate);
        while (!queue.isEmpty()) {
            String current = queue.removeFirst();
            if (!seen.add(current)) continue;
            if (target.equals(current)) return true;
            ClassNode node = nodes == null ? null : nodes.get(current);
            if (node == null) continue;
            if (node.superName != null) queue.add(node.superName);
            if (node.interfaces != null) queue.addAll(node.interfaces);
        }
        return false;
    }

    private static boolean isPayloadCandidate(String type, Map<String, ClassNode> nodes) {
        if (type == null || type.startsWith("java/") || type.startsWith("javax/")
                || type.startsWith("net/minecraft/client/") || isKeyMappingType(type)
                || isNetworkTransportType(type)) return false;
        return !isScreenType(type, nodes);
    }

    /** Network API receiver/helper objects can appear immediately before the actual payload load
     * because JVM evaluation pushes the channel/connection receiver before method arguments. They
     * are never themselves the C2S payload. Treating a returned SimpleChannel/PacketDistributor as
     * the packet creates a particularly dangerous false action: the UI looks interactive, but no
     * server handler can ever exist for the transport object. Keep this list structural and limited
     * to well-known transport class shapes rather than package/mod ids. */
    private static boolean isNetworkTransportType(String type) {
        if (type == null || type.isBlank()) return false;
        String lower = type.toLowerCase(Locale.ROOT).replace('.', '/');
        String simple = lower.substring(lower.lastIndexOf('/') + 1);
        return simple.equals("simplechannel")
                || simple.equals("eventnetworkchannel")
                || simple.equals("networkchannel")
                || simple.equals("packetdistributor")
                || simple.equals("clientpacketlistener")
                || simple.equals("connection");
    }

    private static String returnedObject(String descriptor) {
        try {
            Type type = Type.getReturnType(descriptor);
            return type.getSort() == Type.OBJECT ? type.getInternalName() : null;
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static boolean isScreenType(String type, Map<String, ClassNode> nodes) {
        if (type == null) return false;
        Set<String> seen = new HashSet<>();
        boolean namedScreenCandidate = false;
        // Breadth-first over superclasses *and* interfaces: mod screens frequently reach a vanilla
        // base through an interface (MenuAccess) or an embedded library's abstract screen, and a
        // superName-only walk misses those entirely.
        Deque<String> queue = new ArrayDeque<>();
        queue.add(type);
        while (!queue.isEmpty()) {
            String current = queue.poll();
            if (current == null || !seen.add(current)) continue;
            if (SCREEN_ROOTS.contains(current)) return true;
            // Desktop/app UIs are not Minecraft client screens. Check the whole resolved ancestry
            // before accepting a name-only ".../gui/*GUI" fallback; otherwise embedded Swing tools
            // such as crash reporters are accidentally certified as HUD_SCENE surfaces.
            if (desktopUiType(current)) return false;
            namedScreenCandidate |= looksLikeScreenName(current);
            ClassNode node = nodes == null ? null : nodes.get(current);
            if (node == null) continue;
            if (node.superName != null) queue.add(node.superName);
            if (node.interfaces != null) queue.addAll(node.interfaces);
        }
        return namedScreenCandidate;
    }

    private static boolean desktopUiType(String type) {
        if (type == null) return false;
        String lower = type.toLowerCase(Locale.ROOT);
        return lower.startsWith("javax/swing/") || lower.startsWith("java/awt/")
                || lower.startsWith("javafx/") || lower.startsWith("org/eclipse/swt/");
    }

    /**
     * Conservative name test for a client UI class: the type must sit in a recognised UI package
     * (or under {@code net/minecraft/client/}) *and* end in a UI-ish suffix. Requiring both halves
     * is what keeps network payloads that merely end in "Screen" from being classified as GUIs.
     */
    private static boolean looksLikeScreenName(String type) {
        if (type == null) return false;
        String lower = type.toLowerCase(Locale.ROOT);
        boolean uiPath = lower.startsWith("net/minecraft/client/") || lower.contains("/client/");
        if (!uiPath) {
            for (String token : UI_PATH_TOKENS) {
                if (lower.contains(token)) { uiPath = true; break; }
            }
        }
        if (!uiPath) return false;
        // Compare against the simple name so that inner classes (Foo$BarScreen) and package
        // segments do not produce accidental matches.
        int slash = lower.lastIndexOf('/');
        String simple = slash < 0 ? lower : lower.substring(slash + 1);
        int dollar = simple.lastIndexOf('$');
        String leaf = dollar < 0 ? simple : simple.substring(dollar + 1);
        // Anonymous inner classes (Foo$1) carry no name signal; fall back to the enclosing name.
        if (!leaf.isEmpty() && leaf.chars().allMatch(Character::isDigit)) {
            leaf = dollar < 0 ? simple : simple.substring(0, dollar);
        }
        if (leaf.contains("screenbase")) return true;
        for (String suffix : UI_NAME_SUFFIXES) {
            if (leaf.endsWith(suffix)) return true;
        }
        return false;
    }

    private static boolean isKeyMappingType(String type) {
        if (type == null) return false;
        String lower = type.toLowerCase(Locale.ROOT);
        return lower.endsWith("/keymapping") || lower.endsWith("/keybinding")
                || lower.endsWith("/hotkey") || lower.endsWith("/hotkeybinding")
                || lower.contains("client/keymapping") || lower.contains("client/option/keybinding");
    }

    private static boolean isKeyMappingDescriptor(String descriptor) {
        if (descriptor == null) return false;
        try {
            Type type = Type.getType(descriptor);
            return type.getSort() == Type.OBJECT && isKeyMappingType(type.getInternalName());
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private static String fieldKey(String owner, String name) {
        return owner + "." + name;
    }

    private static String methodKey(MethodNode method) {
        return methodKey(method.name, method.desc);
    }

    private static String methodKey(String name, String desc) {
        return name + desc;
    }

    private static String methodKey(String owner, String name, String desc) {
        return owner + '#' + methodKey(name, desc);
    }

    private static void scanMethod(MethodNode method, Set<String> refs, ClassSurface surface) {
        collectAnnotationReferences(method.visibleAnnotations, refs, surface.mixinTargets);
        collectAnnotationReferences(method.invisibleAnnotations, refs, surface.mixinTargets);
        boolean methodClientAnnotation = clientAnnotation(method.visibleAnnotations)
                || clientAnnotation(method.invisibleAnnotations);
        String name = method.name.toLowerCase(Locale.ROOT);
        String desc = method.desc.toLowerCase(Locale.ROOT);
        Hook hook = new Hook();
        hook.method = method.name;
        hook.descriptor = method.desc;

        boolean mixinInjectionHook = mixinInjectionHook(method.visibleAnnotations)
                || mixinInjectionHook(method.invisibleAnnotations);
        if (mixinInjectionHook) {
            hook.mixinInjection = true;
            hook.kinds.add(Kind.MIXIN);
            classifyMixinHookAnnotations(method.visibleAnnotations, hook.kinds);
            classifyMixinHookAnnotations(method.invisibleAnnotations, hook.kinds);
        }

        if (desc.contains("guigraphics") || desc.contains("renderguioverlayevent")
                || desc.contains("renderguievent")) {
            hook.kinds.add(Kind.HUD);
            if (name.contains("overlay")) hook.kinds.add(Kind.OVERLAY);
        }
        if ((name.contains("render") || name.contains("draw"))
                && (desc.contains("posestack") || desc.contains("guigraphics")
                || desc.contains("rendertooltip") || desc.contains("renderlevel"))) {
            hook.kinds.add(Kind.RENDERER);
        }
        if (name.contains("tick") && (name.contains("client") || desc.contains("clienttick"))) {
            hook.kinds.add(Kind.CLIENT_TICK);
        }
        if (desc.contains("mouse") || desc.contains("keyinput") || desc.contains("keyevent")) {
            hook.kinds.add(Kind.INPUT);
        }
        if (inputMethodName(name)) hook.kinds.add(Kind.INPUT);
        if (mapBehaviorMethod(method)) hook.kinds.add(Kind.MAP);

        boolean keyMappingMethod = desc.contains("keymapping") || desc.contains("keybinding")
                || desc.contains("hotkey") || referencesKeyMapping(method)
                || referencesKeyRegistration(method);
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call) {
                collectClassReference(call.owner, refs);
                collectDescriptor(call.desc, refs);
                String callName = call.name.toLowerCase(Locale.ROOT);
                String owner = call.owner.toLowerCase(Locale.ROOT);
                if (callName.contains("sendtoserver")
                        || owner.contains("clientpacketlistener") && callName.equals("send")) {
                    hook.kinds.add(Kind.NETWORK);
                }
                if (runtimeLaunch(call) != null) {
                    surface.blockers.add("process_or_native_loading");
                    surface.processNativeMethods.add(methodKey(method));
                }
                if (externalNetworkCall(call)) {
                    surface.blockers.add("external_network_io");
                    surface.externalNetworkMethods.add(methodKey(method));
                }
                if (localFilesystemCall(call)) surface.blockers.add("local_filesystem");
                if (dynamicReflectionCall(call)) {
                    surface.blockers.add("dynamic_reflection_or_classloading");
                }
                if (owner.contains("keymapping") || owner.contains("keybinding")
                        || owner.contains("hotkey")) keyMappingMethod = true;
            } else if (instruction instanceof FieldInsnNode field) {
                collectClassReference(field.owner, refs);
                collectDescriptor(field.desc, refs);
                String owner = field.owner.toLowerCase(Locale.ROOT);
                if (owner.contains("keymapping") || owner.contains("keybinding")
                        || owner.contains("hotkey")) keyMappingMethod = true;
            } else if (instruction instanceof TypeInsnNode type) {
                collectClassReference(type.desc, refs);
                String typeName = type.desc.toLowerCase(Locale.ROOT);
                if (typeName.contains("keymapping") || typeName.contains("keybinding")
                        || typeName.contains("hotkey")) {
                    keyMappingMethod = true;
                }
            } else if (instruction instanceof LdcInsnNode ldc) {
                if (ldc.cst instanceof Type type) collectType(type, refs);
                if (ldc.cst instanceof String text && looksLikeKeybind(text, keyMappingMethod)) {
                    addKeybind(surface, text);
                }
            }
        }
        if (methodClientAnnotation && hook.kinds.isEmpty()) hook.kinds.add(Kind.CLIENT_TICK);
        if (!hook.kinds.isEmpty()) {
            if (surface.hooks.size() < MAX_HOOKS) surface.hooks.add(hook);
            else markScanTruncated(surface, "hooks", MAX_HOOKS);
            surface.kinds.addAll(hook.kinds);
            surface.clientScore += methodClientAnnotation ? 3 : 1;
        }
    }

    private static boolean inputMethodName(String lowerName) {
        if (lowerName == null || lowerName.isEmpty()) return false;
        return lowerName.equals("mouseclicked")
                || lowerName.equals("mousereleased")
                || lowerName.equals("mousedragged")
                || lowerName.equals("mousescrolled")
                || lowerName.equals("mousemove")
                || lowerName.equals("handlemousewheel")
                || lowerName.equals("handleleftclick")
                || lowerName.equals("handlerightclick")
                || lowerName.equals("keypressed")
                || lowerName.equals("keyreleased")
                || lowerName.equals("chartyped")
                || lowerName.startsWith("handlekey")
                || lowerName.startsWith("onkey");
    }

    /** Structural screen evidence used when a third-party UI base class is absent from the
     * current archive/classpath. One lifecycle-ish method alone is too weak; a widget attachment
     * call or a combination of init + input/render behavior proves an interactive client screen. */
    private static boolean interactiveScreenShape(ClassNode node) {
        if (node == null || node.methods == null) return false;
        boolean init = false;
        boolean renderOrInput = false;
        boolean widgetAttachment = false;
        for (MethodNode method : node.methods) {
            if (method == null || method.name == null) continue;
            String name = method.name.toLowerCase(Locale.ROOT);
            if (name.equals("init") || name.equals("m_7856_")) init = true;
            if (name.contains("render") || name.contains("draw") || inputMethodName(name)
                    || name.equals("onclose") || name.equals("removed")) {
                renderOrInput = true;
            }
            for (AbstractInsnNode instruction : method.instructions) {
                if (!(instruction instanceof MethodInsnNode call) || call.name == null) continue;
                String called = call.name.toLowerCase(Locale.ROOT);
                if (called.contains("addrenderablewidget") || called.contains("addrenderableonly")
                        || called.equals("addwidget") || called.contains("setinitialfocus")) {
                    widgetAttachment = true;
                }
            }
        }
        return widgetAttachment || init && renderOrInput;
    }

    private static boolean mixinInjectionHook(List<AnnotationNode> annotations) {
        if (annotations == null) return false;
        for (AnnotationNode annotation : annotations) {
            if (annotation == null || annotation.desc == null) continue;
            String lower = annotation.desc.toLowerCase(Locale.ROOT);
            if (lower.contains("spongepowered/asm/mixin/overwrite")
                    || lower.contains("spongepowered/asm/mixin/injection/inject")
                    || lower.contains("spongepowered/asm/mixin/injection/redirect")
                    || lower.contains("spongepowered/asm/mixin/injection/modify")
                    || lower.contains("llamalad7/mixinextras/injector")
                    || lower.contains("mixinextras/injector")) return true;
        }
        return false;
    }

    /** Adds semantic hook kinds from exact mixin method selectors such as render(...), tick(),
     * mouseClicked(...), or openWorldMap(). This remains declarative; the injected client method is
     * never invoked on the server. */
    private static void classifyMixinHookAnnotations(List<AnnotationNode> annotations, Set<Kind> out) {
        if (annotations == null || out == null) return;
        for (AnnotationNode annotation : annotations) {
            if (annotation == null || annotation.desc == null) continue;
            String lowerDesc = annotation.desc.toLowerCase(Locale.ROOT);
            if (!(lowerDesc.contains("spongepowered/asm/mixin/injection/")
                    || lowerDesc.contains("mixinextras/injector"))) continue;
            LinkedHashSet<String> strings = new LinkedHashSet<>();
            collectAnnotationStrings(annotation.values, strings);
            for (String value : strings) addHookKindsFromSemantic(value, out);
        }
    }

    private static void collectAnnotationStrings(Object value, Set<String> out) {
        if (value == null || out == null) return;
        if (value instanceof String text) {
            out.add(text);
            return;
        }
        if (value instanceof List<?> list) {
            for (Object element : list) collectAnnotationStrings(element, out);
            return;
        }
        if (value instanceof AnnotationNode annotation) {
            collectAnnotationStrings(annotation.values, out);
        }
    }

    private static void addHookKindsFromSemantic(String value, Set<Kind> out) {
        if (value == null || out == null) return;
        String lower = value.toLowerCase(Locale.ROOT);
        int paren = lower.indexOf('(');
        String method = paren >= 0 ? lower.substring(0, paren) : lower;
        int slash = Math.max(method.lastIndexOf('/'), method.lastIndexOf('.'));
        if (slash >= 0) method = method.substring(slash + 1);
        if (method.contains("render") || method.contains("draw") || method.contains("blit")) {
            out.add(Kind.RENDERER);
        }
        if (method.contains("hud") || method.contains("overlay") || method.contains("gui")) {
            out.add(Kind.HUD);
        }
        if (method.contains("tick")) out.add(Kind.CLIENT_TICK);
        if (inputMethodName(method) || method.contains("input") || method.contains("scroll")) {
            out.add(Kind.INPUT);
        }
        if (method.contains("screen") || method.contains("setgui")) out.add(Kind.SCREEN);
        if (mapSemantic(method) || method.contains("waypoint")) out.add(Kind.MAP);
    }

    private static boolean mapBehaviorMethod(MethodNode method) {
        if (method == null || method.name == null) return false;
        String name = method.name.toLowerCase(Locale.ROOT);
        if (mapSemantic(name) || name.contains("waypoint")
                || name.contains("mapzoom") || name.contains("mapcenter")) return true;
        for (AbstractInsnNode instruction : method.instructions) {
            String owner = null;
            if (instruction instanceof MethodInsnNode call) owner = call.owner;
            else if (instruction instanceof FieldInsnNode field) owner = field.owner;
            else if (instruction instanceof TypeInsnNode type) owner = type.desc;
            if (owner == null) continue;
            String lower = owner.toLowerCase(Locale.ROOT);
            // A helper merely living inside a world-map package is not enough: Xaero's
            // CrosshairMessageOverlay is a HUD owned by the world-map jar but is not itself map
            // rendering. Require a map-specific referenced type/manager here; class/method names
            // are handled separately above.
            if (lower.contains("maprenderer") || lower.contains("maptexture")
                    || lower.contains("waypoint")) return true;
        }
        return false;
    }

    /** Returns only operations that actually launch a process or load native code. Constructing a
     * ProcessBuilder or mentioning JNA/LWJGL types is not itself a launch. */
    private static RuntimeLaunch runtimeLaunch(MethodInsnNode call) {
        if (call == null || call.owner == null || call.name == null) return null;
        String owner = call.owner.toLowerCase(Locale.ROOT);
        String name = call.name.toLowerCase(Locale.ROOT);
        String target = call.owner + '#' + call.name + call.desc;
        if (owner.equals("java/lang/runtime") && name.equals("exec")) {
            return new RuntimeLaunch(ActionType.LAUNCH_PROCESS, target);
        }
        if (owner.equals("java/lang/processbuilder")
                && (name.equals("start") || name.equals("startpipeline"))) {
            return new RuntimeLaunch(ActionType.LAUNCH_PROCESS, target);
        }
        if ((owner.equals("java/lang/system") || owner.equals("java/lang/runtime"))
                && (name.equals("load") || name.equals("loadlibrary"))) {
            return new RuntimeLaunch(ActionType.LOAD_NATIVE_LIBRARY, target);
        }
        if (owner.equals("com/sun/jna/native")
                && (name.equals("load") || name.equals("loadlibrary"))) {
            return new RuntimeLaunch(ActionType.LOAD_NATIVE_LIBRARY, target);
        }
        if (owner.equals("org/lwjgl/system/library")
                && (name.startsWith("load") || name.contains("loadsystem"))) {
            return new RuntimeLaunch(ActionType.LOAD_NATIVE_LIBRARY, target);
        }
        return null;
    }

    /**
     * Proves an actual outbound/network operation from a method invocation. Merely mentioning
     * {@code URI}, {@code URL}, {@code URLEncoder}, HTTP request DTOs, etc. is not network I/O and
     * must not become a hard compatibility blocker. Constructors are included only for socket
     * types whose construction can establish/bind a connection.
     */
    private static boolean externalNetworkCall(MethodInsnNode call) {
        if (call == null || call.owner == null || call.name == null) return false;
        String owner = call.owner.toLowerCase(Locale.ROOT);
        String name = call.name.toLowerCase(Locale.ROOT);

        if (owner.equals("java/net/url")) {
            return name.equals("openconnection") || name.equals("openstream") || name.equals("getcontent");
        }
        if (owner.equals("java/net/urlconnection") || owner.endsWith("/httpurlconnection")
                || owner.endsWith("/httpsurlconnection")) {
            return name.equals("connect") || name.equals("getinputstream")
                    || name.equals("getoutputstream") || name.equals("getcontent");
        }
        if (owner.equals("java/net/socket") || owner.equals("java/net/serversocket")
                || owner.equals("java/net/datagramsocket") || owner.equals("java/net/multicastsocket")) {
            return name.equals("<init>") || name.equals("connect") || name.equals("bind")
                    || name.equals("accept") || name.equals("send") || name.equals("receive")
                    || name.equals("getinputstream") || name.equals("getoutputstream");
        }
        if (owner.equals("java/net/inetaddress")) {
            return name.equals("getbyname") || name.equals("getallbyname") || name.equals("isreachable");
        }
        if (owner.startsWith("java/net/http/httpclient")) {
            return name.equals("send") || name.equals("sendasync");
        }
        if (owner.startsWith("java/net/http/websocket") && name.equals("buildasync")) return true;

        if (owner.startsWith("okhttp3/")) {
            return name.equals("newcall") || name.equals("execute") || name.equals("enqueue")
                    || name.equals("newwebsocket");
        }
        return false;
    }

    /** Path/File values are often just identifiers. Only operations that touch the host file
     * system are compatibility risks for a server-side client stand-in. */
    private static boolean localFilesystemCall(MethodInsnNode call) {
        if (call == null || call.owner == null || call.name == null) return false;
        String owner = call.owner.toLowerCase(Locale.ROOT);
        String name = call.name.toLowerCase(Locale.ROOT);
        if (owner.equals("java/nio/file/files")) return true;
        if (owner.equals("java/nio/channels/filechannel")) return name.equals("open");
        if (owner.equals("java/io/fileinputstream") || owner.equals("java/io/fileoutputstream")
                || owner.equals("java/io/filereader") || owner.equals("java/io/filewriter")
                || owner.equals("java/io/randomaccessfile")) {
            return name.equals("<init>");
        }
        if (owner.equals("java/io/file")) {
            return name.equals("exists") || name.equals("isfile") || name.equals("isdirectory")
                    || name.equals("canread") || name.equals("canwrite") || name.equals("canexecute")
                    || name.equals("list") || name.equals("listfiles") || name.equals("mkdir")
                    || name.equals("mkdirs") || name.equals("createnewfile") || name.equals("delete")
                    || name.equals("deleteonexit") || name.equals("renameto") || name.equals("length")
                    || name.equals("lastmodified") || name.startsWith("setreadable")
                    || name.startsWith("setwritable") || name.startsWith("setexecutable")
                    || name.equals("setlastmodified");
        }
        return false;
    }

    /** Reflection types in signatures/fields are harmless by themselves. Keep the risk only when
     * bytecode actually performs reflective lookup/invocation or dynamic class loading. */
    private static boolean dynamicReflectionCall(MethodInsnNode call) {
        if (call == null || call.owner == null || call.name == null) return false;
        String owner = call.owner.toLowerCase(Locale.ROOT);
        String name = call.name.toLowerCase(Locale.ROOT);
        if (owner.equals("java/lang/classloader")) {
            return name.equals("loadclass") || name.equals("findclass") || name.equals("defineclass")
                    || name.equals("findsystemclass") || name.equals("getsystemclassloader");
        }
        if (owner.equals("java/lang/class")) {
            return name.equals("forname") || name.equals("newinstance")
                    || name.startsWith("getdeclaredmethod") || name.startsWith("getmethod")
                    || name.startsWith("getdeclaredfield") || name.startsWith("getfield")
                    || name.startsWith("getdeclaredconstructor") || name.startsWith("getconstructor")
                    || name.equals("getdeclaredmethods") || name.equals("getmethods")
                    || name.equals("getdeclaredfields") || name.equals("getfields")
                    || name.equals("getdeclaredconstructors") || name.equals("getconstructors");
        }
        if (owner.equals("java/lang/reflect/method")) return name.equals("invoke");
        if (owner.equals("java/lang/reflect/constructor")) return name.equals("newinstance");
        if (owner.equals("java/lang/reflect/field")) {
            return name.equals("get") || name.startsWith("getboolean") || name.startsWith("getbyte")
                    || name.startsWith("getchar") || name.startsWith("getshort") || name.startsWith("getint")
                    || name.startsWith("getlong") || name.startsWith("getfloat") || name.startsWith("getdouble")
                    || name.equals("set") || name.startsWith("setboolean") || name.startsWith("setbyte")
                    || name.startsWith("setchar") || name.startsWith("setshort") || name.startsWith("setint")
                    || name.startsWith("setlong") || name.startsWith("setfloat") || name.startsWith("setdouble");
        }
        if (owner.equals("java/lang/reflect/accessibleobject")) {
            return name.equals("setaccessible") || name.equals("trysetaccessible");
        }
        if (owner.contains("methodhandles$lookup")) {
            return name.startsWith("find") || name.startsWith("unreflect") || name.startsWith("define")
                    || name.equals("ensureinitialized");
        }
        return owner.equals("java/util/serviceloader") && (name.equals("load") || name.equals("reload"));
    }

    private static boolean referencesKeyMapping(MethodNode method) {
        for (AbstractInsnNode instruction : method.instructions) {
            String owner = null;
            if (instruction instanceof MethodInsnNode call) owner = call.owner;
            else if (instruction instanceof FieldInsnNode field) owner = field.owner;
            else if (instruction instanceof TypeInsnNode type) owner = type.desc;
            if (owner == null) continue;
            String lower = owner.toLowerCase(Locale.ROOT);
            if (lower.contains("keymapping") || lower.contains("keybinding")
                    || lower.endsWith("/hotkey") || lower.contains("hotkeybinding")) return true;
        }
        return false;
    }

    private static boolean referencesKeyRegistration(MethodNode method) {
        if (method == null || method.instructions == null) return false;
        for (AbstractInsnNode instruction : method.instructions) {
            if (!(instruction instanceof MethodInsnNode call) || call.name == null) continue;
            String name = call.name.toLowerCase(Locale.ROOT).replace("_", "");
            if (name.contains("registerkeymapping") || name.contains("registerkeybinding")
                    || name.contains("registerhotkey") || name.equals("addhotkey")
                    || name.equals("bindhotkey")) return true;
        }
        return false;
    }

    static boolean looksLikeKeybind(String text, boolean keyMappingMethod) {
        if (text == null || text.length() > 180) return false;
        // Translation/key ids are token-like. Diagnostic/error messages often mention "hotkey"
        // while running through the same registration helpers; accepting those creates fake
        // controls that a vanilla client can never invoke.
        for (int i = 0; i < text.length(); i++) {
            if (Character.isWhitespace(text.charAt(i))) return false;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.startsWith("key.categories.") || lower.startsWith("category.")) return false;
        if (lower.endsWith(".category") || lower.contains(".category.")) return false;
        if (lower.startsWith("key.")) return true;
        if (!keyMappingMethod) return false;
        return lower.startsWith("gui.") || lower.startsWith("controls.")
                || lower.contains("keybind") || lower.contains("hotkey")
                || lower.contains("open_map") || lower.contains("openmap");
    }

    private static void classifyReference(String ref, ClassSurface surface) {
        if (ref == null) return;
        String lower = ref.toLowerCase(Locale.ROOT);
        if (isClientReference(ref)) addClientReference(surface, ref);
        if (lower.contains("keymapping") || lower.contains("keybinding")
                || lower.contains("registerkeymapping") || lower.contains("hotkey")) {
            surface.kinds.add(Kind.KEYBIND);
        }
        if (lower.equals("net/minecraft/client/gui/screens/screen")
                || lower.contains("client/gui/screens/")) surface.kinds.add(Kind.SCREEN);
        if (lower.contains("inputevent") || lower.contains("mousehandler")
                || lower.contains("keyboardhandler")) surface.kinds.add(Kind.INPUT);
        if (lower.contains("clienttickevent") || lower.contains("clienttick")) {
            surface.kinds.add(Kind.CLIENT_TICK);
        }
        if (lower.contains("guigraphics") || lower.contains("renderguioverlay")
                || lower.contains("gui/overlay") || lower.contains("iguioverlay")) {
            surface.kinds.add(Kind.HUD);
        }
        if (lower.contains("overlay")) surface.kinds.add(Kind.OVERLAY);
        if (lower.contains("renderer") || lower.contains("renderlevelstage")
                || lower.contains("posestack") || lower.contains("vertexconsumer")) {
            surface.kinds.add(Kind.RENDERER);
        }
        if (lower.contains("registercolorhandlersevent") || lower.contains("colorresolver")
                || lower.endsWith("/blockcolor") || lower.endsWith("/itemcolor")
                || lower.contains("registerrenderersevent") || lower.contains("registerlayerdefinition")) {
            surface.kinds.add(Kind.RENDERER);
        }
        if (lower.contains("registerguioverlaysevent")) {
            surface.kinds.add(Kind.HUD);
            surface.kinds.add(Kind.OVERLAY);
        }
        if (lower.contains("maprenderer") || lower.contains("mapitemsaveddata")
                || lower.contains("maptexture") || lower.contains("waypoint")) {
            surface.kinds.add(Kind.MAP);
        }
        if (lower.contains("configscreen") || lower.contains("configscreens")
                || lower.contains("configscreenfactory")) surface.kinds.add(Kind.CONFIG);
        if (lower.contains("clientpacketlistener")) surface.kinds.add(Kind.NETWORK);

        if (lower.startsWith("org/lwjgl/") || lower.contains("opengl") || lower.contains("glfw")) {
            surface.blockers.add("native_window_or_opengl");
        }
        if (lower.startsWith("com/mojang/blaze3d/") && (lower.contains("rendersystem")
                || lower.contains("platform/window"))) surface.blockers.add("gpu_client_runtime");
        if (lower.contains("clientlevel") || lower.contains("clientplayer")) {
            surface.blockers.add("requires_client_world_state");
        }
    }

    private static void addKeybind(ClassSurface surface, String keybind) {
        if (surface == null || keybind == null || surface.keybinds.contains(keybind)) return;
        if (surface.keybinds.size() >= MAX_KEYBINDS) {
            markScanTruncated(surface, "keybinds", MAX_KEYBINDS);
            return;
        }
        surface.keybinds.add(keybind);
    }

    private static void addClientReference(ClassSurface surface, String reference) {
        if (surface == null || reference == null || surface.clientReferences.contains(reference)) return;
        if (surface.clientReferences.size() >= MAX_REFERENCES) {
            markScanTruncated(surface, "references", MAX_REFERENCES);
            return;
        }
        surface.clientReferences.add(reference);
    }

    private static boolean reserveAction(ClassSurface surface) {
        if (surface == null) return false;
        if (surface.actions.size() < MAX_ACTIONS) return true;
        markScanTruncated(surface, "actions", MAX_ACTIONS);
        return false;
    }

    private static void markScanTruncated(ClassSurface surface, String category, int limit) {
        if (surface == null || category == null) return;
        surface.scanComplete = false;
        surface.blockers.add("SCAN_TRUNCATED");
        for (ScanIssue issue : surface.scanIssues) {
            if (issue != null && issue.type == ScanIssueType.SCAN_TRUNCATED
                    && category.equals(issue.category) && issue.limit == limit) return;
        }
        surface.scanIssues.add(new ScanIssue(ScanIssueType.SCAN_TRUNCATED, category, limit));
    }

    private static void classifyName(String lowerClass, ClassSurface surface) {
        boolean clientEvidence = surface.clientScore > 0 || !surface.clientReferences.isEmpty()
                || !surface.keybinds.isEmpty();
        if (lowerClass.contains("screen") || lowerClass.endsWith("gui")) {
            if (clientEvidence || lowerClass.contains("/client/")) surface.kinds.add(Kind.SCREEN);
        }
        if (clientEvidence && (lowerClass.contains("hud") || lowerClass.contains("overlay"))) {
            surface.kinds.add(Kind.HUD);
        }
        if (clientEvidence && lowerClass.contains("overlay")) surface.kinds.add(Kind.OVERLAY);
        if (clientEvidence && (lowerClass.contains("renderer") || lowerClass.contains("renderhandler"))) {
            surface.kinds.add(Kind.RENDERER);
        }
        boolean visualAssetContext = lowerClass.contains("/client/render/")
                || lowerClass.contains("/client/renderer/")
                || lowerClass.contains("/client/model/")
                || lowerClass.contains("/client/models/");
        boolean visualAssetRole = lowerClass.contains("modelrepository")
                || lowerClass.contains("modelprovider") || lowerClass.contains("modelloader")
                || lowerClass.contains("modelcache") || lowerClass.contains("assetrepository")
                || lowerClass.contains("textureloader") || lowerClass.contains("texturecache");
        if (clientEvidence && visualAssetContext && visualAssetRole) {
            surface.kinds.add(Kind.RENDERER);
        }
        if (clientEvidence && lowerClass.contains("config") && (lowerClass.contains("screen")
                || lowerClass.contains("gui"))) surface.kinds.add(Kind.CONFIG);
        if (clientEvidence && mapSemantic(lowerClass)) surface.kinds.add(Kind.MAP);
        for (String key : surface.keybinds) {
            if (mapSemantic(key.toLowerCase(Locale.ROOT))) surface.kinds.add(Kind.MAP);
        }
    }

    private static boolean mapSemantic(String value) {
        String compact = value.replace("_", "").replace("-", "").replace(".", "");
        return compact.contains("worldmap") || compact.contains("minimap")
                || compact.contains("fullscreenmap") || compact.contains("waypoint")
                || compact.contains("openmap") || compact.contains("mapzoom")
                || compact.contains("zoommap") || compact.contains("centermap")
                || compact.contains("mapcenter") || compact.contains("togglemap")
                || compact.endsWith("mapscreen");
    }

    private static void chooseTranslations(ClassSurface surface) {
        if (surface.metadataOnly) return;
        boolean nativeBlocked = surface.blockers.contains("native_window_or_opengl")
                || surface.blockers.contains("gpu_client_runtime");
        if (surface.kinds.contains(Kind.MAP)) surface.translations.add(Translation.MAP_HUD);
        if (surface.kinds.contains(Kind.SCREEN) || surface.kinds.contains(Kind.HUD)
                || surface.kinds.contains(Kind.OVERLAY) || surface.kinds.contains(Kind.CONFIG)) {
            surface.translations.add(nativeBlocked ? Translation.CAPTURE_RENDER : Translation.HUD_SCENE);
        }
        if (surface.kinds.contains(Kind.KEYBIND) || surface.kinds.contains(Kind.INPUT)
                || surface.kinds.contains(Kind.NETWORK)) surface.translations.add(Translation.INPUT_BRIDGE);
        if (surface.kinds.contains(Kind.CLIENT_TICK)) surface.translations.add(Translation.STATE_MIRROR);
        if (surface.kinds.contains(Kind.RENDERER)) {
            surface.translations.add(nativeBlocked ? Translation.CAPTURE_RENDER : Translation.DISPLAY_PROXY);
        }
        if (surface.kinds.contains(Kind.MIXIN)) surface.translations.add(Translation.HOOK_MIRROR);
        boolean hardExternal = surface.blockers.contains("external_network_io")
                && !surface.remoteVisualFallback && !surface.ancillaryRuntimeEffects
                && hasCoreHardRuntimeBehavior(surface);
        boolean hardProcess = surface.blockers.contains("process_or_native_loading")
                && !surface.ancillaryRuntimeEffects && hasCoreHardRuntimeBehavior(surface);
        if (surface.clientServiceFallback && (hardExternal || hardProcess)) {
            surface.translations.add(Translation.CLIENT_SERVICE_FALLBACK);
        } else if (hardExternal || hardProcess) {
            surface.translations.add(Translation.UNSUPPORTED);
        }
        if (surface.translations.isEmpty()) surface.translations.add(Translation.UNSUPPORTED);
    }

    private static boolean clientAnnotation(List<AnnotationNode> annotations) {
        if (annotations == null) return false;
        for (AnnotationNode annotation : annotations) {
            String desc = annotation.desc == null ? "" : annotation.desc.toLowerCase(Locale.ROOT);
            boolean sideAnnotation = desc.contains("distmarker/onlyin")
                    || desc.contains("fabricmc/api/environment")
                    || desc.contains("eventbussubscriber")
                    || desc.equals("lnet/neoforged/fml/common/mod;")
                    || desc.equals("lnet/minecraftforge/fml/common/mod;")
                    || desc.contains("clientonly");
            if (!sideAnnotation) continue;
            if (desc.contains("clientonly") || annotationContainsClient(annotation.values)) return true;
        }
        return false;
    }

    private static void collectAnnotationReferences(List<AnnotationNode> annotations, Set<String> refs,
                                                    Set<String> mixinTargets) {
        if (annotations == null) return;
        for (AnnotationNode annotation : annotations) {
            if (annotation == null) continue;
            boolean mixin = annotation.desc != null
                    && annotation.desc.toLowerCase(Locale.ROOT).contains("spongepowered/asm/mixin/mixin");
            collectAnnotationValue(annotation.values, refs, mixin ? mixinTargets : null);
        }
    }

    private static void collectAnnotationValue(Object value, Set<String> refs, Set<String> targets) {
        if (value == null) return;
        if (value instanceof Type type) {
            if (type.getSort() == Type.OBJECT) {
                collectClassReference(type.getInternalName(), refs);
                if (targets != null) targets.add(type.getInternalName());
            }
            return;
        }
        if (value instanceof String string) {
            if (targets != null && string.indexOf('.') > 0 && !string.contains(" ")) {
                String target = string.replace('.', '/');
                collectClassReference(target, refs);
                targets.add(target);
            }
            return;
        }
        if (value instanceof Collection<?> values) {
            for (Object entry : values) collectAnnotationValue(entry, refs, targets);
        }
    }

    private static boolean annotationContainsClient(Object value) {
        if (value == null) return false;
        if (value instanceof String string) return "CLIENT".equalsIgnoreCase(string);
        if (value instanceof String[] strings) {
            for (String string : strings) if (annotationContainsClient(string)) return true;
            return false;
        }
        if (value instanceof Collection<?> values) {
            for (Object entry : values) if (annotationContainsClient(entry)) return true;
        }
        return false;
    }

    private static boolean inheritsAny(ClassNode node, Set<String> targets,
                                       Map<String, ClassNode> nodes) {
        Set<String> seen = new HashSet<>();
        String current = node.superName;
        while (current != null && seen.add(current)) {
            if (targets.contains(current)) return true;
            ClassNode parent = nodes.get(current);
            current = parent == null ? null : parent.superName;
        }
        return false;
    }

    private static boolean isClientReference(String ref) {
        if (ref == null) return false;
        return ref.startsWith("net/minecraft/client/")
                || ref.startsWith("net/minecraftforge/client/")
                || ref.startsWith("net/neoforged/neoforge/client/")
                || ref.startsWith("net/fabricmc/fabric/api/client/")
                || ref.equals("net/fabricmc/api/ClientModInitializer")
                || ref.startsWith("com/mojang/blaze3d/") || ref.startsWith("org/lwjgl/");
    }

    private static void collectDescriptor(String descriptor, Set<String> refs) {
        if (descriptor == null || descriptor.isBlank()) return;
        try {
            Type type = descriptor.charAt(0) == '(' ? Type.getMethodType(descriptor)
                    : Type.getType(descriptor);
            collectType(type, refs);
        } catch (IllegalArgumentException ignored) {
        }
    }

    private static void collectType(Type type, Set<String> refs) {
        if (type == null) return;
        if (type.getSort() == Type.METHOD) {
            collectType(type.getReturnType(), refs);
            for (Type argument : type.getArgumentTypes()) collectType(argument, refs);
        } else if (type.getSort() == Type.ARRAY) {
            collectType(type.getElementType(), refs);
        } else if (type.getSort() == Type.OBJECT) {
            collectClassReference(type.getInternalName(), refs);
        }
    }

    private static void collectClassReference(String ref, Set<String> refs) {
        if (ref != null && !ref.isBlank()) refs.add(ref);
    }

    /** Exact keybind-to-surface lookup used by the runtime before falling back to name heuristics. */
    public static Set<Kind> keyKinds(Collection<ModScanner.Profile> profiles, String key) {
        if (profiles == null || key == null || key.isBlank()) return Set.of();
        String wanted = normalizeKey(key);
        LinkedHashSet<Kind> out = new LinkedHashSet<>();
        for (ModScanner.Profile profile : profiles) collectKeyKinds(profile, wanted, out);
        return Set.copyOf(out);
    }

    /** Exact screen classes opened by the requested key according to traced bytecode. */
    public static Set<String> screenTargets(Collection<ModScanner.Profile> profiles, String key) {
        return actionTargets(profiles, key, ActionType.OPEN_SCREEN);
    }

    /** Exact client->server payload classes sent by the requested key according to traced code. */
    public static Set<String> packetTargets(Collection<ModScanner.Profile> profiles, String key) {
        return actionTargets(profiles, key, ActionType.SEND_PACKET);
    }

    /** Exact key-triggered packet edges including branch-local constructor evidence. */
    public static List<Action> packetActions(Collection<ModScanner.Profile> profiles, String key) {
        if (profiles == null || key == null || key.isBlank()) return List.of();
        String wanted = normalizeKey(key);
        ArrayList<Action> out = new ArrayList<>();
        for (ModScanner.Profile profile : profiles) collectPacketActions(profile, wanted, out);
        out.sort(java.util.Comparator
                .comparingInt((Action action) -> action.confidence).reversed()
                .thenComparing(action -> action.target == null ? "" : action.target)
                .thenComparing(action -> action.constructorDesc == null ? "" : action.constructorDesc));
        return List.copyOf(out);
    }

    /** All exact key-triggered behavior edges for one virtual key. This is used by runtime
     * fallbacks to surface explicit unsupported scanner actions instead of silently falling through
     * to a fuzzy screen-name heuristic. */
    public static List<Action> keyActions(Collection<ModScanner.Profile> profiles, String key) {
        if (profiles == null || key == null || key.isBlank()) return List.of();
        String wanted = normalizeKey(key);
        ArrayList<Action> out = new ArrayList<>();
        for (ModScanner.Profile profile : profiles) collectKeyActions(profile, wanted, out);
        out.sort(java.util.Comparator
                .comparingInt((Action action) -> action.confidence).reversed()
                .thenComparing(action -> action.type == null ? "" : action.type.name())
                .thenComparing(action -> action.target == null ? "" : action.target));
        return List.copyOf(out);
    }

    /** Exact widget-triggered behavior edges originating from a concrete client Screen class. */
    public static List<Action> widgetActions(Collection<ModScanner.Profile> profiles,
                                             String sourceScreen) {
        if (profiles == null || sourceScreen == null || sourceScreen.isBlank()) return List.of();
        String wanted = sourceScreen.replace('.', '/');
        Map<String, ClassSurface> surfaces = new HashMap<>();
        for (ModScanner.Profile profile : profiles) collectSurfaces(profile, surfaces);
        LinkedHashSet<String> hierarchy = new LinkedHashSet<>();
        String cursor = wanted;
        while (cursor != null && !cursor.isBlank() && hierarchy.add(cursor)) {
            ClassSurface surface = surfaces.get(cursor);
            cursor = surface == null ? null : surface.superClass;
        }
        ArrayList<Action> out = new ArrayList<>();
        for (ModScanner.Profile profile : profiles) collectWidgetActions(profile, hierarchy, out);
        out.sort(java.util.Comparator
                .comparingInt((Action action) -> hierarchyIndex(hierarchy, action.sourceScreen))
                .thenComparingInt(action -> action.widgetOrdinal)
                .thenComparing(action -> action.type == null ? "" : action.type.name())
                .thenComparing(action -> action.target == null ? "" : action.target));
        return List.copyOf(out);
    }

    /**
     * Authoritative target exposed by the current server container menu for this screen. The proof
     * is intentionally stronger than "some packet used the menu": every menu-derived packet
     * argument found on the exact screen must resolve through the same member and target kind.
     * This lets runtime seed an actionTarget for a menu-backed root screen without guessing a
     * nearby entity/block, while conflicting or partially unrelated menu routes stay unresolved.
     */
    public static MenuTarget menuTarget(Collection<ModScanner.Profile> profiles, String sourceScreen) {
        if (profiles == null || sourceScreen == null || sourceScreen.isBlank()) return null;
        String wanted = sourceScreen.replace('.', '/');
        ArrayList<MenuTarget> found = new ArrayList<>();
        for (ModScanner.Profile profile : profiles) collectMenuTargets(profile, wanted, found);
        if (found.isEmpty()) return null;
        MenuTarget agreed = found.get(0);
        for (int i = 1; i < found.size(); i++) {
            if (!agreed.equals(found.get(i))) return null;
        }
        return agreed;
    }

    /** Same menu-target proof, scoped to one surface that the caller has already located. This is
     * intentionally package-private so compatibility aggregation can avoid rescanning an entire
     * profile once per screen without weakening the public cross-profile agreement check. */
    static MenuTarget menuTarget(ClassSurface surface) {
        if (surface == null || surface.actions == null) return null;
        MenuTarget agreed = null;
        for (Action action : surface.actions) {
            if (action == null || action.packetArguments == null) continue;
            for (PacketArgument argument : action.packetArguments) {
                MenuTarget target = menuTarget(argument);
                if (target == null) continue;
                if (agreed == null) agreed = target;
                else if (!agreed.equals(target)) return null;
            }
        }
        return agreed;
    }

    private static void collectMenuTargets(ModScanner.Profile profile, String wanted,
                                           List<MenuTarget> out) {
        if (profile == null || wanted == null || out == null) return;
        for (ClassSurface surface : profile.clientSurfaces) {
            if (surface == null || surface.clazz == null
                    || !wanted.equals(surface.clazz.replace('.', '/')) || surface.actions == null) continue;
            for (Action action : surface.actions) {
                if (action == null || action.packetArguments == null) continue;
                for (PacketArgument argument : action.packetArguments) {
                    MenuTarget target = menuTarget(argument);
                    if (target != null) out.add(target);
                }
            }
        }
        for (ModScanner.Profile nested : profile.nested) collectMenuTargets(nested, wanted, out);
    }

    private static MenuTarget menuTarget(PacketArgument argument) {
        if (argument == null || argument.kind == null || argument.menuOwner == null
                || argument.menuMemberName == null || argument.menuMemberDesc == null) return null;
        String targetType;
        try {
            if (argument.kind == PacketArgumentKind.MENU_ENTITY_UUID && argument.menuMemberMethod) {
                Type type = Type.getReturnType(argument.menuMemberDesc);
                if (type.getSort() != Type.OBJECT) return null;
                targetType = type.getInternalName();
            } else if (argument.kind == PacketArgumentKind.MENU_BLOCK_POS && !argument.menuMemberMethod) {
                Type type = Type.getType(argument.menuMemberDesc);
                if (type.getSort() != Type.OBJECT) return null;
                targetType = type.getInternalName();
            } else {
                return null;
            }
        } catch (IllegalArgumentException ignored) {
            return null;
        }
        return new MenuTarget(argument.kind, argument.menuOwner, argument.menuMemberName,
                argument.menuMemberDesc, argument.menuMemberMethod, targetType);
    }

    /** Packet commits owned by Screen.onClose()/removed() for this concrete screen hierarchy. */
    public static List<Action> screenCloseActions(Collection<ModScanner.Profile> profiles,
                                                  String sourceScreen) {
        if (profiles == null || sourceScreen == null || sourceScreen.isBlank()) return List.of();
        String wanted = sourceScreen.replace('.', '/');
        Map<String, ClassSurface> surfaces = new HashMap<>();
        for (ModScanner.Profile profile : profiles) collectSurfaces(profile, surfaces);
        LinkedHashSet<String> hierarchy = new LinkedHashSet<>();
        String cursor = wanted;
        while (cursor != null && !cursor.isBlank() && hierarchy.add(cursor)) {
            ClassSurface surface = surfaces.get(cursor);
            cursor = surface == null ? null : surface.superClass;
        }
        ArrayList<Action> out = new ArrayList<>();
        for (ModScanner.Profile profile : profiles) collectScreenCloseActions(profile, hierarchy, out);
        out.sort(java.util.Comparator
                .comparingInt((Action action) -> hierarchyIndex(hierarchy, action.sourceScreen))
                .thenComparing(action -> action.target == null ? "" : action.target)
                .thenComparing(action -> action.constructorDesc == null ? "" : action.constructorDesc));
        return List.copyOf(out);
    }

    private static int hierarchyIndex(Set<String> hierarchy, String owner) {
        if (owner == null) return Integer.MAX_VALUE;
        String wanted = owner.replace('.', '/');
        int index = 0;
        for (String candidate : hierarchy) {
            if (candidate.equals(wanted)) return index;
            index++;
        }
        return Integer.MAX_VALUE;
    }

    private static void collectSurfaces(ModScanner.Profile profile,
                                        Map<String, ClassSurface> out) {
        if (profile == null) return;
        for (ClassSurface surface : profile.clientSurfaces) {
            if (surface != null && surface.clazz != null) {
                out.putIfAbsent(surface.clazz.replace('.', '/'), surface);
            }
        }
        for (ModScanner.Profile nested : profile.nested) collectSurfaces(nested, out);
    }

    private static void collectWidgetActions(ModScanner.Profile profile, Set<String> wanted,
                                             List<Action> out) {
        if (profile == null) return;
        for (ClassSurface surface : profile.clientSurfaces) {
            for (Action action : surface.actions) {
                if (action.triggerType == TriggerType.WIDGET && action.sourceScreen != null
                        && wanted.contains(action.sourceScreen.replace('.', '/'))) out.add(action);
            }
        }
        for (ModScanner.Profile nested : profile.nested) collectWidgetActions(nested, wanted, out);
    }

    private static void collectScreenCloseActions(ModScanner.Profile profile, Set<String> wanted,
                                                  List<Action> out) {
        if (profile == null) return;
        for (ClassSurface surface : profile.clientSurfaces) {
            for (Action action : surface.actions) {
                if (action.triggerType == TriggerType.SCREEN_CLOSE && action.sourceScreen != null
                        && wanted.contains(action.sourceScreen.replace('.', '/'))) out.add(action);
            }
        }
        for (ModScanner.Profile nested : profile.nested) collectScreenCloseActions(nested, wanted, out);
    }

    private static Set<String> actionTargets(Collection<ModScanner.Profile> profiles, String key,
                                             ActionType type) {
        if (profiles == null || key == null || key.isBlank()) return Set.of();
        String wanted = normalizeKey(key);
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (ModScanner.Profile profile : profiles) collectActionTargets(profile, wanted, type, out);
        return Set.copyOf(out);
    }

    private static void collectActionTargets(ModScanner.Profile profile, String wanted,
                                             ActionType type, Set<String> out) {
        if (profile == null) return;
        for (ClassSurface surface : profile.clientSurfaces) {
            for (Action action : surface.actions) {
                if (action.type == type && action.keybind != null
                        && normalizeKey(action.keybind).equals(wanted) && action.target != null) {
                    out.add(action.target);
                }
            }
        }
        for (ModScanner.Profile nested : profile.nested) {
            collectActionTargets(nested, wanted, type, out);
        }
    }

    private static void collectPacketActions(ModScanner.Profile profile, String wanted,
                                             List<Action> out) {
        if (profile == null) return;
        for (ClassSurface surface : profile.clientSurfaces) {
            for (Action action : surface.actions) {
                if (action.type == ActionType.SEND_PACKET && action.triggerType == TriggerType.KEYBIND
                        && action.keybind != null && normalizeKey(action.keybind).equals(wanted)
                        && action.target != null) {
                    out.add(action);
                }
            }
        }
        for (ModScanner.Profile nested : profile.nested) collectPacketActions(nested, wanted, out);
    }

    private static void collectKeyActions(ModScanner.Profile profile, String wanted,
                                          List<Action> out) {
        if (profile == null) return;
        for (ClassSurface surface : profile.clientSurfaces) {
            for (Action action : surface.actions) {
                if (action.triggerType == TriggerType.KEYBIND && action.keybind != null
                        && normalizeKey(action.keybind).equals(wanted)) out.add(action);
            }
        }
        for (ModScanner.Profile nested : profile.nested) collectKeyActions(nested, wanted, out);
    }

    private static void collectKeyKinds(ModScanner.Profile profile, String wanted, Set<Kind> out) {
        if (profile == null) return;
        for (ClassSurface surface : profile.clientSurfaces) {
            for (String candidate : surface.keybinds) {
                if (!normalizeKey(candidate).equals(wanted)) continue;
                for (Kind kind : surface.kinds) {
                    // MAP is key-specific. Large client mods often register every hotkey in one
                    // class; inheriting MAP from a sibling "open map" key would make zoom/config
                    // keys incorrectly open F2V's full-screen map.
                    if (kind != Kind.MAP || mapSemantic(candidate.toLowerCase(Locale.ROOT))) {
                        out.add(kind);
                    }
                }
                if (mapSemantic(candidate.toLowerCase(Locale.ROOT))
                        || surface.actions.stream().anyMatch(action -> action.type == ActionType.OPEN_SCREEN
                        && action.keybind != null && normalizeKey(action.keybind).equals(wanted)
                        && action.target != null
                        && mapSemantic(action.target.toLowerCase(Locale.ROOT)))) {
                    out.add(Kind.MAP);
                }
            }
        }
        for (ModScanner.Profile nested : profile.nested) collectKeyKinds(nested, wanted, out);
    }

    /** Returns scanner-discovered client hooks of one semantic kind, including nested jars. */
    public static List<HookTarget> hooks(Collection<ModScanner.Profile> profiles, Kind kind) {
        if (profiles == null || kind == null) return List.of();
        ArrayList<HookTarget> out = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (ModScanner.Profile profile : profiles) collectHooks(profile, kind, out, seen);
        return List.copyOf(out);
    }

    private static void collectHooks(ModScanner.Profile profile, Kind kind, List<HookTarget> out,
                                     Set<String> seen) {
        if (profile == null) return;
        for (ClassSurface surface : profile.clientSurfaces) {
            if (surface == null || surface.clazz == null) continue;
            for (Hook hook : surface.hooks) {
                if (hook == null || !hook.kinds.contains(kind)) continue;
                String key = surface.clazz + '\n' + hook.method + '\n' + hook.descriptor;
                if (seen.add(key)) {
                    out.add(new HookTarget(surface.clazz, hook.method, hook.descriptor,
                            Set.copyOf(hook.kinds)));
                }
            }
        }
        for (ModScanner.Profile nested : profile.nested) collectHooks(nested, kind, out, seen);
    }

    /**
     * Exact proof used by the capture-client direct execution backend. The dedicated server still
     * treats client surfaces as declarative; only a real client runtime may invoke a method after
     * matching all three bytecode identifiers.
     */
    public static boolean hasExactHook(Collection<ModScanner.Profile> profiles, String owner,
                                       String method, String descriptor) {
        if (profiles == null || owner == null || method == null || descriptor == null) return false;
        String wanted = owner.replace('.', '/');
        for (ModScanner.Profile profile : profiles) {
            if (profileHasExactHook(profile, wanted, method, descriptor)) return true;
        }
        return false;
    }

    private static boolean profileHasExactHook(ModScanner.Profile profile, String owner,
                                               String method, String descriptor) {
        if (profile == null) return false;
        for (ClassSurface surface : profile.clientSurfaces) {
            if (surface == null || surface.clazz == null
                    || !owner.equals(surface.clazz.replace('.', '/'))) continue;
            for (Hook hook : surface.hooks) {
                if (hook != null && method.equals(hook.method) && descriptor.equals(hook.descriptor)) {
                    return true;
                }
            }
        }
        for (ModScanner.Profile nested : profile.nested) {
            if (profileHasExactHook(nested, owner, method, descriptor)) return true;
        }
        return false;
    }

    /** Whether discovery found at least one client surface of the requested semantic kind. */
    public static boolean hasKind(Collection<ModScanner.Profile> profiles, Kind kind) {
        if (profiles == null || kind == null) return false;
        for (ModScanner.Profile profile : profiles) {
            if (profileHasKind(profile, kind)) return true;
        }
        return false;
    }

    /**
     * Whether discovery proved a map is rendered as a persistent HUD/overlay rather than merely
     * exposing a map screen, waypoint screen, keybind, or map renderer somewhere in the mod.
     *
     * <p>This distinction matters for passive translations: a plain {@link Kind#MAP} hit is much
     * broader than "this mod owns a minimap". Treating every map-related class as a passive HUD
     * causes unrelated full-screen/world-map mods to spawn a synthetic map for every player.</p>
     */
    public static boolean hasPassiveMapHud(Collection<ModScanner.Profile> profiles) {
        if (profiles == null) return false;
        for (ModScanner.Profile profile : profiles) {
            if (profileHasPassiveMapHud(profile)) return true;
        }
        return false;
    }

    /** Whether client bytecode contains a runtime pixel-manufacturing surface at all. */
    public static boolean hasRuntimePixelSurfaces(Collection<ModScanner.Profile> profiles) {
        if (profiles == null) return false;
        for (ModScanner.Profile profile : profiles) {
            if (profileHasRuntimePixelSurfaces(profile)) return true;
        }
        return false;
    }

    /**
     * Whether runtime-manufactured pixels remain unresolved after the server-side bytecode pass.
     * DynamicTexture constructors are not automatically a reason to launch a real client: when
     * {@link DynamicTextureBytecodeScanner} recovered the producing method, pack generation can
     * replay the same rectangle-copy program directly from jar PNGs.
     */
    public static boolean requiresRuntimePixelCapture(Collection<ModScanner.Profile> profiles) {
        if (profiles == null) return false;
        for (ModScanner.Profile profile : profiles) {
            if (profileRequiresRuntimePixelCapture(profile)) return true;
        }
        return false;
    }

    private static boolean profileHasKind(ModScanner.Profile profile, Kind kind) {
        if (profile == null) return false;
        for (ClassSurface surface : profile.clientSurfaces) {
            if (surface != null && surface.kinds.contains(kind)) return true;
        }
        for (ModScanner.Profile nested : profile.nested) {
            if (profileHasKind(nested, kind)) return true;
        }
        return false;
    }

    private static boolean profileRequiresRuntimePixelCapture(ModScanner.Profile profile) {
        if (profile == null) return false;
        for (ClassSurface surface : profile.clientSurfaces) {
            if (isRuntimePixelSurface(surface) && !hasDynamicTextureRecipe(profile, surface.clazz)) {
                return true;
            }
        }
        for (ModScanner.Profile nested : profile.nested) {
            if (profileRequiresRuntimePixelCapture(nested)) return true;
        }
        return false;
    }

    private static boolean profileHasRuntimePixelSurfaces(ModScanner.Profile profile) {
        if (profile == null) return false;
        for (ClassSurface surface : profile.clientSurfaces) {
            if (isRuntimePixelSurface(surface)) return true;
        }
        for (ModScanner.Profile nested : profile.nested) {
            if (profileHasRuntimePixelSurfaces(nested)) return true;
        }
        return false;
    }

    private static boolean isRuntimePixelSurface(ClassSurface surface) {
        if (surface == null) return false;
        boolean image = false;
        boolean dynamicTexture = false;
        boolean textureManager = false;
        for (String reference : surface.clientReferences) {
            String lower = reference == null ? "" : reference.toLowerCase(Locale.ROOT);
            image |= lower.contains("nativeimage");
            dynamicTexture |= lower.contains("dynamictexture");
            textureManager |= lower.contains("texturemanager");
        }
        return dynamicTexture || image && textureManager;
    }

    private static boolean hasDynamicTextureRecipe(ModScanner.Profile profile, String producerClass) {
        if (profile == null || producerClass == null) return false;
        for (DynamicTextureBytecodeScanner.TextureRecipe recipe : profile.dynamicTextures) {
            if (recipe != null && (producerClass.equals(recipe.producerClass)
                    || producerClass.equals(recipe.assemblerClass))) return true;
        }
        return false;
    }

    private static boolean profileHasPassiveMapHud(ModScanner.Profile profile) {
        if (profile == null) return false;
        for (ClassSurface surface : profile.clientSurfaces) {
            if (surface == null || !surface.kinds.contains(Kind.MAP)
                    || surface.kinds.contains(Kind.SCREEN)) continue;
            // Passive injection is intentionally conservative. MAP alone also describes world-map
            // screens and render helpers; HUD alone catches any GuiGraphics helper; OVERLAY alone
            // catches unrelated waypoint/world overlays. Requiring all three on the same non-screen
            // class avoids turning those independent hints into a fabricated always-on minimap.
            if (surface.kinds.contains(Kind.HUD) && surface.kinds.contains(Kind.OVERLAY)) {
                return true;
            }
        }
        for (ModScanner.Profile nested : profile.nested) {
            if (profileHasPassiveMapHud(nested)) return true;
        }
        return false;
    }

    private static String normalizeKey(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    /** Writes the machine-readable translation inventory consumed by diagnostics and future
     * compatibility backends. The detailed data also remains embedded in discovery caches. */
    public static Summary writeReport(Collection<ModScanner.Profile> profiles, Path file)
            throws IOException {
        JsonObject root = new JsonObject();
        root.addProperty("schema", SCHEMA);
        root.addProperty("directExecutionAllowed", false);
        root.addProperty("captureClientDirectExecutionAvailable", true);
        root.addProperty("purpose", "client bytecode translation inventory");
        JsonArray mods = new JsonArray();
        int[] counts = new int[6];
        Set<String> seenProfiles = new HashSet<>();
        if (profiles != null) {
            for (ModScanner.Profile profile : profiles) appendProfile(mods, profile, counts, seenProfiles);
        }
        JsonObject summary = new JsonObject();
        summary.addProperty("mods", counts[0]);
        summary.addProperty("clientOnlyMods", counts[1]);
        summary.addProperty("classes", counts[2]);
        summary.addProperty("keybinds", counts[3]);
        summary.addProperty("actions", counts[4]);
        summary.addProperty("blocked", counts[5]);
        root.add("summary", summary);
        root.add("mods", mods);
        Path parent = file.getParent();
        if (parent != null) Files.createDirectories(parent);
        Files.writeString(file, new GsonBuilder().setPrettyPrinting().create().toJson(root),
                StandardCharsets.UTF_8);
        return new Summary(counts[0], counts[1], counts[2], counts[3], counts[4], counts[5]);
    }

    private static void appendProfile(JsonArray mods, ModScanner.Profile profile, int[] counts,
                                      Set<String> seenProfiles) {
        if (profile == null) return;
        String identity = String.valueOf(profile.jarFile) + "\n" + String.valueOf(profile.modId);
        if (!seenProfiles.add(identity)) return;
        if (!profile.clientSurfaces.isEmpty()) {
            JsonObject mod = new JsonObject();
            List<ClassSurface> orderedSurfaces = new ArrayList<>(profile.clientSurfaces);
            orderedSurfaces.sort(java.util.Comparator.comparing(surface ->
                    surface.clazz == null ? "" : surface.clazz));
            boolean clientOnlyLikely = profile.declaredClientOnly
                    || profile.menus.isEmpty() && profile.blocks.isEmpty()
                    && profile.blockEntities.isEmpty() && profile.entities.isEmpty()
                    && profile.fluids.isEmpty();
            mod.addProperty("modId", profile.modId);
            mod.addProperty("modName", profile.modName);
            mod.addProperty("version", profile.version);
            mod.addProperty("jarFile", profile.jarFile);
            mod.addProperty("declaredClientOnly", profile.declaredClientOnly);
            mod.addProperty("clientOnlyLikely", clientOnlyLikely);
            mod.add("surfaces", new GsonBuilder().create().toJsonTree(orderedSurfaces));
            mods.add(mod);
            counts[0]++;
            if (clientOnlyLikely) counts[1]++;
            counts[2] += orderedSurfaces.size();
            for (ClassSurface surface : orderedSurfaces) {
                counts[3] += surface.keybinds.size();
                counts[4] += surface.actions.size();
                if (!surface.blockers.isEmpty()) counts[5]++;
            }
        }
        for (ModScanner.Profile nested : profile.nested) appendProfile(mods, nested, counts, seenProfiles);
    }
}
