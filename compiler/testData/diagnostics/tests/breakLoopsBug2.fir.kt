// ISSUE: KT-71966

// FILE: A.kt

package com.jetbrains.cidr.lang.fixtures

open class OCDelegatingCodeInsightTestCase {
    open class Nested {}
}

// FILE: B.kt

class OCDelegatingCodeInsightTestCase : <!CYCLIC_INHERITANCE_HIERARCHY!>com.jetbrains.cidr.lang.fixtures.OCDelegatingCodeInsightTestCase.Nested<!>()
