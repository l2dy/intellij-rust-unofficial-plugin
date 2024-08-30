/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.ide.annotator

import org.rust.MockEdition
import org.rust.SkipTestWrapping
import org.rust.cargo.project.workspace.CargoWorkspace.Edition
import org.rust.ide.colors.RsColor

@SkipTestWrapping
class RsEdition2024KeywordsAnnotatorTest : RsAnnotatorTestBase(RsEdition2024KeywordsAnnotator::class) {

    override fun setUp() {
        super.setUp()
        annotationFixture.registerSeverities(listOf(RsColor.KEYWORD.testSeverity))
    }

    @MockEdition(Edition.EDITION_2015)
    fun `test edition 2024 keywords in edition 2015`() = checkErrors("""
        fn main() {
            let gen = ();
            let z = gen;
        }
    """)

    @MockEdition(Edition.EDITION_2024)
    fun `test edition 2024 keywords in edition 2024`() = checkErrors("""
        fn main() {
            let <error descr="`gen` is reserved keyword in Edition 2024">gen</error> = ();
            let z = <error descr="`gen` is reserved keyword in Edition 2024">gen</error>;
        }
    """)

    // We should report an error here
    fun `test reserved keywords in macro names in edition 2024`() = checkErrors("""
        fn main() {
            let z = gen!(());
        }
    """)

    @MockEdition(Edition.EDITION_2015)
    fun `test gen in edition 2015`() = checkErrors("""
        fn main() {
            <error descr="This feature is only available in Edition 2024">gen</error> { () };
        }
    """)

    @MockEdition(Edition.EDITION_2024)
    fun `test gen in edition 2024`() = checkErrors("""
        fn main() {
            <KEYWORD>gen</KEYWORD> { () };
        }
    """)
}
