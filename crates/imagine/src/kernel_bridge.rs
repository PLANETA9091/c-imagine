//! M3.1 kernel state bridge — native side.
//!
//! Wiring (all cross-loader contact speaks NAME + String/primitive values —
//! the shadow space and Paper's space never share a classloader):
//!
//! ```text
//! native poll thread (this module)
//!   ├─ JVMTI find_class("org/bukkit/Bukkit")            [Paper's loader]
//!   ├─ JNI: Bukkit.getServer().getPluginManager()
//!   │        .getPlugin("c-imagine") → plugin object
//!   ├─ poll plugin.isWorldReady() until the world accepts ops
//!   └─ JNI: KernelBridge.runSmoke(pluginObj)            [bootstrap loader]
//!         KernelBridge reflects on the plugin by method NAME
//! ```

use jni::objects::{JClass, JObject, JValue};
use jni::sys::jobject;
use std::sync::OnceLock;
use std::time::{Duration, Instant};

static JVM: OnceLock<jni::JavaVM> = OnceLock::new();
/// Raw jobject of a LEAKED global ref to the bootstrap jar URLClassLoader
/// (created in run_bootstrap; the leak is intentional — process-lifetime).
static BOOTSTRAP_LOADER: OnceLock<usize> = OnceLock::new();

/// Remember the VM and the bootstrap jar URLClassLoader (leaked global ref,
/// raw jobject as usize) so the bridge thread can reach both worlds later.
pub fn init(vm_raw: *mut jni::sys::JavaVM, loader_global: usize) {
    match unsafe { jni::JavaVM::from_raw(vm_raw) } {
        Ok(jvm) => {
            let _ = JVM.set(jvm);
        }
        Err(e) => eprintln!("[c-imagine] bridge: JavaVM wrap failed: {e}"),
    }
    let _ = BOOTSTRAP_LOADER.set(loader_global);
}

/// Entry point called from `cplugin_init` after the bootstrap side is up.
///
/// Spawns the bridge poll thread; never blocks module init.
pub fn start() {
    std::thread::Builder::new()
        .name("cim-bridge".into())
        .spawn(|| {
            if let Err(e) = bridge_loop() {
                eprintln!("[c-imagine] bridge: {e}");
            }
        })
        .map(|_| ())
        .unwrap_or_else(|e| eprintln!("[c-imagine] bridge thread spawn failed: {e}"));
}

/// Attach the current thread and run `f` with a fresh env. Errors are
/// logged with any pending JNI exception cleared.
fn attached<R>(what: &str, f: impl FnOnce(&mut jni::JNIEnv) -> Result<R, String>) -> Option<R> {
    let vm = JVM.get()?;
    let mut guard = match vm.attach_current_thread() {
        Ok(g) => g,
        Err(e) => {
            eprintln!("[c-imagine] bridge: attach failed ({what}): {e}");
            return None;
        }
    };
    #[allow(clippy::explicit_auto_deref)] // AttachGuard Deref, not a plain ref
    let env: &mut jni::JNIEnv = &mut guard;
    match f(env) {
        Ok(r) => Some(r),
        Err(e) => {
            if env.exception_check().unwrap_or(false) {
                let _ = env.exception_describe();
                let _ = env.exception_clear();
            }
            eprintln!("[c-imagine] bridge: {what} failed: {e}");
            None
        }
    }
}

