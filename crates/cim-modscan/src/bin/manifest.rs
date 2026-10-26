//! `cim-manifest` — CLI wrapper around the c-imagine scanner.
//!
//! Scans a `mods/` directory and prints the layer manifest JSON (or writes
//! it to a file). Used by the local layer harness and for relocation
//! throughput measurements against real mods — the exact same code path the
//! CRUSSTY module runs in-process.
//!
//! Usage: `cim-manifest <modsDir> [out.json] [--reloc]`
//!   `--reloc` additionally builds the relocation table and benchmarks a
//!   full relocation pass over every class in every scanned jar (classes/sec,
//!   MB/sec, rewritten class count) — the M1/M2 performance signal.

use cim_modscan::{build_layer_manifest, build_relocation_table, scan_dir_full};
use std::io::{Read, Write};
use std::time::Instant;

fn main() {
    let args: Vec<String> = std::env::args().collect();
    if args.len() < 2 {
        eprintln!("usage: cim-manifest <modsDir> [out.json] [--reloc]");
        std::process::exit(2);
    }
    let dir = std::path::Path::new(&args[1]);
    let out = args.get(2).filter(|a| !a.starts_with("--"));
    let want_reloc = args.iter().any(|a| a == "--reloc");

    let scan_started = Instant::now();
    let (mut scanned, stats) = scan_dir_full(dir); // ScannedMod entries drive the bench below
    let layer_at = cim_modscan::loader::prepend_layer_neoforge(&mut scanned);
    let failed = stats.failed;
    let scan_elapsed = scan_started.elapsed();

    let manifest = match build_layer_manifest(&scanned) {
        Ok(mut m) => {
            m.at_entries = stats.at_lines;
            m.at_entries.extend(layer_at);
            m
        }
        Err(e) => {
            eprintln!("manifest error: {e}");
            std::process::exit(1);
        }
    };

    let json = serde_json::to_string(&manifest).expect("serialize manifest");
    let bytes: usize = scanned.iter().map(|m| m.size as usize).sum();
    let class_count: usize = scanned.iter().map(|m| m.classes.len()).sum();
    eprintln!(
        "scanned {} jar(s) ({failed} failed), {bytes} bytes, {class_count} classes in {:?} ({:.1} MB/s)",
        scanned.len(),
        scan_elapsed,
        bytes as f64 / 1e6 / scan_elapsed.as_secs_f64().max(1e-9),
    );
    for m in &manifest.mods {
        eprintln!(
            "mod {}: version={} file={} entrypoints={} classes={} annotations={}",
            m.id,
            m.version,
            m.file,
            m.entrypoints.len(),
            m.classes.len(),
            m.annotations.len()
        );
    }

    if want_reloc {
        let table = build_relocation_table(&manifest);
        eprintln!(
            "relocation table: {} prefix rule(s), {} exact entr(ies)",
            table.rules().len(),
            table.exact_count()
        );
        let mut classes_seen = 0usize;
        let mut classes_rewritten = 0usize;
        let mut bytes_in = 0u64;
        let bench_started = Instant::now();
        for m in &scanned {
            let Some(path) = &m.path else { continue };
            let Ok(file) = std::fs::File::open(path) else { continue };
            let Ok(mut zip) = zip::ZipArchive::new(file) else { continue };
            let names: Vec<String> = zip.file_names().map(str::to_string).collect();
            for name in names {
                if !name.ends_with(".class") {
                    continue;
                }
                let Ok(mut entry) = zip.by_name(&name) else { continue };
                let mut buf = Vec::with_capacity(entry.size() as usize);
                if entry.read_to_end(&mut buf).is_err() {
                    continue;
                }
                let internal = name.trim_end_matches(".class");
                classes_seen += 1;
                bytes_in += buf.len() as u64;
                match cim_reloc::relocate_class(&table, internal, &buf) {
                    Ok(out) => {
                        if out.len() != buf.len() {
                            classes_rewritten += 1;
                        }
                    }
                    Err(e) => eprintln!("reloc error {internal}: {e}"),
                }
            }
        }
        let elapsed = bench_started.elapsed();
        eprintln!(
            "relocation bench: {classes_seen} classes ({classes_rewritten} rewritten, {bytes_in} bytes) in {:?} — {:.0} classes/s, {:.1} MB/s",
            elapsed,
            classes_seen as f64 / elapsed.as_secs_f64().max(1e-9),
            bytes_in as f64 / 1e6 / elapsed.as_secs_f64().max(1e-9),
        );
    }

    match out {
        Some(path) => {
            std::fs::write(path, &json).expect("write manifest");
            eprintln!("manifest written: {path} ({} bytes)", json.len());
        }
        None => {
            let mut stdout = std::io::stdout();
            stdout.write_all(json.as_bytes()).expect("stdout");
            stdout.write_all(b"\n").expect("stdout");
        }
    }
}
