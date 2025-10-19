#[macro_use]
extern crate rustc_macros;

pub mod edition;
pub mod symbol;

pub use symbol::{kw, sym, Symbol};

#[derive(Copy, Clone, Debug)]
pub struct Span;