fn bridge_loop() -> Result<(), String> {
    // 1) wait for the Paper plugin class to load (resolved through JVMTI, so
    //    it works no matter which Paper loader owns it)
    let deadline = Instant::now() + Duration::from_secs(180);
    let bukkit_cls = loop {
        if Instant::now() > deadline {
            return Err("bridge: Paper never came online (no Bukkit class)".into());
        }
        if let Some(c) = cplug_sdk::classes::find_class("org/bukkit/Bukkit") {
            break c;
        }
        std::thread::sleep(Duration::from_millis(500));
    };

    // 2) resolve the plugin INSTANCE through the Bukkit registry. The Bukkit
    //    CLASS loads early in Paper's boot but the server instance (and the
    //    plugin registry) only exist after plugins enable — retry until both
    //    are live, then wait for the world (plugin flag, polled).
    let plugin = {
        let deadline = Instant::now() + Duration::from_secs(300);
        let resolved;
        loop {
            if Instant::now() > deadline {
                return Err("bridge: plugin instance never resolved".into());
            }
            let attempt = attached("plugin resolve", |env| {
                let bukkit = unsafe { JClass::from_raw(bukkit_cls.as_jclass() as jobject) };
                let server = env
                    .call_static_method(&bukkit, "getServer", "()Lorg/bukkit/Server;", &[])
                    .map_err(|e| format!("Bukkit.getServer: {e}"))?
                    .l()
                    .map_err(|e| format!("getServer l(): {e}"))?;
                if server.is_null() {
                    // class up, instance not set yet — try again shortly
                    return Err("server instance not set yet".into());
                }
                let pm = env
                    .call_method(
                        &server,
                        "getPluginManager",
                        "()Lorg/bukkit/plugin/PluginManager;",
                        &[],
                    )
                    .map_err(|e| format!("getPluginManager: {e}"))?
                    .l()
                    .map_err(|e| format!("pm l(): {e}"))?;
                let name = env
                    .new_string("c-imagine")
                    .map_err(|e| format!("new_string: {e}"))?;
                let plugin = env
                    .call_method(
                        &pm,
                        "getPlugin",
                        "(Ljava/lang/String;)Lorg/bukkit/plugin/Plugin;",
                        &[JValue::Object(&name)],
                    )
                    .map_err(|e| format!("getPlugin: {e}"))?
                    .l()
                    .map_err(|e| format!("plugin l(): {e}"))?;
                if plugin.is_null() {
                    return Err("c-imagine plugin not registered yet".into());
                }
                eprintln!("[c-imagine] bridge: plugin instance resolved");
                // GlobalRef is 'static — the only JNI handle type allowed to
                // escape this attach scope.
                env.new_global_ref(&plugin)
                    .map_err(|e| format!("plugin global ref: {e}"))
            });
            if let Some(g) = attempt {
                resolved = g;
                break;
            }
            std::thread::sleep(Duration::from_millis(1000));
        }
        resolved
    };
    let plugin_obj = plugin.as_obj();

    {
        let deadline = Instant::now() + Duration::from_secs(300);
        loop {
            let ready = attached("isWorldReady", |env| {
                env.call_method(plugin_obj, "isWorldReady", "()Z", &[])
                    .map_err(|e| format!("isWorldReady: {e}"))?
                    .z()
                    .map_err(|e| format!("z(): {e}"))
            });
            if ready == Some(true) {
                break;
            }
            if Instant::now() > deadline {
                return Err("bridge: world never became ready".into());
            }
            std::thread::sleep(Duration::from_millis(500));
        }
    }
    eprintln!("[c-imagine] bridge: world ready — running facade smoke");

    // 3) run the smoke on the facade side (bootstrap loader)
    let loader_idx = *BOOTSTRAP_LOADER
        .get()
        .ok_or("bridge: no bootstrap loader global")?;
    attached("smoke", |env| {
        let loader = unsafe { JObject::from_raw(loader_idx as jobject) };
        let name = env
            .new_string("dev.cim.bridge.KernelBridge")
            .map_err(|e| format!("new_string: {e}"))?;
        let bridge_cls = env
            .call_method(
                &loader,
                "loadClass",
                "(Ljava/lang/String;)Ljava/lang/Class;",
                &[JValue::Object(&name)],
            )
            .map_err(|e| format!("loadClass(KernelBridge): {e}"))?
            .l()
            .map_err(|e| format!("class l(): {e}"))?;
        let bridge: JClass = bridge_cls.into();
        env.call_static_method(
            &bridge,
            "runSmoke",
            "(Ljava/lang/Object;)V",
            &[JValue::Object(plugin_obj)],
        )
        .map_err(|e| format!("runSmoke: {e}"))?;
        Ok(())
    });
    Ok(())
}
