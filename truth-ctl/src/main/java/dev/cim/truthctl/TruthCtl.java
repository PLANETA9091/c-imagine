package dev.cim.truthctl;

import com.google.gson.JsonObject;
import java.util.Deque;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientChatReceivedEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * truth-ctl: client-only control socket for the parity harness.
 *
 * <p>Entry point. Starts the 127.0.0.1 socket thread ({@link Protocol}) and drives long
 * actions (break, wait, place, ...) as {@link Task} state machines advanced from
 * {@link ClientTickEvent.Post} on the client main thread.
 */
@Mod(value = "truthctl", dist = Dist.CLIENT)
public final class TruthCtl {
    public static final String PROTOCOL = "truth-ctl/1";
    public static final String TAG = "[truthctl] ";
    static final Logger LOG = LoggerFactory.getLogger("truthctl");

    /** Chat lines received by the client (most recent last), capped. */
    static final Deque<String> CHAT = new ConcurrentLinkedDeque<>();
    private static final int CHAT_CAP = 500;

    /** The one long action in flight. Only touched on the main thread. */
    private static Task active;

    public TruthCtl() {
        NeoForge.EVENT_BUS.addListener(TruthCtl::onTickPost);
        NeoForge.EVENT_BUS.addListener(TruthCtl::onChat);
        Protocol.start();
        LOG.info(TAG + "mod loaded, protocol {}", PROTOCOL);
    }

    static void onTickPost(ClientTickEvent.Post event) {
        Task t = active;
        if (t == null) {
            return;
        }
        try {
            t.ticks++;
            if (t.ticks > t.limit) {
                active = null;
                t.future.complete(err(t.name + " timed out after " + t.limit + " ticks", "timeout"));
                return;
            }
            if (t.step(Minecraft.getInstance())) {
                active = null;
            }
        } catch (CmdError ce) {
            active = null;
            t.future.complete(err(ce.getMessage(), ce.kind));
        } catch (Throwable ex) {
            active = null;
            LOG.error(TAG + "task {} failed", t.name, ex);
            t.future.complete(err(String.valueOf(ex), "exception"));
        }
    }

    static void onChat(ClientChatReceivedEvent event) {
        try {
            CHAT.addLast(event.getMessage().getString());
            while (CHAT.size() > CHAT_CAP) {
                CHAT.pollFirst();
            }
        } catch (Throwable ignored) {
            // chat capture is best effort
        }
    }

    /** Installs a task on the main thread. Returns its future, or a busy error. */
    static CompletableFuture<JsonObject> install(Task t) throws CmdError {
        if (active != null) {
            throw new CmdError("another action is in progress (" + active.name + ")", "busy");
        }
        t.start(Minecraft.getInstance());
        active = t;
        return t.future;
    }

    static JsonObject ok() {
        JsonObject o = new JsonObject();
        o.addProperty("ok", true);
        return o;
    }

    static JsonObject err(String msg, String kind) {
        JsonObject o = new JsonObject();
        o.addProperty("error", msg);
        o.addProperty("kind", kind);
        return o;
    }

    /** A command failure with a reply kind (e.g. "bad_request", "not_found", "no_world"). */
    public static final class CmdError extends Exception {
        private static final long serialVersionUID = 1L;
        public final String kind;

        public CmdError(String message, String kind) {
            super(message);
            this.kind = kind;
        }
    }

    /**
     * A multi-tick action. {@link #step} runs once per client tick on the main thread; the
     * task completes {@link #future} itself and returns true when finished.
     */
    public abstract static class Task {
        final String name;
        final int limit;
        int ticks;
        final CompletableFuture<JsonObject> future = new CompletableFuture<>();

        protected Task(String name, int limit) {
            this.name = name;
            this.limit = limit;
        }

        /** Called once on the main thread when installed. May throw CmdError. */
        void start(Minecraft mc) throws CmdError {}

        /** Called each client tick. Returns true when finished (future completed). */
        abstract boolean step(Minecraft mc) throws CmdError;
    }
}
