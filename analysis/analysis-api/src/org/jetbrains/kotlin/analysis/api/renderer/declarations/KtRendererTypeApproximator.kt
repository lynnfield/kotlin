/*
 * Copyright 2010-2022 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.analysis.api.renderer.declarations

import org.jetbrains.kotlin.analysis.api.KtAnalysisSession
import org.jetbrains.kotlin.analysis.api.types.KtType
import org.jetbrains.kotlin.types.Variance

public interface KtRendererTypeApproximator {
    public fun approximateType(analysisSession: KtAnalysisSession, type: KtType, position: Variance): KtType

    public object TO_DENOTABLE : KtRendererTypeApproximator {
        override fun approximateType(analysisSession: KtAnalysisSession, type: KtType, position: Variance): KtType {
            with(analysisSession) {
                type.getEnhancedType()?.let { return it }

                return when (position) {
                    Variance.INVARIANT -> type
                    Variance.IN_VARIANCE -> type.approximateToSubPublicDenotableOrSelf(approximateLocalTypes = false)
                    Variance.OUT_VARIANCE -> type.approximateToSuperPublicDenotableOrSelf(approximateLocalTypes = false)
                }
            }
        }
    }

    public object NO_APPROXIMATION : KtRendererTypeApproximator {
        override fun approximateType(analysisSession: KtAnalysisSession, type: KtType, position: Variance): KtType {
            return type
        }
    }
}
