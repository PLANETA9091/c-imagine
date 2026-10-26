//! Native JNI entry for the Java-side relocation bridge
//! (`dev.cim.bootstrap.NativeReloc`).
//!
//! The shadow classloader (Java) hands every class it defines through
//! [`Java_dev_cim_bootstrap_NativeReloc_relocate0`]; classes inside the
//! relocation space get their constant pool rewritten by `cim-reloc`,
//! everything else passes through unchanged (the input array is returned
//! as-is — zero copy for the bulk of the jar set: guava, netty, log4j, ...).
//!
//! Failure contract (fail-closed): any error returns a zero-length array and
//! the Java side refuses to define the class — a wrongly-unrelocated class
//! would silently bind to Paper 26.3 lookalikes, which is the worst failure
//! mode this subsystem has, so it must never happen quietly.

use std::sync::OnceLock;

use jni::objects::{JByteArray, JClass, JString};
use jni::JNIEnv;

use cim_reloc::RelocationTable;

/// The relocation table is fixed for the process lifetime (M1: the layer
/// table; M2 adds per-mod rules through a superset built once).
static TABLE: OnceLock<RelocationTable> = OnceLock::new();

fn table() -> &'static RelocationTable {
    TABLE.get_or_init(RelocationTable::layer_default)
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

        let relocated =
            cim_reloc::relocate_class(table(), &jname, &bytes).map_err(|e| e.to_string())?;
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
