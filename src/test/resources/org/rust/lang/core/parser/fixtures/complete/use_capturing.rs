// =============================================================================
// Basic use bounds
// =============================================================================
fn empty() -> impl use<> Sized {}
fn one_lifetime<'a>() -> impl use<'a> Sized {}
fn one_type<T>() -> impl use<T> Sized {}
fn one_const<const N: usize>() -> impl use<N> Sized {}

// Multiple captures
fn multiple<'a, 'b, T, U>() -> impl use<'a, 'b, T, U> Iterator<Item = &'a T> {}

// With trait bounds
fn with_bounds<'a, T>() -> impl use<'a, T> Clone + Send + 'static {}

// Trailing comma (RFC 3617: optional trailing comma)
fn trailing_comma<'a, T,>() -> impl use<'a, T,> Sized {}

// =============================================================================
// Self in trait definitions (RFC 3617: Self only valid in trait definitions)
// =============================================================================
trait Foo {
    fn with_self() -> impl use<Self> Clone;
}

// =============================================================================
// Elided lifetime (RFC 3617: use<'_> captures elided lifetime)
// =============================================================================
fn elided(x: &()) -> impl use<'_> Sized { x }

// =============================================================================
// Outer impl generic parameters (in scope per RFC 3498)
// =============================================================================
struct S<'s, T>(&'s T);
impl<'s, T> S<'s, T> {
    fn method() -> impl use<'s, T> Sized {}
    fn method_with_own<'m, U>() -> impl use<'s, 'm, T, U> Sized {}
}

// =============================================================================
// Capturing from outer inherent impl (RFC 3617 example)
// =============================================================================
struct Ty<'a, 'b>(&'a (), &'b ());
impl<'a, 'b> Ty<'a, 'b> {
    fn foo(x: &'a (), _: &'b ()) -> impl use<'a> Sized { x }
}

// =============================================================================
// Nested in complex types
// =============================================================================
fn nested<'a, T>() -> Option<impl use<'a, T> Iterator<Item = &'a T>> { None }

// =============================================================================
// APIT context (RFC 3617: anonymous params cannot be named in use<..>)
// To capture APIT, must convert to named parameter
// =============================================================================
fn apit_no_capture(_: impl Sized) -> impl use<> Sized {}
// To capture the APIT type, name it:
fn apit_with_capture<T: Sized>(_: T) -> impl use<T> Sized {}

// =============================================================================
// Const generic semantics (RFC 3617)
// Must capture const to use in TYPE, but not to use as VALUE
// =============================================================================
fn const_as_type<const C: usize>() -> impl use<C> Sized { [(); C] }
fn const_as_value<const C: usize>() -> impl use<> Sized { C + 1 }

// =============================================================================
// Combining with for<..> (RFC 3617: use<..> applies to entire impl Trait)
// =============================================================================
fn with_hrtb<T>(_: T) -> impl use<T> for<'a> FnOnce(&'a ()) { |_| () }

// =============================================================================
// Higher-ranked lifetime in nested opaque (RFC 3617)
// Note: capturing higher-ranked lifetimes in nested opaques not yet supported
// =============================================================================
trait Tr<'a> { type Ty; }
impl Tr<'_> for () { type Ty = (); }
// This avoids capturing 'a from outer for<'a>:
fn avoid_hrtb_capture() -> impl for<'a> Tr<'a, Ty = impl use<> Sized> { () }
