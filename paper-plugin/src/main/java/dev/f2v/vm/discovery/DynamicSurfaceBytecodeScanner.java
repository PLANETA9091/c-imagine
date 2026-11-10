package dev.f2v.vm.discovery;

import org.objectweb.asm.Type;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Archive-wide bytecode correlation for dynamic client surfaces.
 *
 * <p>Some client screens are not just static widgets: they periodically send a request payload,
 * the server gathers authoritative data, sends a response payload, and the client stores that
 * payload before rendering a graph/map/diagram. Per-class scanning cannot recover this relationship
 * because each edge lives in a different class. This pass joins the already declarative behavior
 * graph without classloading or executing any mod code.</p>
 *
 * <p>A flow is emitted only when all four concrete edges are present:</p>
 * <ol>
 *     <li>a scanner-proven Screen sends a serverbound payload,</li>
 *     <li>that payload has a server packet handler,</li>
 *     <li>a class reached by that handler sends a concrete clientbound payload, and</li>
 *     <li>the response packet handler calls back into the same Screen class.</li>
 * </ol>
 */
public final class DynamicSurfaceBytecodeScanner {

    public static final int SCHEMA = 2;
    private static final int MAX_BRIDGE_DEPTH = 3;
    private static final int MAX_ACCESSORS = 96;

    /** Exact request/response dataflow recovered from bytecode. */
    public record Flow(String screenClass,
                       String requestPacket,
                       String serverBridgeClass,
                       String responsePacket,
                       String clientUpdateMethod,
                       List<String> payloadFields,
                       List<String> dataAccessors,
                       int confidence) {}

    /** One clientbound semantic payload whose client handler provably opens a concrete Screen. */
    public record OpenFlow(String packetClass, String screenClass, int confidence) {}

    /** Exact scalar copied from one clientbound payload backing field into one concrete Screen
     * backing field through constructor/helper parameter forwarding. */
    public record ScreenFieldMirror(String screenFieldTarget,
                                    String payloadFieldOwner, String payloadFieldName,
                                    String payloadFieldDesc, int confidence) {}

    /** One concrete Screen field whose constructor receives an object selected by
     * {@code java.util.Map#get} using an exact field from the opening clientbound payload. */
    public record ScreenObjectLookup(String screenFieldTarget, String objectClass,
                                     String payloadIdentityFieldOwner,
                                     String payloadIdentityFieldName,
                                     String payloadIdentityFieldDesc,
                                     int confidence) {}

    /** One exact payload backing field forwarded unchanged into one constructor argument of a
     * client-side state object from a clientbound packet handler. This is declarative provenance;
     * it does not claim what the constructor does with that argument. */
    public record ConstructedObjectField(String packetClass, String objectClass,
                                         String constructorDesc, int constructorArgument,
                                         String payloadFieldOwner, String payloadFieldName,
                                         String payloadFieldDesc, int confidence) {}

    /** Exact constructor argument that determines the cardinality of one client-state collection
     * field. This is emitted only from BehaviorBytecodeScanner's counted-fill proof. */
    public record CollectionCardinalitySource(String objectClass, String collectionFieldTarget,
                                              String constructorDesc, int constructorArgument,
                                              int confidence) {}

    private DynamicSurfaceBytecodeScanner() {}

    public static List<Flow> discover(Collection<ModScanner.Profile> profiles) {
        if (profiles == null || profiles.isEmpty()) return List.of();

        LinkedHashMap<String, BehaviorBytecodeScanner.BehaviorSurface> behaviors = new LinkedHashMap<>();
        LinkedHashSet<String> screens = new LinkedHashSet<>();
        for (ModScanner.Profile profile : profiles) collect(profile, behaviors, screens);
        if (behaviors.isEmpty() || screens.isEmpty()) return List.of();

        Map<String, List<ClientConsumer>> consumers = responseConsumers(behaviors, screens);
        ArrayList<Flow> result = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();

        for (String screen : screens) {
            BehaviorBytecodeScanner.BehaviorSurface screenBehavior = behaviors.get(screen);
            if (screenBehavior == null) continue;
            for (BehaviorBytecodeScanner.MethodBehavior method : screenBehavior.methods) {
                for (BehaviorBytecodeScanner.CallEdge send : method.calls) {
                    if (send.effect != BehaviorBytecodeScanner.Effect.SEND_SERVER_PACKET
                            || send.target == null) continue;
                    String request = normalize(send.target);
                    BehaviorBytecodeScanner.BehaviorSurface requestBehavior = behaviors.get(request);
                    if (requestBehavior == null || !hasPacketHandler(requestBehavior)) continue;

                    for (BridgeEntry bridgeEntry : reachableBridgeEntries(requestBehavior, behaviors)) {
                        String bridge = bridgeEntry.clazz;
                        BehaviorBytecodeScanner.BehaviorSurface bridgeBehavior = behaviors.get(bridge);
                        if (bridgeBehavior == null) continue;
                        BehaviorBytecodeScanner.MethodBehavior requestBridgeMethod =
                                method(bridgeBehavior, bridgeEntry.methodKey);
                        if (requestBridgeMethod == null) continue;
                        for (BehaviorBytecodeScanner.MethodBehavior bridgeMethod : bridgeBehavior.methods) {
                            for (BehaviorBytecodeScanner.CallEdge responseSend : bridgeMethod.calls) {
                                if (responseSend.effect != BehaviorBytecodeScanner.Effect.SEND_CLIENT_PACKET
                                        || responseSend.target == null) continue;
                                if (!stateCorrelated(bridgeBehavior, requestBridgeMethod, bridgeMethod)) continue;
                                String response = normalize(responseSend.target);
                                List<ClientConsumer> responseConsumers = consumers.get(response);
                                if (responseConsumers == null) continue;
                                for (ClientConsumer consumer : responseConsumers) {
                                    if (!screen.equals(consumer.screenClass)) continue;
                                    String token = screen + '\n' + request + '\n' + bridge + '\n'
                                            + response + '\n' + consumer.updateMethod;
                                    if (!seen.add(token)) continue;
                                    result.add(new Flow(screen, request, bridge, response,
                                            consumer.updateMethod,
                                            payloadFields(behaviors.get(response)),
                                            dataAccessors(bridgeBehavior, bridgeMethod),
                                            Math.min(100, Math.max(92,
                                                    Math.min(send.confidence, responseSend.confidence)))));
                                }
                            }
                        }
                    }
                }
            }
        }

        result.sort(Comparator.comparing(Flow::screenClass)
                .thenComparing(Flow::requestPacket)
                .thenComparing(Flow::responsePacket));
        return List.copyOf(result);
    }

