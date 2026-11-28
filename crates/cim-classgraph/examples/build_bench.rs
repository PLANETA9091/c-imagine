//! Timed benchmark: cold index build, then cached load, over every jar in the given dirs.
//! Usage: cargo run --release -p cim-classgraph --example build_bench -- <dir> [<dir>...]
use std::path::PathBuf;
use std::time::Instant;

fn main() {
    let mut jars: Vec<PathBuf> = Vec::new();
    for dir in std::env::args().skip(1) {
        let mut v: Vec<PathBuf> = std::fs::read_dir(&dir)
            .expect("read dir")
            .filter_map(|e| e.ok().map(|e| e.path()))
            .filter(|p| p.extension().is_some_and(|x| x == "jar"))
            .collect();
        v.sort();
        jars.extend(v);
    }
    let cache = std::env::temp_dir().join("cim-classgraph-bench-cache");
    let _ = std::fs::remove_dir_all(&cache);
    let t = Instant::now();
    let idx = cim_classgraph::build(&jars);
    let cold = t.elapsed();
    println!("jars={} classes={} cold_build_ms={:.1}", jars.len(), idx.len(), cold.as_secs_f64() * 1e3);
    let t = Instant::now();
    let _ = cim_classgraph::load_or_build(&jars, &cache);
    let first = t.elapsed();
    let t = Instant::now();
    let idx2 = cim_classgraph::load_or_build(&jars, &cache);
    let warm = t.elapsed();
    println!("load_or_build first_ms={:.1} cached_ms={:.1} cached_classes={}", first.as_secs_f64() * 1e3, warm.as_secs_f64() * 1e3, idx2.len());
    let _ = std::fs::remove_dir_all(&cache);
}
