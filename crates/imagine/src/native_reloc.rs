//! Native JNI entries for the Java-side relocation bridge
//! (`dev.cim.bootstrap.NativeReloc`).
//!
//! The shadow classloader (Java) hands every class it defines through
//! [`Java_dev_cim_bootstrap_NativeReloc_relocate0`]; classes inside the
//! relocation space get their constant pool rewritten by `cim-reloc`,
//! everything else passes through unchanged (the input array is returned
//! as-is — zero copy for the bulk of the jar set: guava, netty, log4j, ...).
//!
//! M2 mod space:
//!
//! - [`Java_dev_cim_bootstrap_NativeReloc_registerMods0`] validates the scan
//!   manifest at registration time (fail early, not mid-class-load). Mod
//!   classes keep their ORIGINAL binary names (real-FML parity: ServiceLoader
//!   files, reflection-by-name and resources stay intact); only references
//!   into the layer namespaces are rewritten.
//! - [`Java_dev_cim_bootstrap_NativeReloc_relocateMod0`] performs that
//!   refs-only rewrite (self name preserved, annotation type descriptors
//!   relocated so JVM reflection sees the relocated annotation classes).
//!
//! Failure contract (fail-closed): any relocation error returns a zero-length
//! array and the Java side refuses to define the class — a wrongly-unrelocated
//! class would silently bind to Paper 26.3 lookalikes, which is the worst
//! failure mode this subsystem has, so it must never happen quietly.

use std::sync::RwLock;

use jni::objects::{JByteArray, JClass, JString};
use jni::JNIEnv;

use cim_reloc::RelocationTable;

/// The relocation table (the M1 layer prefixes). Mod-space rewrites reuse the
/// same table in refs-only mode: mod references into `net.minecraft` /
/// `com.mojang` / `net.neoforged` must land on the relocated layer exactly
/// like layer-internal references do.
static TABLE: RwLock<Option<RelocationTable>> = RwLock::new(None);

/// Access-transform database parsed from the manifest's AT directives.
static AT_TABLE: RwLock<Option<cim_reloc::at::AccessTransformTable>> = RwLock::new(None);

fn at_table() -> cim_reloc::at::AccessTransformTable {
    AT_TABLE
        .read()
        .ok()
        .and_then(|g| g.clone())
        .unwrap_or_default()
}

fn table() -> RelocationTable {
    TABLE
        .read()
        .ok()
        .and_then(|g| g.clone())
        .unwrap_or_else(RelocationTable::layer_default)
}

/// `private static native void registerMods0(String manifestJson);`
///
/// Validates the layer manifest (parse = fail-early contract) and logs the
/// mod-space stats. The relocation table itself stays the layer table: mod
/// classes are rewritten refs-only against it.
///
/// # Safety
/// Standard JNI contract.
#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_dev_cim_bootstrap_NativeReloc_registerMods0<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    manifest_json: JString<'local>,
) {
    let run = (|| -> Result<String, String> {
        let json: String = env
            .get_string(&manifest_json)
            .map_err(|e| format!("get_string: {e}"))?
            .to_string_lossy()
            .into_owned();
        let manifest: cim_modscan::LayerManifest =
            serde_json::from_str(&json).map_err(|e| format!("manifest json: {e}"))?;
        let classes: usize = manifest.mods.iter().map(|m| m.classes.len()).sum();
        let at = cim_reloc::at::AccessTransformTable::parse(&manifest.at_entries.join("\n"));
        let msg = format!(
            "mod relocation armed (refs-only mode): {} mod(s), {classes} class(es) tracked, {} AT class directive(s)",
            manifest.mods.len(),
            at.class_directive_count()
        );
        *AT_TABLE.write().map_err(|e| format!("at lock: {e}"))? = Some(at);
        Ok(msg)
    })();
    match run {
        Ok(msg) => eprintln!("{} {msg}", cim_core::LOG_PREFIX),
        Err(e) => eprintln!("{} ERROR: registerMods: {e}", cim_core::LOG_PREFIX),
    }
}