    /** Flows whose client screen is one of the concrete scanner-selected screen classes. */
    public static List<Flow> forScreens(Collection<ModScanner.Profile> profiles,
                                        Collection<String> screenClasses) {
        if (screenClasses == null || screenClasses.isEmpty()) return List.of();
        LinkedHashSet<String> wanted = new LinkedHashSet<>();
        for (String screen : screenClasses) {
            if (screen != null && !screen.isBlank()) wanted.add(normalize(screen));
        }
        if (wanted.isEmpty()) return List.of();
        return discover(profiles).stream().filter(flow -> wanted.contains(normalize(flow.screenClass())))
                .toList();
    }

    /**
     * Finds one-shot clientbound payload -> Screen routes. The proof starts at a scanner-classified
     * PACKET_HANDLE method and follows exact invocation edges until an OPEN_SCREEN effect is
     * reached. This covers direct handlers and small client navigation helpers without relying on
     * packet or screen names.
     */
    public static List<OpenFlow> clientboundScreenOpenings(Collection<ModScanner.Profile> profiles) {
        if (profiles == null || profiles.isEmpty()) return List.of();
        LinkedHashMap<String, BehaviorBytecodeScanner.BehaviorSurface> behaviors = new LinkedHashMap<>();
        LinkedHashSet<String> screens = new LinkedHashSet<>();
        for (ModScanner.Profile profile : profiles) collect(profile, behaviors, screens);
        if (behaviors.isEmpty() || screens.isEmpty()) return List.of();

        LinkedHashMap<String, OpenFlow> found = new LinkedHashMap<>();
        for (BehaviorBytecodeScanner.BehaviorSurface handler : behaviors.values()) {
            if (handler == null || handler.methods == null) continue;
            for (BehaviorBytecodeScanner.MethodBehavior method : handler.methods) {
                if (method == null || method.trigger != BehaviorBytecodeScanner.Trigger.PACKET_HANDLE) continue;
                String packet = clientPacketParameter(method.descriptor);
                if (packet == null || packet.startsWith("net/minecraft/")) continue;
                LinkedHashSet<String> reached = new LinkedHashSet<>();
                collectOpenedScreens(handler.clazz, method, behaviors, screens, reached,
                        new LinkedHashSet<>(), 0);
                for (String screen : reached) {
                    String token = packet + '\n' + screen;
                    found.putIfAbsent(token, new OpenFlow(packet, screen,
                            Math.min(100, Math.max(88, method.confidence))));
                }
            }
        }
        ArrayList<OpenFlow> out = new ArrayList<>(found.values());
        out.sort(Comparator.comparing(OpenFlow::packetClass).thenComparing(OpenFlow::screenClass));
        return List.copyOf(out);
    }

    /** Returns the unique scanner-proven Screen for an exact semantic payload class. */
    public static String clientboundScreenFor(Collection<ModScanner.Profile> profiles,
                                              String packetClass) {
        String wanted = normalize(packetClass);
        if (wanted.isBlank()) return null;
        String found = null;
        for (OpenFlow flow : clientboundScreenOpenings(profiles)) {
            if (!wanted.equals(normalize(flow.packetClass()))) continue;
            String screen = normalize(flow.screenClass());
            if (found != null && !found.equals(screen)) return null;
            found = screen;
        }
        return found;
    }

    /**
     * Correlates one clientbound packet's exact backing fields with concrete Screen constructor
     * fields. No names or type-only matching are used: the packet object starts as a distinguished
     * method-parameter origin, invocation metadata carries unchanged parameters / simple backing
     * getters through helpers, and the final constructor must assign that exact parameter directly
     * to {@code this.field}. Computed/merged values simply produce no mirror.
     */
    public static List<ScreenFieldMirror> clientboundOpeningScalarMirrors(
            Collection<ModScanner.Profile> profiles, String packetClass, String screenClass) {
        if (profiles == null || packetClass == null || screenClass == null) return List.of();
        String wantedPacket = normalize(packetClass);
        String wantedScreen = normalize(screenClass);
        if (wantedPacket.isBlank() || wantedScreen.isBlank()) return List.of();
        LinkedHashMap<String, BehaviorBytecodeScanner.BehaviorSurface> behaviors = new LinkedHashMap<>();
        LinkedHashSet<String> screens = new LinkedHashSet<>();
        for (ModScanner.Profile profile : profiles) collect(profile, behaviors, screens);
        if (!screens.contains(wantedScreen)) return List.of();

        LinkedHashMap<String, ScreenFieldMirror> found = new LinkedHashMap<>();
        for (BehaviorBytecodeScanner.BehaviorSurface handler : behaviors.values()) {
            if (handler == null || handler.methods == null) continue;
            for (BehaviorBytecodeScanner.MethodBehavior method : handler.methods) {
                if (method == null || method.trigger != BehaviorBytecodeScanner.Trigger.PACKET_HANDLE) continue;
                int packetParameter = parameterIndex(method.descriptor, wantedPacket);
                if (packetParameter < 0) continue;
                LinkedHashMap<Integer, ValueOrigin> origins = new LinkedHashMap<>();
                origins.put(packetParameter, ValueOrigin.root(wantedPacket));
                collectScreenFieldMirrors(handler.clazz, method, behaviors, wantedScreen,
                        origins, null, found, new LinkedHashSet<>(), 0,
                        Math.min(100, Math.max(88, method.confidence)));
            }
        }
        ArrayList<ScreenFieldMirror> out = new ArrayList<>(found.values());
        out.sort(Comparator.comparing(ScreenFieldMirror::screenFieldTarget)
                .thenComparing(ScreenFieldMirror::payloadFieldOwner)
                .thenComparing(ScreenFieldMirror::payloadFieldName));
        return List.copyOf(out);
    }

