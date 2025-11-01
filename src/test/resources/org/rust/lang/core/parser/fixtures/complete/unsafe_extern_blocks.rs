// Edition 2024 syntax - extern blocks require unsafe
unsafe extern "C" {
    pub safe fn sqrt(x: f64) -> f64;
    pub unsafe fn strlen(p: *const u8) -> usize;
    pub fn default_unsafe(ptr: *mut u8);  // defaults to unsafe

    pub safe static SAFE_CONSTANT: i32;
    pub unsafe static UNSAFE_PTR: *const u8;
    static DEFAULT_UNSAFE: *mut u8;  // defaults to unsafe

    pub type OpaqueType;
}

// Multiple ABIs
unsafe extern {
    safe fn no_abi_safe();
}

unsafe extern "system" {
    unsafe fn system_unsafe();
}

unsafe extern "Rust" {
    fn rust_abi_fn();
}

// Legacy syntax (pre-2024) - still valid without unsafe
extern "C" {
    fn legacy_function();
    static LEGACY_STATIC: i32;
}

// Nested in modules
mod ffi {
    unsafe extern "C" {
        pub safe fn nested_safe();
    }
}

// With attributes
#[link(name = "mylib")]
unsafe extern "C" {
    #[link_name = "actual_name"]
    safe fn attributed_fn();
}
