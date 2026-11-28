mod support;
use cim_classgraph::{build, cache_key, load_or_build, Index};
use std::path::PathBuf;
use support::{class_file, jar};

fn tmpdir(tag: &str) -> PathBuf {
    let d = std::env::temp_dir().join(format!("cim-classgraph-test-{tag}-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&d);
    std::fs::create_dir_all(&d).unwrap();
    d
}

/// Fixture hierarchy:
///   Mod1 -> Base -> Object(root)        Base implements Iface
///   Mod2 (nested jar) -> Mod1, Mod2 implements Marker
///   Cyc1 -> Cyc2 -> Cyc1 (cycle, must terminate)
fn fixture_jars(dir: &std::path::Path) -> (PathBuf, PathBuf) {
    let inner = jar(&[
        ("pkg/Mod2.class", class_file("pkg/Mod2", Some("pkg/Mod1"), &["pkg/Marker"], 0x21, false)),
        ("pkg/Marker.class", class_file("pkg/Marker", None, &[], 0x601, false)),
    ]);
    let outer = jar(&[
        ("pkg/Mod1.class", class_file("pkg/Mod1", Some("pkg/Base"), &[], 0x21, true)),
        ("pkg/Base.class", class_file("pkg/Base", None, &["pkg/Iface"], 0x21, false)),
        ("pkg/Iface.class", class_file("pkg/Iface", None, &[], 0x601, false)),
        ("pkg/Cyc1.class", class_file("pkg/Cyc1", Some("pkg/Cyc2"), &[], 0x21, false)),
        ("pkg/Cyc2.class", class_file("pkg/Cyc2", Some("pkg/Cyc1"), &[], 0x21, false)),
        ("META-INF/jarjar/inner.jar", inner),
        ("META-INF/MANIFEST.MF", b"Manifest-Version: 1.0\n".to_vec()),
        ("pkg/Broken.class", vec![1, 2, 3]), // unparsable class must be skipped
    ]);
    let a = dir.join("outer.jar");
    std::fs::write(&a, outer).unwrap();
    let b = dir.join("dup.jar");
    // duplicate Base with a different super: first jar wins
    std::fs::write(&b, jar(&[("pkg/Base.class", class_file("pkg/Base", Some("pkg/Other"), &[], 0x21, false))])).unwrap();
    (a, b)
}

fn idx(dir: &std::path::Path) -> Index {
    let (a, b) = fixture_jars(dir);
    build(&[a, b])
}

#[test]
fn transitive_subclass_across_nested_jar() {
    let d = tmpdir("trans");
    let i = idx(&d);
    assert!(i.is_subclass("pkg/Mod2", "pkg/Mod1"));
    assert!(i.is_subclass("pkg/Mod2", "pkg/Base")); // through Mod1
    assert!(i.is_subclass("pkg/Mod2", "pkg/Iface")); // through Base's interface
    assert!(i.is_subclass("pkg/Mod2", "pkg/Marker")); // direct interface in nested jar
    assert!(!i.is_subclass("pkg/Base", "pkg/Mod1"));
    assert!(!i.is_subclass("pkg/Mod1", "pkg/Mod1")); // strict
    assert!(i.is_a("pkg/Mod1", "pkg/Mod1"));
}

#[test]
fn ancestors_and_subclasses() {
    let d = tmpdir("anc");
    let i = idx(&d);
    let anc = i.ancestors("pkg/Mod2");
    assert_eq!(anc[0], "pkg/Mod1");
    for want in ["pkg/Base", "pkg/Iface", "pkg/Marker"] {
        assert!(anc.iter().any(|a| a == want), "missing {want}: {anc:?}");
    }
    assert_eq!(i.subclasses_of("pkg/Base"), vec!["pkg/Mod1", "pkg/Mod2"]);
    assert!(i.subclasses_of("pkg/Nope").is_empty());
}

#[test]
fn cycles_terminate() {
    let d = tmpdir("cyc");
    let i = idx(&d);
    assert!(i.is_subclass("pkg/Cyc1", "pkg/Cyc2"));
    assert!(!i.is_subclass("pkg/Cyc1", "pkg/Missing"));
    assert_eq!(i.ancestors("pkg/Cyc1").len(), 2);
}

#[test]
fn first_jar_wins_and_source_is_tracked() {
    let d = tmpdir("dup");
    let i = idx(&d);
    assert_eq!(i.get("pkg/Base").unwrap().super_name, None);
    assert!(i.source_of("pkg/Base").unwrap().ends_with("outer.jar"));
    assert!(i.source_of("pkg/Mod2").unwrap().contains("outer.jar!/META-INF/jarjar/inner.jar"));
    assert!(i.get("pkg/Broken").is_none());
}

#[test]
fn classify_batch_bitmask() {
    let d = tmpdir("cls");
    let i = idx(&d);
    let got = i.classify(&["pkg/Mod1", "pkg/Mod2", "pkg/Base", "pkg/Unknown"], &["pkg/Base", "pkg/Iface", "pkg/Mod1"]);
    assert_eq!(got, vec![0b011, 0b111, 0b010, 0b000]);
}

#[test]
fn missing_jar_is_skipped() {
    let d = tmpdir("miss");
    let i = build(&[d.join("nope.jar")]);
    assert!(i.is_empty());
}

#[test]
fn cache_roundtrip_and_invalidation() {
    let d = tmpdir("cache");
    let (a, b) = fixture_jars(&d);
    let jars = vec![a.clone(), b.clone()];
    let cache = d.join("cache");
    let first = load_or_build(&jars, &cache);
    let files: Vec<_> = std::fs::read_dir(&cache).unwrap().collect();
    assert_eq!(files.len(), 1);
    let second = load_or_build(&jars, &cache);
    assert_eq!(second.len(), first.len());
    assert_eq!(second.get("pkg/Mod2"), first.get("pkg/Mod2"));
    assert!(second.is_subclass("pkg/Mod2", "pkg/Iface"));
    // Changing an input changes the key: a new cache file appears.
    let key_before = cache_key(&jars);
    std::fs::write(&b, jar(&[("pkg/Z.class", class_file("pkg/Z", None, &[], 0x21, false))])).unwrap();
    assert_ne!(cache_key(&jars), key_before);
    let third = load_or_build(&jars, &cache);
    assert!(third.get("pkg/Z").is_some());
    assert_eq!(std::fs::read_dir(&cache).unwrap().count(), 2);
}

#[test]
fn corrupt_cache_is_rebuilt() {
    let d = tmpdir("corrupt");
    let (a, b) = fixture_jars(&d);
    let jars = vec![a, b];
    let cache = d.join("cache");
    load_or_build(&jars, &cache);
    let f = std::fs::read_dir(&cache).unwrap().next().unwrap().unwrap().path();
    std::fs::write(&f, b"{not json").unwrap();
    let i = load_or_build(&jars, &cache);
    assert!(i.is_subclass("pkg/Mod2", "pkg/Marker"));
}

const FIXTURE_MAPPING: &str = "# official-style fixture\n\
net.minecraft.client.gui.screens.Screen -> a:\n\
\x20   void render(int,int,float) -> render\n\
net.minecraft.client.gui.screens.ConfirmScreen -> b:\n\
net.minecraft.client.gui.screens.Screen$Inner -> a$c:\n";

fn obf_client(dir: &std::path::Path) -> (PathBuf, PathBuf) {
    let jar_path = dir.join("client.jar");
    std::fs::write(
        &jar_path,
        jar(&[
            ("a.class", class_file("a", Some("java/lang/Object"), &[], 0x21, false)),
            ("b.class", class_file("b", Some("a"), &[], 0x21, false)),
            ("a$c.class", class_file("a$c", Some("a"), &[], 0x21, false)),
        ]),
    )
    .unwrap();
    let map_path = dir.join("client.mappings.txt");
    std::fs::write(&map_path, FIXTURE_MAPPING).unwrap();
    (jar_path, map_path)
}

#[test]
fn mapped_jar_exposes_named_classes() {
    let d = tmpdir("mapped");
    let (jar_path, map_path) = obf_client(&d);
    let idx = cim_classgraph::build_mapped(std::slice::from_ref(&jar_path), &[(jar_path.clone(), map_path.clone())]);
    let screen = "net/minecraft/client/gui/screens/Screen";
    let confirm = "net/minecraft/client/gui/screens/ConfirmScreen";
    assert!(idx.get(screen).is_some());
    assert!(idx.get("a").is_none());
    assert_eq!(idx.get(confirm).unwrap().super_name.as_deref(), Some(screen));
    assert!(idx.is_subclass(confirm, screen));
    assert!(idx.is_subclass(&format!("{screen}$Inner"), screen));
    // unmapped superclass keeps its own name
    assert_eq!(idx.get(screen).unwrap().super_name.as_deref(), Some("java/lang/Object"));
}

#[test]
fn unmapped_jar_keeps_obfuscated_names() {
    let d = tmpdir("unmapped");
    let (jar_path, _map) = obf_client(&d);
    let idx = build(&[jar_path]);
    assert!(idx.get("b").is_some());
    assert!(idx.get("net/minecraft/client/gui/screens/ConfirmScreen").is_none());
}

#[test]
fn mapped_cache_key_tracks_mapping_file() {
    let d = tmpdir("mapkey");
    let (jar_path, map_path) = obf_client(&d);
    let pairs = vec![(jar_path.clone(), map_path.clone())];
    let k1 = cim_classgraph::cache_key_mapped(std::slice::from_ref(&jar_path), &pairs);
    assert_ne!(k1, cim_classgraph::cache_key(std::slice::from_ref(&jar_path)));
    std::fs::write(&map_path, format!("{FIXTURE_MAPPING}net.minecraft.Extra -> z:\n")).unwrap();
    assert_ne!(k1, cim_classgraph::cache_key_mapped(std::slice::from_ref(&jar_path), &pairs));
    let cache = d.join("cache");
    let idx = cim_classgraph::load_or_build_mapped(&[jar_path], &pairs, &cache);
    assert!(idx.get("net/minecraft/client/gui/screens/Screen").is_some());
    assert_eq!(std::fs::read_dir(&cache).unwrap().count(), 1);
}