    /** Exact object identity counterpart to {@link #clientboundOpeningScalarMirrors}. This proves
     * only that a Screen field receives the object selected by a Map#get keyed from one opening
     * payload field. Object contents are intentionally not inferred here. */
    public static List<ScreenObjectLookup> clientboundOpeningObjectLookups(
            Collection<ModScanner.Profile> profiles, String packetClass, String screenClass) {
        if (profiles == null || packetClass == null || screenClass == null) return List.of();
        String wantedPacket = normalize(packetClass);
        String wantedScreen = normalize(screenClass);
        if (wantedPacket.isBlank() || wantedScreen.isBlank()) return List.of();
        LinkedHashMap<String, BehaviorBytecodeScanner.BehaviorSurface> behaviors = new LinkedHashMap<>();
        LinkedHashSet<String> screens = new LinkedHashSet<>();
        for (ModScanner.Profile profile : profiles) collect(profile, behaviors, screens);
        if (!screens.contains(wantedScreen)) return List.of();

        LinkedHashMap<String, ScreenObjectLookup> found = new LinkedHashMap<>();
        for (BehaviorBytecodeScanner.BehaviorSurface handler : behaviors.values()) {
            if (handler == null || handler.methods == null) continue;
            for (BehaviorBytecodeScanner.MethodBehavior method : handler.methods) {
                if (method == null || method.trigger != BehaviorBytecodeScanner.Trigger.PACKET_HANDLE) continue;
                int packetParameter = parameterIndex(method.descriptor, wantedPacket);
                if (packetParameter < 0) continue;
                LinkedHashMap<Integer, ValueOrigin> origins = new LinkedHashMap<>();
                origins.put(packetParameter, ValueOrigin.root(wantedPacket));
                collectScreenObjectLookups(handler.clazz, method, behaviors, wantedScreen,
                        origins, null, found, new LinkedHashSet<>(), 0,
                        Math.min(100, Math.max(88, method.confidence)));
            }
        }
        ArrayList<ScreenObjectLookup> out = new ArrayList<>(found.values());
        out.sort(Comparator.comparing(ScreenObjectLookup::screenFieldTarget)
                .thenComparing(ScreenObjectLookup::objectClass)
                .thenComparing(ScreenObjectLookup::payloadIdentityFieldOwner)
                .thenComparing(ScreenObjectLookup::payloadIdentityFieldName));
        return List.copyOf(out);
    }

    /** Exact client-state constructor arguments sourced from one clientbound payload. */
    public static List<ConstructedObjectField> clientboundConstructedObjectFields(
            Collection<ModScanner.Profile> profiles, String packetClass) {
        if (profiles == null || packetClass == null) return List.of();
        String wantedPacket = normalize(packetClass);
        if (wantedPacket.isBlank()) return List.of();
        LinkedHashMap<String, BehaviorBytecodeScanner.BehaviorSurface> behaviors = new LinkedHashMap<>();
        LinkedHashSet<String> screens = new LinkedHashSet<>();
        for (ModScanner.Profile profile : profiles) collect(profile, behaviors, screens);

        LinkedHashMap<String, ConstructedObjectField> found = new LinkedHashMap<>();
        for (BehaviorBytecodeScanner.BehaviorSurface handler : behaviors.values()) {
            if (handler == null || handler.methods == null) continue;
            for (BehaviorBytecodeScanner.MethodBehavior method : handler.methods) {
                if (method == null || method.trigger != BehaviorBytecodeScanner.Trigger.PACKET_HANDLE) continue;
                int packetParameter = parameterIndex(method.descriptor, wantedPacket);
                if (packetParameter < 0) continue;
                LinkedHashMap<Integer, ValueOrigin> origins = new LinkedHashMap<>();
                origins.put(packetParameter, ValueOrigin.root(wantedPacket));
                collectConstructedObjectFields(handler.clazz, method, behaviors,
                        origins, null, found, new LinkedHashSet<>(), 0,
                        Math.min(100, Math.max(88, method.confidence)));
            }
        }
        ArrayList<ConstructedObjectField> out = new ArrayList<>(found.values());
        out.sort(Comparator.comparing(ConstructedObjectField::objectClass)
                .thenComparing(ConstructedObjectField::constructorDesc)
                .thenComparingInt(ConstructedObjectField::constructorArgument)
                .thenComparing(ConstructedObjectField::payloadFieldOwner)
                .thenComparing(ConstructedObjectField::payloadFieldName));
        return List.copyOf(out);
    }

    public static List<CollectionCardinalitySource> collectionCardinalitySources(
            Collection<ModScanner.Profile> profiles, String objectClass, String collectionFieldTarget) {
        if (profiles == null || objectClass == null || collectionFieldTarget == null) return List.of();
        String wantedObject = normalize(objectClass);
        LinkedHashMap<String, BehaviorBytecodeScanner.BehaviorSurface> behaviors = new LinkedHashMap<>();
        LinkedHashSet<String> screens = new LinkedHashSet<>();
        for (ModScanner.Profile profile : profiles) collect(profile, behaviors, screens);
        BehaviorBytecodeScanner.BehaviorSurface behavior = behaviors.get(wantedObject);
        if (behavior == null) return List.of();
        LinkedHashMap<String, CollectionCardinalitySource> found = new LinkedHashMap<>();
        for (BehaviorBytecodeScanner.MethodBehavior method : behavior.methods) {
            if (method == null || method.collectionCardinalities == null) continue;
            for (BehaviorBytecodeScanner.CollectionCardinality cardinality : method.collectionCardinalities) {
                if (cardinality == null || cardinality.fieldOwner == null || cardinality.fieldName == null
                        || cardinality.fieldDesc == null || cardinality.constructorDesc == null
                        || cardinality.constructorArgument < 0) continue;
                String target = cardinality.fieldOwner + "." + cardinality.fieldName + ':'
                        + cardinality.fieldDesc;
                if (!collectionFieldTarget.equals(target)) continue;
                CollectionCardinalitySource source = new CollectionCardinalitySource(wantedObject,
                        target, cardinality.constructorDesc, cardinality.constructorArgument,
                        cardinality.confidence);
                found.putIfAbsent(cardinality.constructorDesc + '\n' + cardinality.constructorArgument, source);
            }
        }
        ArrayList<CollectionCardinalitySource> out = new ArrayList<>(found.values());
        out.sort(Comparator.comparing(CollectionCardinalitySource::constructorDesc)
                .thenComparingInt(CollectionCardinalitySource::constructorArgument));
        return List.copyOf(out);
    }

