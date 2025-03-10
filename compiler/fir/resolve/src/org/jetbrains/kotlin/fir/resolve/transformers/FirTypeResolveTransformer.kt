/*
 * Copyright 2010-2023 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.resolve.transformers

import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toPersistentList
import org.jetbrains.kotlin.KtFakeSourceElementKind
import org.jetbrains.kotlin.builtins.StandardNames
import org.jetbrains.kotlin.config.AnalysisFlags
import org.jetbrains.kotlin.config.LanguageFeature
import org.jetbrains.kotlin.config.LanguageVersionSettings
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.annotations.AnnotationUseSiteTarget
import org.jetbrains.kotlin.descriptors.annotations.AnnotationUseSiteTarget.*
import org.jetbrains.kotlin.fir.*
import org.jetbrains.kotlin.fir.declarations.*
import org.jetbrains.kotlin.fir.declarations.utils.fromPrimaryConstructor
import org.jetbrains.kotlin.fir.declarations.utils.isFromVararg
import org.jetbrains.kotlin.fir.declarations.utils.isInner
import org.jetbrains.kotlin.fir.declarations.utils.isLocal
import org.jetbrains.kotlin.fir.expressions.*
import org.jetbrains.kotlin.fir.expressions.builder.buildAnnotationCallCopy
import org.jetbrains.kotlin.fir.expressions.builder.buildAnnotationCopy
import org.jetbrains.kotlin.fir.expressions.builder.buildExpressionStub
import org.jetbrains.kotlin.fir.resolve.ScopeSession
import org.jetbrains.kotlin.fir.resolve.TypeResolutionConfiguration
import org.jetbrains.kotlin.fir.resolve.diagnostics.ConeAmbiguouslyResolvedAnnotationFromPlugin
import org.jetbrains.kotlin.fir.resolve.diagnostics.ConeCyclicTypeBound
import org.jetbrains.kotlin.fir.resolve.lookupSuperTypes
import org.jetbrains.kotlin.fir.scopes.FirScope
import org.jetbrains.kotlin.fir.scopes.createImportingScopes
import org.jetbrains.kotlin.fir.scopes.getNestedClassifierScope
import org.jetbrains.kotlin.fir.scopes.impl.FirMemberTypeParameterScope
import org.jetbrains.kotlin.fir.scopes.impl.nestedClassifierScope
import org.jetbrains.kotlin.fir.scopes.impl.wrapNestedClassifierScopeWithSubstitutionForSuperType
import org.jetbrains.kotlin.fir.types.*
import org.jetbrains.kotlin.fir.types.builder.buildErrorTypeRef
import org.jetbrains.kotlin.fir.visitors.transformSingle
import org.jetbrains.kotlin.name.StandardClassIds
import org.jetbrains.kotlin.util.PrivateForInline
import org.jetbrains.kotlin.utils.addToStdlib.shouldNotBeCalled

class FirTypeResolveProcessor(
    session: FirSession,
    scopeSession: ScopeSession
) : FirTransformerBasedResolveProcessor(session, scopeSession, FirResolvePhase.TYPES) {
    override val transformer: FirTypeResolveTransformer = FirTypeResolveTransformer(session, scopeSession)
}

fun <F : FirClassLikeDeclaration> F.runTypeResolvePhaseForLocalClass(
    session: FirSession,
    scopeSession: ScopeSession,
    currentScopeList: List<FirScope>,
    useSiteFile: FirFile,
    containingDeclarations: List<FirDeclaration>,
): F {
    val transformer = FirTypeResolveTransformer(
        session,
        scopeSession,
        currentScopeList,
        initialCurrentFile = useSiteFile,
        classDeclarationsStack = containingDeclarations.filterIsInstanceTo(ArrayDeque())
    )

    return this.transform(transformer, null)
}

@OptIn(PrivateForInline::class)
open class FirTypeResolveTransformer(
    final override val session: FirSession,
    @property:PrivateForInline val scopeSession: ScopeSession,
    initialScopes: List<FirScope> = emptyList(),
    initialCurrentFile: FirFile? = null,
    @property:PrivateForInline val classDeclarationsStack: ArrayDeque<FirClass> = ArrayDeque()
) : FirAbstractTreeTransformer<Any?>(FirResolvePhase.TYPES) {
    /**
     * All current scopes sorted from outermost to innermost.
     */
    @PrivateForInline
    var scopes: PersistentList<FirScope> = initialScopes.asReversed().toPersistentList()

    /**
     * Scopes that are accessible statically, i.e. [scopes] minus type parameter scopes.
     */
    @PrivateForInline
    var staticScopes: PersistentList<FirScope> = scopes

    @set:PrivateForInline
    var scopesBefore: PersistentList<FirScope>? = null

    @set:PrivateForInline
    var staticScopesBefore: PersistentList<FirScope>? = null

    private var currentDeclaration: FirDeclaration? = null

    private inline fun <T> withDeclaration(declaration: FirDeclaration, crossinline action: () -> T): T {
        val oldDeclaration = currentDeclaration
        return try {
            currentDeclaration = declaration
            action()
        } finally {
            currentDeclaration = oldDeclaration
        }
    }

    private val typeResolverTransformer: FirSpecificTypeResolverTransformer = FirSpecificTypeResolverTransformer(session, expandTypeAliases = true)

    @PrivateForInline
    var currentFile: FirFile? = initialCurrentFile

    override fun transformFile(file: FirFile, data: Any?): FirFile {
        checkSessionConsistency(file)
        return withFileScope(file) {
            super.transformFile(file, data)
        }
    }

    inline fun <R> withFileScope(file: FirFile, crossinline action: () -> R): R {
        currentFile = file
        return withScopeCleanup {
            addScopes(createImportingScopes(file, session, scopeSession))
            action()
        }
    }

    override fun transformRegularClass(regularClass: FirRegularClass, data: Any?): FirStatement {
        whileAnalysing(session, regularClass) {
            withClassDeclarationCleanup(regularClass) {
                transformClassTypeParameters(regularClass, data)
                return resolveClassContent(regularClass, data)
            }
        }
    }

    fun transformClassTypeParameters(regularClass: FirRegularClass, data: Any?) {
        withScopeCleanup {
            // Remove type parameter scopes for classes that are neither inner nor local
            if (removeOuterTypeParameterScope(regularClass)) {
                this.scopes = staticScopes
            }
            addTypeParametersScope(regularClass)
            regularClass.typeParameters.forEach {
                it.accept(this, data)
            }
            unboundCyclesInTypeParametersSupertypes(regularClass)
        }
    }

    inline fun <R> withClassDeclarationCleanup(regularClass: FirRegularClass, action: () -> R): R {
        return withClassDeclarationCleanup(classDeclarationsStack, regularClass, action)
    }

    override fun transformAnonymousObject(anonymousObject: FirAnonymousObject, data: Any?): FirStatement {
        withClassDeclarationCleanup(classDeclarationsStack, anonymousObject) {
            return resolveClassContent(anonymousObject, data)
        }
    }

    override fun transformConstructor(constructor: FirConstructor, data: Any?): FirConstructor = whileAnalysing(session, constructor) {
        return withScopeCleanup {
            addTypeParametersScope(constructor)
            val result = transformDeclaration(constructor, data) as FirConstructor

            if (result.isPrimary) {
                val shouldAddDefaultStubs = session.languageVersionSettings.getFlag(AnalysisFlags.stdlibCompilation) &&
                        session.moduleData.isCommon &&
                        constructor.returnTypeRef.coneType.classId == StandardClassIds.Enum
                for (valueParameter in result.valueParameters) {
                    if (valueParameter.correspondingProperty != null) {
                        valueParameter.moveOrDeleteIrrelevantAnnotations()
                    }
                    if (shouldAddDefaultStubs) {
                        valueParameter.replaceDefaultValue(buildExpressionStub()) // TODO: Remove when KT-67381 is implemented
                    }
                }
            }

            result
        }
    }

    override fun transformAnonymousInitializer(anonymousInitializer: FirAnonymousInitializer, data: Any?): FirAnonymousInitializer {
        return withScopeCleanup {
            transformDeclaration(anonymousInitializer, data) as FirAnonymousInitializer
        }
    }

    override fun transformErrorPrimaryConstructor(errorPrimaryConstructor: FirErrorPrimaryConstructor, data: Any?): FirConstructor =
        transformConstructor(errorPrimaryConstructor, data)

    override fun transformTypeAlias(typeAlias: FirTypeAlias, data: Any?): FirTypeAlias = whileAnalysing(session, typeAlias) {
        withScopeCleanup {
            addTypeParametersScope(typeAlias)
            transformDeclaration(typeAlias, data)
        } as FirTypeAlias
    }

    override fun transformEnumEntry(enumEntry: FirEnumEntry, data: Any?): FirEnumEntry = whileAnalysing(session, enumEntry) {
        enumEntry.transformReturnTypeRef(this, data)
        enumEntry.transformTypeParameters(this, data)
        enumEntry.transformAnnotations(this, data)
        enumEntry
    }

    override fun transformReceiverParameter(receiverParameter: FirReceiverParameter, data: Any?): FirReceiverParameter {
        return receiverParameter.transformAnnotations(this, data).transformTypeRef(this, data)
    }

    override fun transformProperty(property: FirProperty, data: Any?): FirProperty = whileAnalysing(session, property) {
        withScopeCleanup {
            withDeclaration(property) {
                addTypeParametersScope(property)
                property.transformTypeParameters(this, data)
                    .transformReturnTypeRef(this, data)
                    .transformReceiverParameter(this, data)
                    .transformContextParameters(this, data)
                    .transformGetter(this, data)
                    .transformSetter(this, data)
                    .transformBackingField(this, data)
                    .transformAnnotations(this, data)

                if (property.isFromVararg == true) {
                    property.transformTypeToArrayType(session)
                    property.backingField?.transformTypeToArrayType(session)
                    setAccessorTypesByPropertyType(property)
                }

                when {
                    property.returnTypeRef is FirResolvedTypeRef && property.delegate != null -> {
                        setAccessorTypesByPropertyType(property)
                    }
                    property.returnTypeRef !is FirResolvedTypeRef && property.initializer == null &&
                            property.getter?.returnTypeRef is FirResolvedTypeRef -> {
                        val returnTypeRef = property.getter!!.returnTypeRef

                        property.replaceReturnTypeRef(returnTypeRef.copyWithNewSourceKind(KtFakeSourceElementKind.PropertyTypeFromGetterReturnType))
                        property.backingField?.replaceReturnTypeRef(
                            returnTypeRef.copyWithNewSourceKind(KtFakeSourceElementKind.PropertyTypeFromGetterReturnType)
                        )

                        property.setter?.valueParameters?.forEach {
                            it.replaceReturnTypeRef(
                                returnTypeRef.copyWithNewSourceKind(KtFakeSourceElementKind.PropertyTypeFromGetterReturnType)
                            )
                        }
                    }
                }

                unboundCyclesInTypeParametersSupertypes(property)

                property.moveOrDeleteIrrelevantAnnotations()
                property
            }
        }
    }

    private fun setAccessorTypesByPropertyType(property: FirProperty) {
        property.getter?.replaceReturnTypeRef(property.returnTypeRef)
        property.setter?.valueParameters?.map { it.replaceReturnTypeRef(property.returnTypeRef) }
    }

    override fun transformField(field: FirField, data: Any?): FirField = whileAnalysing(session, field) {
        withScopeCleanup {
            field.transformReturnTypeRef(this, data).transformAnnotations(this, data)
            field
        }
    }

    override fun transformBackingField(backingField: FirBackingField, data: Any?): FirStatement = whileAnalysing(session, backingField) {
        backingField.transformAnnotations(this, data)
        super.transformBackingField(backingField, data)
    }

    override fun transformSimpleFunction(
        simpleFunction: FirSimpleFunction,
        data: Any?,
    ): FirSimpleFunction = whileAnalysing(session, simpleFunction) {
        withScopeCleanup {
            withDeclaration(simpleFunction) {
                addTypeParametersScope(simpleFunction)
                val result = transformDeclaration(simpleFunction, data).also {
                    unboundCyclesInTypeParametersSupertypes(it as FirTypeParametersOwner)
                }

                if (result.source?.kind == KtFakeSourceElementKind.DataClassGeneratedMembers &&
                    result is FirSimpleFunction &&
                    result.name == StandardNames.DATA_CLASS_COPY
                ) {
                    for (valueParameter in result.valueParameters) {
                        valueParameter.moveOrDeleteIrrelevantAnnotations()
                    }
                }

                result
            }
        } as FirSimpleFunction
    }

    private fun unboundCyclesInTypeParametersSupertypes(typeParametersOwner: FirTypeParameterRefsOwner) {
        for (typeParameter in typeParametersOwner.typeParameters) {
            if (typeParameter !is FirTypeParameter) continue
            if (hasSupertypePathToParameter(typeParameter, typeParameter, mutableSetOf())) {
                val errorType = buildErrorTypeRef {
                    diagnostic = ConeCyclicTypeBound(typeParameter.symbol, typeParameter.bounds.toImmutableList())
                    source = typeParameter.bounds.first().source
                }
                typeParameter.replaceBounds(
                    listOf(errorType)
                )
            }
        }
    }

    private fun hasSupertypePathToParameter(
        currentTypeParameter: FirTypeParameter,
        typeParameter: FirTypeParameter,
        visited: MutableSet<FirTypeParameter>
    ): Boolean {
        if (visited.isNotEmpty() && currentTypeParameter == typeParameter) return true
        if (!visited.add(currentTypeParameter)) return false

        fun ConeKotlinType.toNextTypeParameter(): FirTypeParameter? = when (this) {
            is ConeTypeParameterType -> lookupTag.typeParameterSymbol.fir
            is ConeDefinitelyNotNullType -> original.toNextTypeParameter()
            else -> null
        }

        return currentTypeParameter.bounds.any {
            val nextTypeParameter = it.coneTypeOrNull?.toNextTypeParameter() ?: return@any false

            hasSupertypePathToParameter(nextTypeParameter, typeParameter, visited)
        }
    }

    override fun transformImplicitTypeRef(implicitTypeRef: FirImplicitTypeRef, data: Any?): FirTypeRef {
        return implicitTypeRef
    }

    override fun transformTypeRef(typeRef: FirTypeRef, data: Any?): FirResolvedTypeRef {
        return typeRef.transform(
            typeResolverTransformer,
            TypeResolutionConfiguration(scopes.asReversed(), classDeclarationsStack, currentFile)
        )
    }

    override fun transformValueParameter(
        valueParameter: FirValueParameter,
        data: Any?,
    ): FirStatement = whileAnalysing(session, valueParameter) {
        withDeclaration(valueParameter) {
            valueParameter.transformReturnTypeRef(this, data)
            valueParameter.transformAnnotations(this, data)
            valueParameter.transformVarargTypeToArrayType(session)
            valueParameter
        }
    }

    override fun transformBlock(block: FirBlock, data: Any?): FirStatement {
        return block
    }

    override fun transformArgumentList(argumentList: FirArgumentList, data: Any?): FirArgumentList {
        return argumentList
    }

    override fun transformAnnotation(annotation: FirAnnotation, data: Any?): FirStatement {
        shouldNotBeCalled()
    }

    override fun transformAnnotationCall(
        annotationCall: FirAnnotationCall,
        data: Any?
    ): FirStatement = whileAnalysing(session, annotationCall) {
        when (val originalTypeRef = annotationCall.annotationTypeRef) {
            is FirResolvedTypeRef -> {
                when (annotationCall.annotationResolvePhase) {
                    FirAnnotationResolvePhase.Unresolved -> when (originalTypeRef) {
                        is FirErrorTypeRef -> return annotationCall.also { it.replaceAnnotationResolvePhase(FirAnnotationResolvePhase.Types) }
                        else -> shouldNotBeCalled()
                    }
                    FirAnnotationResolvePhase.CompilerRequiredAnnotations -> {
                        annotationCall.transformTypeArguments(this, data)
                        annotationCall.replaceAnnotationResolvePhase(FirAnnotationResolvePhase.Types)
                        val alternativeResolvedTypeRef =
                            originalTypeRef.delegatedTypeRef?.transformSingle(this, data) ?: return annotationCall
                        val coneTypeFromCompilerRequiredPhase = originalTypeRef.coneType
                        val coneTypeFromTypesPhase = alternativeResolvedTypeRef.coneType
                        if (coneTypeFromTypesPhase != coneTypeFromCompilerRequiredPhase) {
                            val errorTypeRef = buildErrorTypeRef {
                                source = originalTypeRef.source
                                coneType = coneTypeFromCompilerRequiredPhase
                                annotations += originalTypeRef.annotations
                                delegatedTypeRef = originalTypeRef.delegatedTypeRef
                                diagnostic = ConeAmbiguouslyResolvedAnnotationFromPlugin(
                                    coneTypeFromCompilerRequiredPhase,
                                    coneTypeFromTypesPhase
                                )
                            }
                            annotationCall.replaceAnnotationTypeRef(errorTypeRef)
                        }
                    }
                    FirAnnotationResolvePhase.Types -> {}
                }
            }
            else -> {
                val transformedTypeRef = originalTypeRef.transformSingle(this, data)
                annotationCall.transformTypeArguments(this, data)
                annotationCall.replaceAnnotationResolvePhase(FirAnnotationResolvePhase.Types)
                annotationCall.replaceAnnotationTypeRef(transformedTypeRef)
            }
        }

        return annotationCall
    }

    inline fun <T> withScopeCleanup(crossinline l: () -> T): T {
        val scopesBeforeSnapshot = scopes
        val scopesBeforeBeforeSnapshot = scopesBefore
        scopesBefore = scopesBeforeSnapshot

        val staticScopesBeforeSnapshot = staticScopes
        val staticScopesBeforeBeforeSnapshot = staticScopesBefore
        staticScopesBefore = staticScopesBeforeSnapshot

        return try {
            l()
        } finally {
            scopes = scopesBeforeSnapshot
            scopesBefore = scopesBeforeBeforeSnapshot
            staticScopes = staticScopesBeforeSnapshot
            staticScopesBefore = staticScopesBeforeBeforeSnapshot
        }
    }

    private fun resolveClassContent(
        firClass: FirClass,
        data: Any?
    ): FirStatement = withClassScopes(
        firClass,
        actionInsideStaticScope = {
            withScopeCleanup {
                firClass.transformAnnotations(this, null)

                if (firClass is FirRegularClass) {
                    addTypeParametersScope(firClass)
                }

                // ConstructedTypeRef should be resolved only with type parameters, but not with nested classes and classes from supertypes
                for (declaration in firClass.declarations) {
                    when (declaration) {
                        is FirConstructor -> transformDelegatedConstructorCall(declaration)
                        is FirField -> {
                            if (declaration.origin == FirDeclarationOrigin.Synthetic.DelegateField) {
                                transformDelegateField(declaration)
                            }
                        }

                        else -> {}
                    }
                }
            }
        }
    ) {
        // Note that annotations are still visited here
        // again, although there's no need in it
        transformElement(firClass, data)
    }

    fun transformDelegatedConstructorCall(constructor: FirConstructor) {
        constructor.delegatedConstructor?.let(this::resolveConstructedTypeRefForDelegatedConstructorCall)
    }

    fun transformDelegateField(field: FirField) {
        field.transformReturnTypeRef(this, null)
    }

    fun removeOuterTypeParameterScope(firClass: FirClass): Boolean = !firClass.isInner && !firClass.isLocal

    /**
     * Changes to the order of scopes should also be reflected in
     * [org.jetbrains.kotlin.fir.resolve.transformers.body.resolve.BodyResolveContext.withScopesForClass].
     * Otherwise, we get different behavior between type resolve and body resolve phases.
     */
    inline fun <R> withClassScopes(
        firClass: FirClass,
        crossinline actionInsideStaticScope: () -> Unit = {},
        crossinline action: () -> R,
    ): R = withScopeCleanup {
        // Remove type parameter scopes for classes that are neither inner nor local
        if (removeOuterTypeParameterScope(firClass)) {
            this.scopes = staticScopes
        }

        actionInsideStaticScope()

        // ? Is it Ok to use original file session here ?
        val superTypes = lookupSuperTypes(
            firClass,
            lookupInterfaces = false,
            deep = true,
            substituteTypes = true,
            useSiteSession = session
        ).asReversed()

        val scopesToAdd = mutableListOf<FirScope>()

        for (superType in superTypes) {
            superType.lookupTag.getNestedClassifierScope(session, scopeSession)?.let { nestedClassifierScope ->
                val scope = nestedClassifierScope.wrapNestedClassifierScopeWithSubstitutionForSuperType(superType, session)
                scopesToAdd.add(scope)
            }
        }

        if (firClass is FirRegularClass) {
            // Companion scope is added before static scope,
            // i.e., static scope is checked first during resolution (scopes are in reverse order).
            // This is because we can qualify companion scope using `Companion.` if we want to explicitly refer to a declaration in the
            // companion.
            firClass.companionObjectSymbol?.fir
                ?.let(session::nestedClassifierScope)
                ?.let(scopesToAdd::add)

            session.nestedClassifierScope(firClass)?.let(scopesToAdd::add)

            addScopes(scopesToAdd)
            addTypeParametersScope(firClass)
        } else {
            session.nestedClassifierScope(firClass)?.let(scopesToAdd::add)
            addScopes(scopesToAdd)
        }

        action()
    }

    private fun resolveConstructedTypeRefForDelegatedConstructorCall(
        delegatedConstructorCall: FirDelegatedConstructorCall
    ) {
        delegatedConstructorCall.replaceConstructedTypeRef(delegatedConstructorCall.constructedTypeRef.transformSingle(this, null))
        delegatedConstructorCall.transformCalleeReference(this, null)
    }

    fun addTypeParametersScope(firMemberDeclaration: FirMemberDeclaration) {
        if (firMemberDeclaration.typeParameters.isNotEmpty()) {
            scopes = scopes.add(FirMemberTypeParameterScope(firMemberDeclaration))
        }
    }

    fun addScopes(list: List<FirScope>) {
        // small optimization to skip unnecessary allocations
        val scopesAreTheSame = scopes === staticScopes

        scopes = scopes.addAll(list)
        staticScopes = if (scopesAreTheSame) scopes else staticScopes.addAll(list)
    }

    /**
     * Filters annotations by target.
     * For example, in the following snippet the annotation may apply to the constructor value parameter, the property or the underlying field:
     * ```
     * class Foo(@Ann val x: String)
     * ```
     * This ambiguity may be resolved by specifying the use-site explicitly, i.e. `@field:Ann` or by analysing the allowed targets from
     * the [kotlin.annotation.Target] meta-annotation.
     * In latter case, the method will ensure that the annotation is moved to the correct element (field or parameter) or left at the property.
     */
    private fun FirVariable.moveOrDeleteIrrelevantAnnotations() {
        if (annotations.isEmpty()) return
        val languageVersionSettings = session.languageVersionSettings
        replaceAnnotations(annotations.filter { annotation ->
            when (annotation.useSiteTarget) {
                null -> annotation.multiplexWithoutUseSiteTarget(this, languageVersionSettings)
                ALL -> annotation.multiplexWithAllUseSiteTarget(this, languageVersionSettings)
                else -> true
            }
        })
    }

    private fun FirAnnotation.multiplexWithoutUseSiteTarget(
        annotated: FirDeclaration,
        languageVersionSettings: LanguageVersionSettings
    ): Boolean {
        val allowedTargets = useSiteTargetsFromMetaAnnotation(session)
        return when (annotated) {
            // If parameter is allowed, we apply annotation to it in the first turn, independent of the targeting mode
            is FirValueParameter -> {
                CONSTRUCTOR_PARAMETER in allowedTargets
            }
            is FirProperty if annotated.fromPrimaryConstructor == true && CONSTRUCTOR_PARAMETER in allowedTargets -> {
                when {
                    !languageVersionSettings.supportsFeature(LanguageFeature.PropertyParamAnnotationDefaultTargetMode) -> {
                        false
                    }
                    // In the property-param mode,
                    // we should apply annotation also to the property (or to the field) if it's allowed
                    PROPERTY in allowedTargets -> true
                    annotated.backingField != null && propertyAnnotationShouldBeMovedToField(allowedTargets) -> {
                        if (classDeclarationsStack.lastOrNull()?.classKind != ClassKind.ANNOTATION_CLASS) {
                            val backingField = annotated.backingField!!
                            backingField.replaceAnnotations(backingField.annotations + this)
                        }
                        false
                    }
                    else -> false
                }
            }
            // Otherwise (for a regular property or for a constructor property if annotation isn't applicable to parameter),
            // we simply choose between a property and a field
            is FirProperty if annotated.backingField != null && propertyAnnotationShouldBeMovedToField(allowedTargets) -> {
                val backingField = annotated.backingField!!
                backingField.replaceAnnotations(backingField.annotations + this)
                false
            }
            // Here we can come with a regular (non-constructor) property without a backing field,
            // or with some other non-parameter variable
            else -> {
                true
            }
        }
    }

    private fun FirAnnotation.multiplexWithAllUseSiteTarget(
        annotated: FirDeclaration,
        languageVersionSettings: LanguageVersionSettings
    ): Boolean {
        if (!languageVersionSettings.supportsFeature(LanguageFeature.AnnotationAllUseSiteTarget)) {
            return true
        }
        val allowedTargets = useSiteTargetsFromMetaAnnotation(session)
        return when (annotated) {
            is FirValueParameter -> {
                CONSTRUCTOR_PARAMETER in allowedTargets
            }
            is FirProperty -> {
                var addedSomewhere = false

                fun FirCallableDeclaration.addAnnotationWithoutUseSiteTarget(annotation: FirAnnotation) {
                    val copy = if (annotation is FirAnnotationCall) {
                        buildAnnotationCallCopy(annotation) {
                            useSiteTarget = null
                        }
                    } else {
                        buildAnnotationCopy(annotation) {
                            useSiteTarget = null
                        }
                    }
                    replaceAnnotations(annotations + copy)
                    addedSomewhere = true
                }

                if (FIELD in allowedTargets && annotated.delegate == null) {
                    annotated.backingField?.addAnnotationWithoutUseSiteTarget(this)
                }
                if (PROPERTY_GETTER in allowedTargets) {
                    annotated.getter?.addAnnotationWithoutUseSiteTarget(this)
                }
                if (annotated.isVar && SETTER_PARAMETER in allowedTargets) {
                    annotated.setter?.valueParameters?.firstOrNull()?.addAnnotationWithoutUseSiteTarget(this)
                }
                if (CONSTRUCTOR_PARAMETER in allowedTargets && annotated.fromPrimaryConstructor == true) {
                    // It's already on a constructor parameter, but we set the flag to prevent reporting an error
                    addedSomewhere = true
                }
                // If annotation isn't applicable anywhere or the property is delegated, we keep it at property to report an error later
                PROPERTY in allowedTargets || !addedSomewhere || annotated.delegate != null
            }
            else -> {
                true
            }
        }
    }

    /**
     * @param allowedTargets allowed use-site targets of a given property annotation
     * @return true if the given annotation on a property (initially placed there during raw FIR building)
     * is in fact inapplicable to properties, but applicable to fields.
     */
    private fun propertyAnnotationShouldBeMovedToField(allowedTargets: Set<AnnotationUseSiteTarget>): Boolean =
        (FIELD in allowedTargets || PROPERTY_DELEGATE_FIELD in allowedTargets) && PROPERTY !in allowedTargets
}
