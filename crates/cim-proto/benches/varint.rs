//! VarInt codec benchmarks — the hottest primitive in every packet path.
//! Baselines are archived by profiling.yml; >10% regressions fail CI.

use cim_proto::{read_varint, write_varint};
use criterion::{black_box, criterion_group, criterion_main, Criterion};

fn bench_write(c: &mut Criterion) {
    c.bench_function("varint/write/mixed", |b| {
        b.iter(|| {
            let mut buf = Vec::with_capacity(16);
            for v in [0i32, 1, 127, 128, 16_383, 65_535, i32::MAX, -1] {
                write_varint(&mut buf, black_box(v));
            }
            buf
        })
    });
}

fn bench_read(c: &mut Criterion) {
    let mut buf = Vec::with_capacity(64);
    for v in [0i32, 1, 127, 128, 16_383, 65_535, i32::MAX, -1] {
        write_varint(&mut buf, v);
    }
    c.bench_function("varint/read/mixed", |b| {
        b.iter(|| {
            let mut off = 0usize;
            let mut acc = 0i32;
            while off < buf.len() {
                acc = acc.wrapping_add(read_varint(black_box(&buf), &mut off).unwrap_or(0));
            }
            acc
        })
    });
}

criterion_group!(benches, bench_write, bench_read);
criterion_main!(benches);
