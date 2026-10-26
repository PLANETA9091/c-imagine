//! Class-file rewrite tests against hand-assembled fixtures.
//!
//! No JDK is needed (repo law): the fixtures are valid minimal class files
//! built byte-by-byte here, covering the constant-pool shapes the relocation
//! stage must handle without corrupting shared entries.

use cim_reloc::classfile::ClassFile;
use cim_reloc::{relocate_class, RelocationTable};

/// Pool entry spec: (tag, payload) or a placeholder slot (None) after
/// Long/Double entries.
type Slot = Option<(u8, Vec<u8>)>;

fn utf8(s: &str) -> Slot {
    // On disk a CONSTANT_Utf8 is tag + u2 length + bytes.
    let mut payload = (s.len() as u16).to_be_bytes().to_vec();
    payload.extend_from_slice(s.as_bytes());
    Some((1, payload))
}

fn class(name_idx: u16) -> Slot {
    Some((7, name_idx.to_be_bytes().to_vec()))
}

fn string(str_idx: u16) -> Slot {
    Some((8, str_idx.to_be_bytes().to_vec()))
}

fn name_and_type(name_idx: u16, desc_idx: u16) -> Slot {
    Some((
        12,
        [name_idx.to_be_bytes(), desc_idx.to_be_bytes()].concat(),
    ))
}

fn method_ref(class_idx: u16, nat_idx: u16) -> Slot {
    Some((
        10,
        [class_idx.to_be_bytes(), nat_idx.to_be_bytes()].concat(),
    ))
}

/// Assemble a minimal class file from pool slots + a standard body.
/// The body is `access u2, this u2, super u2, interfaces u2(count=0),
/// fields u2(0), methods u2(0), attributes u2(0)`.
fn build_class(slots: &[Slot], this: u16) -> Vec<u8> {
    let mut out = Vec::new();
    out.extend_from_slice(&[0xCA, 0xFE, 0xBA, 0xBE]);
    out.extend_from_slice(&0u16.to_be_bytes()); // minor
    out.extend_from_slice(&61u16.to_be_bytes()); // major
    out.extend_from_slice(&((slots.len() + 1) as u16).to_be_bytes()); // cp count
    for slot in slots {
        match slot {
            Some((tag, data)) => {
                out.push(*tag);
                out.extend_from_slice(data);
            }
            None => { /* placeholder after long/double: no bytes on disk */ }
        }
    }
    out.extend_from_slice(&0x0021u16.to_be_bytes()); // ACC_PUBLIC | ACC_SUPER
    out.extend_from_slice(&this.to_be_bytes());
    out.extend_from_slice(&2u16.to_be_bytes()); // super = slot 2
    out.extend_from_slice(&0u16.to_be_bytes()); // interfaces
    out.extend_from_slice(&0u16.to_be_bytes()); // fields
    out.extend_from_slice(&0u16.to_be_bytes()); // methods
    out.extend_from_slice(&0u16.to_be_bytes()); // attributes
    out
}

/// Walk the pool like the parser does and return the tag byte offset of
/// `slot` (1-based).
fn slot_tag_offset(bytes: &[u8], target: usize) -> usize {
    let count = u16::from_be_bytes([bytes[8], bytes[9]]) as usize;
    let mut pos = 10usize;
    let mut i = 1usize;
    while i < count {
        if i == target {
            return pos;
        }
        let tag = bytes[pos];
        pos += 1;
        match tag {
            1 => {
                let len = u16::from_be_bytes([bytes[pos], bytes[pos + 1]]) as usize;
                pos += 2 + len;
            }
            5 | 6 => pos += 8,
            3 | 4 | 9 | 10 | 11 | 17 | 18 => pos += 4,
            15 => pos += 3,
            7 | 8 | 16 | 19 | 20 => pos += 2,
            12 => pos += 4,
            other => panic!("fixture produced unexpected tag {other}"),
        }
        i += 1;
        if tag == 5 || tag == 6 {
            i += 1; // placeholder slot
        }
    }
    panic!("slot {target} not reached");
}