    private record ValueOrigin(String rootPacket, String fieldOwner,
                               String fieldName, String fieldDesc,
                               String lookupObjectClass, String lookupKeyFieldOwner,
                               String lookupKeyFieldName, String lookupKeyFieldDesc) {
        static ValueOrigin root(String packet) {
            return new ValueOrigin(packet, null, null, null, null, null, null, null);
        }
        static ValueOrigin field(String packet, String owner, String name, String desc) {
            return new ValueOrigin(packet, owner, name, desc, null, null, null, null);
        }
        static ValueOrigin lookup(String packet, String objectClass,
                                  String keyOwner, String keyName, String keyDesc) {
            return new ValueOrigin(packet, null, null, null,
                    objectClass, keyOwner, keyName, keyDesc);
        }
        boolean rootOnly() {
            return fieldOwner == null && fieldName == null && fieldDesc == null
                    && lookupObjectClass == null && lookupKeyFieldOwner == null
                    && lookupKeyFieldName == null && lookupKeyFieldDesc == null;
        }
        boolean fieldValue() { return fieldOwner != null && fieldName != null && fieldDesc != null; }
        boolean lookupValue() {
            return lookupObjectClass != null && lookupKeyFieldOwner != null
                    && lookupKeyFieldName != null && lookupKeyFieldDesc != null;
        }
    }

    private static void collectScreenFieldMirrors(
            String owner, BehaviorBytecodeScanner.MethodBehavior method,
            Map<String, BehaviorBytecodeScanner.BehaviorSurface> behaviors,
            String wantedScreen, Map<Integer, ValueOrigin> parameterOrigins,
            ValueOrigin thisOrigin, Map<String, ScreenFieldMirror> found,
            Set<String> visiting, int depth, int confidence) {
        if (owner == null || method == null || depth > 8) return;
        String visit = normalize(owner) + '#' + method.method + method.descriptor + "@scalar-mirror";
        if (!visiting.add(visit)) return;
        try {
            for (BehaviorBytecodeScanner.InvocationEdge call : method.invocations) {
                if (call == null || call.owner == null || call.method == null || call.descriptor == null) continue;
                LinkedHashMap<Integer, ValueOrigin> callArguments = new LinkedHashMap<>();
                for (BehaviorBytecodeScanner.InvocationArgument argument : call.arguments) {
                    ValueOrigin origin = invocationArgumentOrigin(argument, parameterOrigins, thisOrigin);
                    if (origin != null) callArguments.put(argument.index, origin);
                }
                ValueOrigin callReceiver = call.receiverIsThis ? thisOrigin
                        : call.receiverParameterIndex < 0 ? null
                        : parameterOrigins.get(call.receiverParameterIndex);

                String callOwner = normalize(call.owner);
                if ("<init>".equals(call.method) && wantedScreen.equals(callOwner)) {
                    BehaviorBytecodeScanner.BehaviorSurface screen = behaviors.get(callOwner);
                    BehaviorBytecodeScanner.MethodBehavior constructor = screen == null ? null
                            : method(screen, call.method + call.descriptor);
                    if (constructor == null) continue;
                    for (BehaviorBytecodeScanner.FieldAssignment assignment
                            : constructor.parameterFieldAssignments) {
                        if (assignment == null || assignment.parameterIndex < 0
                                || assignment.fieldOwner == null || assignment.fieldName == null
                                || assignment.fieldDesc == null || !wantedScreen.equals(
                                normalize(assignment.fieldOwner))
                                || !scalarDescriptor(assignment.fieldDesc)) continue;
                        ValueOrigin origin = callArguments.get(assignment.parameterIndex);
                        if (origin == null || !origin.fieldValue()
                                || !assignment.fieldDesc.equals(origin.fieldDesc)) continue;
                        String target = assignment.fieldOwner + "." + assignment.fieldName
                                + ':' + assignment.fieldDesc;
                        ScreenFieldMirror mirror = new ScreenFieldMirror(target, origin.fieldOwner,
                                origin.fieldName, origin.fieldDesc, confidence);
                        String token = target + '\n' + origin.fieldOwner + '.' + origin.fieldName
                                + ':' + origin.fieldDesc;
                        found.putIfAbsent(token, mirror);
                    }
                    continue;
                }

                BehaviorBytecodeScanner.BehaviorSurface nested = behaviors.get(callOwner);
                if (nested == null || "<init>".equals(call.method)) continue;
                BehaviorBytecodeScanner.MethodBehavior nestedMethod =
                        method(nested, call.method + call.descriptor);
                if (nestedMethod == null || callArguments.isEmpty() && callReceiver == null) continue;
                collectScreenFieldMirrors(callOwner, nestedMethod, behaviors, wantedScreen,
                        callArguments, callReceiver, found, visiting, depth + 1, confidence);
            }
        } finally {
            visiting.remove(visit);
        }
    }

    private static void collectScreenObjectLookups(
            String owner, BehaviorBytecodeScanner.MethodBehavior method,
            Map<String, BehaviorBytecodeScanner.BehaviorSurface> behaviors,
            String wantedScreen, Map<Integer, ValueOrigin> parameterOrigins,
            ValueOrigin thisOrigin, Map<String, ScreenObjectLookup> found,
            Set<String> visiting, int depth, int confidence) {
        if (owner == null || method == null || depth > 8) return;
        String visit = normalize(owner) + '#' + method.method + method.descriptor + "@object-lookup";
        if (!visiting.add(visit)) return;
        try {
            for (BehaviorBytecodeScanner.InvocationEdge call : method.invocations) {
                if (call == null || call.owner == null || call.method == null || call.descriptor == null) continue;
                LinkedHashMap<Integer, ValueOrigin> callArguments = new LinkedHashMap<>();
                for (BehaviorBytecodeScanner.InvocationArgument argument : call.arguments) {
                    ValueOrigin origin = invocationArgumentOrigin(argument, parameterOrigins, thisOrigin);
                    if (origin != null) callArguments.put(argument.index, origin);
                }
                ValueOrigin callReceiver = call.receiverIsThis ? thisOrigin
                        : call.receiverParameterIndex < 0 ? null
                        : parameterOrigins.get(call.receiverParameterIndex);

                String callOwner = normalize(call.owner);
                if ("<init>".equals(call.method) && wantedScreen.equals(callOwner)) {
                    BehaviorBytecodeScanner.BehaviorSurface screen = behaviors.get(callOwner);
                    BehaviorBytecodeScanner.MethodBehavior constructor = screen == null ? null
                            : method(screen, call.method + call.descriptor);
                    if (constructor == null) continue;
                    for (BehaviorBytecodeScanner.FieldAssignment assignment
                            : constructor.parameterFieldAssignments) {
                        if (assignment == null || assignment.parameterIndex < 0
                                || assignment.fieldOwner == null || assignment.fieldName == null
                                || assignment.fieldDesc == null || !wantedScreen.equals(
                                normalize(assignment.fieldOwner))) continue;
                        ValueOrigin origin = callArguments.get(assignment.parameterIndex);
                        if (origin == null || !origin.lookupValue()
                                || !scalarDescriptor(origin.lookupKeyFieldDesc)) continue;
                        String assignedObject = objectClass(assignment.fieldDesc);
                        if (assignedObject == null || !assignedObject.equals(origin.lookupObjectClass)) continue;
                        String target = assignment.fieldOwner + "." + assignment.fieldName
                                + ':' + assignment.fieldDesc;
                        ScreenObjectLookup lookup = new ScreenObjectLookup(target,
                                origin.lookupObjectClass, origin.lookupKeyFieldOwner,
                                origin.lookupKeyFieldName, origin.lookupKeyFieldDesc, confidence);
                        String token = target + '\n' + origin.lookupObjectClass + '\n'
                                + origin.lookupKeyFieldOwner + '.' + origin.lookupKeyFieldName
                                + ':' + origin.lookupKeyFieldDesc;
                        found.putIfAbsent(token, lookup);
                    }
                    continue;
                }

                BehaviorBytecodeScanner.BehaviorSurface nested = behaviors.get(callOwner);
                if (nested == null || "<init>".equals(call.method)) continue;
                BehaviorBytecodeScanner.MethodBehavior nestedMethod =
                        method(nested, call.method + call.descriptor);
                if (nestedMethod == null || callArguments.isEmpty() && callReceiver == null) continue;
                collectScreenObjectLookups(callOwner, nestedMethod, behaviors, wantedScreen,
                        callArguments, callReceiver, found, visiting, depth + 1, confidence);
            }
        } finally {
            visiting.remove(visit);
        }
    }

