//! TestTube's native core: runs YouTube's signature and n-parameter functions in QuickJS
//! instead of Rhino, and finds regex matches in big texts (the player file, HTML pages) for
//! the extractor (see `regex_scan`). Loaded by `org.schabi.newpipe.extractor.utils.NativeJavaScript`,
//! which falls back to Rhino if this library is missing or fails.

pub mod engine;
pub mod regex_scan;

use std::panic::{catch_unwind, AssertUnwindSafe};
use std::sync::{Mutex, MutexGuard, OnceLock};

use jni::objects::{JClass, JObjectArray, JString};
use jni::sys::{jint, jlong, jstring};
use jni::JNIEnv;

pub use engine::{Engine, JsError, JsResult};

static ENGINE: OnceLock<Mutex<Option<Engine>>> = OnceLock::new();
/// Separate from the engine so a regex search never waits for JavaScript and the other way round.
static SCANNER: OnceLock<Mutex<regex_scan::Scanner>> = OnceLock::new();

fn scanner() -> MutexGuard<'static, regex_scan::Scanner> {
    let lock = SCANNER.get_or_init(|| Mutex::new(regex_scan::Scanner::new()));
    match lock.lock() {
        Ok(guard) => guard,
        Err(poisoned) => {
            let mut guard = poisoned.into_inner();
            guard.clear();
            lock.clear_poison();
            guard
        }
    }
}

/// The shared engine. QuickJS is single-threaded; the extractor's callers are already
/// `synchronized`, the lock just makes that a guarantee.
fn engine() -> JsResult<MutexGuard<'static, Option<Engine>>> {
    let lock = ENGINE.get_or_init(|| Mutex::new(None));
    // a panic mid-call leaves the engine in an unknown state: start a new one
    let mut guard = match lock.lock() {
        Ok(guard) => guard,
        Err(poisoned) => {
            let mut guard = poisoned.into_inner();
            *guard = None;
            lock.clear_poison();
            guard
        }
    };
    if guard.is_none() {
        *guard = Some(Engine::new()?);
    }
    Ok(guard)
}

pub fn load(code: &str) -> JsResult<u64> {
    engine()?.as_mut().expect("engine").load(code)
}

pub fn call(handle: u64, function_name: &str, args: &[String]) -> JsResult<Option<String>> {
    engine()?.as_mut().expect("engine").call(handle, function_name, args)
}

pub fn check(code: &str) -> JsResult<()> {
    engine()?.as_mut().expect("engine").check(code)
}

pub fn run(code: &str, function_name: &str, args: &[String]) -> JsResult<String> {
    engine()?.as_mut().expect("engine").run(code, function_name, args)
}

pub fn clear() {
    if let Ok(mut guard) = engine() {
        if let Some(engine) = guard.as_mut() {
            engine.clear();
        }
    }
}

// ---- JNI ------------------------------------------------------------------------------------

const EXCEPTION: &str = "java/lang/RuntimeException";

fn throw(env: &mut JNIEnv, message: &str) {
    // if this fails a Java exception is already pending, which is what we want anyway
    let _ = env.throw_new(EXCEPTION, message);
}

fn string(env: &mut JNIEnv, value: &JString) -> Result<String, String> {
    env.get_string(value).map(Into::into).map_err(|e| e.to_string())
}

/// Runs `body`, turning an error or a panic into a pending Java exception.
fn guarded<T: Default>(env: &mut JNIEnv, body: impl FnOnce(&mut JNIEnv) -> Result<T, String>) -> T {
    match catch_unwind(AssertUnwindSafe(|| body(env))) {
        Ok(Ok(value)) => value,
        Ok(Err(message)) => {
            throw(env, &message);
            T::default()
        }
        Err(panic) => {
            let message = panic
                .downcast_ref::<&str>()
                .map(|s| s.to_string())
                .or_else(|| panic.downcast_ref::<String>().cloned())
                .unwrap_or_else(|| "panic".into());
            throw(env, &format!("testtube_core panicked: {message}"));
            T::default()
        }
    }
}