#[test]
fn rewrite_is_exact_and_preserves_shared_entries() {
    let table = RelocationTable::layer_default();

    // Slot layout (1-based):
    //  1 utf8 "net/minecraft/server/Main"               <- class name (slot 2)
    //  2 class  -> 1                                      AND string literal (slot 10)
    //  3 utf8 "java/lang/Object"
    //  4 class  -> 3                                    <- super
    //  5 utf8 "(Lnet/minecraft/world/item/Item;)V"      <- NAT descriptor (slot 7)
    //  6 utf8 "m"
    //  7 nat    6, 5
    //  8 methodref 2, 7
    //  9 utf8 "Lnet/minecraft/world/item/Item;"         <- annotation-style, never rewritten
    // 10 string -> 1                                    <- string literal, must survive
    // 11 utf8 "unused"
    let slots = vec![
        utf8("net/minecraft/server/Main"),          // 1
        class(1),                                   // 2
        utf8("java/lang/Object"),                   // 3
        class(3),                                   // 4
        utf8("(Lnet/minecraft/world/item/Item;)V"), // 5
        utf8("m"),                                  // 6
        name_and_type(6, 5),                        // 7
        method_ref(2, 7),                           // 8
        utf8("Lnet/minecraft/world/item/Item;"),    // 9
        string(1),                                  // 10
        utf8("unused"),                             // 11
    ];
    let bytes = build_class(&slots, 2);
    let original = ClassFile::parse(&bytes).expect("fixture parses");
    assert_eq!(original.pool_slot_count(), 12);

    let (out, stats) = original.rewrite(&table).expect("rewrite ok");
    assert_eq!(stats.classes_renamed, 1, "only slot 2 relocates");
    assert_eq!(stats.descriptors_rewritten, 1, "only the NAT descriptor");
    assert_eq!(stats.utf8_added, 2, "relocated class name + descriptor");

    let reparsed = ClassFile::parse(&out).expect("rewritten file re-parses");
    let names = reparsed.class_names();
    assert!(names.contains(&"cim/shadow/mc1211/net/minecraft/server/Main".to_string()));
    assert_eq!(
        names
            .iter()
            .filter(|n| n.as_str() == "java/lang/Object")
            .count(),
        1
    );

    // Original slots are intact (never mutated in place).
    assert_eq!(
        reparsed.utf8_at(1).unwrap(),
        b"net/minecraft/server/Main" as &[u8],
        "original class-name utf8 untouched"
    );
    assert_eq!(
        reparsed.utf8_at(5).unwrap(),
        b"(Lnet/minecraft/world/item/Item;)V" as &[u8],
        "original descriptor utf8 untouched"
    );
    assert_eq!(
        reparsed.utf8_at(9).unwrap(),
        b"Lnet/minecraft/world/item/Item;" as &[u8],
        "annotation-style utf8 untouched"
    );

    // The string literal (slot 10) still points at the ORIGINAL name.
    let str_target = u16::from_be_bytes([
        out[slot_tag_offset(&out, 10) + 1],
        out[slot_tag_offset(&out, 10) + 2],
    ]);
    assert_eq!(
        reparsed.utf8_at(str_target).unwrap(),
        b"net/minecraft/server/Main" as &[u8]
    );

    // Appended utf8s (slots after the original pool) carry the relocated
    // forms; original slots keep their indices.
    let orig_count = original.pool_slot_count() as u16;
    let new_count = reparsed.pool_slot_count() as u16;
    assert_eq!(new_count - orig_count, 2);
    let appended: Vec<String> = (orig_count..new_count)
        .map(|i| String::from_utf8_lossy(reparsed.utf8_at(i).unwrap()).into_owned())
        .collect();
    assert!(appended.contains(&"cim/shadow/mc1211/net/minecraft/server/Main".to_string()));
    assert!(appended
        .iter()
        .any(|s| s == "(Lcim/shadow/mc1211/net/minecraft/world/item/Item;)V"));

    // Body copied verbatim: nothing after the pool changed.
    assert_eq!(body_of(&bytes), body_of(&out));
}

#[test]
fn long_double_slots_survive_rebuild() {
    let table = RelocationTable::layer_default();
    // 1 utf8 class name, 2 class, 3 utf8 super, 4 class,
    // 5 long (5+6), 7 double (7+8), 9 utf8 (same content as 1 — shared bytes)
    let slots: Vec<Slot> = vec![
        utf8("net/minecraft/world/Level"),                          // 1
        class(1),                                                   // 2
        utf8("java/lang/Object"),                                   // 3
        class(3),                                                   // 4
        Some((5, 0x1122_3344_5566_7788u64.to_be_bytes().to_vec())), // 5
        None,                                                       // 6 placeholder
        Some((6, 1.5f64.to_bits().to_be_bytes().to_vec())),         // 7
        None,                                                       // 8 placeholder
        utf8("net/minecraft/world/Level"),                          // 9
    ];
    let bytes = build_class(&slots, 2);
    let parsed = ClassFile::parse(&bytes).expect("parses");
    assert_eq!(parsed.pool_slot_count(), 10, "9 slots + invalid slot 0");

    let (out, stats) = parsed.rewrite(&table).expect("rewrite");
    assert_eq!(stats.classes_renamed, 1, "slot 2 only; slot 9 is utf8");
    let reparsed = ClassFile::parse(&out).expect("re-parses");
    assert!(out
        .windows(8)
        .any(|w| w == 0x1122_3344_5566_7788u64.to_be_bytes()));
    assert!(out.windows(8).any(|w| w == 1.5f64.to_bits().to_be_bytes()));
    assert_eq!(
        reparsed.class_names(),
        vec![
            "cim/shadow/mc1211/net/minecraft/world/Level".to_string(),
            "java/lang/Object".to_string()
        ]
    );
    // The shared-content utf8 at slot 9 is untouched.
    assert_eq!(
        reparsed.utf8_at(9).unwrap(),
        b"net/minecraft/world/Level" as &[u8]
    );
}

