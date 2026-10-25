//! Bridge ring throughput benchmark — the transport every state-bridge frame
//! and F2V v2 packet handoff rides on.

use cim_bridge::ring::SpscRing;
use criterion::{black_box, criterion_group, criterion_main, Criterion};

fn bench_write_read_64b(c: &mut Criterion) {
    let ring = SpscRing::new(64 * 1024).unwrap();
    let payload = [0xA5u8; 64];
    let mut first = [0u8; 64];
    let mut spill = Vec::new();
    c.bench_function("ring/roundtrip/64b", |b| {
        b.iter(|| {
            ring.write_frame(black_box(&payload)).unwrap();
            let got = ring.read_frame_into(&mut first, &mut spill).unwrap();
            black_box(got);
        })
    });
}

fn bench_write_read_4k(c: &mut Criterion) {
    let ring = SpscRing::new(64 * 1024).unwrap();
    let payload = vec![0x5Au8; 4096];
    let mut first = vec![0u8; 4096];
    let mut spill = Vec::new();
    c.bench_function("ring/roundtrip/4k", |b| {
        b.iter(|| {
            ring.write_frame(black_box(&payload)).unwrap();
            let got = ring.read_frame_into(&mut first, &mut spill).unwrap();
            black_box(got.len());
        })
    });
}

criterion_group!(benches, bench_write_read_64b, bench_write_read_4k);
criterion_main!(benches);
