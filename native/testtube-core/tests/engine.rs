use testtube_core::engine::{Engine, MAX_SOURCES};

fn args(values: &[&str]) -> Vec<String> {
    values.iter().map(|s| s.to_string()).collect()
}

const N_LIKE: &str = r#"
var deobfuscate_n_param = function(a) {
    var b = a.split(""),
        c = [function(d, e) { e = (e % d.length + d.length) % d.length; d.splice(-e).reverse().forEach(function(f) { d.unshift(f) }) },
             -1453208271, function(d) { d.reverse() },
             function(d, e) { var f = d[0]; d[0] = d[e % d.length]; d[e % d.length] = f },
             null, b, "\u0000", function(d, e) { d.splice(0, 1, d.splice(e, 1, d[0])[0]) }];
    c[4] = c;
    try {
        c[0](c[5], 3); c[2](c[5]); c[3](c[5], 7); c[7](c[5], 2); c[0](c[5], -4);
    } catch (g) {
        return "enhanced_except_" + a;
    }
    return b.join("");
};
"#;

#[test]
fn runs_a_function_with_string_args() {
    let mut engine = Engine::new().unwrap();
    let code = "function deobfuscate(a){return a.split('').reverse().join('')}";
    assert_eq!(engine.run(code, "deobfuscate", &args(&["abc"])).unwrap(), "cba");
}

#[test]
fn coerces_results_like_string() {
    let mut engine = Engine::new().unwrap();
    assert_eq!(engine.run("function f(a){return a.length}", "f", &args(&["abcd"])).unwrap(), "4");
    assert_eq!(engine.run("function g(){return undefined}", "g", &[]).unwrap(), "undefined");
    assert_eq!(engine.run("function h(a,b){return a+b}", "h", &args(&["x", "y"])).unwrap(), "xy");
}

#[test]
fn n_like_function_is_deterministic_and_cached() {
    let mut engine = Engine::new().unwrap();
    let first = engine.run(N_LIKE, "deobfuscate_n_param", &args(&["PRdwTdCCOcfGh9"])).unwrap();
    let second = engine.run(N_LIKE, "deobfuscate_n_param", &args(&["PRdwTdCCOcfGh9"])).unwrap();
    assert_eq!(first, second);
    assert_eq!(first.len(), "PRdwTdCCOcfGh9".len());
    assert_ne!(first, "PRdwTdCCOcfGh9");
    assert!(!first.starts_with("enhanced_except_"));
    assert_eq!(engine.loaded(), 1, "same code is evaluated once");
}

#[test]
fn handles_are_stable_and_eviction_is_reported() {
    let mut engine = Engine::new().unwrap();
    let first = engine.load("function f(a){return 'first:'+a}").unwrap();
    assert_eq!(engine.load("function f(a){return 'first:'+a}").unwrap(), first);
    assert_eq!(engine.call(first, "f", &args(&["x"])).unwrap().as_deref(), Some("first:x"));
    for i in 0..MAX_SOURCES {
        engine.load(&format!("function f(a){{return '{i}'}}")).unwrap();
    }
    assert_eq!(engine.loaded(), MAX_SOURCES);
    assert_eq!(engine.call(first, "f", &args(&["x"])).unwrap(), None, "oldest one dropped");
    assert_ne!(engine.load("function f(a){return 'first:'+a}").unwrap(), first, "reloaded gets a new handle");
}

#[test]
fn recently_used_code_survives_eviction() {
    let mut engine = Engine::new().unwrap();
    let kept = engine.load("function k(){return 'kept'}").unwrap();
    for i in 0..MAX_SOURCES * 3 {
        engine.load(&format!("function f(){{return {i}}}")).unwrap();
        assert_eq!(engine.call(kept, "k", &[]).unwrap().as_deref(), Some("kept"));
    }
}

#[test]
fn check_finds_syntax_errors_without_running_code() {
    let mut engine = Engine::new().unwrap();
    assert!(engine.check("var x = function(a){ return a.split(''); };").is_ok());
    assert!(engine.check("var x = function(a){ return a.split(''); ").is_err());
    assert!(engine.check("while(true){}").is_ok(), "only parsed, never run");
    assert_eq!(engine.loaded(), 0, "a check keeps nothing");
}

#[test]
fn exceptions_and_missing_functions_are_errors() {
    let mut engine = Engine::new().unwrap();
    let err = engine.run("function f(){throw new Error('boom')}", "f", &[]).unwrap_err();
    assert!(err.0.contains("boom"), "{}", err.0);
    assert!(engine.run("function f(){}", "nope", &[]).is_err());
    assert!(engine.run("this is not javascript", "f", &[]).is_err());
}

#[test]
fn runaway_scripts_are_stopped() {
    let mut engine = Engine::new().unwrap();
    let started = std::time::Instant::now();
    assert!(engine.run("function f(){for(;;){}}", "f", &[]).is_err());
    assert!(started.elapsed().as_secs() < 10);
    // and the engine still works afterwards
    assert_eq!(engine.run("function g(){return 'ok'}", "g", &[]).unwrap(), "ok");
}

#[test]
fn deep_recursion_is_an_error_not_a_crash() {
    let mut engine = Engine::new().unwrap();
    assert!(engine.run("function f(n){return f(n+1)}", "f", &args(&["0"])).is_err());
}

#[test]
fn the_shared_engine_works_across_threads() {
    let code = "function t(a){return a+'!'}";
    let threads: Vec<_> = (0..8)
        .map(|i| std::thread::spawn(move || testtube_core::run(code, "t", &[i.to_string()]).unwrap()))
        .collect();
    let mut results: Vec<String> = threads.into_iter().map(|t| t.join().unwrap()).collect();
    results.sort();
    assert_eq!(results.len(), 8);
    assert_eq!(results[0], "0!");
}
