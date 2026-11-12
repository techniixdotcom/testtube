//! `cargo bench -- <n code file> [function name]`: the first call (evaluates the code) and later
//! calls, on code built by the extractor from a real player.
//! Without a file it uses a small built-in function.

use std::time::Instant;

use testtube_core::engine::Engine;

const FALLBACK: &str = "function deobfuscate_n_param(a){var b=a.split('');b.reverse();return b.join('')}";

fn main() {
    let mut cli = std::env::args().skip(1).filter(|a| !a.starts_with("--"));
    let code = cli.next().map(|p| std::fs::read_to_string(p).expect("read code")).unwrap_or_else(|| FALLBACK.into());
    let name = cli.next().unwrap_or_else(|| "deobfuscate_n_param".into());
    let inputs = ["PRdwTdCCOcfGh9", "u3o8fHkF-H0Tq1b", "Yiz8C3H_OBmwFNc", "abcdEFGH-ijkl_m", "Xr0Qp9sLk2mNvB"];

    let mut engine = Engine::new().expect("engine");
    let rss_before = rss_kb();
    let started = Instant::now();
    let handle = engine.load(&code);
    println!("load ({} KB): {:.1} ms {}", code.len() / 1024, ms(started), if handle.is_ok() { "ok" } else { "FAILED" });
    println!("memory kept for it: ~{} MB", rss_kb().saturating_sub(rss_before) / 1024);
    let handle = match handle {
        Ok(h) => h,
        Err(e) => return println!("  {e}"),
    };
    for input in inputs {
        let started = Instant::now();
        let result = engine.call(handle, &name, &[input.to_string()]);
        let shown = match &result {
            Ok(Some(r)) => r.clone(),
            Ok(None) => "evicted".into(),
            Err(e) => format!("ERR {}", e.0.lines().next().unwrap_or("")),
        };
        println!("quickjs {input} -> {shown}  {:.3} ms", ms(started));
    }
    // what the old code paid every time: a fresh evaluation
    let started = Instant::now();
    let mut fresh = Engine::new().expect("engine");
    let _ = fresh.run(&code, &name, &[inputs[0].to_string()]);
    println!("fresh engine, load + call: {:.1} ms", ms(started));
}

fn ms(started: Instant) -> f64 {
    started.elapsed().as_secs_f64() * 1000.0
}

/// Resident memory in KB (Linux; 0 elsewhere).
fn rss_kb() -> u64 {
    std::fs::read_to_string("/proc/self/status")
        .ok()
        .and_then(|s| s.lines().find(|l| l.starts_with("VmRSS:")).map(|l| l.split_whitespace().nth(1).unwrap_or("0").parse().unwrap_or(0)))
        .unwrap_or(0)
}
