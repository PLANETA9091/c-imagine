package dev.f2v.vm.discovery;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns raw client-surface discovery into an explicit compatibility contract.
 *
 * <p>This class is deliberately static/declarative: it never loads a mod class. A packet-backed
 * action is considered native-bridgeable only when discovery also found its server handler. Key
 * actions are stricter because {@code ClientInputActionBridge} must reconstruct the packet itself;
 * their constructor/factory shape therefore has to be scanner-proven too.</p>
 */
public final class ClientActionCompatibility {

    public record Verdict(ClientSurfaceScanner.CompatibilityState state, String reason) {
        public boolean supported() {
            return state != ClientSurfaceScanner.CompatibilityState.UNSUPPORTED;
        }
    }

    private ClientActionCompatibility() {}

    public static Verdict action(Collection<ModScanner.Profile> profiles,
                                 ClientSurfaceScanner.Action action) {
        if (action == null || action.type == null) return unsupported("missing_action_type");
        return switch (action.type) {
            case CLOSE_SCREEN -> vanilla("server_hud_close");
            case NAVIGATE_BACK -> vanilla("translated_screen_history");
            case OPEN_SCREEN -> vanillaCloseTarget(action.target)
                    ? vanilla("close_to_vanilla_parent")
                    : confirmationTarget(action.target)
                    ? unsupported("confirmation_callback_not_translated")
                    : knownScreen(profiles, action.target)
                    ? vanilla("server_reconstructed_screen")
                    : unsupported("target_screen_not_discovered");
            case OPEN_URL -> externalUrlTarget(action.target)
                    ? vanilla("vanilla_external_link")
                    : unsupported("external_url_invalid");
            case WRITE_STATE -> stateWriteAction(profiles, action);
            case TOGGLE_COLLECTION -> collectionAction(profiles, action);
            case INVOKE_CLIENT_HELPER -> action.visualOnlyHelper
                    ? visualOnly("render_only_client_helper")
                    : unsupported("client_helper_effect_not_translated");
            case EXTERNAL_NETWORK_IO -> visualOnly("client_network_service_omitted");
            case LAUNCH_PROCESS -> visualOnly("client_process_service_omitted");
            case LOAD_NATIVE_LIBRARY -> visualOnly("client_native_service_omitted");
            case SEND_PACKET -> packetAction(profiles, action);
        };
    }

    /** Client-machine-only effects are never executed by F2V. They are represented as an explicit
     * safe omission fallback: runtime can keep the reconstructed screen/map/gameplay state while
     * telling the player that the remote/native helper itself was skipped. */
    public static boolean clientServiceAction(ClientSurfaceScanner.Action action) {
        if (action == null || action.type == null) return false;
        return action.type == ClientSurfaceScanner.ActionType.EXTERNAL_NETWORK_IO
                || action.type == ClientSurfaceScanner.ActionType.LAUNCH_PROCESS
                || action.type == ClientSurfaceScanner.ActionType.LOAD_NATIVE_LIBRARY;
    }

    public static Verdict surface(Collection<ModScanner.Profile> profiles,
                                  ClientSurfaceScanner.ClassSurface surface) {
        if (surface == null) return unsupported("missing_surface");
        if (!surface.scanComplete || surface.scanIssues.stream().anyMatch(issue ->
                issue != null && issue.type == ClientSurfaceScanner.ScanIssueType.SCAN_TRUNCATED)) {
            return unsupported("SCAN_TRUNCATED");
        }
        if (surface.translations.contains(ClientSurfaceScanner.Translation.UNSUPPORTED)) {
            return unsupported(surface.blockers.isEmpty() ? "unsupported_translation"
                    : String.join(",", surface.blockers));
        }

        if (surface.actions.isEmpty()) {
            boolean interactive = surface.kinds.contains(ClientSurfaceScanner.Kind.KEYBIND)
                    || surface.kinds.contains(ClientSurfaceScanner.Kind.INPUT)
                    || surface.kinds.contains(ClientSurfaceScanner.Kind.NETWORK);
            if (interactive) return unsupported("interactive_surface_without_action");
            return new Verdict(ClientSurfaceScanner.CompatibilityState.VISUAL_ONLY,
                    "non_interactive_client_surface");
        }

        List<String> unsupported = new ArrayList<>();
        boolean vanilla = false;
        for (ClientSurfaceScanner.Action action : surface.actions) {
            Verdict actionVerdict = action(profiles, action);
            if (!actionVerdict.supported()) unsupported.add(actionVerdict.reason());
            vanilla |= actionVerdict.state() == ClientSurfaceScanner.CompatibilityState.VANILLA_REPRESENTATION;
        }
        if (!unsupported.isEmpty()) {
            return unsupported("actions:" + String.join(",", new LinkedHashSet<>(unsupported)));
        }
        if (vanilla || surface.kinds.contains(ClientSurfaceScanner.Kind.SCREEN)
                || surface.kinds.contains(ClientSurfaceScanner.Kind.HUD)
                || surface.kinds.contains(ClientSurfaceScanner.Kind.OVERLAY)
                || surface.kinds.contains(ClientSurfaceScanner.Kind.CONFIG)
                || surface.kinds.contains(ClientSurfaceScanner.Kind.MAP)) {
            return vanilla("server_reconstructed_client_surface");
        }
        return nativeBridge("all_discovered_actions_replayable");
    }

    /** Compatibility of one concrete user interaction (one key press, widget click, or screen
     * close). A callback can expose several bytecode edges -- for example local state writes plus
     * navigation -- but those edges are not independent user actions. The interaction is supported
     * only when every required edge is supported. */
    public static Verdict interaction(Collection<ModScanner.Profile> profiles,
                                      Collection<ClientSurfaceScanner.Action> actions) {
        if (actions == null || actions.isEmpty()) return unsupported("interaction_without_action");
        LinkedHashSet<String> unsupported = new LinkedHashSet<>();
        boolean vanilla = false;
        for (ClientSurfaceScanner.Action action : actions) {
            Verdict verdict = action(profiles, action);
            if (!verdict.supported()
                    && action != null
                    && action.type == ClientSurfaceScanner.ActionType.SEND_PACKET
                    && action.triggerType == ClientSurfaceScanner.TriggerType.WIDGET
                    && "widget_packet_constructor_not_replayable".equals(verdict.reason())
                    && packetReplayableWithVirtualScreenState(profiles, action, actions)) {
                verdict = nativeBridge("virtual_screen_state_packet");
            }
            if (!verdict.supported()) unsupported.add(verdict.reason());
            vanilla |= verdict.state() == ClientSurfaceScanner.CompatibilityState.VANILLA_REPRESENTATION;
        }
        if (!unsupported.isEmpty()) {
            return unsupported("edges:" + String.join(",", unsupported));
        }
        boolean textInput = actions.stream().anyMatch(ClientActionCompatibility::virtualTextInputState);
        if (textInput && actions.stream().anyMatch(action ->
                !textInputCompanion(profiles, actions, action))) {
            // Opening SignInput defers the value until the player confirms it. Do not claim a
            // packet/navigation/config edge was executed atomically if it depends on that future
            // value; only input-state plus visual-only bookkeeping is currently representable.
            return unsupported("text_input_dependent_effect_not_translated");
        }
        return vanilla ? vanilla("translated_interaction")
                : nativeBridge("all_interaction_edges_replayable");
    }