/// `private static native byte[] relocateMod0(String internalName, byte[] classBytes);`
///
/// Mod-space relocation: references into the layer namespaces are rewritten
/// (including runtime annotation type descriptors), the class's own binary
/// name is preserved. Returns the input array when nothing changed.
///
/// # Safety
/// Standard JNI contract: `env`/`_class` are provided by the JVM; arguments
/// are valid local references owned by the calling frame.
#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_dev_cim_bootstrap_NativeReloc_relocateMod0<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    name: JString<'local>,
    input: JByteArray<'local>,
) -> JByteArray<'local> {
    let run = (|| -> Result<JByteArray<'local>, String> {
        let jname: String = env
            .get_string(&name)
            .map_err(|e| format!("get_string: {e}"))?
            .to_string_lossy()
            .into_owned();

        let len = env
            .get_array_length(&input)
            .map_err(|e| format!("get_array_length: {e}"))? as usize;
        let mut signed = vec![0i8; len];
        env.get_byte_array_region(&input, 0, &mut signed)
            .map_err(|e| format!("get_byte_array_region: {e}"))?;
        let bytes: Vec<u8> = signed.into_iter().map(|b| b as u8).collect();

        let relocated =
            cim_reloc::relocate_class_refs_only_at(&table(), &at_table(), &jname, &bytes)
                .map_err(|e| e.to_string())?;

        if std::env::var("CIM_DUMP_RELOC")
            .map(|v| v == "1")
            .unwrap_or(false)
        {
            let path = std::env::temp_dir()
                .join("cim-dump")
                .join(format!("{}.class", jname.replace('/', "_")));
            let _ = std::fs::create_dir_all(path.parent().unwrap());
            let _ = std::fs::write(&path, &relocated);
        }

        if relocated == bytes {
            return Ok(input);
        }
        let arr = env
            .new_byte_array(relocated.len() as i32)
            .map_err(|e| format!("new_byte_array: {e}"))?;
        let signed_out: Vec<i8> = relocated.iter().map(|&b| b as i8).collect();
        env.set_byte_array_region(&arr, 0, &signed_out)
            .map_err(|e| format!("set_byte_array_region: {e}"))?;
        Ok(arr)
    })();

    match run {
        Ok(arr) => arr,
        Err(e) => {
            eprintln!("{} relocation ERROR (mod): {e}", cim_core::LOG_PREFIX);
            env.new_byte_array(0)
                .unwrap_or_else(|_| JByteArray::from(jni::objects::JObject::null()))
        }
    }
}

/// `private static native String mapInternal0(String internalName);`
///
/// Returns the relocated internal name for `internalName`, or null when the
/// name is outside the relocation space. Used by the shadow loader to index
/// layer jars under their relocated class paths.
///
/// # Safety
/// Standard JNI contract.
#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_dev_cim_bootstrap_NativeReloc_mapInternal0<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    name: JString<'local>,
) -> JString<'local> {
    let run = (|| -> Result<Option<String>, String> {
        let jname: String = env
            .get_string(&name)
            .map_err(|e| format!("get_string: {e}"))?
            .to_string_lossy()
            .into_owned();
        Ok(table().map_internal(&jname))
    })();
    match run {
        Ok(Some(mapped)) => match env.new_string(mapped) {
            Ok(s) => s,
            Err(_) => JString::from(jni::objects::JObject::null()),
        },
        _ => JString::from(jni::objects::JObject::null()),
    }
}

/// `private static native byte[] relocate0(String internalName, byte[] classBytes);`
///
/// Layer-space relocation (M1 semantics): full shadow-namespace rename.
///
/// # Safety
/// Standard JNI contract: `env`/`_class` are provided by the JVM; arguments
/// are valid local references owned by the calling frame.
#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_dev_cim_bootstrap_NativeReloc_relocate0<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    name: JString<'local>,
    input: JByteArray<'local>,
) -> JByteArray<'local> {
    let run = (|| -> Result<JByteArray<'local>, String> {
        let jname: String = env
            .get_string(&name)
            .map_err(|e| format!("get_string: {e}"))?
            .to_string_lossy()
            .into_owned();

        // Passthrough fast path: hand the caller's array back untouched.
        if !table().needs_relocation(&jname) {
            return Ok(input);
        }

        let len = env
            .get_array_length(&input)
            .map_err(|e| format!("get_array_length: {e}"))? as usize;
        let mut signed = vec![0i8; len];
        env.get_byte_array_region(&input, 0, &mut signed)
            .map_err(|e| format!("get_byte_array_region: {e}"))?;
        let bytes: Vec<u8> = signed.into_iter().map(|b| b as u8).collect();

        let relocated = cim_reloc::relocate_class_at(&table(), &at_table(), &jname, &bytes)
            .map_err(|e| e.to_string())?;
        let arr = env
            .new_byte_array(relocated.len() as i32)
            .map_err(|e| format!("new_byte_array: {e}"))?;
        let signed_out: Vec<i8> = relocated.iter().map(|&b| b as i8).collect();
        env.set_byte_array_region(&arr, 0, &signed_out)
            .map_err(|e| format!("set_byte_array_region: {e}"))?;
        Ok(arr)
    })();

    match run {
        Ok(arr) => arr,
        Err(e) => {
            eprintln!("{} relocation ERROR: {e}", cim_core::LOG_PREFIX);
            env.new_byte_array(0)
                .unwrap_or_else(|_| JByteArray::from(jni::objects::JObject::null()))
        }
    }
}
