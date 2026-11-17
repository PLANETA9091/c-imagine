//! Bundled mods: jars shipped inside the module directory (`modules/imagine/`)
//! that the loader installs into `mods/` before the scan, so an ordinary install
//! (unzip the module zip, start the server) runs them without a manual copy.
//!
//! The FML layer discovers mods only through the `mods/` scan in `modwatch`, so
//! the bundled jar has to land there. Install is idempotent: a jar already in
//! `mods/` with identical bytes is left alone. Problems are logged, never fatal:
//! a missing bundled jar must not stop the server from booting.

use std::path::{Path, PathBuf};

/// Jars shipped in `modules/imagine/` and installed into `mods/`. Each name must
/// match the file the packaging script copies into the zip.
pub const BUNDLED_MODS: &[&str] = &["cimlive-1.0.0.jar"];

/// The directory this module was loaded from (`<root>/modules/imagine`).
///
/// Prefers the real `libimagine.so` location from `/proc/self/maps`; falls back to
/// `<modules dir>/imagine` when only a bare file name is known.
pub fn module_dir(module_lib: &str, modules_dir: Option<&Path>) -> Option<PathBuf> {
    Path::new(module_lib)
        .parent()
        .filter(|p| !p.as_os_str().is_empty())
        .map(PathBuf::from)
        .or_else(|| modules_dir.map(|m| m.join("imagine")))
}

/// Copy each `names` entry from `module_dir` into `mods_dir` when it is missing or
/// differs. Returns the names that were written. A name absent from `module_dir`
/// (dev builds without the jar) is skipped silently.
pub fn install_bundled_mods(module_dir: &Path, mods_dir: &Path, names: &[&str]) -> Vec<String> {
    let mut written = Vec::new();
    for name in names {
        let src = module_dir.join(name);
        if !src.is_file() {
            continue;
        }
        let dst = mods_dir.join(name);
        match install_one(&src, &dst) {
            Ok(true) => {
                eprintln!(
                    "{} bundled mod installed: {} -> {}",
                    cim_core::LOG_PREFIX,
                    src.display(),
                    dst.display()
                );
                written.push((*name).to_string());
            }
            Ok(false) => {}
            Err(e) => eprintln!(
                "{} bundled mod {name}: install failed: {e}",
                cim_core::LOG_PREFIX
            ),
        }
    }
    written
}

/// Returns `Ok(true)` when `dst` was written, `Ok(false)` when it already matched.
/// The copy goes through a `.part` file and a rename so the scan never sees a
/// half-written jar.
fn install_one(src: &Path, dst: &Path) -> std::io::Result<bool> {
    if same_bytes(src, dst)? {
        return Ok(false);
    }
    if let Some(parent) = dst.parent() {
        std::fs::create_dir_all(parent)?;
    }
    let part = PathBuf::from(format!("{}.part", dst.display()));
    std::fs::copy(src, &part)?;
    std::fs::rename(&part, dst)?;
    Ok(true)
}

/// Whether `dst` exists and holds exactly the bytes of `src`. A missing `dst` is
/// `Ok(false)`; any other read error is returned.
fn same_bytes(src: &Path, dst: &Path) -> std::io::Result<bool> {
    let dst_len = match std::fs::metadata(dst) {
        Ok(m) => m.len(),
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => return Ok(false),
        Err(e) => return Err(e),
    };
    if dst_len != std::fs::metadata(src)?.len() {
        return Ok(false);
    }
    Ok(std::fs::read(src)? == std::fs::read(dst)?)
}

#[cfg(test)]
mod tests {
    use super::*;

    /// A fresh scratch directory under the system temp dir, unique per test.
    fn scratch(tag: &str) -> PathBuf {
        let nanos = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map(|d| d.as_nanos())
            .unwrap_or(0);
        let dir =
            std::env::temp_dir().join(format!("cim-bundled-{tag}-{}-{nanos}", std::process::id()));
        std::fs::create_dir_all(&dir).expect("scratch dir");
        dir
    }

    #[test]
    fn module_dir_is_the_library_parent() {
        assert_eq!(
            module_dir(
                "/srv/modules/imagine/libimagine.so",
                Some(Path::new("/x/modules"))
            ),
            Some(PathBuf::from("/srv/modules/imagine"))
        );
    }

    #[test]
    fn module_dir_falls_back_to_modules_dir_for_bare_names() {
        assert_eq!(
            module_dir("libimagine.so", Some(Path::new("/srv/modules"))),
            Some(PathBuf::from("/srv/modules/imagine"))
        );
        assert_eq!(module_dir("libimagine.so", None), None);
    }

    #[test]
    fn installs_missing_then_skips_identical() {
        let root = scratch("install");
        let module = root.join("modules/imagine");
        let mods = root.join("mods");
        std::fs::create_dir_all(&module).unwrap();
        std::fs::write(module.join("cimlive-1.0.0.jar"), b"jar-bytes").unwrap();

        let first = install_bundled_mods(&module, &mods, BUNDLED_MODS);
        assert_eq!(first, vec!["cimlive-1.0.0.jar".to_string()]);
        assert_eq!(
            std::fs::read(mods.join("cimlive-1.0.0.jar")).unwrap(),
            b"jar-bytes"
        );
        assert!(!mods.join("cimlive-1.0.0.jar.part").exists());

        let second = install_bundled_mods(&module, &mods, BUNDLED_MODS);
        assert!(second.is_empty(), "identical jar must not be rewritten");
        let _ = std::fs::remove_dir_all(&root);
    }

    #[test]
    fn replaces_a_different_jar_of_the_same_name() {
        let root = scratch("replace");
        let module = root.join("modules/imagine");
        let mods = root.join("mods");
        std::fs::create_dir_all(&module).unwrap();
        std::fs::create_dir_all(&mods).unwrap();
        std::fs::write(module.join("cimlive-1.0.0.jar"), b"new-build").unwrap();
        std::fs::write(mods.join("cimlive-1.0.0.jar"), b"old").unwrap();

        let written = install_bundled_mods(&module, &mods, BUNDLED_MODS);
        assert_eq!(written.len(), 1);
        assert_eq!(
            std::fs::read(mods.join("cimlive-1.0.0.jar")).unwrap(),
            b"new-build"
        );
        let _ = std::fs::remove_dir_all(&root);
    }

    #[test]
    fn absent_bundled_jar_is_skipped_quietly() {
        let root = scratch("absent");
        let module = root.join("modules/imagine");
        std::fs::create_dir_all(&module).unwrap();
        let written = install_bundled_mods(&module, &root.join("mods"), BUNDLED_MODS);
        assert!(written.is_empty());
        assert!(!root.join("mods").exists());
        let _ = std::fs::remove_dir_all(&root);
    }
}
