#[macro_use]
extern crate rustc_macros;

pub mod edition;
pub mod symbol;

#[derive(Copy, Clone, Debug)]
pub struct Span;