    private static void collectConstructedObjectFields(
            String owner, BehaviorBytecodeScanner.MethodBehavior method,
            Map<String, BehaviorBytecodeScanner.BehaviorSurface> behaviors,
            Map<Integer, ValueOrigin> parameterOrigins, ValueOrigin thisOrigin,
            Map<String, ConstructedObjectField> found,
            Set<String> visiting, int depth, int confidence) {
        if (owner == null || method == null || depth > 8) return;
        String visit = normalize(owner) + '#' + method.method + method.descriptor + "@constructed-fields";
        if (!visiting.add(visit)) return;
        try {
            for (BehaviorBytecodeScanner.InvocationEdge call : method.invocations) {
                if (call == null || call.owner == null || call.method == null || call.descriptor == null) continue;
                LinkedHashMap<Integer, ValueOrigin> callArguments = new LinkedHashMap<>();
                for (BehaviorBytecodeScanner.InvocationArgument argument : call.arguments) {
                    ValueOrigin origin = invocationArgumentOrigin(argument, parameterOrigins, thisOrigin);
                    if (origin != null) callArguments.put(argument.index, origin);
                }
                ValueOrigin callReceiver = call.receiverIsThis ? thisOrigin
                        : call.receiverParameterIndex < 0 ? null
                        : parameterOrigins.get(call.receiverParameterIndex);

                String callOwner = normalize(call.owner);
                if ("<init>".equals(call.method)) {
                    for (Map.Entry<Integer, ValueOrigin> entry : callArguments.entrySet()) {
                        ValueOrigin origin = entry.getValue();
                        if (origin == null || !origin.fieldValue() || !scalarDescriptor(origin.fieldDesc)) continue;
                        ConstructedObjectField field = new ConstructedObjectField(
                                origin.rootPacket, callOwner, call.descriptor, entry.getKey(),
                                origin.fieldOwner, origin.fieldName, origin.fieldDesc, confidence);
                        String token = field.packetClass + '\n' + field.objectClass + '\n'
                                + field.constructorDesc + '\n' + field.constructorArgument + '\n'
                                + field.payloadFieldOwner + '.' + field.payloadFieldName
                                + ':' + field.payloadFieldDesc;
                        found.putIfAbsent(token, field);
                    }
                    continue;
                }

                BehaviorBytecodeScanner.BehaviorSurface nested = behaviors.get(callOwner);
                if (nested == null || "<init>".equals(call.method)) continue;
                BehaviorBytecodeScanner.MethodBehavior nestedMethod =
                        method(nested, call.method + call.descriptor);
                if (nestedMethod == null || callArguments.isEmpty() && callReceiver == null) continue;
                collectConstructedObjectFields(callOwner, nestedMethod, behaviors,
                        callArguments, callReceiver, found, visiting, depth + 1, confidence);
            }
        } finally {
            visiting.remove(visit);
        }
    }

    private static ValueOrigin invocationArgumentOrigin(
            BehaviorBytecodeScanner.InvocationArgument argument,
            Map<Integer, ValueOrigin> parameters, ValueOrigin thisOrigin) {
        if (argument == null) return null;
        if (argument.parameterIndex >= 0) return parameters.get(argument.parameterIndex);
        if (argument.fieldOwner != null && argument.fieldName != null && argument.fieldDesc != null) {
            ValueOrigin receiver = argument.fieldReceiverIsThis ? thisOrigin
                    : argument.fieldReceiverParameterIndex < 0 ? null
                    : parameters.get(argument.fieldReceiverParameterIndex);
            if (receiver == null || !receiver.rootOnly()) return null;
            return ValueOrigin.field(receiver.rootPacket, argument.fieldOwner,
                    argument.fieldName, argument.fieldDesc);
        }
        if (argument.descriptor != null && argument.lookupKeyFieldOwner != null
                && argument.lookupKeyFieldName != null && argument.lookupKeyFieldDesc != null) {
            ValueOrigin receiver = argument.lookupKeyFieldReceiverIsThis ? thisOrigin
                    : argument.lookupKeyFieldReceiverParameterIndex < 0 ? null
                    : parameters.get(argument.lookupKeyFieldReceiverParameterIndex);
            if (receiver == null || !receiver.rootOnly()) return null;
            String objectClass;
            try {
                Type type = Type.getType(argument.descriptor);
                objectClass = type.getSort() == Type.OBJECT ? normalize(type.getInternalName()) : null;
            } catch (IllegalArgumentException ignored) {
                objectClass = null;
            }
            if (objectClass == null || objectClass.isBlank()) return null;
            return ValueOrigin.lookup(receiver.rootPacket, objectClass,
                    argument.lookupKeyFieldOwner, argument.lookupKeyFieldName,
                    argument.lookupKeyFieldDesc);
        }
        return null;
    }

