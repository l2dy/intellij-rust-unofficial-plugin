/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.ide.annotator.fixes

import org.rust.ide.annotator.ExplicitPreview
import org.rust.ide.annotator.RsAnnotatorTestBase
import org.rust.ide.annotator.RsErrorAnnotator

class AddMissingSupertraitImplFixTest : RsAnnotatorTestBase(RsErrorAnnotator::class) {
    fun `test empty supertrait`() = checkFixByText("Implement missing supertrait(s)", """
        trait A {}
        trait B: A {}

        struct S;

        impl <error>B/*caret*/</error> for S {}
    """, """
        trait A {}
        trait B: A {}

        struct S;

        impl A for S {}

        impl B/*caret*/ for S {}
    """)

    fun `test supertrait with items`() = checkFixByText("Implement missing supertrait(s)", """
        trait A {
            type FOO;
            const BAR: u32;
            fn foo(&self);
        }
        trait B: A {}

        struct S;

        impl <error>B/*caret*/</error> for S {}
    """, """
        trait A {
            type FOO;
            const BAR: u32;
            fn foo(&self);
        }
        trait B: A {}

        struct S;

        impl A for S {
            type FOO = <selection>()</selection>;
            const BAR: u32 = 0;

            fn foo(&self) {
                todo!()
            }
        }

        impl B for S {}
    """)

    fun `test multiple supertraits`() = checkFixByText("Implement missing supertrait(s)", """
        trait A {}
        trait B {}
        trait C: A + B {}

        struct S;

        impl <error>C/*caret*/</error> for S {}
    """, """
        trait A {}
        trait B {}
        trait C: A + B {}

        struct S;

        impl A for S {}

        impl B for S {}

        impl C/*caret*/ for S {}
    """)

    fun `test grandparent supertrait`() = checkFixByText("Implement missing supertrait(s)", """
        trait A {}
        trait B: A {}
        trait C: B {}

        struct S;

        impl <error>C/*caret*/</error> for S {}
    """, """
        trait A {}
        trait B: A {}
        trait C: B {}

        struct S;

        impl B for S {}

        impl A for S {}

        impl C/*caret*/ for S {}
    """)

    fun `test do not implement supertrait multiple times`() = checkFixByText("Implement missing supertrait(s)", """
        trait A {}
        trait B: A {}
        trait C: B + A {}

        struct S;

        impl <error>C/*caret*/</error> for S {}
    """, """
        trait A {}
        trait B: A {}
        trait C: B + A {}

        struct S;

        impl B for S {}

        impl A for S {}

        impl C/*caret*/ for S {}
    """)

    fun `test implement supertrait multiple times with different generic arguments`() = checkFixByText("Implement missing supertrait(s)", """
        trait A<T> {}
        trait B: A<u32> {}
        trait C: B + A<bool> {}

        struct S;

        impl <error>C/*caret*/</error> for S {}
    """, """
        trait A<T> {}
        trait B: A<u32> {}
        trait C: B + A<bool> {}

        struct S;

        impl B for S {}

        impl A<u32> for S {}

        impl A<bool> for S {}

        impl C/*caret*/ for S {}
    """)

    fun `test already implemented trait`() = checkFixByText("Implement missing supertrait(s)", """
        trait A {}
        trait B {}
        trait C: A + B {}

        struct S;

        impl B for S {}

        impl <error>C/*caret*/</error> for S {}
    """, """
        trait A {}
        trait B {}
        trait C: A + B {}

        struct S;

        impl B for S {}

        impl A for S {}

        impl C/*caret*/ for S {}
    """)

    fun `test implemented trait for specific generic argument`() = checkFixByText("Implement missing supertrait(s)", """
        trait A<T> {
            fn foo(&self) -> T;
        }
        trait C<T>: A<T> {}

        struct S;

        impl <error>C<u32>/*caret*/</error> for S {}
    """, """
        trait A<T> {
            fn foo(&self) -> T;
        }
        trait C<T>: A<T> {}

        struct S;

        impl A<u32> for S {
            fn foo(&self) -> u32 {
                <selection>todo!()</selection>
            }
        }

        impl C<u32> for S {}
    """)

    fun `test trait partially implemented for specific type`() = checkFixByText("Implement missing supertrait(s)", """
        trait A<T> {}
        trait C<T>: A<T> {}

        struct S;

        impl A<u32> for S {}

        impl <R> <error>C<R>/*caret*/</error> for S {}
    """, """
        trait A<T> {}
        trait C<T>: A<T> {}

        struct S;

        impl A<u32> for S {}

        impl<R> A<R> for S {}

        impl <R> C<R>/*caret*/ for S {}
    """)

