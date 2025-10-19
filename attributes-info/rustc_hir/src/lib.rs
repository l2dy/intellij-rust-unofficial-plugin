#[derive(Copy, Clone, PartialEq, Eq, Debug)]
pub enum AttrStyle {
    Outer,
    Inner,
}

pub mod attrs {
    #[derive(Copy, Clone, PartialEq, Eq)]
    pub enum EncodeCrossCrate {
        Yes,
        No,
    }
}