    private static boolean textInputCompanion(Collection<ModScanner.Profile> profiles,
                                              Collection<ClientSurfaceScanner.Action> interaction,
                                              ClientSurfaceScanner.Action action) {
        if (action == null) return false;
        if (virtualTextInputState(action)) return true;
        if (action.type == ClientSurfaceScanner.ActionType.SEND_PACKET) {
            return packetReplayableWithVirtualScreenState(profiles, action, interaction);
        }
        if (action.type == ClientSurfaceScanner.ActionType.WRITE_STATE) {
            return action.visualOnlyState || ClientSurfaceScanner.widgetRuntimeState(action) != null
                    || virtualScreenScalarState(action) || virtualReceiverScalarState(action)
                    || virtualWidgetScalarState(action);
        }
        return action.type == ClientSurfaceScanner.ActionType.INVOKE_CLIENT_HELPER
                && action.visualOnlyHelper;
    }

    private static Verdict packetAction(Collection<ModScanner.Profile> profiles,
                                         ClientSurfaceScanner.Action action) {
        if (action.target == null || action.target.isBlank()) return unsupported("packet_target_missing");
        if (!BehaviorBytecodeScanner.hasServerHandler(profiles, action.target)) {
            return unsupported("server_handler_not_discovered");
        }
        if (action.triggerType == ClientSurfaceScanner.TriggerType.KEYBIND
                && !standalonePacketReplayable(profiles, action)) {
            return unsupported("key_packet_constructor_not_replayable");
        }
        if (action.triggerType == ClientSurfaceScanner.TriggerType.SCREEN_CLOSE
                && !standalonePacketReplayable(profiles, action)
                && !packetReplayableWithVirtualScreenState(profiles, action, List.of(action))) {
            return unsupported("screen_close_packet_constructor_not_replayable");
        }
        if (action.triggerType == ClientSurfaceScanner.TriggerType.WIDGET
                && screenTargetTypes(profiles, action.sourceScreen).isEmpty()
                && !standalonePacketReplayable(profiles, action)) {
            // Targetless captured screens cannot fall back to ServerMutationBridge's live
            // BlockEntity/Entity replay. Their payload must therefore be reconstructable from
            // scanner-proven constructor/factory arguments just like a key or close action.
            return unsupported("widget_packet_constructor_not_replayable");
        }
        return nativeBridge("scanner_proven_server_packet_handler");
    }

    public static boolean standalonePacketReplayable(Collection<ModScanner.Profile> profiles,
                                                     ClientSurfaceScanner.Action action) {
        if (singletonFactory(action)) return true;
        String descriptor = action.constructorDesc;
        List<BehaviorBytecodeScanner.PacketRecipe> recipes =
                BehaviorBytecodeScanner.packetRecipesForTargets(profiles, List.of(action.target));
        if (descriptor == null && supportsConstructor(recipes, action.target, "()V")) {
            descriptor = "()V";
        }
        if (descriptor == null) return false;

        boolean constructorKnown = supportsConstructor(recipes, action.target, descriptor)
                || ServerMutationScanner.packetHasConstructor(profiles, action.target, descriptor);
        if (!constructorKnown) return false;
        if (packetArgumentsReplayable(action, descriptor)) return true;
        if ("()V".equals(descriptor)) return true;
        if ("(Z)V".equals(descriptor)) return action.booleanArgument != null;
        if ("(Ljava/lang/String;)V".equals(descriptor)) return action.stringArgument != null;
        if (playerConstructor(descriptor)) return true;
        if (action.staticFieldArgumentIndex == 0
                && action.staticFieldArgumentOwner != null
                && action.staticFieldArgumentName != null
                && action.staticFieldArgumentDesc != null
                && action.booleanArgument != null
                && ("(" + action.staticFieldArgumentDesc + "Z)V").equals(descriptor)) return true;
        return action.staticFieldArgumentIndex == 0
                && action.staticFieldArgumentOwner != null
                && action.staticFieldArgumentName != null
                && action.staticFieldArgumentDesc != null
                && ("(" + action.staticFieldArgumentDesc + ")V").equals(descriptor);
    }

    /** True only when every constructor parameter has an exact scanner-proven constant/static
     * source. Index and JVM descriptor must match the concrete constructor one-for-one. */
    public static boolean packetArgumentsReplayable(ClientSurfaceScanner.Action action,
                                                    String descriptor) {
        if (action == null || descriptor == null || action.packetArguments == null
                || action.packetArguments.isEmpty()) return false;
        org.objectweb.asm.Type[] expected;
        try {
            expected = org.objectweb.asm.Type.getArgumentTypes(descriptor);
        } catch (IllegalArgumentException ignored) {
            return false;
        }
        if (expected.length == 0 || action.packetArguments.size() != expected.length) return false;
        boolean[] seen = new boolean[expected.length];
        for (ClientSurfaceScanner.PacketArgument argument : action.packetArguments) {
            if (argument == null || argument.kind == null || argument.index < 0
                    || argument.index >= expected.length || seen[argument.index]
                    || !expected[argument.index].getDescriptor().equals(argument.descriptor)) return false;
            seen[argument.index] = true;
            if (!packetArgumentReplayable(argument)) return false;
        }
        for (boolean present : seen) if (!present) return false;
        return true;
    }

