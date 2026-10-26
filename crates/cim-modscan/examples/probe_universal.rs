fn main() {
    let dir = std::path::Path::new("/home/z/my-project/artifacts/realmods");
    let (mut mods, _) = cim_modscan::scan_dir_full(dir);
    std::env::set_var("CIM_LAYER_DIR", "/home/z/my-project/artifacts/layer1211/libraries");
    cim_modscan::loader::prepend_layer_neoforge(&mut mods);
    let m = cim_modscan::build_layer_manifest(&mods).unwrap();
    for e in &m.mods {
        if e.id == "neoforge" {
            println!("neoforge jar: '{}', layer: {}, classes: {}", e.jar, e.layer, e.classes.len());
        }
    }
    println!("order: {:?}", m.load_order);
}
