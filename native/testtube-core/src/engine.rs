//! A QuickJS engine that keeps YouTube's deobfuscation functions compiled between calls.
//!
//! Rhino (what the extractor used before) started a fresh context and re-parsed the whole
//! function source on every call, in interpreted mode: one full parse per stream URL, dozens per
//! video. Here each distinct source is evaluated once into its own context and the function is
//! kept, so a call is just the call.

use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Arc;
use std::time::Instant;

use rquickjs::convert::Coerced;
use rquickjs::function::Rest;
use rquickjs::{CatchResultExt, Context, Function, Runtime};

/// Sources kept compiled. YouTube has one sig and one n source per player version (the n one is
/// the whole player, a few MB, ~20-40 MB once evaluated); two spare slots cover a player change
/// mid-session.
pub const MAX_SOURCES: usize = 4;
/// Room for two evaluated players at once; stops a runaway script.
const MEMORY_LIMIT: usize = 256 * 1024 * 1024;
/// Below the 1 MB of Android's worker threads, so QuickJS throws before the thread overflows.
const STACK_LIMIT: usize = 512 * 1024;
/// A call that takes longer than this is stuck (the real ones take microseconds).
const CALL_TIMEOUT_MS: u64 = 3_000;

#[derive(Debug)]
pub struct JsError(pub String);

impl std::fmt::Display for JsError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(&self.0)
    }
}

impl std::error::Error for JsError {}

pub type JsResult<T> = Result<T, JsError>;

struct Source {
    id: u64,
    code: Box<str>,
    context: Context,
}

pub struct Engine {
    runtime: Runtime,
    /// Most recently used last.
    sources: Vec<Source>,
    /// Deadline of the call in progress, in ms since `epoch`; 0 when idle.
    deadline: Arc<AtomicU64>,
    epoch: Instant,
    next_id: u64,
}

impl Engine {
    pub fn new() -> JsResult<Self> {
        let runtime = Runtime::new().map_err(err)?;
        runtime.set_memory_limit(MEMORY_LIMIT);
        runtime.set_max_stack_size(STACK_LIMIT);
        let deadline = Arc::new(AtomicU64::new(0));
        let epoch = Instant::now();
        let watched = deadline.clone();
        runtime.set_interrupt_handler(Some(Box::new(move || {
            let until = watched.load(Ordering::Relaxed);
            until != 0 && epoch.elapsed().as_millis() as u64 > until
        })));
        Ok(Self { runtime, sources: Vec::with_capacity(MAX_SOURCES), deadline, epoch, next_id: 1 })
    }

    /// Evaluates `code` (if it isn't loaded yet), so a later [`run`](Self::run) only calls.
    /// Fails on a syntax error or an exception at the top level, like Rhino's compile check.
    /// Returns a handle for [`call`](Self::call); the same code gets the same handle while it
    /// stays loaded.
    pub fn load(&mut self, code: &str) -> JsResult<u64> {
        let index = self.source_index(code)?;
        Ok(self.sources[index].id)
    }

    /// Calls a function of code loaded with [`load`](Self::load). `Ok(None)` if that code has
    /// been dropped since (more than [`MAX_SOURCES`] others loaded after it): load it again.
    pub fn call(&mut self, handle: u64, function_name: &str, args: &[String]) -> JsResult<Option<String>> {
        let Some(index) = self.sources.iter().rposition(|s| s.id == handle) else {
            return Ok(None);
        };
        self.touch(index);
        let index = self.sources.len() - 1;
        self.arm();
        let result = Self::call_in(&self.sources[index], function_name, args);
        self.disarm();
        result.map(Some)
    }

    /// Syntax check without running anything or keeping the code (Rhino's compile check).
    pub fn check(&mut self, code: &str) -> JsResult<()> {
        // `Function(code)` parses code as a function body and doesn't run it
        let context = Context::full(&self.runtime).map_err(err)?;
        self.arm();
        let checked = context.with(|ctx| {
            let constructor: Function = ctx.globals().get("Function").map_err(err)?;
            constructor.call::<_, rquickjs::Value>((code,)).catch(&ctx).map(|_| ()).map_err(err)
        });
        self.disarm();
        checked
    }

    /// Calls `function_name` defined by `code` with string `args`; the result is turned into a
    /// string the way JavaScript's `String(x)` does (Rhino's `toString()` did the same).
    pub fn run(&mut self, code: &str, function_name: &str, args: &[String]) -> JsResult<String> {
        let index = self.source_index(code)?;
        self.arm();
        let result = Self::call_in(&self.sources[index], function_name, args);
        self.disarm();
        result
    }

    /// Number of sources currently compiled (for tests).
    pub fn loaded(&self) -> usize {
        self.sources.len()
    }

    pub fn clear(&mut self) {
        self.sources.clear();
        self.runtime.run_gc();
    }

    fn call_in(source: &Source, function_name: &str, args: &[String]) -> JsResult<String> {
        source.context.with(|ctx| {
            // a property lookup on the global object: far below a microsecond
            let function: Function = ctx
                .globals()
                .get(function_name)
                .map_err(|_| JsError(format!("{function_name} is not a function")))?;
            let args: Vec<&str> = args.iter().map(String::as_str).collect();
            let value: Coerced<String> = function.call((Rest(args),)).catch(&ctx).map_err(err)?;
            Ok(value.0)
        })
    }

    fn source_index(&mut self, code: &str) -> JsResult<usize> {
        // newest first: the n code is several MB, but a memcmp of that is ~0.1 ms, and Java only
        // sends it again after the engine dropped it
        if let Some(found) = self.sources.iter().rposition(|s| s.code.len() == code.len() && &*s.code == code) {
            self.touch(found);
            return Ok(self.sources.len() - 1);
        }
        let context = Context::full(&self.runtime).map_err(err)?;
        self.arm();
        let evaluated = context.with(|ctx| {
            ctx.eval::<(), _>(code).catch(&ctx).map_err(err)
        });
        self.disarm();
        evaluated?;
        if self.sources.len() == MAX_SOURCES {
            self.sources.remove(0);
        }
        let id = self.next_id;
        self.next_id += 1;
        self.sources.push(Source { id, code: code.into(), context });
        Ok(self.sources.len() - 1)
    }

    /// Moves a source to the back: most recently used.
    fn touch(&mut self, index: usize) {
        if index + 1 != self.sources.len() {
            let source = self.sources.remove(index);
            self.sources.push(source);
        }
    }

    fn arm(&self) {
        let until = self.epoch.elapsed().as_millis() as u64 + CALL_TIMEOUT_MS;
        self.deadline.store(until.max(1), Ordering::Relaxed);
    }

    fn disarm(&self) {
        self.deadline.store(0, Ordering::Relaxed);
    }
}

impl Drop for Engine {
    fn drop(&mut self) {
        // contexts go before the runtime
        self.sources.clear();
    }
}

fn err(e: impl std::fmt::Display) -> JsError {
    JsError(e.to_string())
}
