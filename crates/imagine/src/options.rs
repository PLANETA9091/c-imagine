//! Runtime options parsing (`options` string passed to `cplugin_init`).
//!
//! CRUSSTY passes a `;`-separated key=value list such as
//! `modules=<dir>;versions=<dir>;kernel=<jar>`. We extract what the loader
//! needs and derive the server root from the modules dir.

use std::ffi::CStr;
use std::path::PathBuf;

#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct ModuleOptions {
    /// Directory containing installed CRUSSTY modules.
    pub modules_dir: Option<PathBuf>,
    /// Kernel jar path (the Paper jar we are injected into).
    pub kernel: String,
    /// Explicit mods dir override (rare; defaults to `<server root>/mods`).
    pub mods_dir: Option<PathBuf>,
}

/// Parse the raw options pointer. Null-safe; never panics.
///
/// # Safety
/// `options` must be null or a valid NUL-terminated C string.
pub unsafe fn parse_options(options: *const std::ffi::c_char) -> ModuleOptions {
    let raw = if options.is_null() {
        String::new()
    } else {
        unsafe { CStr::from_ptr(options) }
            .to_string_lossy()
            .into_owned()
    };
    parse_str(&raw)
}

/// Parse the `;`-separated `key=value` option list.
pub fn parse_str(raw: &str) -> ModuleOptions {
    let mut opts = ModuleOptions {
        kernel: String::new(),
        ..ModuleOptions::default()
    };
    for part in raw.split(';') {
        let part = part.trim();
        if let Some((key, value)) = part.split_once('=') {
            match key.trim() {
                "modules" => {
                    let p = PathBuf::from(value.trim());
                    if p.as_os_str().is_empty() {
                        opts.modules_dir = None;
                    } else {
                        opts.modules_dir = Some(p);
                    }
                }
                "kernel" => opts.kernel = value.trim().to_string(),
                "mods" => {
                    let p = PathBuf::from(value.trim());
                    if p.as_os_str().is_empty() {
                        opts.mods_dir = None;
                    } else {
                        opts.mods_dir = Some(p);
                    }
                }
                _ => {} // unknown keys ignored (forward compatibility)
            }
        }
    }
    opts
}

impl ModuleOptions {
    /// Server root inferred from the modules dir (`<root>/modules` → `<root>`).
    /// Falls back to the current dir when unknown.
    /// M1 wires this into layer install paths; kept exercised by tests.
    #[cfg_attr(not(test), expect(dead_code))]
    pub fn server_root(&self) -> PathBuf {
        self.modules_dir
            .as_ref()
            .and_then(|p| p.ancestors().nth(1).map(PathBuf::from))
            .unwrap_or_else(|| PathBuf::from("."))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_reference_options() {
        let opts = parse_str("modules=/srv/modules;versions=/srv/versions;kernel=/srv/paper.jar");
        assert_eq!(opts.modules_dir, Some(PathBuf::from("/srv/modules")));
        assert_eq!(opts.kernel, "/srv/paper.jar");
        assert_eq!(opts.server_root(), PathBuf::from("/srv"));
    }

    #[test]
    fn empty_and_null_options_are_default() {
        assert_eq!(parse_str(""), ModuleOptions::default());
        let via_ptr = unsafe { parse_options(std::ptr::null()) };
        assert_eq!(via_ptr, ModuleOptions::default());
    }

    #[test]
    fn unknown_keys_ignored_mods_override_honored() {
        let opts = parse_str("future=key;mods=/custom/mods");
        assert_eq!(opts.mods_dir, Some(PathBuf::from("/custom/mods")));
        assert_eq!(opts.kernel, "");
    }

    #[test]
    fn server_root_falls_back_to_cwd() {
        let opts = ModuleOptions::default();
        assert_eq!(opts.server_root(), PathBuf::from("."));
    }
}