fn string_array(env: &mut JNIEnv, array: &JObjectArray) -> Result<Vec<String>, String> {
    let count = env.get_array_length(array).map_err(|e| e.to_string())?;
    let mut values = Vec::with_capacity(count as usize);
    for i in 0..count {
        let item = env.get_object_array_element(array, i).map_err(|e| e.to_string())?;
        // Rhino passed a Java null as JavaScript null; String(null) is "null" either way
        let value = if item.is_null() {
            "null".to_string()
        } else {
            let item = JString::from(item);
            let value = string(env, &item)?;
            env.delete_local_ref(item).map_err(|e| e.to_string())?;
            value
        };
        values.push(value);
    }
    Ok(values)
}

/// `static native long nativeLoad(String code)`: evaluates code once and returns its handle.
#[no_mangle]
pub extern "system" fn Java_org_schabi_newpipe_extractor_utils_NativeJavaScript_nativeLoad(
    mut env: JNIEnv,
    _class: JClass,
    code: JString,
) -> jlong {
    guarded(&mut env, |env| {
        let code = string(env, &code)?;
        load(&code).map(|h| h as jlong).map_err(|e| e.0)
    })
}

/// `static native String nativeCall(long handle, String functionName, String[] args)`:
/// the result, or null if the handle's code was dropped (load it again).
#[no_mangle]
pub extern "system" fn Java_org_schabi_newpipe_extractor_utils_NativeJavaScript_nativeCall(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    function_name: JString,
    args: JObjectArray,
) -> jstring {
    guarded(&mut env, |env| {
        let function_name = string(env, &function_name)?;
        let values = string_array(env, &args)?;
        match call(handle as u64, &function_name, &values).map_err(|e| e.0)? {
            Some(result) => env.new_string(result).map(|s| s.into_raw()).map_err(|e| e.to_string()),
            None => Ok(std::ptr::null_mut()),
        }
    })
}

/// `static native void nativeCheck(String code)`: throws on a syntax error.
#[no_mangle]
pub extern "system" fn Java_org_schabi_newpipe_extractor_utils_NativeJavaScript_nativeCheck(
    mut env: JNIEnv,
    _class: JClass,
    code: JString,
) {
    guarded(&mut env, |env| {
        let code = string(env, &code)?;
        check(&code).map_err(|e| e.0)
    })
}

/// `static native void nativeClear()`
#[no_mangle]
pub extern "system" fn Java_org_schabi_newpipe_extractor_utils_NativeJavaScript_nativeClear(
    mut env: JNIEnv,
    _class: JClass,
) {
    guarded(&mut env, |_| {
        clear();
        Ok(())
    })
}

/// `static native long nativeLoadText(String text)`: keeps a big text for `nativeFind`.
#[no_mangle]
pub extern "system" fn Java_org_schabi_newpipe_extractor_utils_NativeRegex_nativeLoadText(
    mut env: JNIEnv,
    _class: JClass,
    text: JString,
) -> jlong {
    guarded(&mut env, |env| {
        let text = string(env, &text)?;
        Ok(scanner().load(text) as jlong)
    })
}

/// `static native long nativeFind(long text, String pattern, int flags, int from)`: see
/// [`regex_scan::Scanner::find`].
#[no_mangle]
pub extern "system" fn Java_org_schabi_newpipe_extractor_utils_NativeRegex_nativeFind(
    mut env: JNIEnv,
    _class: JClass,
    text: jlong,
    pattern: JString,
    flags: jint,
    from: jint,
) -> jlong {
    guarded(&mut env, |env| {
        let pattern = string(env, &pattern)?;
        Ok(scanner().find(text as u64, &pattern, flags, from.max(0) as usize))
    })
}

/// `static native void nativeClear()` (NativeRegex)
#[no_mangle]
pub extern "system" fn Java_org_schabi_newpipe_extractor_utils_NativeRegex_nativeClear(
    mut env: JNIEnv,
    _class: JClass,
) {
    guarded(&mut env, |_| {
        scanner().clear();
        Ok(())
    })
}

/// `static native int nativeVersion()`: lets Java check it has the library it was built with.
#[no_mangle]
pub extern "system" fn Java_org_schabi_newpipe_extractor_utils_NativeJavaScript_nativeVersion(
    _env: JNIEnv,
    _class: JClass,
) -> jint {
    1
}

/// `static native int nativeVersion()` (NativeRegex)
#[no_mangle]
pub extern "system" fn Java_org_schabi_newpipe_extractor_utils_NativeRegex_nativeVersion(
    _env: JNIEnv,
    _class: JClass,
) -> jint {
    1
}
