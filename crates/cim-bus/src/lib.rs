//! c-imagine event bus.
//!
//! The embedded mini-NeoForge layer dispatches lifecycle and game events
//! through this bus. Design constraints:
//!
//! - **Deterministic ordering**: listeners run in priority order, then in
//!   registration order within the same priority. Load order of mods therefore
//!   fully determines event delivery order — required for NeoForge parity.
//! - **Cancellation semantics**: a cancelled event stops delivery to lower
//!   priorities; `Monitor` listeners always observe, mirroring NeoForge's
//!   `EventPriority` behavior.
//! - **Zero unsafe**, no background threads: dispatch happens on the calling
//!   thread (the layer runs on the kernel main thread).
//!
//! Handlers are typed at registration time and type-checked at dispatch time
//! via `TypeId`, so a mis-typed registration is a silent no-op for other event
//! types — never UB.

use std::any::{Any, TypeId};
use std::collections::HashMap;

/// Listener priority. Ordering matches NeoForge semantics: `Lowest` runs
/// first, `Monitor` last and observes even cancelled events.
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub enum Priority {
    Lowest,
    Low,
    Normal,
    High,
    Highest,
    Monitor,
}

/// Base behavior every event must implement.
pub trait Event: Any {
    /// Stable event name (used in logs and metrics).
    fn name(&self) -> &'static str;
    /// Whether this event type supports cancellation.
    fn cancellable(&self) -> bool {
        false
    }
    /// Current cancellation flag; ignored when `cancellable()` is false.
    fn cancelled(&self) -> bool {
        false
    }
    /// Set the cancellation flag; ignored when `cancellable()` is false.
    fn set_cancelled(&mut self, _cancelled: bool) {}
}

/// Opaque listener handle returned by [`EventBus::on`]; pass it to
/// [`EventBus::remove`] to detach.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub struct ListenerId(u64);

/// Type-erased handler: downcasts the event to the concrete type it was
/// registered for, then invokes the user closure.
type ErasedHandler = Box<dyn FnMut(&mut dyn Any) + Send>;

struct ListenerEntry {
    id: ListenerId,
    priority: Priority,
    invoke: ErasedHandler,
}

/// The event bus. Create one per logical server instance.
#[derive(Default)]
pub struct EventBus {
    next_id: u64,
    listeners: HashMap<TypeId, Vec<ListenerEntry>>,
}

impl EventBus {
    pub fn new() -> Self {
        Self::default()
    }

    /// Register a handler for event type `E`.
    pub fn on<E, F>(&mut self, priority: Priority, mut handler: F) -> ListenerId
    where
        E: Event,
        F: FnMut(&mut E) + Send + 'static,
    {
        let id = ListenerId(self.next_id);
        self.next_id += 1;
        let invoke = Box::new(move |ev: &mut dyn Any| {
            if let Some(concrete) = ev.downcast_mut::<E>() {
                handler(concrete);
            }
        });
        let bucket = self.listeners.entry(TypeId::of::<E>()).or_default();
        bucket.push(ListenerEntry {
            id,
            priority,
            invoke,
        });
        id
    }

    /// Remove a previously registered listener. Returns true when removed.
    pub fn remove(&mut self, id: ListenerId) -> bool {
        for bucket in self.listeners.values_mut() {
            if let Some(pos) = bucket.iter().position(|l| l.id == id) {
                bucket.remove(pos);
                return true;
            }
        }
        false
    }

    /// Dispatch `event` to all listeners of its concrete type.
    ///
    /// Delivery order: ascending [`Priority`], then registration order. When
    /// the event becomes cancelled, only `Monitor` listeners still run.
    pub fn post<E: Event>(&mut self, mut event: E) -> E {
        // Buckets keep registration order; a keyed sort makes delivery
        // deterministic without disturbing remove-by-id lookups.
        let entries = match self.listeners.get_mut(&TypeId::of::<E>()) {
            Some(v) => v,
            None => return event,
        };
        entries.sort_by_key(|l| l.priority);

        let cancellable = event.cancellable();
        for entry in entries.iter_mut() {
            if cancellable && entry.priority != Priority::Monitor && event.cancelled() {
                continue;
            }
            (entry.invoke)(&mut event as &mut dyn Any);
        }
        event
    }

