/*
 * Copyright 2010-2022 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.analysis.native.checkers

import org.jetbrains.kotlin.fir.analysis.checkers.declaration.*

object NativeDeclarationExtraCheckers : DeclarationCheckers() {
    override val callableDeclarationCheckers: Set<FirCallableDeclarationChecker>
        get() = setOf(
            FirNativeSpecificAtomicChecker,
        )
}