    private static boolean packetArgumentReplayable(ClientSurfaceScanner.PacketArgument argument) {
        if (argument == null || argument.kind == null || argument.descriptor == null) return false;
        return switch (argument.kind) {
            case BOOLEAN_LITERAL -> argument.booleanValue != null && "Z".equals(argument.descriptor);
            case INT_LITERAL -> argument.intValue != null && integralDescriptor(argument.descriptor);
            case LONG_LITERAL -> argument.longValue != null && "J".equals(argument.descriptor);
            case FLOAT_LITERAL -> argument.doubleValue != null && "F".equals(argument.descriptor);
            case DOUBLE_LITERAL -> argument.doubleValue != null && "D".equals(argument.descriptor);
            case STRING_LITERAL -> argument.stringValue != null
                    && "Ljava/lang/String;".equals(argument.descriptor);
            case UUID_LITERAL -> argument.uuidValue != null
                    && "Ljava/util/UUID;".equals(argument.descriptor)
                    && validUuid(argument.uuidValue);
            case STATIC_FIELD -> argument.fieldOwner != null && argument.fieldName != null
                    && argument.fieldDesc != null && argument.fieldDesc.equals(argument.descriptor)
                    && !clientOnlyOwner(argument.fieldOwner);
            case SERVER_PLAYER -> "Lnet/minecraft/world/entity/player/Player;".equals(argument.descriptor)
                    || "Lnet/minecraft/server/level/ServerPlayer;".equals(argument.descriptor);
            case SERVER_PLAYER_ENTITY_ID -> "I".equals(argument.descriptor);
            case SERVER_PLAYER_UUID -> "Ljava/util/UUID;".equals(argument.descriptor);
            case MENU_BLOCK_POS -> "Lnet/minecraft/core/BlockPos;".equals(argument.descriptor)
                    && argument.menuOwner != null && argument.menuMemberName != null
                    && argument.menuMemberDesc != null && !clientOnlyOwner(argument.menuOwner);
            case MENU_ENTITY_UUID -> "Ljava/util/UUID;".equals(argument.descriptor)
                    && argument.menuOwner != null && argument.menuMemberName != null
                    && argument.menuMemberDesc != null && argument.menuMemberMethod
                    && !clientOnlyOwner(argument.menuOwner);
            case SCREEN_FIELD, SCREEN_FIELD_GETTER -> false;
        };
    }

