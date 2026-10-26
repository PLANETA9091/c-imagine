//! Real-jar probe (skipped unless `CIM_PROBE_JAR` points at a mod jar).

use std::io::{Cursor, Read};

#[test]
fn probe_real_jar() {
    let Ok(path) = std::env::var("CIM_PROBE_JAR") else {
        eprintln!("CIM_PROBE_JAR not set - skipping");
        return;
    };
    let file = std::fs::File::open(&path).expect("open jar");
    let scanned = cim_modscan::scan_jar(std::io::BufReader::new(file)).expect("scan");
    println!("file: {path}");
    println!("toml ids: {:?}", scanned.toml.as_ref().map(|t| t.mods.iter().map(|m| m.mod_id.clone()).collect::<Vec<_>>()));
    println!("code ids: {:?}", scanned.code_mod_ids);
    println!("entrypoints: {:?}", scanned.entrypoint_classes);
    println!("classes: {}", scanned.classes.len());
    println!("annotation hits: {}", scanned.annotation_hits.len());
    for hit in scanned.annotation_hits.iter().take(20) {
        println!(
            "  {} member={:?} ann={} values={}",
            hit.class_internal,
            hit.member,
            hit.annotation.type_descriptor,
            hit.annotation.values.len()
        );
    }
    let file = std::fs::File::open(&path).expect("open jar 2");
    let mut zip = zip::ZipArchive::new(file).expect("zip");
    let names: Vec<String> = zip.file_names().map(str::to_string).collect();
    let mut parse_fail = 0usize;
    let mut hit_but_failed = Vec::new();
    for name in names {
        if !name.ends_with(".class") {
            continue;
        }
        let mut entry = zip.by_name(&name).unwrap();
        let mut buf = Vec::with_capacity(entry.size() as usize);
        entry.read_to_end(&mut buf).unwrap();
        match cim_modscan::classfile::parse_class(&buf) {
            Ok(info) => {
                let has_mod = info
                    .annotations
                    .iter()
                    .any(|a| a.type_descriptor.ends_with("/fml/common/Mod;"));
                if has_mod {
                    println!("@Mod on {} (parsed ok)", info.this_class);
                }
            }
            Err(e) => {
                parse_fail += 1;
                if buf.windows(30).any(|w| w == b"Lnet/neoforged/fml/common/Mod;") {
                    hit_but_failed.push((name, e.to_string()));
                }
            }
        }
    }
    println!("parse failures: {parse_fail}");
    // dump @Mod annotation values on the entrypoint class
    {
        let file = std::fs::File::open(&path).unwrap();
        let mut zip = zip::ZipArchive::new(std::io::BufReader::new(file)).unwrap();
        let mut buf = Vec::new();
        {
            let mut entry = zip.by_name("snownee/jade/util/CommonProxy.class").unwrap();
            entry.read_to_end(&mut buf).unwrap();
        }
        if let Ok(info) = cim_modscan::classfile::parse_class(&buf) {
            for a in &info.annotations {
                let pairs: Vec<(String, String)> = a
                    .values
                    .iter()
                    .map(|(k, v)| (k.clone(), format!("{v:?}")))
                    .collect();
                println!("ANN {} pairs={pairs:?}", a.type_descriptor);
            }
        }
    }
    for (n, e) in hit_but_failed.iter().take(10) {
        println!("  FAILED-WITH-MOD: {n}: {e}");
    }
}
