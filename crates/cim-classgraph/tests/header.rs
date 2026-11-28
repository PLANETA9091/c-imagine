mod support;
use cim_classgraph::{parse_header, HeaderError};
use support::class_file;

#[test]
fn parses_names_super_and_interfaces() {
    let b = class_file("a/b/Foo", Some("a/b/Base"), &["x/Iface", "y/Other"], 0x0021, false);
    let h = parse_header(&b).unwrap();
    assert_eq!(h.name, "a/b/Foo");
    assert_eq!(h.super_name.as_deref(), Some("a/b/Base"));
    assert_eq!(h.interfaces, vec!["x/Iface", "y/Other"]);
    assert_eq!(h.access, 0x0021);
}

#[test]
fn interface_and_object_root_have_no_super() {
    let b = class_file("x/Iface", None, &[], 0x0601, false);
    let h = parse_header(&b).unwrap();
    assert_eq!(h.super_name, None);
    assert!(h.interfaces.is_empty());
}

#[test]
fn long_constants_take_two_slots() {
    let b = class_file("L/Wide", Some("L/Sup"), &["L/I"], 0x0001, true);
    let h = parse_header(&b).unwrap();
    assert_eq!(h.name, "L/Wide");
    assert_eq!(h.super_name.as_deref(), Some("L/Sup"));
    assert_eq!(h.interfaces, vec!["L/I"]);
}

#[test]
fn rejects_bad_magic_and_truncation() {
    let mut b = class_file("A", None, &[], 1, false);
    b[0] = 0;
    assert_eq!(parse_header(&b), Err(HeaderError::BadMagic));
    let good = class_file("A", None, &[], 1, false);
    assert_eq!(parse_header(&good[..12]), Err(HeaderError::Truncated));
}

#[test]
fn rejects_unknown_constant_tag() {
    let mut b = class_file("A", None, &[], 1, false);
    // first pool entry tag sits at offset 10
    b[10] = 99;
    assert!(matches!(parse_header(&b), Err(HeaderError::BadTag(99, 1))));
}

#[test]
fn large_constant_pool_parses_fully() {
    let long: Vec<String> = (0..40).map(|i| format!("p/{}{}", "x".repeat(200), i)).collect();
    let refs: Vec<&str> = long.iter().map(String::as_str).collect();
    let b = class_file("Big/Class", Some("Big/Super"), &refs, 0x21, false);
    assert!(b.len() > 8192);
    let h = parse_header(&b).unwrap();
    assert_eq!(h.interfaces.len(), 40);
    assert_eq!(h.interfaces[39], long[39]);
}
