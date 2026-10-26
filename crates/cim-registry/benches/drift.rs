//! Drift-translation cost benchmark — M4 packet hooks call these per block
//! state / entity id inside rewritten packets, so the per-call budget is the
//! number that matters.

use cim_registry::drift;
use criterion::{black_box, criterion_group, criterion_main, Criterion};

fn bench_state_translation(c: &mut Criterion) {
    let t = drift::tables();
    c.bench_function("drift/state/identity_passthrough", |b| {
        b.iter(|| {
            black_box(t.layer_to_kernel_state(black_box("minecraft:stone")));
        })
    });
    c.bench_function("drift/state/renamed_block", |b| {
        b.iter(|| {
            black_box(
                t.layer_to_kernel_state(black_box("minecraft:chain[axis=y,waterlogged=true]")),
            );
        })
    });
    c.bench_function("drift/state/prop_drift_block", |b| {
        b.iter(|| {
            black_box(
                t.kernel_to_layer_state(black_box("minecraft:note_block[instrument=trumpet]")),
            );
        })
    });
    c.bench_function("drift/key/entity_split", |b| {
        b.iter(|| {
            black_box(t.layer_to_kernel_key(black_box("entity_type"), black_box("minecraft:boat")));
        })
    });
    c.bench_function("drift/key/identity_passthrough", |b| {
        b.iter(|| {
            black_box(t.layer_to_kernel_key(black_box("entity_type"), black_box("minecraft:pig")));
        })
    });
}

criterion_group!(benches, bench_state_translation);
criterion_main!(benches);
