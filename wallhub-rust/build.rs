use std::env;
use std::path::PathBuf;

fn main() {
    let source_dir = PathBuf::from(env::var_os("CARGO_MANIFEST_DIR").unwrap()).join("native");
    let mut build = cc::Build::new();
    build
        .cpp(true)
        .std("c++17")
        .opt_level(3)
        .include(&source_dir)
        .file(source_dir.join("ProcessRGB.cpp"))
        .file(source_dir.join("Dither.cpp"))
        .file(source_dir.join("Tables.cpp"))
        .file(source_dir.join("etc2_bridge.cpp"));

    if env::var_os("CARGO_CFG_TARGET_OS").as_deref() == Some(std::ffi::OsStr::new("android")) {
        build.define("__ANDROID__", None);
        build.cpp_link_stdlib("c++_static");
    }

    build.compile("wallhub_etc2");
    println!("cargo:rerun-if-changed={}", source_dir.display());
}