#[test]
fn relocate_class_passthrough_for_out_of_space_names() {
    let table = RelocationTable::layer_default();
    let bytes = vec![0xCA, 0xFE, 0xBA, 0xBE, 1, 2, 3];
    let out = relocate_class(&table, "dev/cim/layer/LayerMain", &bytes).expect("passthrough");
    assert_eq!(out, bytes, "out-of-space classes are copied unchanged");
}

#[test]
fn relocation_is_idempotent() {
    let table = RelocationTable::layer_default();
    let slots = vec![
        utf8("net/minecraft/server/Main"),          // 1
        class(1),                                   // 2
        utf8("java/lang/Object"),                   // 3
        class(3),                                   // 4
        utf8("(Lnet/minecraft/world/item/Item;)V"), // 5
        utf8("m"),                                  // 6
        name_and_type(6, 5),                        // 7
        method_ref(2, 7),                           // 8
    ];
    let bytes = build_class(&slots, 2);
    let once = relocate_class(&table, "net/minecraft/server/Main", &bytes).expect("first pass");
    let twice = relocate_class(&table, "cim/shadow/mc1211/net/minecraft/server/Main", &once)
        .expect("second pass");
    assert_eq!(once, twice, "already-relocated classes are passthrough");
}

#[test]
fn bad_magic_and_unknown_tags_are_rejected() {
    let table = RelocationTable::layer_default();
    let junk = [0u8; 32];
    assert!(relocate_class(&table, "net/minecraft/X", &junk).is_err());

    let slots: Vec<Slot> = vec![
        utf8("net/minecraft/X"),  // 1
        class(1),                 // 2
        utf8("java/lang/Object"), // 3
        class(3),                 // 4
        utf8("tail"),             // 5
    ];
    let mut bytes = build_class(&slots, 2);
    let tag_pos = slot_tag_offset(&bytes, 5);
    assert_eq!(bytes[tag_pos], 1, "slot 5 is the utf8 we think it is");
    bytes[tag_pos] = 99; // unknown tag
    assert!(relocate_class(&table, "net/minecraft/X", &bytes).is_err());
}

#[test]
fn truncated_files_are_rejected() {
    let table = RelocationTable::layer_default();
    let slots = vec![
        utf8("net/minecraft/server/Main"),
        class(1),
        utf8("java/lang/Object"),
        class(3),
    ];
    let bytes = build_class(&slots, 2);
    let cut = &bytes[..body_start(&bytes) - 2]; // cut into the last pool entry
    assert!(relocate_class(&table, "net/minecraft/server/Main", cut).is_err());
}

#[test]
fn array_class_names_relocate_as_descriptors() {
    let table = RelocationTable::layer_default();
    let slots = vec![
        utf8("[Lnet/minecraft/world/entity/Entity;"), // 1
        class(1),                                     // 2
        utf8("java/lang/Object"),                     // 3
        class(3),                                     // 4
    ];
    let bytes = build_class(&slots, 2);
    let parsed = ClassFile::parse(&bytes).expect("parses");
    let (out, stats) = parsed.rewrite(&table).expect("rewrite");
    assert_eq!(stats.classes_renamed, 1);
    assert!(String::from_utf8_lossy(&out)
        .contains("cim/shadow/mc1211/net/minecraft/world/entity/Entity"));
}

// ---- helpers --------------------------------------------------------------

fn body_start(bytes: &[u8]) -> usize {
    let count = u16::from_be_bytes([bytes[8], bytes[9]]) as usize;
    let mut pos = 10usize;
    let mut i = 1usize;
    while i < count {
        let tag = bytes[pos];
        pos += 1;
        match tag {
            1 => {
                let len = u16::from_be_bytes([bytes[pos], bytes[pos + 1]]) as usize;
                pos += 2 + len;
            }
            5 | 6 => pos += 8,
            3 | 4 | 9 | 10 | 11 | 17 | 18 => pos += 4,
            15 => pos += 3,
            7 | 8 | 16 | 19 | 20 => pos += 2,
            12 => pos += 4,
            other => panic!("unexpected tag {other}"),
        }
        i += 1;
        if tag == 5 || tag == 6 {
            i += 1;
        }
    }
    pos
}

fn body_of(bytes: &[u8]) -> Vec<u8> {
    bytes[body_start(bytes)..].to_vec()
}
