//! Build script: stage the Java bootstrap jar for embedding.
//!
//! CI order: javac builds `cim-bootstrap.jar` first, then invokes cargo with
//! `CIM_BOOTSTRAP_JAR=<path>` — the real jar is embedded into the module
//! binary. Local builds without the env var embed `embedded/placeholder.jar`
//! (a valid empty zip) so the crate always compiles and tests.

use std::env;
use std::fs;
use std::path::PathBuf;

fn main() {
    println!("cargo:rerun-if-env-changed=CIM_BOOTSTRAP_JAR");
    println!("cargo:rerun-if-changed=embedded/placeholder.jar");

    let out_dir = PathBuf::from(env::var("OUT_DIR").expect("OUT_DIR"));
    let dest = out_dir.join("cim-bootstrap.jar");

    match env::var("CIM_BOOTSTRAP_JAR") {
        Ok(src) => {
            fs::copy(&src, &dest).unwrap_or_else(|e| panic!("copy CIM_BOOTSTRAP_JAR ({src}): {e}"));
        }
        Err(_) => {
            let manifest =
                PathBuf::from(env::var("CARGO_MANIFEST_DIR").expect("CARGO_MANIFEST_DIR"));
            let placeholder = manifest.join("embedded").join("placeholder.jar");
            fs::copy(&placeholder, &dest).unwrap_or_else(|e| panic!("copy placeholder jar: {e}"));
        }
    }
}
