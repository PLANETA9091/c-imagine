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

mod kernel_bridge;
mod layerconfig;
mod modwatch;
mod native_reloc;
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

    // M4 DIAGNOSTIC (temporary): dump the bytes our pipeline receives for the
    // handshake listener — reveals whether the runtime's built-in net-hooks
    // weave runs BEFORE our byte hooks (bytes arrive pre-woven and broken:
    // tableswitch mis-alignment VerifyError) or AFTER (we see the original).
    cplug_sdk::hooks::register_bytes(
        "net/minecraft/server/network/ServerHandshakePacketListenerImpl",
        |name, data| {
            let dump = std::env::temp_dir().join("cim-handshake-incoming.class");
            if let Err(e) = std::fs::write(&dump, data) {
                eprintln!("{} netwatch dump write failed: {e}", cim_core::LOG_PREFIX);
            } else {
                eprintln!(
                    "{} netwatch: dumped {} bytes of {} to {}",
                    cim_core::LOG_PREFIX,
                    data.len(),
                    name,
                    dump.display()
                );
            }
            None
        },
    );

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

    // 2) Layer configuration (crus/c-imagine.json in the server root) and
    //    this module's own .so path (for the Java relocation natives).
    let server_root = opts.server_root();
    let layer_cfg = layerconfig::load(&server_root);
    let module_lib = layerconfig::module_lib_path().unwrap_or_default();
    // modwatch reads the layer dir through the env contract to locate the
    // neoforge universal jar (it is a layer jar AND a mod).
    if !layer_cfg.layer_dir.is_empty() {
        let abs = server_root.join(&layer_cfg.layer_dir);
        std::env::set_var("CIM_LAYER_DIR", &abs);
    }
    if !layer_cfg.layer_dir.is_empty() {
        eprintln!(
            "{} layer config: dir={} moduleLib={}",
            cim_core::LOG_PREFIX,
            layer_cfg.layer_dir,
            if module_lib.is_empty() {
                "<unknown>"
            } else {
                &module_lib
            }
        );
    }

    // 3) Scan mods/ (static, no class loading) and build the manifest.
    //    Runs before the Java call: the manifest travels inside the same JNI
    //    session (registerMods + loadMods consume it on the Java side).
    let mods_dir = opts
        .mods_dir
        .clone()
        .unwrap_or_else(|| PathBuf::from("mods"));
    let (manifest, count) = modwatch::scan_and_build_manifest(&mods_dir);
    eprintln!(
        "{} loader: scanned {} jar(s) in {}",
        cim_core::LOG_PREFIX,
        count,
        mods_dir.display()
    );

    // 4) Drive the Java side through JNI: layer boot, then mod execution.
    let status = run_bootstrap(
        vm,
        &jar_path,
        &opts,
        &layer_cfg.layer_dir,
        &module_lib,
        manifest,
    );
    eprintln!("{} bootstrap ready: {status}", cim_core::LOG_PREFIX);

    // Persist the combined status for the Paper-side companion plugin
    // (/cim status reads it — see paper-plugin CimPlugin). Failure is
    // non-fatal: the file is a convenience bridge, not a boot dependency.
    let status_dir = server_root.join("c-imagine");
    if let Err(e) = std::fs::create_dir_all(&status_dir)
        .and_then(|_| std::fs::write(status_dir.join("status.json"), &status))
    {
        eprintln!(
            "{} WARN: status.json write failed: {e}",
            cim_core::LOG_PREFIX
        );
    } else {
        eprintln!(
            "{} status file: {}",
            cim_core::LOG_PREFIX,
            status_dir.join("status.json").display()
        );
    }
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
    layer_dir: &str,
    module_lib: &str,
    manifest: Option<String>,
) -> String {
    let result = (|| -> Result<String, String> {
        let jvm = unsafe { jni::JavaVM::from_raw(vm.0 as *mut jni_sys::JavaVM) }
            .map_err(|e| format!("JavaVM::from_raw: {e}"))?;
        let mut env = jvm
            .attach_current_thread()
            .map_err(|e| format!("attach_current_thread: {e}"))?;

        // The bootstrap jar is NOT on the system classpath, so a bare
        // FindClass cannot see it. Build a dedicated URLClassLoader over the
        // deployed jar (bootstrap parent -> fully isolated) and load the
        // entrypoint through it:
        //   File f = new File(jarPath);
        //   URL url = f.toURI().toURL();
        //   URLClassLoader loader = new URLClassLoader(new URL[]{url}, null);
        //   Class<?> boot = Class.forName("dev.cim.bootstrap.Bootstrap", true, loader);
        let jpath = env
            .new_string(jar_path.to_string_lossy().as_ref())
            .map_err(|e| format!("new_string(jar): {e}"))?;
        let file_cls = env
            .find_class("java/io/File")
            .map_err(|e| format!("find File: {e}"))?;
        let file = env
            .new_object(
                file_cls,
                "(Ljava/lang/String;)V",
                &[jni::objects::JValue::Object(&jpath)],
            )
            .map_err(|e| format!("new File: {e}"))?;
        let uri = env
            .call_method(&file, "toURI", "()Ljava/net/URI;", &[])
            .map_err(|e| format!("File.toURI: {e}"))?
            .l()
            .map_err(|e| format!("File.toURI l(): {e}"))?;
        let url = env
            .call_method(&uri, "toURL", "()Ljava/net/URL;", &[])
            .map_err(|e| format!("URI.toURL: {e}"))?
            .l()
            .map_err(|e| format!("URI.toURL l(): {e}"))?;

        let url_cls = env
            .find_class("java/net/URL")
            .map_err(|e| format!("find URL: {e}"))?;
        let url_array = env
            .new_object_array(1, url_cls, &url)
            .map_err(|e| format!("URL[] alloc: {e}"))?;

        let ucl_cls = env
            .find_class("java/net/URLClassLoader")
            .map_err(|e| format!("find URLClassLoader: {e}"))?;
        let null_parent = jni::objects::JObject::null();
        let loader = env
            .new_object(
                ucl_cls,
                "([Ljava/net/URL;Ljava/lang/ClassLoader;)V",
                &[
                    jni::objects::JValue::Object(&url_array),
                    jni::objects::JValue::Object(&null_parent),
                ],
            )
            .map_err(|e| format!("new URLClassLoader: {e}"))?;

        // M3.1 kernel bridge: stash the loader (leaked process-lifetime global
        // ref) and arm the bridge poll thread. The thread resolves the Paper
        // plugin through JVMTI once Bukkit's loader comes online, waits for
        // the world, then drives KernelBridge.runSmoke on the facade side.
        let loader_global = env
            .new_global_ref(&loader)
            .map_err(|e| format!("loader global ref: {e}"))?;
        let loader_raw = loader_global.as_obj().as_raw() as usize;
        std::mem::forget(loader_global); // process lifetime by design
        kernel_bridge::init(vm.0 as *mut jni::sys::JavaVM, loader_raw);
        kernel_bridge::start();

        let class_cls = env
            .find_class("java/lang/Class")
            .map_err(|e| format!("find Class: {e}"))?;
        let boot_name = env
            .new_string("dev.cim.bootstrap.Bootstrap")
            .map_err(|e| format!("new_string(name): {e}"))?;
        let class = env
            .call_static_method(
                class_cls,
                "forName",
                "(Ljava/lang/String;ZLjava/lang/ClassLoader;)Ljava/lang/Class;",
                &[
                    jni::objects::JValue::Object(&boot_name),
                    jni::objects::JValue::Bool(1),
                    jni::objects::JValue::Object(&loader),
                ],
            )
            .map_err(|e| format!("Class.forName(Bootstrap): {e}"))?
            .l()
            .map_err(|e| format!("forName l(): {e}"))?;
        let boot_cls = jni::objects::JClass::from(class);

        let options_json = serde_options_json(jar_path, opts, layer_dir, module_lib);
        let jopts = env
            .new_string(options_json)
            .map_err(|e| format!("new_string: {e}"))?;
        let empty = env.new_string("").map_err(|e| format!("new_string: {e}"))?;
        let jmanifest = match &manifest {
            Some(json) => env
                .new_string(json.as_str())
                .map_err(|e| format!("new_string(manifest): {e}"))?,
            None => empty,
        };

        let ret = env
            .call_static_method(
                &boot_cls,
                "init",
                "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
                &[
                    jni::objects::JValue::Object(&jopts),
                    jni::objects::JValue::Object(&jmanifest),
                ],
            )
            .map_err(|e| format!("Bootstrap.init: {e}"))?;
        let ret_obj = ret.l().map_err(|e| format!("ret.l: {e}"))?;
        let jstr = jni::objects::JString::from(ret_obj);
        let text = env
            .get_string(&jstr)
            .map_err(|e| format!("get_string: {e}"))?
            .to_string_lossy()
            .into_owned();

        // M2: mod execution — hand the manifest to the shadow space. The
        // Java side arms the mod relocation rules, extends the shadow loader
        // with the mod jars and runs the FML pipeline (construct + register).
        let mods_status = match manifest {
            Some(json) => {
                let jjson = env
                    .new_string(json.as_str())
                    .map_err(|e| format!("new_string(manifest): {e}"))?;
                let mret = env
                    .call_static_method(
                        &boot_cls,
                        "loadMods",
                        "(Ljava/lang/String;)Ljava/lang/String;",
                        &[jni::objects::JValue::Object(&jjson)],
                    )
                    .map_err(|e| format!("Bootstrap.loadMods: {e}"))?;
                let mobj = mret.l().map_err(|e| format!("loadMods l(): {e}"))?;
                let mstr = jni::objects::JString::from(mobj);
                let text = env
                    .get_string(&mstr)
                    .map_err(|e| format!("get_string: {e}"))?;
                let owned = text.to_string_lossy().into_owned();
                owned
            }
            None => "{\"state\":\"skipped\",\"reason\":\"no mods\"}".to_string(),
        };

        Ok(format!("{{\"init\":{text},\"mods\":{mods_status}}}"))
    })();

    match result {
        Ok(status) => status,
        Err(e) => {
            eprintln!("{} ERROR: {e}", cim_core::LOG_PREFIX);
            "error".to_string()
        }
    }
}

fn serde_options_json(
    jar_path: &std::path::Path,
    opts: &options::ModuleOptions,
    layer_dir: &str,
    module_lib: &str,
) -> String {
    // Tiny hand-rolled JSON (no serde dependency at the module boundary).
    let jar = jar_path.to_string_lossy().replace('\\', "/");
    let mods = opts
        .mods_dir
        .as_ref()
        .map(|p| p.to_string_lossy().replace('\\', "/"))
        .unwrap_or_default();
    format!(
        "{{\"jar\":\"{jar}\",\"mods\":\"{mods}\",\"kernel\":\"{}\",\"layerDir\":\"{}\",\"moduleLib\":\"{}\"}}",
        opts.kernel.replace('\\', "/"),
        layer_dir.replace('\\', "/"),
        module_lib.replace('\\', "/"),
    )
}
