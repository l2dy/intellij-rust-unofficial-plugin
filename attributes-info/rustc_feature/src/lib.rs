use std::fmt;
use std::num::NonZeroU32;

pub use accepted::ACCEPTED_LANG_FEATURES;
pub use builtin_attrs::AttributeDuplicates;
pub use builtin_attrs::{
    deprecated_attributes, find_gated_cfg, is_builtin_attr_name, AttributeGate, AttributeTemplate,
    AttributeType, BuiltinAttribute, GatedCfg, BUILTIN_ATTRIBUTES, BUILTIN_ATTRIBUTE_MAP,
};
pub use removed::REMOVED_LANG_FEATURES;
use rustc_span::symbol::Symbol;
pub use unstable::{Features, INCOMPATIBLE_FEATURES, UNSTABLE_LANG_FEATURES};

mod accepted;
mod builtin_attrs;
mod removed;
mod unstable;

#[derive(Clone, Copy)]
pub enum State {
    Accepted,
    Active,
    Removed { reason: Option<&'static str> },
}

impl fmt::Debug for State {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            State::Accepted { .. } => write!(f, "accepted"),
            State::Active => write!(f, "active"),
            State::Removed { .. } => write!(f, "removed"),
        }
    }
}

#[derive(Debug, Clone, Copy)]
pub struct Feature {
    pub name: Symbol,
    pub since: &'static str,
    issue: Option<NonZeroU32>,
}

#[derive(Debug, Clone, Copy)]
pub struct RemovedFeature {
    pub feature: Feature,
    pub reason: Option<&'static str>,
}

#[derive(Copy, Clone, Debug)]
pub enum Stability {
    Unstable,
    // First argument is tracking issue link; second argument is an optional
    // help message, which defaults to "remove this attribute".
    Deprecated(&'static str, Option<&'static str>),
}

const fn to_nonzero(n: Option<u32>) -> Option<NonZeroU32> {
    // Can be replaced with `n.and_then(NonZeroU32::new)` if that is ever usable
    // in const context. Requires https://github.com/rust-lang/rfcs/pull/2632.
    match n {
        None => None,
        Some(n) => NonZeroU32::new(n),
    }
}
