//! JNI entries for the class-hierarchy index (`dev.cim.bootstrap.NativeClassGraph`).
//!
//! One process-global index, built once from the jar list and then queried in
//! batch: [`Java_dev_cim_bootstrap_NativeClassGraph_classify0`] answers a whole
//! jar's worth of "is this class a subtype of root i" questions in a single
//! crossing. Every entry point fails soft (0 / false / null) and logs; the Java
//! side keeps its own walk as the fallback.

use std::path::{Path, PathBuf};
use std::sync::{Arc, RwLock};

use jni::objects::{JClass, JIntArray, JObject, JObjectArray, JString};
use jni::sys::{jboolean, jint, jintArray, jobjectArray};
use jni::JNIEnv;

use cim_classgraph::Index;

static INDEX: RwLock<Option<Arc<Index>>> = RwLock::new(None);

fn current() -> Option<Arc<Index>> {
    INDEX.read().ok().and_then(|g| g.clone())
}

fn read_strings(env: &mut JNIEnv, arr: &JObjectArray) -> Result<Vec<String>, String> {
    let n = env.get_array_length(arr).map_err(|e| format!("array length: {e}"))?;
    let mut out = Vec::with_capacity(n as usize);
    for i in 0..n {
        let obj = env.get_object_array_element(arr, i).map_err(|e| format!("element {i}: {e}"))?;
        if obj.is_null() {
            out.push(String::new());
            continue;
        }
        let js = JString::from(obj);
        let s: String = env.get_string(&js).map_err(|e| format!("get_string: {e}"))?.into();
        out.push(s);
    }
    Ok(out)
}

fn jstr(env: &mut JNIEnv, s: JString) -> Result<String, String> {
    Ok(env.get_string(&s).map_err(|e| format!("get_string: {e}"))?.into())
}

/// `static native int build0(String[] jars, String cacheDir, String[] mappings);`
/// Builds (or loads from cache) the global index. `mappings` entries are `jar\tmapping`
/// pairs: that jar's obfuscated class names are renamed through the ProGuard mapping file.
/// Returns the class count, or -1.
///
/// # Safety
/// Standard JNI contract; arguments are valid references owned by the caller frame.
#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_dev_cim_bootstrap_NativeClassGraph_build0<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    jars: JObjectArray<'local>,
    cache_dir: JString<'local>,
    mappings: JObjectArray<'local>,
) -> jint {
    let run = (|| -> Result<usize, String> {
        let paths: Vec<PathBuf> = read_strings(&mut env, &jars)?.into_iter().map(PathBuf::from).collect();
        let pairs: Vec<(PathBuf, PathBuf)> = read_strings(&mut env, &mappings)?
            .into_iter()
            .filter_map(|entry| {
                let (jar, map) = entry.split_once('\t')?;
                Some((PathBuf::from(jar), PathBuf::from(map)))
            })
            .collect();
        let cache = jstr(&mut env, cache_dir)?;
        let idx = if cache.is_empty() {
            cim_classgraph::build_mapped(&paths, &pairs)
        } else {
            cim_classgraph::load_or_build_mapped(&paths, &pairs, Path::new(&cache))
        };
        let n = idx.len();
        *INDEX.write().map_err(|e| format!("lock: {e}"))? = Some(Arc::new(idx));
        Ok(n)
    })();
    match run {
        Ok(n) => {
            eprintln!("{} classgraph: indexed {n} class(es)", cim_core::LOG_PREFIX);
            n as jint
        }
        Err(e) => {
            eprintln!("{} ERROR: classgraph build: {e}", cim_core::LOG_PREFIX);
            -1
        }
    }
}

/// `static native int[] classify0(String[] classes, String[] roots);`
/// Per class: bit i set when it is a proper subtype of roots[i] (at most 32 roots).
/// Returns null when no index is built or the arguments cannot be read.
///
/// # Safety
/// Standard JNI contract.
#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_dev_cim_bootstrap_NativeClassGraph_classify0<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    classes: JObjectArray<'local>,
    roots: JObjectArray<'local>,
) -> jintArray {
    let Some(idx) = current() else { return std::ptr::null_mut() };
    let run = (|| -> Result<Vec<i32>, String> {
        let cls = read_strings(&mut env, &classes)?;
        let rts = read_strings(&mut env, &roots)?;
        let cls_ref: Vec<&str> = cls.iter().map(String::as_str).collect();
        let rts_ref: Vec<&str> = rts.iter().map(String::as_str).collect();
        Ok(idx.classify(&cls_ref, &rts_ref).into_iter().map(|m| m as i32).collect())
    })();
    match run {
        Ok(mask) => {
            let arr: JIntArray = match env.new_int_array(mask.len() as i32) {
                Ok(a) => a,
                Err(_) => return std::ptr::null_mut(),
            };
            if env.set_int_array_region(&arr, 0, &mask).is_err() {
                return std::ptr::null_mut();
            }
            arr.into_raw()
        }
        Err(e) => {
            eprintln!("{} ERROR: classgraph classify: {e}", cim_core::LOG_PREFIX);
            std::ptr::null_mut()
        }
    }
}

/// `static native boolean isSubclass0(String cls, String target);` (strict, transitive)
///
/// # Safety
/// Standard JNI contract.
#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_dev_cim_bootstrap_NativeClassGraph_isSubclass0<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    cls: JString<'local>,
    target: JString<'local>,
) -> jboolean {
    let Some(idx) = current() else { return 0 };
    let (Ok(c), Ok(t)) = (jstr(&mut env, cls), jstr(&mut env, target)) else { return 0 };
    idx.is_subclass(&c, &t) as jboolean
}

/// `static native String[] ancestors0(String cls);` Empty array when unknown.
///
/// # Safety
/// Standard JNI contract.
#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_dev_cim_bootstrap_NativeClassGraph_ancestors0<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    cls: JString<'local>,
) -> jobjectArray {
    let Ok(c) = jstr(&mut env, cls) else { return std::ptr::null_mut() };
    let list = current().map(|i| i.ancestors(&c)).unwrap_or_default();
    let Ok(string_class) = env.find_class("java/lang/String") else { return std::ptr::null_mut() };
    let Ok(arr) = env.new_object_array(list.len() as i32, string_class, JObject::null()) else {
        return std::ptr::null_mut();
    };
    for (i, s) in list.iter().enumerate() {
        let Ok(js) = env.new_string(s) else { return std::ptr::null_mut() };
        if env.set_object_array_element(&arr, i as i32, js).is_err() {
            return std::ptr::null_mut();
        }
    }
    arr.into_raw()
}

/// `static native boolean known0(String cls);` true when the class is in the index.
///
/// # Safety
/// Standard JNI contract.
#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_dev_cim_bootstrap_NativeClassGraph_known0<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    cls: JString<'local>,
) -> jboolean {
    let Some(idx) = current() else { return 0 };
    let Ok(c) = jstr(&mut env, cls) else { return 0 };
    idx.knows(&c) as jboolean
}