    private static boolean validUuid(String value) {
        if (value == null) return false;
        try {
            java.util.UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    /** A targetless translated widget may reconstruct a packet from F2V-owned virtual screen
     * scalars only when every SCREEN_FIELD constructor argument is backed by an exact WRITE_STATE
     * edge from that same widget callback. This is an interaction-level proof: the packet remains
     * non-replayable in isolation, so stale/cached state from another control cannot authorize it. */
    public static boolean packetReplayableWithVirtualScreenState(
            Collection<ModScanner.Profile> profiles, ClientSurfaceScanner.Action packet,
            Collection<ClientSurfaceScanner.Action> interaction) {
        if (packet == null || interaction == null
                || packet.type != ClientSurfaceScanner.ActionType.SEND_PACKET
                || (packet.triggerType != ClientSurfaceScanner.TriggerType.WIDGET
                && packet.triggerType != ClientSurfaceScanner.TriggerType.SCREEN_CLOSE)
                || packet.target == null || packet.target.isBlank()
                || packet.constructorDesc == null || packet.packetArguments == null
                || packet.packetArguments.isEmpty()
                || !BehaviorBytecodeScanner.hasServerHandler(profiles, packet.target)) return false;
        org.objectweb.asm.Type[] expected;
        try {
            expected = org.objectweb.asm.Type.getArgumentTypes(packet.constructorDesc);
        } catch (IllegalArgumentException ignored) {
            return false;
        }
        if (expected.length == 0 || packet.packetArguments.size() != expected.length) return false;
        boolean constructorKnown = supportsConstructor(
                BehaviorBytecodeScanner.packetRecipesForTargets(profiles, List.of(packet.target)),
                packet.target, packet.constructorDesc)
                || ServerMutationScanner.packetHasConstructor(profiles, packet.target, packet.constructorDesc);
        if (!constructorKnown) return false;

        boolean[] seen = new boolean[expected.length];
        boolean sawScreenField = false;
        for (ClientSurfaceScanner.PacketArgument argument : packet.packetArguments) {
            if (argument == null || argument.kind == null || argument.index < 0
                    || argument.index >= expected.length || seen[argument.index]
                    || !expected[argument.index].getDescriptor().equals(argument.descriptor)) return false;
            seen[argument.index] = true;
            if (argument.kind != ClientSurfaceScanner.PacketArgumentKind.SCREEN_FIELD) {
                if (!packetArgumentReplayable(argument)) return false;
                continue;
            }
            sawScreenField = true;
            if (!virtualPacketScalarDescriptor(argument.descriptor)
                    || argument.fieldOwner == null || argument.fieldName == null
                    || argument.fieldDesc == null || !argument.descriptor.equals(argument.fieldDesc)) {
                return false;
            }
            String fieldTarget = argument.fieldOwner + "." + argument.fieldName + ':' + argument.fieldDesc;
            boolean backed = false;
            for (ClientSurfaceScanner.Action state : interaction) {
                if (!sameWidgetInteraction(packet, state)
                        || state.type != ClientSurfaceScanner.ActionType.WRITE_STATE
                        || !fieldTarget.equals(state.target)
                        || (!virtualScreenScalarState(state) && !virtualTextInputState(state))
                        || state.stateWriteKind == ClientSurfaceScanner.StateWriteKind.ASSIGN_STATIC_FIELD) {
                    continue;
                }
                backed = true;
                break;
            }
            if (!backed && persistentVirtualScreenFieldInitial(
                    profiles, packet.sourceScreen, fieldTarget) != null) {
                backed = true;
            }
            if (!backed) return false;
        }
        for (boolean present : seen) if (!present) return false;
        return sawScreenField;
    }

    /**
     * Returns the exact initial value for a screen-local boolean/int field that F2V can keep
     * authoritative for the lifetime of one translated screen. Every scanner-visible write to the
     * field must itself be a translated widget scalar mutation and all writes must carry the same
     * proven constructor/JVM initial value. Any dynamic/key/close/helper write rejects the proof.
     */
    public static Object persistentVirtualScreenFieldInitial(Collection<ModScanner.Profile> profiles,
                                                             String sourceScreen,
                                                             String fieldTarget) {
        if (profiles == null || sourceScreen == null || fieldTarget == null) return null;
        List<ClientSurfaceScanner.Action> writes = new ArrayList<>();
        for (ModScanner.Profile profile : profiles) {
            collectStateWrites(profile, sourceScreen, fieldTarget, writes);
        }
        if (writes.isEmpty()) return null;

        Object initial = null;
        boolean sawInitial = false;
        for (ClientSurfaceScanner.Action write : writes) {
            if (write == null || write.type != ClientSurfaceScanner.ActionType.WRITE_STATE
                    || write.triggerType != ClientSurfaceScanner.TriggerType.WIDGET
                    || !virtualScreenScalarState(write)) return null;
            Object candidate = switch (write.stateWriteKind) {
                case ASSIGN_BOOLEAN, TOGGLE_BOOLEAN -> write.stateInitialBoolean;
                case ASSIGN_INT, ADD_INT, WRAP_ADD_INT -> write.stateInitialInt;
                default -> null;
            };
            if (candidate == null) return null;
            if (!sawInitial) {
                initial = candidate;
                sawInitial = true;
            } else if (!java.util.Objects.equals(initial, candidate)) {
                return null;
            }
        }
        return sawInitial ? initial : null;
    }

    private static void collectStateWrites(ModScanner.Profile profile, String sourceScreen,
                                           String fieldTarget,
                                           List<ClientSurfaceScanner.Action> out) {
        if (profile == null || out == null) return;
        if (profile.clientSurfaces != null) {
            for (ClientSurfaceScanner.ClassSurface surface : profile.clientSurfaces) {
                if (surface == null || surface.actions == null) continue;
                for (ClientSurfaceScanner.Action action : surface.actions) {
                    if (action == null || action.type != ClientSurfaceScanner.ActionType.WRITE_STATE
                            || !sourceScreen.equals(action.sourceScreen)
                            || !fieldTarget.equals(action.target)) continue;
                    out.add(action);
                }
            }
        }
        if (profile.nested != null) {
            for (ModScanner.Profile child : profile.nested) {
                collectStateWrites(child, sourceScreen, fieldTarget, out);
            }
        }
    }

    private static boolean sameWidgetInteraction(ClientSurfaceScanner.Action first,
                                                 ClientSurfaceScanner.Action second) {
        return first != null && second != null
                && second.triggerType == ClientSurfaceScanner.TriggerType.WIDGET
                && first.widgetOrdinal == second.widgetOrdinal
                && java.util.Objects.equals(first.sourceScreen, second.sourceScreen)
                && java.util.Objects.equals(first.widgetClass, second.widgetClass)
                && java.util.Objects.equals(first.method, second.method);
    }

    private static boolean virtualPacketScalarDescriptor(String descriptor) {
        return integralDescriptor(descriptor) || "Z".equals(descriptor) || "J".equals(descriptor)
                || "F".equals(descriptor) || "D".equals(descriptor)
                || "Ljava/lang/String;".equals(descriptor);
    }

    private static boolean clientOnlyOwner(String owner) {
        if (owner == null) return true;
        String normalized = owner.replace('.', '/').toLowerCase(java.util.Locale.ROOT);
        return normalized.startsWith("net/minecraft/client/") || normalized.contains("/client/");
    }

    /** A client key handler that constructs its packet from the local Player can be replayed with
     * the authoritative ServerPlayer representing that same connection. Keep this intentionally
     * narrow: Entity/LivingEntity and arbitrary supertypes could mean a looked-at or ridden entity
     * rather than the local player. */
    public static boolean playerConstructor(String descriptor) {
        return "(Lnet/minecraft/world/entity/player/Player;)V".equals(descriptor)
                || "(Lnet/minecraft/server/level/ServerPlayer;)V".equals(descriptor);
    }

    private static boolean supportsConstructor(List<BehaviorBytecodeScanner.PacketRecipe> recipes,
                                               String packetClass, String descriptor) {
        if (recipes == null || packetClass == null || descriptor == null) return false;
        String wanted = packetClass.replace('.', '/');
        return recipes.stream().anyMatch(recipe -> recipe != null && recipe.packetClass() != null
                && wanted.equals(recipe.packetClass().replace('.', '/'))
                && descriptor.equals(recipe.constructorDesc()));
    }

    private static boolean singletonFactory(ClientSurfaceScanner.Action action) {
        if (action == null || action.target == null || action.producerOwner == null
                || action.producerMethod == null || action.producerDesc == null
                || "<init>".equals(action.producerMethod)) return false;
        String target = action.target.replace('.', '/');
        return target.equals(action.producerOwner.replace('.', '/'))
                && ("()L" + target + ";").equals(action.producerDesc);
    }

    private static boolean validStateTarget(String target) {
        if (target == null || target.isBlank()) return false;
        int colon = target.lastIndexOf(':');
        int dot = colon < 0 ? -1 : target.lastIndexOf('.', colon);
        return dot > 0 && colon > dot + 1 && colon + 1 < target.length();
    }

    private static Verdict stateWriteAction(Collection<ModScanner.Profile> profiles,
                                            ClientSurfaceScanner.Action action) {
        if (action == null || !validStateTarget(action.target)) {
            return unsupported("state_write_target_unresolved");
        }
        if (action.visualOnlyState) {
            return visualOnly("render_only_client_state");
        }
        // EntityClientScreenMenu has the richer scanner-config editor (boolean and numeric fields).
        // The generic captured block-screen path deliberately starts with exact boolean toggles;
        // arbitrary numeric deltas cannot be inferred from a lone widget without the entity editor's
        // paired-control geometry contract.
        if (entityTargetForScreen(profiles, action.sourceScreen)) {
            return nativeBridge("scanner_proven_entity_state_write");
        }
        if (action.stateWriteKind == null) return unsupported("state_write_value_not_recovered");
        if (virtualTextInputState(action)) {
            return vanilla("virtual_text_input_state");
        }
        if (ClientSurfaceScanner.widgetRuntimeState(action) != null) {
            return vanilla("virtual_widget_framework_state");
        }
        if (virtualScreenScalarState(action) || virtualReceiverScalarState(action)
                || virtualWidgetScalarState(action)) {
            return vanilla("virtual_client_scalar_state");
        }
        if (blockTargetForScreen(profiles, action.sourceScreen)
                && executableBlockBooleanState(action)) {
            return nativeBridge("scanner_proven_block_boolean_state_write");
        }
        return unsupported("state_write_runtime_target_unresolved");
    }

    /** Exact source-screen-local constant scalar mutation that the compatibility HUD can retain
     * as virtual client state without loading the original Screen class. Relative operations are
     * intentionally excluded until an initial value is scanner-proven. */
    public static boolean virtualScreenScalarState(ClientSurfaceScanner.Action action) {
        if (action == null || action.triggerType != ClientSurfaceScanner.TriggerType.WIDGET
                || action.sourceScreen == null || action.target == null || action.stateWriteKind == null) {
            return false;
        }
        int colon = action.target.lastIndexOf(':');
        int dot = colon < 0 ? -1 : action.target.lastIndexOf('.', colon);
        if (dot <= 0 || colon <= dot + 1) return false;
        String owner = action.target.substring(0, dot).replace('.', '/');
        if (!owner.equals(action.sourceScreen.replace('.', '/'))) return false;
        String desc = action.target.substring(colon + 1);
        return switch (action.stateWriteKind) {
            case ASSIGN_BOOLEAN -> "Z".equals(desc) && action.stateBooleanValue != null;
            case ASSIGN_INT -> integralDescriptor(desc) && action.stateIntValue != null;
            case TOGGLE_BOOLEAN -> "Z".equals(desc) && action.stateInitialBoolean != null;
            case ADD_INT -> "I".equals(desc) && action.stateInitialInt != null
                    && action.stateIntValue != null;
            case WRAP_ADD_INT -> "I".equals(desc) && action.stateInitialInt != null
                    && action.stateIntValue != null && action.stateIntModulus != null
                    && action.stateIntModulus > 0;
            case ASSIGN_LONG -> "J".equals(desc) && action.stateLongValue != null;
            case ASSIGN_FLOAT -> "F".equals(desc) && action.stateDoubleValue != null;
            case ASSIGN_DOUBLE -> "D".equals(desc) && action.stateDoubleValue != null;
            case ASSIGN_STRING -> objectDescriptor(desc) && action.stateStringValue != null;
            case ASSIGN_INPUT_STRING -> false;
            case ASSIGN_STATIC_FIELD -> objectDescriptor(desc)
                    && action.stateStaticFieldOwner != null && action.stateStaticFieldName != null
                    && action.stateStaticFieldDesc != null;
        };
    }

    /**
     * Structural shape of a wrapped screen-local integer whose modulus is another exact
     * screen-local integer. This is intentionally <em>not</em> enough for static compatibility:
     * the bound value must still be present in the concrete HUD's authoritative virtual state at
     * runtime. Keeping that distinction prevents a cached discovery profile from claiming a
     * dynamic pager is replayable merely because its bytecode expression was understood.
     */
    public static boolean virtualDynamicWrappedIntState(ClientSurfaceScanner.Action action) {
        if (action == null || action.triggerType != ClientSurfaceScanner.TriggerType.WIDGET
                || action.sourceScreen == null || action.target == null
                || action.stateWriteKind != ClientSurfaceScanner.StateWriteKind.WRAP_ADD_INT
                || action.stateInitialInt == null || action.stateIntValue == null
                || action.stateIntModulus != null || action.stateIntModulusTarget == null
                || action.stateIntModulusTarget.equals(action.target)
                || action.stateIntModulusMinimum != null && action.stateIntModulusMinimum <= 0) {
            return false;
        }
        ParsedVirtualField target = parsedVirtualField(action.target);
        ParsedVirtualField modulus = parsedVirtualField(action.stateIntModulusTarget);
        if (target == null || modulus == null || !"I".equals(target.desc())
                || !"I".equals(modulus.desc())) return false;
        String screen = action.sourceScreen.replace('.', '/');
        return screen.equals(target.owner()) && screen.equals(modulus.owner());
    }

    /**
     * Structural shape of a helper-owned cyclic integer whose modulus is the cardinality of an
     * authoritative object held by the source Screen. Like {@link #virtualDynamicWrappedIntState},
     * this is not a static compatibility success: runtime must still join a concrete opening
     * payload identity to earlier clientbound construction facts and supply the exact bound.
     */
    public static boolean virtualHistoricalWrappedIntState(ClientSurfaceScanner.Action action) {
        if (action == null || action.triggerType != ClientSurfaceScanner.TriggerType.WIDGET
                || action.sourceScreen == null || action.target == null
                || action.stateWriteKind != ClientSurfaceScanner.StateWriteKind.WRAP_ADD_INT
                || action.stateInitialInt == null || action.stateIntValue == null
                || action.stateIntModulus != null || action.stateIntModulusTarget == null
                || action.stateIntModulusScreenObjectFieldTarget == null
                || action.stateIntModulusMinimum != null && action.stateIntModulusMinimum <= 0) {
            return false;
        }
        ParsedVirtualField target = parsedVirtualField(action.target);
        ParsedVirtualField collection = parsedVirtualField(action.stateIntModulusTarget);
        ParsedVirtualField screenObject = parsedVirtualField(action.stateIntModulusScreenObjectFieldTarget);
        if (target == null || collection == null || screenObject == null
                || !"I".equals(target.desc()) || !objectDescriptor(collection.desc())
                || !objectDescriptor(screenObject.desc())) return false;
        String screen = action.sourceScreen.replace('.', '/');
        if (!screen.equals(screenObject.owner())) return false;
        String objectType = objectInternalName(screenObject.desc());
        if (objectType == null || !objectType.equals(collection.owner())) return false;
        boolean screenLocal = screen.equals(target.owner());
        boolean screenHeldHelper = action.stateReceiverOwnedByScreen
                && action.stateReceiverFieldOwner != null && action.stateReceiverFieldName != null;
        return screenLocal || screenHeldHelper;
    }

    private record ParsedVirtualField(String owner, String name, String desc) {}

    private static ParsedVirtualField parsedVirtualField(String target) {
        if (target == null) return null;
        int colon = target.lastIndexOf(':');
        int dot = colon < 0 ? -1 : target.lastIndexOf('.', colon);
        if (dot <= 0 || colon <= dot + 1 || colon + 1 >= target.length()) return null;
        return new ParsedVirtualField(target.substring(0, dot).replace('.', '/'),
                target.substring(dot + 1, colon), target.substring(colon + 1));
    }

    private static String objectInternalName(String descriptor) {
        return descriptor != null && descriptor.length() > 2 && descriptor.charAt(0) == 'L'
                && descriptor.charAt(descriptor.length() - 1) == ';'
                ? descriptor.substring(1, descriptor.length() - 1) : null;
    }

    /** Exact constant scalar state on a helper object held by the source screen. This is client
     * state, not reflective execution: F2V stores the value under the proven receiver-field path. */
    public static boolean virtualReceiverScalarState(ClientSurfaceScanner.Action action) {
        if (action == null || action.triggerType != ClientSurfaceScanner.TriggerType.WIDGET
                || action.sourceScreen == null || action.target == null || action.stateWriteKind == null
                || action.stateReceiverFieldOwner == null || action.stateReceiverFieldName == null) return false;
        if (!action.stateReceiverOwnedByScreen
                && !action.sourceScreen.replace('.', '/').equals(
                action.stateReceiverFieldOwner.replace('.', '/'))) {
            return false;
        }
        return switch (action.stateWriteKind) {
            case ASSIGN_BOOLEAN -> action.stateBooleanValue != null;
            case ASSIGN_INT -> action.stateIntValue != null;
            case TOGGLE_BOOLEAN -> action.stateInitialBoolean != null;
            case ADD_INT -> action.stateInitialInt != null && action.stateIntValue != null;
            case WRAP_ADD_INT -> action.stateInitialInt != null && action.stateIntValue != null
                    && action.stateIntModulus != null && action.stateIntModulus > 0;
            case ASSIGN_LONG -> action.stateLongValue != null;
            case ASSIGN_FLOAT, ASSIGN_DOUBLE -> action.stateDoubleValue != null;
            case ASSIGN_STRING -> action.stateStringValue != null;
            case ASSIGN_INPUT_STRING -> false;
            case ASSIGN_STATIC_FIELD -> action.stateStaticFieldOwner != null
                    && action.stateStaticFieldName != null && action.stateStaticFieldDesc != null;
        };
    }

    /** Exact client-local state owned by the translated widget itself. The value is retained per
     * screen/widget identity, so repeated controls of the same class never share state. This is
     * deliberately limited to scanner-proven scalar assignments; arbitrary object construction or
     * method effects remain unsupported. */
    public static boolean virtualWidgetScalarState(ClientSurfaceScanner.Action action) {
        if (action == null || action.triggerType != ClientSurfaceScanner.TriggerType.WIDGET
                || action.sourceScreen == null || action.widgetClass == null || action.widgetOrdinal < 0
                || action.target == null || action.stateWriteKind == null) return false;
        int colon = action.target.lastIndexOf(':');
        int dot = colon < 0 ? -1 : action.target.lastIndexOf('.', colon);
        if (dot <= 0 || colon <= dot + 1) return false;
        String owner = action.target.substring(0, dot).replace('.', '/');
        if (!owner.equals(action.widgetClass.replace('.', '/'))) return false;
        String desc = action.target.substring(colon + 1);
        return switch (action.stateWriteKind) {
            case ASSIGN_BOOLEAN -> "Z".equals(desc) && action.stateBooleanValue != null;
            case ASSIGN_INT -> integralDescriptor(desc) && action.stateIntValue != null;
            case ASSIGN_LONG -> "J".equals(desc) && action.stateLongValue != null;
            case ASSIGN_FLOAT -> "F".equals(desc) && action.stateDoubleValue != null;
            case ASSIGN_DOUBLE -> "D".equals(desc) && action.stateDoubleValue != null;
            case ASSIGN_STRING -> objectDescriptor(desc) && action.stateStringValue != null;
            case ASSIGN_INPUT_STRING -> false;
            case ASSIGN_STATIC_FIELD -> objectDescriptor(desc)
                    && action.stateStaticFieldOwner != null && action.stateStaticFieldName != null
                    && action.stateStaticFieldDesc != null;
            case TOGGLE_BOOLEAN -> "Z".equals(desc) && action.stateInitialBoolean != null;
            case ADD_INT -> "I".equals(desc) && action.stateInitialInt != null
                    && action.stateIntValue != null;
            case WRAP_ADD_INT -> "I".equals(desc) && action.stateInitialInt != null
                    && action.stateIntValue != null && action.stateIntModulus != null
                    && action.stateIntModulus > 0;
        };
    }

    /** Scanner-proven single-line text value supplied by the actual input widget callback. The
     * runtime value is collected with vanilla SignInput, so it intentionally has no scan-time
     * stateStringValue. Rich/helper-object text state remains unsupported. */
    public static boolean virtualTextInputState(ClientSurfaceScanner.Action action) {
        if (action == null || action.type != ClientSurfaceScanner.ActionType.WRITE_STATE
                || action.triggerType != ClientSurfaceScanner.TriggerType.WIDGET
                || action.stateWriteKind != ClientSurfaceScanner.StateWriteKind.ASSIGN_INPUT_STRING
                || action.sourceScreen == null || action.target == null) return false;
        int colon = action.target.lastIndexOf(':');
        int dot = colon < 0 ? -1 : action.target.lastIndexOf('.', colon);
        if (dot <= 0 || colon <= dot + 1) return false;
        String owner = action.target.substring(0, dot).replace('.', '/');
        return owner.equals(action.sourceScreen.replace('.', '/'))
                && "Ljava/lang/String;".equals(action.target.substring(colon + 1));
    }

    private static boolean integralDescriptor(String desc) {
        return "I".equals(desc) || "B".equals(desc) || "S".equals(desc) || "C".equals(desc);
    }

    private static boolean objectDescriptor(String desc) {
        return desc != null && (desc.startsWith("L") || desc.startsWith("["));
    }

    private static boolean executableBlockBooleanState(ClientSurfaceScanner.Action action) {
        if (action == null || !"Z".equals(stateDescriptor(action.target))) return false;
        return switch (action.stateWriteKind) {
            case ASSIGN_BOOLEAN -> action.stateBooleanValue != null;
            case TOGGLE_BOOLEAN -> true;
            default -> false;
        };
    }

    private static Verdict collectionAction(Collection<ModScanner.Profile> profiles,
                                             ClientSurfaceScanner.Action action) {
        if (!validCollectionAction(action)) return unsupported("collection_mutation_incomplete");
        // ClientEntityActionMenu expands the scanner registry into concrete per-value toggles. The
        // generic captured-screen path does not yet have enough evidence to choose which registry
        // value one source widget represents, so do not certify those surfaces merely because the
        // accessor itself was discovered.
        return entityTargetForScreen(profiles, action.sourceScreen)
                ? nativeBridge("scanner_proven_entity_collection_mutation")
                : unsupported("collection_mutation_runtime_target_unresolved");
    }

    private static String stateDescriptor(String target) {
        if (target == null) return "";
        int colon = target.lastIndexOf(':');
        return colon < 0 || colon + 1 >= target.length() ? "" : target.substring(colon + 1);
    }

    static Set<String> screenTargetTypes(Collection<ModScanner.Profile> profiles, String sourceScreen) {
        if (profiles == null || sourceScreen == null || sourceScreen.isBlank()) return Set.of();
        String wanted = sourceScreen.replace('.', '/');
        Map<String, LinkedHashSet<String>> targetsByScreen = new LinkedHashMap<>();
        Map<String, LinkedHashSet<String>> incomingScreens = new LinkedHashMap<>();
        Set<String> targetlessEntrypoints = new LinkedHashSet<>();
        for (ModScanner.Profile profile : profiles) {
            collectDirectScreenTargetTypes(profile, targetsByScreen);
            collectScreenNavigation(profile, incomingScreens, targetlessEntrypoints);
        }

        // ClientOnlyHudSimulator preserves the live actionTarget when a scanner-proven widget
        // opens another translated screen. Mirror that runtime contract here so a nested panel is
        // not forced to reconstruct packets standalone merely because only its root screen has the
        // BlockEntity/Entity route. Propagation is deliberately conservative: every discovered
        // widget entry into the child must itself carry a target, and any keybind entry keeps the
        // child ambiguous because key-driven screens are opened without an actionTarget.
        boolean changed;
        int remaining = 64;
        do {
            changed = false;
            for (Map.Entry<String, LinkedHashSet<String>> entry : incomingScreens.entrySet()) {
                String child = entry.getKey();
                if (child == null || child.isBlank() || targetlessEntrypoints.contains(child)) continue;
                LinkedHashSet<String> parents = entry.getValue();
                if (parents == null || parents.isEmpty()) continue;
                boolean allTargeted = true;
                LinkedHashSet<String> inherited = new LinkedHashSet<>();
                for (String parent : parents) {
                    Set<String> parentTargets = targetsByScreen.get(parent);
                    if (parentTargets == null || parentTargets.isEmpty()) {
                        allTargeted = false;
                        break;
                    }
                    inherited.addAll(parentTargets);
                }
                if (!allTargeted || inherited.isEmpty()) continue;
                LinkedHashSet<String> childTargets = targetsByScreen.computeIfAbsent(
                        child, ignored -> new LinkedHashSet<>());
                if (childTargets.addAll(inherited)) changed = true;
            }
        } while (changed && remaining-- > 0);

        Set<String> out = targetsByScreen.get(wanted);
        return out == null || out.isEmpty() ? Set.of() : Set.copyOf(out);
    }

    private static void collectDirectScreenTargetTypes(ModScanner.Profile profile,
                                                       Map<String, LinkedHashSet<String>> out) {
        if (profile == null || out == null) return;
        if (profile.blockUiRoutes != null) {
            for (BlockUiScanner.Route route : profile.blockUiRoutes) {
                if (route == null || route.targetType == null || route.screens == null) continue;
                String target = route.targetType.replace('.', '/');
                for (String screen : route.screens) {
                    if (screen == null || screen.isBlank()) continue;
                    out.computeIfAbsent(screen.replace('.', '/'), ignored -> new LinkedHashSet<>())
                            .add(target);
                }
            }
        }
        if (profile.entityScreenRoutes != null) {
            for (EntityScreenBytecodeScanner.Route route : profile.entityScreenRoutes) {
                if (route == null || route.entityClass == null || route.screenClass == null) continue;
                out.computeIfAbsent(route.screenClass.replace('.', '/'), ignored -> new LinkedHashSet<>())
                        .add(route.entityClass.replace('.', '/'));
            }
        }
        if (profile.clientSurfaces != null) {
            for (ClientSurfaceScanner.ClassSurface surface : profile.clientSurfaces) {
                if (surface == null || surface.clazz == null || surface.clazz.isBlank()) continue;
                // We already have the exact surface in hand. Re-entering the public profile lookup
                // here made target classification quadratic (one complete client-surface walk per
                // screen) on large compatibility batches.
                ClientSurfaceScanner.MenuTarget target = ClientSurfaceScanner.menuTarget(surface);
                if (target == null || target.targetType() == null || target.targetType().isBlank()) continue;
                out.computeIfAbsent(surface.clazz.replace('.', '/'), ignored -> new LinkedHashSet<>())
                        .add(target.targetType().replace('.', '/'));
            }
        }
        if (profile.nested != null) {
            for (ModScanner.Profile child : profile.nested) collectDirectScreenTargetTypes(child, out);
        }
    }

    private static void collectScreenNavigation(ModScanner.Profile profile,
                                                Map<String, LinkedHashSet<String>> incomingScreens,
                                                Set<String> targetlessEntrypoints) {
        if (profile == null) return;
        if (profile.clientSurfaces != null) {
            for (ClientSurfaceScanner.ClassSurface surface : profile.clientSurfaces) {
                if (surface == null || surface.actions == null) continue;
                for (ClientSurfaceScanner.Action action : surface.actions) {
                    if (action == null || action.type != ClientSurfaceScanner.ActionType.OPEN_SCREEN
                            || action.target == null || action.target.isBlank()) continue;
                    String target = action.target.replace('.', '/');
                    if (action.triggerType == ClientSurfaceScanner.TriggerType.KEYBIND) {
                        targetlessEntrypoints.add(target);
                        continue;
                    }
                    if (action.triggerType != ClientSurfaceScanner.TriggerType.WIDGET
                            || action.sourceScreen == null || action.sourceScreen.isBlank()) continue;
                    String source = action.sourceScreen.replace('.', '/');
                    if (source.equals(target)) continue;
                    incomingScreens.computeIfAbsent(target, ignored -> new LinkedHashSet<>()).add(source);
                }
            }
        }
        if (profile.nested != null) {
            for (ModScanner.Profile child : profile.nested) {
                collectScreenNavigation(child, incomingScreens, targetlessEntrypoints);
            }
        }
    }

    private static boolean blockTargetForScreen(Collection<ModScanner.Profile> profiles, String sourceScreen) {
        if (profiles == null || sourceScreen == null || sourceScreen.isBlank()) return false;
        String wanted = sourceScreen.replace('.', '/');
        for (ModScanner.Profile profile : profiles) if (blockTargetForScreen(profile, wanted)) return true;
        return false;
    }

    private static boolean blockTargetForScreen(ModScanner.Profile profile, String wanted) {
        if (profile == null) return false;
        if (profile.blockUiRoutes != null) {
            for (BlockUiScanner.Route route : profile.blockUiRoutes) {
                if (route == null || route.targetType == null || route.screens == null) continue;
                for (String screen : route.screens) {
                    if (screen != null && wanted.equals(screen.replace('.', '/'))) return true;
                }
            }
        }
        if (profile.nested != null) {
            for (ModScanner.Profile child : profile.nested) if (blockTargetForScreen(child, wanted)) return true;
        }
        return false;
    }

    private static boolean entityTargetForScreen(Collection<ModScanner.Profile> profiles, String sourceScreen) {
        if (profiles == null || sourceScreen == null || sourceScreen.isBlank()) return false;
        String wanted = sourceScreen.replace('.', '/');
        for (ModScanner.Profile profile : profiles) if (entityTargetForScreen(profile, wanted)) return true;
        return false;
    }

    private static boolean entityTargetForScreen(ModScanner.Profile profile, String wanted) {
        if (profile == null) return false;
        if (profile.entityScreenRoutes != null) {
            for (EntityScreenBytecodeScanner.Route route : profile.entityScreenRoutes) {
                if (route != null && route.entityClass != null && route.screenClass != null
                        && wanted.equals(route.screenClass.replace('.', '/'))) return true;
            }
        }
        if (profile.nested != null) {
            for (ModScanner.Profile child : profile.nested) if (entityTargetForScreen(child, wanted)) return true;
        }
        return false;
    }

    private static void collectScreenTargetTypes(ModScanner.Profile profile, String wanted,
                                                 Set<String> out) {
        if (profile == null) return;
        if (profile.blockUiRoutes != null) {
            for (BlockUiScanner.Route route : profile.blockUiRoutes) {
                if (route == null || route.targetType == null || route.screens == null) continue;
                for (String screen : route.screens) {
                    if (screen != null && wanted.equals(screen.replace('.', '/'))) {
                        out.add(route.targetType.replace('.', '/'));
                    }
                }
            }
        }
        if (profile.entityScreenRoutes != null) {
            for (EntityScreenBytecodeScanner.Route route : profile.entityScreenRoutes) {
                if (route != null && route.entityClass != null && route.screenClass != null
                        && wanted.equals(route.screenClass.replace('.', '/'))) {
                    out.add(route.entityClass.replace('.', '/'));
                }
            }
        }
        if (profile.nested != null) {
            for (ModScanner.Profile child : profile.nested) collectScreenTargetTypes(child, wanted, out);
        }
    }

    private static boolean validCollectionAction(ClientSurfaceScanner.Action action) {
        return action.collectionAccessorOwner != null && action.collectionAccessorMethod != null
                && action.collectionAccessorDesc != null && action.valueRegistryOwner != null
                && action.valueRegistryField != null && action.valueKeyMethod != null;
    }

    private static boolean knownScreen(Collection<ModScanner.Profile> profiles, String target) {
        if (profiles == null || target == null || target.isBlank()) return false;
        String wanted = target.replace('.', '/');
        for (ModScanner.Profile profile : profiles) if (knownScreen(profile, wanted)) return true;
        return false;
    }

    /** Exact vanilla destinations whose client-side meaning is to leave the current translated
     * surface rather than enter another mod interaction. A bare Screen target is the common
     * parent/back reference; TitleScreen is a terminal client-only destination. On a connected
     * vanilla player F2V represents both by closing its HUD, never by disconnecting the player. */
    public static boolean vanillaCloseTarget(String target) {
        if (target == null) return false;
        String wanted = target.replace('.', '/');
        return "net/minecraft/client/gui/screens/Screen".equals(wanted)
                || "net/minecraft/client/gui/screens/TitleScreen".equals(wanted);
    }

    /** Vanilla ConfirmScreen is a container for a captured BooleanConsumer callback, not a screen
     * that can be reconstructed safely from its class name alone. Keep it distinct from an unknown
     * mod screen so reports and runtime can explain the missing callback translation precisely. */
    public static boolean confirmationTarget(String target) {
        if (target == null || target.isBlank()) return false;
        return "net/minecraft/client/gui/screens/ConfirmScreen".equals(target.replace('.', '/'));
    }

    /** A scanner-proven external link is handed back to the vanilla client as an OPEN_URL chat
     * component. Keep this deliberately narrower than java.net.URL: local/file/custom schemes are
     * never made clickable by compatibility translation. */
    public static boolean externalUrlTarget(String target) {
        if (target == null || target.isBlank() || target.length() > 2048) return false;
        try {
            java.net.URI uri = java.net.URI.create(target);
            String scheme = uri.getScheme();
            return ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                    && uri.getHost() != null && !uri.getHost().isBlank();
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private static boolean knownScreen(ModScanner.Profile profile, String wanted) {
        if (profile == null) return false;
        for (ModScanner.ScreenInfo screen : profile.screens) {
            if (screen != null && screen.screenClass != null
                    && wanted.equals(screen.screenClass.replace('.', '/'))) return true;
        }
        for (ClientSurfaceScanner.ClassSurface surface : profile.clientSurfaces) {
            if (surface != null && surface.clazz != null && wanted.equals(surface.clazz.replace('.', '/'))
                    && surface.kinds.contains(ClientSurfaceScanner.Kind.SCREEN)) return true;
        }
        for (ModScanner.Profile nested : profile.nested) if (knownScreen(nested, wanted)) return true;
        return false;
    }

    private static Verdict nativeBridge(String reason) {
        return new Verdict(ClientSurfaceScanner.CompatibilityState.NATIVE_BRIDGE, reason);
    }

    private static Verdict vanilla(String reason) {
        return new Verdict(ClientSurfaceScanner.CompatibilityState.VANILLA_REPRESENTATION, reason);
    }

    private static Verdict visualOnly(String reason) {
        return new Verdict(ClientSurfaceScanner.CompatibilityState.VISUAL_ONLY, reason);
    }

    private static Verdict unsupported(String reason) {
        return new Verdict(ClientSurfaceScanner.CompatibilityState.UNSUPPORTED, reason);
    }
}
