//! c-imagine — CRUSSTY native module.
//!
//! Boots the mini-NeoForge layer inside the Paper 26.3 JVM:
//! 1. `cplugin_init` (pre-kernel): arm class-load hooks, spawn the loader
//!    thread, return 0.
//! 2. Loader thread: embed-deploy `cim-bootstrap.jar`, drive it through JNI,
//!    scan `mods/` via `cim-modscan` and report the deterministic load order.
//! 3. Kernel ready (`org.bukkit.Bukkit` defined): announce readiness; M1+
//!    continues into shadow-namespace relocation and layer boot.

use std::ffi::c_char;
use std::path::PathBuf;

use cplug_abi::{CPluginApi, JavaVmPtr};

mod modwatch;
mod options;

/// Sendable wrapper around the process-global JavaVM pointer.
///
/// # Safety contract
/// `JavaVM*` is process-global; `AttachCurrentThread` is documented as
/// thread-safe. The pointer is only dereferenced through JNI attach calls,
/// never across threads concurrently.
#[derive(Clone, Copy)]
struct SendVmPtr(JavaVmPtr);
unsafe impl Send for SendVmPtr {}

/// Embedded Java bootstrap (staged by build.rs; real jar in CI, placeholder
/// locally).
const BOOTSTRAP_JAR: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/cim-bootstrap.jar"));

/// The single required export (cplug-abi contract).
///
/// # Safety
/// `api` must be the runtime-owned CPluginApi, `vm` the live JavaVM pointer,
/// `options` a valid NUL-terminated string (or null).
#[unsafe(no_mangle)]
pub unsafe extern "C" fn cplugin_init(
    api: *const CPluginApi,
    vm: JavaVmPtr,
    options: *const c_char,
) -> i32 {
    eprintln!("{} module init (native, pre-kernel)", cim_core::LOG_PREFIX);

    // SDK pipeline first — everything below rides on it.
    unsafe { cplug_sdk::init(api, vm) };

    // Prove the hot-patch chain reaches us: fires when the bootstrap class is
    // defined by our own loader thread below.
    cplug_sdk::hooks::register("dev/cim/bootstrap/Bootstrap", |name| {
        eprintln!("{} class-load hook observed: {name}", cim_core::LOG_PREFIX);
    });

    let opts = options::parse_options(options);

    // Raw JavaVM* is process-global and thread-safe to attach from; wrap it in
    // a Send newtype for the loader thread.
    let vm_ptr = SendVmPtr(vm);

    // Loader thread: deploy + drive the Java bootstrap, then scan mods.
    std::thread::Builder::new()
        .name("cim-loader".into())
        .spawn(move || loader_thread(vm_ptr, opts))
        .expect("spawn cim-loader thread");

    // Kernel lifecycle marker (M1+ continues the boot sequence here).
    cplug_sdk::on_kernel_ready("org.bukkit.Bukkit", || {
        eprintln!(
            "{} kernel ready (Paper class space online)",
            cim_core::LOG_PREFIX
        );
    });

    0
}

fn loader_thread(vm: SendVmPtr, opts: options::ModuleOptions) {
    // 1) Deploy the embedded bootstrap jar to a stable temp location.
    let jar_path = match deploy_bootstrap() {
        Ok(p) => p,
        Err(e) => {
            eprintln!(
                "{} ERROR: bootstrap deploy failed: {e}",
                cim_core::LOG_PREFIX
            );
            return;
        }
    };

    // 2) Drive the Java side through JNI.
    let status = run_bootstrap(vm, &jar_path, &opts);
    eprintln!("{} bootstrap ready: {status}", cim_core::LOG_PREFIX);

    // 3) Scan mods/ (static, no class loading) and report load order.
    let mods_dir = opts
        .mods_dir
        .clone()
        .unwrap_or_else(|| PathBuf::from("mods"));
    let count = modwatch::scan_and_report(&mods_dir);
    eprintln!(
        "{} loader: scanned {} jar(s) in {}",
        cim_core::LOG_PREFIX,
        count,
        mods_dir.display()
    );
}

fn deploy_bootstrap() -> Result<PathBuf, String> {
    let dir = std::env::temp_dir().join("c-imagine");
    std::fs::create_dir_all(&dir).map_err(|e| e.to_string())?;
    let path = dir.join("cim-bootstrap.jar");
    std::fs::write(&path, BOOTSTRAP_JAR).map_err(|e| e.to_string())?;
    Ok(path)
}

fn run_bootstrap(
    vm: SendVmPtr,
    jar_path: &std::path::Path,
    opts: &options::ModuleOptions,
) -> String {
    let result = (|| -> Result<String, String> {
        let jvm = unsafe { jni::JavaVM::from_raw(vm.0 as *mut jni_sys::JavaVM) }
            .map_err(|e| format!("JavaVM::from_raw: {e}"))?;
        let mut env = jvm
            .attach_current_thread()
            .map_err(|e| format!("attach_current_thread: {e}"))?;

        let class = env
            .find_class("dev/cim/bootstrap/Bootstrap")
            .map_err(|e| format!("find Bootstrap: {e}"))?;

        let options_json = serde_options_json(jar_path, opts);
        let jopts = env
            .new_string(options_json)
            .map_err(|e| format!("new_string: {e}"))?;

        let ret = env
            .call_static_method(
                class,
                "init",
                "(Ljava/lang/String;)Ljava/lang/String;",
                &[jni::objects::JValue::Object(&jopts)],
            )
            .map_err(|e| format!("Bootstrap.init: {e}"))?;
        let ret_obj = ret.l().map_err(|e| format!("ret.l: {e}"))?;
        let jstr = jni::objects::JString::from(ret_obj);
        let text = env
            .get_string(&jstr)
            .map_err(|e| format!("get_string: {e}"))?;
        Ok(text.to_string_lossy().into_owned())
    })();

    match result {
        Ok(status) => status,
        Err(e) => {
            eprintln!("{} ERROR: {e}", cim_core::LOG_PREFIX);
            "error".to_string()
        }
    }
}

fn serde_options_json(jar_path: &std::path::Path, opts: &options::ModuleOptions) -> String {
    // Tiny hand-rolled JSON (no serde dependency at the module boundary).
    let jar = jar_path.to_string_lossy().replace('\\', "/");
    let mods = opts
        .mods_dir
        .as_ref()
        .map(|p| p.to_string_lossy().replace('\\', "/"))
        .unwrap_or_default();
    format!(
        "{{\"jar\":\"{jar}\",\"mods\":\"{mods}\",\"kernel\":\"{}\"}}",
        opts.kernel.replace('\\', "/")
    )
}