    fun `test generic type generic type argument`() = checkFixByText("Implement missing supertrait(s)", """
        trait A<T> {}
        trait B<T>: A<T> {}

        struct S<T>(T);

        impl <R> <error>B<u32>/*caret*/</error> for S<R> {}
    """, """
        trait A<T> {}
        trait B<T>: A<T> {}

        struct S<T>(T);

        impl<R> A<u32> for S<R> {}

        impl <R> B<u32> for S<R> {}
    """)

    fun `test generic type specific type argument`() = checkFixByText("Implement missing supertrait(s)", """
        trait A<T> {}
        trait B<T>: A<T> {}

        struct S<T>(T);

        impl <error>B<u32>/*caret*/</error> for S<bool> {}
    """, """
        trait A<T> {}
        trait B<T>: A<T> {}

        struct S<T>(T);

        impl A<u32> for S<bool> {}

        impl B<u32> for S<bool> {}
    """)

    // TODO: psiSubst merging?
    fun `test recursive generics`() = checkFixByText("Implement missing supertrait(s)", """
        trait T1<A> {}
        trait T2<B>: T1<B> {}
        trait T3<D>: T2<D> {}
        trait T4<R>: T3<R> {}
        trait T5<C>: T4<C> {}
        trait T6<D>: T5<D> {}
        trait T7<E>: T6<E> {}

        struct S<T>(T);

        impl <R> <error>T7<R>/*caret*/</error> for S<R> {}
    """, """
        trait T1<A> {}
        trait T2<B>: T1<B> {}
        trait T3<D>: T2<D> {}
        trait T4<R>: T3<R> {}
        trait T5<C>: T4<C> {}
        trait T6<D>: T5<D> {}
        trait T7<E>: T6<E> {}

        struct S<T>(T);

        impl<R> T6<R> for S<R> {}

        impl<R> T5<R> for S<R> {}

        impl<R> T4<R> for S<R> {}

        impl<R> T3<R> for S<R> {}

        impl<R> T2<R> for S<R> {}

        impl<R> T1<R> for S<R> {}

        impl <R> T7<R> for S<R> {}
    """)

    fun `test filter unused type parameters`() = checkFixByText("Implement missing supertrait(s)", """
        trait A {}
        trait B<T>: A {}

        struct S<T>(T);

        impl <R> <error>B<R>/*caret*/</error> for S<u32> {}
    """, """
        trait A {}
        trait B<T>: A {}

        struct S<T>(T);

        impl A for S<u32> {}

        impl <R> B<R> for S<u32> {}
    """)

    fun `test filter unused where clause`() = checkFixByText("Implement missing supertrait(s)", """
        trait A {}
        trait B<T>: A {}

        struct S<T>(T);

        impl <R> <error>B<R>/*caret*/</error> for S<u32> where R: A {}
    """, """
        trait A {}
        trait B<T>: A {}

        struct S<T>(T);

        impl A for S<u32> {}

        impl <R> B<R> for S<u32> where R: A {}
    """)

    fun `test import trait`() = checkFixByText("Implement missing supertrait(s)", """
        mod foo {
            pub trait A {}
            pub trait B: A {}
        }

        struct S;

        impl <error>foo::B/*caret*/</error> for S {}
    """, """
        use crate::foo::A;

        mod foo {
            pub trait A {}
            pub trait B: A {}
        }

        struct S;

        impl A for S {}

        impl foo::B/*caret*/ for S {}
    """, preview = ExplicitPreview("""
        mod foo {
            pub trait A {}
            pub trait B: A {}
        }

        struct S;

        impl A for S {}

        impl foo::B for S {}
    """))

    fun `test empty supertrait with an impl for normalizable associated type`() = checkFixByText("Implement missing supertrait(s)", """
        struct Struct;
        trait Trait { type Item; }
        impl Trait for Struct { type Item = S; }

        trait A {}
        trait B: A {}

        struct S;

        impl <error>B/*caret*/</error> for <Struct as Trait>::Item {}
    """, """
        struct Struct;
        trait Trait { type Item; }
        impl Trait for Struct { type Item = S; }

        trait A {}
        trait B: A {}

        struct S;

        impl A for <Struct as Trait>::Item {}

        impl B/*caret*/ for <Struct as Trait>::Item {}
    """)

    fun `test grandparent supertrait with an impl for normalizable associated type`() = checkFixByText("Implement missing supertrait(s)", """
        struct Struct;
        trait Trait { type Item; }
        impl Trait for Struct { type Item = S; }

        trait A {}
        trait B: A {}
        trait C: B {}

        struct S;

        impl A for S {}

        impl <error>C/*caret*/</error> for <Struct as Trait>::Item {}
    """, """
        struct Struct;
        trait Trait { type Item; }
        impl Trait for Struct { type Item = S; }

        trait A {}
        trait B: A {}
        trait C: B {}

        struct S;

        impl A for S {}

        impl B for <Struct as Trait>::Item {}

        impl C/*caret*/ for <Struct as Trait>::Item {}
    """)
}

// TODO: all kinds of bounds and generics
