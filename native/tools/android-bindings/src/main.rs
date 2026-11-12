// Same bindgen settings as rquickjs-sys 0.14's build.rs, for one target, with stand-in libc headers
fn main() {
    let a: Vec<String> = std::env::args().collect();
    let (target, header, stubs, resource, out) = (&a[1], &a[2], &a[3], &a[4], &a[5]);
    let b = bindgen::Builder::default()
        .use_core()
        .detect_include_paths(false)
        .clang_arg(format!("--target={target}"))
        .clang_arg("-xc")
        .clang_arg("-nostdinc")
        .clang_arg("-ffreestanding")
        .clang_arg(format!("-isystem{resource}"))
        .clang_arg(format!("-isystem{stubs}"))
        .clang_arg("-D_GNU_SOURCE")
        .size_t_is_usize(false)
        .header(header.clone())
        .allowlist_type("JS.*")
        .allowlist_function("js.*")
        .allowlist_function("JS.*")
        .allowlist_function("__JS.*")
        .allowlist_var("JS.*")
        .opaque_type("FILE")
        .blocklist_type("FILE")
        .blocklist_function("JS_DumpMemoryUsage");
    b.generate().expect("bindgen").write_to_file(out).expect("write");
}
