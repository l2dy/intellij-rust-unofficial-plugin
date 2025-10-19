use std::path::Path;
use std::{env, fs};

use reqwest::blocking::get;
use reqwest::IntoUrl;

fn main() {
    let commit = env::var("RUSTC_COMMIT").unwrap_or(String::from("master"));

    download_file(format!("https://raw.githubusercontent.com/rust-lang/rust/{commit}/compiler/rustc_feature/src/unstable.rs"), "unstable.rs");
    download_file(format!("https://raw.githubusercontent.com/rust-lang/rust/{commit}/compiler/rustc_feature/src/accepted.rs"), "accepted.rs");
    download_file(format!("https://raw.githubusercontent.com/rust-lang/rust/{commit}/compiler/rustc_feature/src/removed.rs"), "removed.rs");
    download_file(format!("https://raw.githubusercontent.com/rust-lang/rust/{commit}/compiler/rustc_feature/src/builtin_attrs.rs"), "builtin_attrs.rs");
}

fn download_file<T: IntoUrl>(url: T, file_name: &str) {
    let text = get(url)
        .unwrap()
        .text()
        .unwrap()
        .lines()
        .skip_while(|line| line.starts_with("//!"))
        .collect::<Vec<_>>()
        .join("\n");

    let text = if file_name == "unstable.rs" {
        adjust_unstable_file(text)
    } else {
        text
    };

    let out_dir = env::var("OUT_DIR").unwrap();
    let dest_path = Path::new(&out_dir).join(file_name);
    fs::write(dest_path, &text).unwrap();
}

fn adjust_unstable_file(text: String) -> String {
    const OLD_BODY: &str = r#"        self.enabled_lang_features
            .iter()
            .map(|feat| (feat.gate_name, feat.attr_sp))
            .chain(self.enabled_lib_features.iter().map(|feat| (feat.gate_name, feat.attr_sp)))
    }
"#;
    const NEW_BODY: &str = r#"        let mut enabled_features: Vec<_> = self
            .enabled_lang_features
            .iter()
            .map(|feat| (feat.gate_name, feat.attr_sp))
            .collect();
        enabled_features.extend(
            self.enabled_lib_features
                .iter()
                .map(|feat| (feat.gate_name, feat.attr_sp)),
        );
        enabled_features.into_iter()
    }
"#;

    if let Some(index) = text.find(OLD_BODY) {
        let mut patched = text.clone();
        patched.replace_range(index..index + OLD_BODY.len(), NEW_BODY);
        patched
    } else {
        panic!("failed to adjust unstable.rs: expected iterator body not found");
    }
}
