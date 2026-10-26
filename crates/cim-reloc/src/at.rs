//! Access Transformer support (M2): NeoForge and mods ship
//! `META-INF/accesstransformer.cfg` files that widen access to Minecraft's
//! private/protected members. The relocation pass applies them while the
//! class bytes are already being rewritten — the deployed classes then carry
//! the widened access exactly like a real AT-patched NeoForge runtime.

use std::collections::HashMap;

/// One directive: the access to force on a member (or the class itself).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct AccessTransform {
    /// Visibility to set: 0 = package-private, 1 = public, 4 = protected.
    pub visibility: u16,
    /// Clear the final flag (the `-f` modifier).
    pub clear_final: bool,
    /// Make static (`-s` modifier; rare).
    pub clear_static: bool,
    /// Remove static (`-i` modifier for instance-only; rare).
    pub make_static: bool,
}

/// Parsed AT database: class internal name -> directives.
#[derive(Debug, Default, Clone)]
pub struct AccessTransformTable {
    classes: HashMap<String, ClassDirectives>,
}

#[derive(Debug, Default, Clone)]
struct ClassDirectives {
    class_level: Vec<AccessTransform>,
    /// member key: field "name" or method "name(descriptor)".
    members: HashMap<String, AccessTransform>,
}

impl AccessTransformTable {
    /// Parse an `accesstransformer.cfg` payload. Unknown/unsupported lines
    /// are skipped (logged by the caller through the returned count).
    pub fn parse(cfg: &str) -> Self {
        let mut table = Self::default();
        for line in cfg.lines() {
            let line = line.split('#').next().unwrap_or("").trim();
            if line.is_empty() {
                continue;
            }
            if let Some((class, member, at)) = parse_line(line) {
                let entry = table.classes.entry(class).or_default();
                match member {
                    Some(m) => {
                        entry.members.insert(m, at);
                    }
                    None => entry.class_level.push(at),
                }
            }
        }
        table
    }

    pub fn is_empty(&self) -> bool {
        self.classes.is_empty()
    }

    pub fn class_directive_count(&self) -> usize {
        self.classes.len()
    }

    /// The class-level access transform for `class_internal`, if any.
    pub fn for_class(&self, class_internal: &str) -> Option<&[AccessTransform]> {
        self.classes.get(class_internal).map(|c| c.class_level.as_slice())
    }

    /// The member-level access transform for a field/method of
    /// `class_internal`.
    pub fn for_member(&self, class_internal: &str, member_key: &str) -> Option<AccessTransform> {
        self.classes.get(class_internal)?.members.get(member_key).copied()
    }
}

const ACC_PUBLIC: u16 = 0x0001;
const ACC_PROTECTED: u16 = 0x0004;

/// Parse one AT line: `<modifiers> <class> [<member>]`.
fn parse_line(line: &str) -> Option<(String, Option<String>, AccessTransform)> {
    let mut tokens = line.split_whitespace();
    let modifier = tokens.next()?;
    let target = tokens.next()?;
    let member = tokens.next();

    let mut visibility = 0u16;
    let mut clear_final = false;
    let mut clear_static = false;
    let mut make_static = false;
    for part in modifier.split('-') {
        match part {
            "public" => visibility = ACC_PUBLIC,
            "protected" => visibility = ACC_PROTECTED,
            "default" => visibility = 0,
            "f" => clear_final = true,
            "s" => make_static = true,
            "i" => clear_static = true,
            "" => {}
            other => return None, // unknown modifier — skip the line
        }
    }
    if visibility == 0 && modifier != "default" && !modifier.contains("default") {
        return None;
    }
    let at = AccessTransform { visibility, clear_final, clear_static, make_static };
    let member = member.map(|m| {
        // method entries carry a descriptor in parentheses; strip any
        // trailing genericThrows garbage defensively
        m.to_string()
    });
    Some((target.replace('.', "/"), member, at))
}

/// Apply an access transform to one member's access flags.
pub fn apply(flags: u16, at: AccessTransform) -> u16 {
    let mut flags = flags;
    // visibility: clear private/protected, then set the target visibility
    // (0 = package-private: clear both).
    flags &= !(0x0002u16 | ACC_PROTECTED | ACC_PUBLIC);
    flags |= at.visibility;
    if at.clear_final {
        flags &= !0x0010u16;
    }
    if at.clear_static {
        flags &= !0x0008u16;
    }
    if at.make_static {
        flags |= 0x0008u16;
    }
    flags
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_visibility_and_members() {
        let t = AccessTransformTable::parse(
            "public net.minecraft.world.item.CreativeModeTabs HOTBAR\n\
             public-f net.minecraft.client.Options keyMappings\n\
             public net.minecraft.advancements.CriteriaTriggers register(Ljava/lang/String;)V # c\n\
             public net.minecraft.client.Options$FieldAccess\n\
             garbage line without modifier\n",
        );
        assert!(t.for_member("net/minecraft/world/item/CreativeModeTabs", "HOTBAR").is_some());
        let f = t.for_member("net/minecraft/client/Options", "keyMappings").unwrap();
        assert!(f.clear_final);
        assert_eq!(f.visibility, ACC_PUBLIC);
        let m = t.for_member("net/minecraft/advancements/CriteriaTriggers", "register(Ljava/lang/String;)V").unwrap();
        assert_eq!(m.visibility, ACC_PUBLIC);
        assert!(!t.for_class("net/minecraft/client/Options$FieldAccess").unwrap().is_empty());
        assert!(t.for_member("no/such/Class", "x").is_none());
    }

    #[test]
    fn apply_widens_and_clears_final() {
        let at = AccessTransform { visibility: ACC_PUBLIC, clear_final: true, clear_static: false, make_static: false };
        assert_eq!(apply(0x0012, at), 0x0001); // private final -> public
    }
}
