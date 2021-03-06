/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.ide.folding

import org.rust.lang.RsTestBase

class RsFoldingBuilderTest : RsTestBase() {
    override val dataPath = "org/rust/ide/folding/fixtures"

    fun testFn() = doTest()
    fun testLoops() = doTest()
    fun testBlockExpr() = doTest()
    fun testImpl() = doTest()
    fun testImplMethod() = doTest()
    fun testStruct() = doTest()
    fun testStructExpr() = doTest()
    fun testTrait() = doTest()
    fun testTraitMethod() = doTest()
    fun testEnum() = doTest()
    fun testEnumVariant() = doTest()
    fun testMod() = doTest()
    fun testMatchExpr() = doTest()
    fun testMacroBraceArg() = doTest()
    fun testUseGlobList() = doTest()
    fun testComments() = doTest()
    fun testOneLinerFunction() = doTest()

    private fun doTest() {
        myFixture.testFolding("$testDataPath/$fileName")
    }
}