    private static int parameterIndex(String descriptor, String wantedType) {
        if (descriptor == null || wantedType == null) return -1;
        Type[] arguments;
        try {
            arguments = Type.getArgumentTypes(descriptor);
        } catch (IllegalArgumentException ignored) {
            return -1;
        }
        int found = -1;
        for (int index = 0; index < arguments.length; index++) {
            Type argument = arguments[index];
            if (argument.getSort() != Type.OBJECT
                    || !wantedType.equals(normalize(argument.getInternalName()))) continue;
            if (found >= 0) return -1;
            found = index;
        }
        return found;
    }

    private static boolean scalarDescriptor(String descriptor) {
        return "Z".equals(descriptor) || "B".equals(descriptor) || "C".equals(descriptor)
                || "S".equals(descriptor) || "I".equals(descriptor) || "J".equals(descriptor)
                || "F".equals(descriptor) || "D".equals(descriptor)
                || "Ljava/lang/String;".equals(descriptor)
                || "Ljava/util/UUID;".equals(descriptor);
    }

    private static String objectClass(String descriptor) {
        if (descriptor == null) return null;
        try {
            Type type = Type.getType(descriptor);
            return type.getSort() == Type.OBJECT ? normalize(type.getInternalName()) : null;
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    /**
     * True only when the exact invocation is reachable from a clientbound packet handler which is
     * independently proven to open the requested screen. Runtime uses this as the second half of a
     * DTO-mirroring proof: ClientSurfaceScanner proves what a converter copies, while this method
     * proves that the opening handler actually uses that converter on the path to the screen.
     */
    public static boolean clientboundOpeningUsesInvocation(
            Collection<ModScanner.Profile> profiles, String packetClass, String screenClass,
            String invocationOwner, String invocationMethod, String invocationDesc) {
        if (profiles == null || packetClass == null || screenClass == null
                || invocationOwner == null || invocationMethod == null || invocationDesc == null) {
            return false;
        }
        String wantedPacket = normalize(packetClass);
        String wantedScreen = normalize(screenClass);
        String wantedOwner = normalize(invocationOwner);
        LinkedHashMap<String, BehaviorBytecodeScanner.BehaviorSurface> behaviors = new LinkedHashMap<>();
        LinkedHashSet<String> screens = new LinkedHashSet<>();
        for (ModScanner.Profile profile : profiles) collect(profile, behaviors, screens);
        if (!screens.contains(wantedScreen)) return false;

        for (BehaviorBytecodeScanner.BehaviorSurface handler : behaviors.values()) {
            if (handler == null || handler.methods == null) continue;
            for (BehaviorBytecodeScanner.MethodBehavior method : handler.methods) {
                if (method == null || method.trigger != BehaviorBytecodeScanner.Trigger.PACKET_HANDLE
                        || !wantedPacket.equals(normalize(clientPacketParameter(method.descriptor)))) continue;
                LinkedHashSet<String> opened = new LinkedHashSet<>();
                collectOpenedScreens(handler.clazz, method, behaviors, screens, opened,
                        new LinkedHashSet<>(), 0);
                if (!opened.contains(wantedScreen)) continue;
                if (reachableInvocation(handler.clazz, method, behaviors, wantedOwner,
                        invocationMethod, invocationDesc, new LinkedHashSet<>(), 0)) return true;
            }
        }
        return false;
    }

    private static boolean reachableInvocation(
            String owner, BehaviorBytecodeScanner.MethodBehavior method,
            Map<String, BehaviorBytecodeScanner.BehaviorSurface> behaviors,
            String wantedOwner, String wantedMethod, String wantedDesc,
            Set<String> visiting, int depth) {
        if (owner == null || method == null || depth > 6) return false;
        String visit = normalize(owner) + '#' + method.method + method.descriptor + "@invocation";
        if (!visiting.add(visit)) return false;
        try {
            for (BehaviorBytecodeScanner.InvocationEdge call : method.invocations) {
                if (call == null || call.owner == null || call.method == null || call.descriptor == null) continue;
                if (wantedOwner.equals(normalize(call.owner))
                        && wantedMethod.equals(call.method) && wantedDesc.equals(call.descriptor)) return true;
            }
            for (BehaviorBytecodeScanner.InvocationEdge call : method.invocations) {
                if (call == null || call.owner == null || call.method == null || call.descriptor == null
                        || "<init>".equals(call.method)) continue;
                String nestedOwner = normalize(call.owner);
                BehaviorBytecodeScanner.BehaviorSurface nested = behaviors.get(nestedOwner);
                if (nested == null) continue;
                BehaviorBytecodeScanner.MethodBehavior nestedMethod =
                        method(nested, call.method + call.descriptor);
                if (nestedMethod != null && reachableInvocation(nestedOwner, nestedMethod, behaviors,
                        wantedOwner, wantedMethod, wantedDesc, visiting, depth + 1)) return true;
            }
            return false;
        } finally {
            visiting.remove(visit);
        }
    }

    private static void collectOpenedScreens(
            String owner, BehaviorBytecodeScanner.MethodBehavior method,
            Map<String, BehaviorBytecodeScanner.BehaviorSurface> behaviors, Set<String> screens,
            Set<String> out, Set<String> visiting, int depth) {
        if (owner == null || method == null || depth > 6) return;
        String visit = normalize(owner) + '#' + method.method + method.descriptor;
        if (!visiting.add(visit)) return;
        try {
            for (BehaviorBytecodeScanner.CallEdge edge : method.calls) {
                if (edge == null || edge.effect != BehaviorBytecodeScanner.Effect.OPEN_SCREEN
                        || edge.target == null) continue;
                String target = normalize(edge.target);
                if (screens.contains(target)) out.add(target);
            }
            for (BehaviorBytecodeScanner.InvocationEdge call : method.invocations) {
                if (call == null || call.owner == null || call.method == null || call.descriptor == null
                        || "<init>".equals(call.method)) continue;
                String nestedOwner = normalize(call.owner);
                BehaviorBytecodeScanner.BehaviorSurface nested = behaviors.get(nestedOwner);
                if (nested == null) continue;
                BehaviorBytecodeScanner.MethodBehavior nestedMethod =
                        method(nested, call.method + call.descriptor);
                if (nestedMethod != null) collectOpenedScreens(nestedOwner, nestedMethod, behaviors,
                        screens, out, visiting, depth + 1);
            }
        } finally {
            visiting.remove(visit);
        }
    }

    private static String clientPacketParameter(String descriptor) {
        if (descriptor == null) return null;
        Type[] arguments;
        try {
            arguments = Type.getArgumentTypes(descriptor);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
        for (Type argument : arguments) {
            if (argument.getSort() != Type.OBJECT) continue;
            String type = normalize(argument.getInternalName());
            if (type.equals("net/minecraft/client/Minecraft")
                    || type.equals("net/minecraft/server/MinecraftServer")
                    || type.equals("net/minecraft/server/level/ServerPlayer")
                    || type.startsWith("net/minecraft/network/")) continue;
            return type;
        }
        return null;
    }

    private static void collect(ModScanner.Profile profile,
                                Map<String, BehaviorBytecodeScanner.BehaviorSurface> behaviors,
                                Set<String> screens) {
        if (profile == null) return;
        if (profile.behaviors != null) {
            for (BehaviorBytecodeScanner.BehaviorSurface surface : profile.behaviors) {
                if (surface != null && surface.clazz != null) behaviors.putIfAbsent(normalize(surface.clazz), surface);
            }
        }
        if (profile.clientSurfaces != null) {
            for (ClientSurfaceScanner.ClassSurface surface : profile.clientSurfaces) {
                if (surface != null && surface.clazz != null
                        && surface.kinds.contains(ClientSurfaceScanner.Kind.SCREEN)) {
                    screens.add(normalize(surface.clazz));
                }
            }
        }
        if (profile.nested != null) {
            for (ModScanner.Profile child : profile.nested) collect(child, behaviors, screens);
        }
    }

    private static Map<String, List<ClientConsumer>> responseConsumers(
            Map<String, BehaviorBytecodeScanner.BehaviorSurface> behaviors,
            Set<String> screens) {
        LinkedHashMap<String, List<ClientConsumer>> result = new LinkedHashMap<>();
        for (Map.Entry<String, BehaviorBytecodeScanner.BehaviorSurface> entry : behaviors.entrySet()) {
            BehaviorBytecodeScanner.BehaviorSurface packet = entry.getValue();
            LinkedHashMap<String, BehaviorBytecodeScanner.MethodBehavior> methods = methodIndex(packet);
            for (BehaviorBytecodeScanner.MethodBehavior method : packet.methods) {
                if (method.trigger != BehaviorBytecodeScanner.Trigger.PACKET_HANDLE) continue;
                collectResponseConsumers(entry.getKey(), packet.clazz, method, methods, screens,
                        result, new LinkedHashSet<>(), 0);
            }
        }
        return result;
    }

    private static void collectResponseConsumers(
            String responseClass, String packetClass,
            BehaviorBytecodeScanner.MethodBehavior method,
            Map<String, BehaviorBytecodeScanner.MethodBehavior> methods,
            Set<String> screens, Map<String, List<ClientConsumer>> result,
            Set<String> visiting, int depth) {
        if (method == null || depth > 5) return;
        String key = method.method + method.descriptor;
        if (!visiting.add(key)) return;
        for (BehaviorBytecodeScanner.InvocationEdge call : method.invocations) {
            if (call == null || call.owner == null || call.method == null || call.descriptor == null) continue;
            String owner = normalize(call.owner);
            // A dynamic update mutates an already-open screen instance. Static calls usually open
            // or construct a screen (e.g. Screen.open(...)) and are a different lifecycle edge.
            if (screens.contains(owner) && !"<init>".equals(call.method)
                    && call.opcode != org.objectweb.asm.Opcodes.INVOKESTATIC) {
                ClientConsumer candidate = new ClientConsumer(owner, call.method + call.descriptor);
                List<ClientConsumer> consumers = result.computeIfAbsent(responseClass,
                        ignored -> new ArrayList<>());
                if (!consumers.contains(candidate)) consumers.add(candidate);
            }
            if (owner.equals(normalize(packetClass))) {
                BehaviorBytecodeScanner.MethodBehavior local = methods.get(call.method + call.descriptor);
                if (local != null) {
                    collectResponseConsumers(responseClass, packetClass, local, methods, screens,
                            result, visiting, depth + 1);
                }
            }
        }
        visiting.remove(key);
    }

    /**
     * Starting at the request handler, follow only concrete invocations into classes that are
     * themselves present in the behavior inventory. This keeps the join inside the scanned mod
     * archive and avoids namespace/name guessing.
     */
    private static List<BridgeEntry> reachableBridgeEntries(
            BehaviorBytecodeScanner.BehaviorSurface request,
            Map<String, BehaviorBytecodeScanner.BehaviorSurface> behaviors) {
        ArrayList<BridgeEntry> result = new ArrayList<>();
        ArrayDeque<BridgeEntry> queue = new ArrayDeque<>();
        LinkedHashSet<String> visited = new LinkedHashSet<>();
        for (BehaviorBytecodeScanner.MethodBehavior method : request.methods) {
            if (method.trigger != BehaviorBytecodeScanner.Trigger.PACKET_HANDLE) continue;
            enqueueMethods(method, behaviors, queue, 0);
        }
        while (!queue.isEmpty()) {
            BridgeEntry node = queue.removeFirst();
            String token = node.clazz + '#' + node.methodKey;
            if (node.depth > MAX_BRIDGE_DEPTH || !visited.add(token)) continue;
            result.add(node);
            BehaviorBytecodeScanner.BehaviorSurface surface = behaviors.get(node.clazz);
            BehaviorBytecodeScanner.MethodBehavior reached = method(surface, node.methodKey);
            if (reached == null || node.depth == MAX_BRIDGE_DEPTH) continue;
            enqueueMethods(reached, behaviors, queue, node.depth + 1);
        }
        return List.copyOf(result);
    }

    private static void enqueueMethods(BehaviorBytecodeScanner.MethodBehavior method,
                                       Map<String, BehaviorBytecodeScanner.BehaviorSurface> behaviors,
                                       ArrayDeque<BridgeEntry> queue, int depth) {
        for (BehaviorBytecodeScanner.InvocationEdge call : method.invocations) {
            String owner = normalize(call.owner);
            BehaviorBytecodeScanner.BehaviorSurface surface = behaviors.get(owner);
            if (surface == null || method(surface, call.method + call.descriptor) == null) continue;
            queue.addLast(new BridgeEntry(owner, call.method + call.descriptor, depth));
        }
    }

    /** A delayed response must be tied to the exact server entry reached by the request. For
     * queue-and-flush designs the request method and later producer share a field (for example a
     * pending-request map); unrelated setters on the same class do not. */
    private static boolean stateCorrelated(BehaviorBytecodeScanner.BehaviorSurface surface,
                                           BehaviorBytecodeScanner.MethodBehavior requestEntry,
                                           BehaviorBytecodeScanner.MethodBehavior responseProducer) {
        if (surface == null || requestEntry == null || responseProducer == null) return false;
        String requestKey = requestEntry.method + requestEntry.descriptor;
        String responseKey = responseProducer.method + responseProducer.descriptor;
        if (requestKey.equals(responseKey)) return true;

        LinkedHashMap<String, BehaviorBytecodeScanner.MethodBehavior> methods = methodIndex(surface);
        Set<String> requestState = stateClosure(surface.clazz, requestEntry, methods,
                new LinkedHashSet<>(), 0);
        Set<String> responseState = stateClosure(surface.clazz, responseProducer, methods,
                new LinkedHashSet<>(), 0);
        if (requestState.isEmpty() || responseState.isEmpty()) return false;
        for (String field : requestState) if (responseState.contains(field)) return true;
        return false;
    }

    private static Set<String> stateClosure(String owner,
                                            BehaviorBytecodeScanner.MethodBehavior method,
                                            Map<String, BehaviorBytecodeScanner.MethodBehavior> methods,
                                            Set<String> visiting, int depth) {
        LinkedHashSet<String> state = new LinkedHashSet<>();
        if (method == null || depth > 5) return state;
        String key = method.method + method.descriptor;
        if (!visiting.add(key)) return state;
        state.addAll(method.fieldReads);
        state.addAll(method.fieldWrites);
        for (BehaviorBytecodeScanner.InvocationEdge call : method.invocations) {
            if (!normalize(owner).equals(normalize(call.owner))) continue;
            BehaviorBytecodeScanner.MethodBehavior local = methods.get(call.method + call.descriptor);
            if (local != null) state.addAll(stateClosure(owner, local, methods, visiting, depth + 1));
        }
        visiting.remove(key);
        return state;
    }

    private static LinkedHashMap<String, BehaviorBytecodeScanner.MethodBehavior> methodIndex(
            BehaviorBytecodeScanner.BehaviorSurface surface) {
        LinkedHashMap<String, BehaviorBytecodeScanner.MethodBehavior> methods = new LinkedHashMap<>();
        if (surface == null || surface.methods == null) return methods;
        for (BehaviorBytecodeScanner.MethodBehavior method : surface.methods) {
            methods.put(method.method + method.descriptor, method);
        }
        return methods;
    }

    private static BehaviorBytecodeScanner.MethodBehavior method(
            BehaviorBytecodeScanner.BehaviorSurface surface, String key) {
        if (surface == null || key == null) return null;
        for (BehaviorBytecodeScanner.MethodBehavior method : surface.methods) {
            if ((method.method + method.descriptor).equals(key)) return method;
        }
        return null;
    }

    private static boolean hasPacketHandler(BehaviorBytecodeScanner.BehaviorSurface surface) {
        return surface != null && surface.methods.stream()
                .anyMatch(method -> method.trigger == BehaviorBytecodeScanner.Trigger.PACKET_HANDLE);
    }

    private static List<String> payloadFields(BehaviorBytecodeScanner.BehaviorSurface response) {
        if (response == null || response.dataFields == null) return List.of();
        ArrayList<String> fields = new ArrayList<>();
        for (BehaviorBytecodeScanner.DataField field : response.dataFields) {
            if (field == null || field.isStatic || field.name == null || field.descriptor == null) continue;
            fields.add(field.name + ':' + field.descriptor);
        }
        return List.copyOf(fields);
    }

    /** Collect server-side accessor evidence from the response-producing method and same-class
     * helper methods it calls. These are descriptors only; runtime code still validates live
     * receiver types before invoking any accessor. */
    private static List<String> dataAccessors(BehaviorBytecodeScanner.BehaviorSurface bridge,
                                              BehaviorBytecodeScanner.MethodBehavior producer) {
        if (bridge == null || producer == null) return List.of();
        LinkedHashMap<String, BehaviorBytecodeScanner.MethodBehavior> methods = new LinkedHashMap<>();
        for (BehaviorBytecodeScanner.MethodBehavior method : bridge.methods) {
            methods.put(method.method + method.descriptor, method);
        }
        LinkedHashSet<String> result = new LinkedHashSet<>();
        collectAccessors(bridge.clazz, producer, methods, result, new LinkedHashSet<>(), 0);
        return List.copyOf(result);
    }

    private static void collectAccessors(String bridgeClass,
                                         BehaviorBytecodeScanner.MethodBehavior method,
                                         Map<String, BehaviorBytecodeScanner.MethodBehavior> methods,
                                         Set<String> result, Set<String> visiting, int depth) {
        if (method == null || depth > 4 || result.size() >= MAX_ACCESSORS) return;
        String methodKey = method.method + method.descriptor;
        if (!visiting.add(methodKey)) return;
        for (BehaviorBytecodeScanner.InvocationEdge call : method.invocations) {
            if (result.size() >= MAX_ACCESSORS) break;
            if (call == null || call.owner == null || call.method == null || call.descriptor == null
                    || "<init>".equals(call.method)) continue;
            String owner = normalize(call.owner);
            if (owner.equals(normalize(bridgeClass))) {
                BehaviorBytecodeScanner.MethodBehavior local = methods.get(call.method + call.descriptor);
                if (local != null) collectAccessors(bridgeClass, local, methods, result, visiting, depth + 1);
                continue;
            }
            if (frameworkNoise(owner, call.method)) continue;
            result.add(owner + '.' + call.method + call.descriptor);
        }
        visiting.remove(methodKey);
    }

    private static boolean frameworkNoise(String owner, String method) {
        String lower = owner.toLowerCase(java.util.Locale.ROOT);
        String name = method.toLowerCase(java.util.Locale.ROOT);
        return lower.startsWith("java/") || lower.startsWith("javax/")
                || lower.startsWith("it/unimi/dsi/fastutil/")
                || lower.startsWith("net/minecraft/network/")
                || lower.contains("packetmanager") || lower.contains("packetsink")
                || name.equals("iterator") || name.equals("hasnext") || name.equals("next")
                || name.equals("put") || name.equals("get") || name.equals("remove")
                || name.equals("contains") || name.equals("add");
    }

    private static String normalize(String value) {
        return value == null ? "" : value.replace('.', '/');
    }

    private record ClientConsumer(String screenClass, String updateMethod) {}
    private record BridgeEntry(String clazz, String methodKey, int depth) {}
}
