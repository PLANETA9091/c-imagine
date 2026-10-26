//! Real-world relocation smoke test: relocate actual Minecraft 1.21.1
//! classes from a local NeoForge server install.
//!
//! Skips silently when `CIM_LAYER_JAR` is not set (CI unit runs), so the
//! suite stays JDK-free and self-contained. Locally (and in the layer-boot
//! CI job), set it to the Mojmap server jar, e.g.:
//!
//! ```text
//! CIM_LAYER_JAR=layer1211/libraries/net/minecraft/server/1.21.1-20240808.144430/\
//! server-1.21.1-20240808.144430-unpacked.jar cargo test -p cim-reloc --test real_class_smoke
//! ```

use std::io::Read;

use cim_reloc::{relocate_class, RelocationTable};

fn layer_jar() -> Option<std::path::PathBuf> {
    std::env::var_os("CIM_LAYER_JAR").map(std::path::PathBuf::from)
}

fn read_entry(jar: &str, entry: &str) -> Option<Vec<u8>> {
    let f = std::fs::File::open(jar).ok()?;
    let mut z = zip::ZipArchive::new(f).ok()?;
    let mut e = z.by_name(entry).ok()?;
    let mut out = Vec::new();
    e.read_to_end(&mut out).ok()?;
    Some(out)
}

#[test]
fn relocates_real_minecraft_classes() {
    let Some(jar) = layer_jar() else {
        eprintln!("CIM_LAYER_JAR not set — skipping real-class smoke");
        return;
    };
    let jar = jar.to_string_lossy().into_owned();
    let table = RelocationTable::layer_default();

    let targets = [
        "net/minecraft/server/MinecraftServer.class",
        "net/minecraft/core/registries/BuiltInRegistries.class",
        "net/minecraft/world/item/Item.class",
        "net/minecraft/world/level/Level.class",
    ];
    for entry in targets {
        let Some(bytes) = read_entry(&jar, entry) else {
            panic!("{entry} not found in {jar}");
        };
        assert!(bytes.len() > 100, "{entry} suspiciously small");
        let name = entry.trim_end_matches(".class");
        let out = relocate_class(&table, name, &bytes)
            .unwrap_or_else(|e| panic!("relocate {entry}: {e}"));
        assert_ne!(out, bytes, "{entry} must be rewritten");
        // The rewritten file must re-parse and every class entry must live
        // in the shadow space.
        let parsed = cim_reloc::classfile::ClassFile::parse(&out)
            .unwrap_or_else(|e| panic!("reparse {entry}: {e}"));
        let names = parsed.class_names();
        let relocated = names
            .iter()
            .filter(|n| n.starts_with("cim/shadow/mc1211/"))
            .count();
        let leftover = names
            .iter()
            .filter(|n| {
                (n.starts_with("net/minecraft/") || n.starts_with("com/mojang/"))
                    && !n.starts_with("cim/shadow/")
            })
            .count();
        assert!(relocated > 0, "{entry}: no relocated class refs");
        assert_eq!(leftover, 0, "{entry}: unrelocated refs remain: {names:?}");
        // Declared member descriptors (field_info/method_info) must be
        // relocated too — HotSpot's verifier resolves types from them.
        for d in parsed.member_descriptors() {
            let bad = d.contains("Lnet/minecraft/")
                || d.contains("Lcom/mojang/")
                || d.contains("Lnet/neoforged/");
            assert!(!bad, "{entry}: declared descriptor not relocated: {d}");
        }
        eprintln!(
            "{entry}: {} B -> {} B, {} class refs relocated",
            bytes.len(),
            out.len(),
            relocated
        );
    }
}