    /// Number of listeners registered for event type `E`.
    pub fn listener_count<E: Event>(&self) -> usize {
        self.listeners
            .get(&TypeId::of::<E>())
            .map(|v| v.len())
            .unwrap_or(0)
    }

    /// Total listeners across all event types.
    pub fn total_listeners(&self) -> usize {
        self.listeners.values().map(|v| v.len()).sum()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[derive(Debug, Default)]
    struct TickEvent {
        tick: u64,
        cancelled_flag: bool,
    }

    impl Event for TickEvent {
        fn name(&self) -> &'static str {
            "tick"
        }
        fn cancellable(&self) -> bool {
            true
        }
        fn cancelled(&self) -> bool {
            self.cancelled_flag
        }
        fn set_cancelled(&mut self, c: bool) {
            self.cancelled_flag = c;
        }
    }

    #[derive(Debug, Default)]
    struct LogEvent(Vec<String>);
    impl Event for LogEvent {
        fn name(&self) -> &'static str {
            "log"
        }
    }

    #[test]
    fn delivery_order_is_priority_then_registration() {
        let mut bus = EventBus::new();
        bus.on::<LogEvent, _>(Priority::Highest, |e| e.0.push("highest".into()));
        bus.on::<LogEvent, _>(Priority::Lowest, |e| e.0.push("lowest-1".into()));
        bus.on::<LogEvent, _>(Priority::Lowest, |e| e.0.push("lowest-2".into()));
        bus.on::<LogEvent, _>(Priority::Normal, |e| e.0.push("normal".into()));

        let e = bus.post(LogEvent::default());
        assert_eq!(e.0, ["lowest-1", "lowest-2", "normal", "highest"]);
    }

    #[test]
    fn cancellation_stops_lower_priority_but_not_monitor() {
        let mut bus = EventBus::new();
        bus.on::<TickEvent, _>(Priority::Lowest, |e| {
            e.tick += 1;
            e.set_cancelled(true);
        });
        bus.on::<TickEvent, _>(Priority::Normal, |e| e.tick += 10);
        bus.on::<TickEvent, _>(Priority::Monitor, |e| e.tick += 100);

        let e = bus.post(TickEvent::default());
        assert_eq!(e.tick, 101, "normal skipped, monitor ran");
    }

    #[test]
    fn remove_detaches_only_that_listener() {
        let mut bus = EventBus::new();
        let keep = bus.on::<LogEvent, _>(Priority::Normal, |e| e.0.push("keep".into()));
        let drop = bus.on::<LogEvent, _>(Priority::Normal, |e| e.0.push("drop".into()));
        assert!(bus.remove(drop));
        assert!(!bus.remove(drop), "second remove is a no-op");
        let e = bus.post(LogEvent::default());
        assert_eq!(e.0, ["keep"]);
        assert_eq!(bus.listener_count::<LogEvent>(), 1);
        assert_eq!(bus.total_listeners(), 1);
        assert!(bus.remove(keep));
        assert_eq!(bus.listener_count::<LogEvent>(), 0);
    }

    #[test]
    fn posting_unknown_event_type_is_a_noop() {
        let mut bus = EventBus::new();
        let e = bus.post(LogEvent::default());
        assert!(e.0.is_empty());
    }

    #[test]
    fn misregistered_types_never_cross_dispatch() {
        let mut bus = EventBus::new();
        bus.on::<LogEvent, _>(Priority::Normal, |e| e.0.push("log".into()));
        let t = bus.post(TickEvent::default());
        assert_eq!(t.tick, 0);
        assert_eq!(bus.total_listeners(), 1);
    }
}
