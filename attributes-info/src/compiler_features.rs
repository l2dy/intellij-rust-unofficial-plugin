use std::collections::HashMap;
use std::fs::File;

use serde::ser::SerializeSeq;
use serde::{Deserialize, Serialize, Serializer};
use version_check::Version;

use rustc_feature::{
    Feature, Features, State, ACCEPTED_FEATURES, REMOVED_FEATURES, UNSTABLE_FEATURES,
};

use crate::formatter::MyFormatter;

#[derive(Serialize, Deserialize)]
struct FeatureInfo {
    name: String,
    state: String,
    since: String,
}

impl FeatureInfo {
    fn from(f: &Feature, s: &State) -> Self {
        FeatureInfo {
            name: f.name.as_str().to_string(),
            state: state_str(f, s),
            since: f.since.to_string(),
        }
    }
}

pub(crate) fn update_compiler_features_json(compiler_features_json_path: &str) {
    let current_features: Vec<FeatureInfo> =
        serde_json::from_reader(File::open(compiler_features_json_path).unwrap()).unwrap();
    let feature_map: HashMap<_, _> = current_features
        .iter()
        .map(|f| -> ((&str, &str), &str) { ((&f.name, &f.state), &f.since) })
        .collect();

    let f = File::create(compiler_features_json_path).unwrap();
    let mut serializer = serde_json::Serializer::with_formatter(f, MyFormatter::new());

    let compiler_version = Version::read().unwrap().to_string();

    let mut seq = serializer.serialize_seq(None).unwrap();
    [
        ACCEPTED_FEATURES
            .iter()
            .map(|f| (f, State::Accepted))
            .collect(),
        UNSTABLE_FEATURES
            .iter()
            .map(|f| (f, State::Active))
            .collect(),
        REMOVED_FEATURES
            .iter()
            .map(|f| (&f.feature, State::Removed { reason: f.reason }))
            .collect(),
    ]
    .iter()
    .flat_map(|features: &Vec<(&Feature, State)>| {
        features.iter().map(|(f, s)| FeatureInfo::from(f, s))
    })
    .for_each(|f| {
        seq.serialize_element(&replace_version_placeholder(
            &feature_map,
            &compiler_version,
            f,
        ))
        .unwrap()
    });
    seq.end().unwrap();
}

fn replace_version_placeholder(
    feature_map: &HashMap<(&str, &str), &str>,
    compiler_version: &str,
    f: FeatureInfo,
) -> FeatureInfo {
    // After https://github.com/rust-lang/rust/pull/100591, compiler uses `CURRENT_RUSTC_VERSION` placeholder
    // as value of `since` field which means that feature is available in the current compiler.
    // It's ok for the compiler, but the plugin should work with different compiler versions,
    // so it needs some concrete version when the feature became available to annotate code properly.
    //
    // The main idea to solve this problem is to use already saved values:
    // - if feature has placeholder and we didn't meet it before,
    //   the current compiler version will be used to replace the placeholder.
    //   Here we assume that this code is launched with the same (or almost the same) rustc version
    //   as in rustc master
    // - otherwise, just use version saved previously
    if f.since == "CURRENT_RUSTC_VERSION" {
        let old_feature = feature_map.get(&(&f.name, &f.state));
        let since = if let Some(v) = old_feature {
            v
        } else {
            compiler_version
        };
        FeatureInfo {
            since: since.to_string(),
            ..f
        }
    } else {
        f
    }
}

fn state_str(feature: &Feature, state: &State) -> String {
    match state {
        State::Accepted => "accepted",
        State::Active => {
            if Features::default().incomplete(feature.name) {
                "incomplete"
            } else {
                "active"
            }
        }
        State::Removed { .. } => "removed",
    }
    .to_string()
}
