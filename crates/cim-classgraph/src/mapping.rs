//! ProGuard-format class mappings (Mojang official `client_mappings`).
//!
//! Class lines look like `net.minecraft.client.gui.screens.Screen -> dsx:`; indented
//! lines are member mappings and are ignored. The map goes obfuscated -> named, in
//! JVM internal form (`net/minecraft/...`), so it can rename `this`/super/interfaces
//! of an obfuscated jar during indexing.

use std::collections::HashMap;

#[derive(Debug, Default, Clone)]
pub struct Mapping {
    obf_to_named: HashMap<String, String>,
}

impl Mapping {
    /// Parse ProGuard text. Unknown or malformed lines are skipped.
    pub fn parse(text: &str) -> Self {
        let mut obf_to_named = HashMap::new();
        for line in text.lines() {
            if line.starts_with(' ') || line.starts_with('\t') || line.starts_with('#') {
                continue;
            }
            let Some((named, rest)) = line.split_once(" -> ") else { continue };
            let Some(obf) = rest.strip_suffix(':') else { continue };
            let named = named.trim().replace('.', "/");
            let obf = obf.trim().replace('.', "/");
            if !named.is_empty() && !obf.is_empty() {
                obf_to_named.insert(obf, named);
            }
        }
        Mapping { obf_to_named }
    }

    pub fn len(&self) -> usize {
        self.obf_to_named.len()
    }

    pub fn is_empty(&self) -> bool {
        self.obf_to_named.is_empty()
    }

    /// Named internal name for an obfuscated one, or the input when unmapped.
    pub fn rename<'a>(&'a self, internal: &'a str) -> &'a str {
        self.obf_to_named.get(internal).map(String::as_str).unwrap_or(internal)
    }
}

#[cfg(test)]
mod tests {
    use super::Mapping;

    #[test]
    fn parses_class_lines_and_skips_members() {
        let text = "# comment\n\
            net.minecraft.client.gui.screens.Screen -> dsx:\n\
            \x20   net.minecraft.client.gui.screens.Screen field_1 -> a\n\
            \x20   void tick() -> b\n\
            net.minecraft.client.gui.screens.Screen$Inner -> dsx$a:\n\
            garbage line\n";
        let m = Mapping::parse(text);
        assert_eq!(m.len(), 2);
        assert_eq!(m.rename("dsx"), "net/minecraft/client/gui/screens/Screen");
        assert_eq!(m.rename("dsx$a"), "net/minecraft/client/gui/screens/Screen$Inner");
        assert_eq!(m.rename("zzz/Other"), "zzz/Other");
    }
}
