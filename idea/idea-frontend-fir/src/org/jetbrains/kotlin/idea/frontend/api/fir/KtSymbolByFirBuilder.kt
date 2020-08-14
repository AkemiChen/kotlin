/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.frontend.api.fir

import com.google.common.collect.MapMaker
import com.intellij.openapi.project.Project
import com.intellij.psi.search.GlobalSearchScope
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.declarations.*
import org.jetbrains.kotlin.fir.declarations.impl.FirValueParameterImpl
import org.jetbrains.kotlin.fir.resolve.providers.FirSymbolProvider
import org.jetbrains.kotlin.fir.resolve.providers.getSymbolByLookupTag
import org.jetbrains.kotlin.fir.symbols.ConeClassLikeLookupTag
import org.jetbrains.kotlin.fir.symbols.ConeTypeParameterLookupTag
import org.jetbrains.kotlin.fir.symbols.impl.FirTypeParameterSymbol
import org.jetbrains.kotlin.fir.types.*
import org.jetbrains.kotlin.fir.types.impl.ConeClassLikeTypeImpl
import org.jetbrains.kotlin.idea.frontend.api.*
import org.jetbrains.kotlin.idea.frontend.api.fir.symbols.*
import org.jetbrains.kotlin.idea.frontend.api.fir.types.*
import org.jetbrains.kotlin.idea.frontend.api.fir.utils.weakRef
import org.jetbrains.kotlin.idea.frontend.api.symbols.*
import org.jetbrains.kotlin.idea.frontend.api.types.KtType
import org.jetbrains.kotlin.idea.stubindex.PackageIndexUtil
import org.jetbrains.kotlin.name.FqName
import java.util.concurrent.ConcurrentMap

/**
 * Maps FirElement to KtSymbol & ConeType to KtType, thread safe
 */
internal class KtSymbolByFirBuilder private constructor(
    firProvider: FirSymbolProvider,
    typeCheckerContext: ConeTypeCheckerContext,
    private val project: Project,
    override val token: ValidityToken,
    val withReadOnlyCaching: Boolean,
    private val symbolsCache: BuilderCache<FirDeclaration, KtSymbol>,
    private val typesCache: BuilderCache<ConeKotlinType, KtType>
) : ValidityTokenOwner {

    constructor(
        firProvider: FirSymbolProvider,
        typeCheckerContext: ConeTypeCheckerContext,
        project: Project,
        token: ValidityToken
    ) : this(
        firProvider = firProvider,
        typeCheckerContext = typeCheckerContext,
        project = project,
        token = token,
        withReadOnlyCaching = false,
        symbolsCache = BuilderCache(),
        typesCache = BuilderCache()
    )

    private val firProvider by weakRef(firProvider)
    private val typeCheckerContext by weakRef(typeCheckerContext)

    fun createReadOnlyCopy(): KtSymbolByFirBuilder {
        check(!withReadOnlyCaching) { "Cannot create readOnly KtSymbolByFirBuilder from a readonly one" }
        return KtSymbolByFirBuilder(
            firProvider,
            typeCheckerContext,
            project,
            token,
            withReadOnlyCaching = true,
            symbolsCache.createReadOnlyCopy(),
            typesCache.createReadOnlyCopy()
        )
    }


    fun buildSymbol(fir: FirDeclaration): KtSymbol = symbolsCache.cache(fir) {
        when (fir) {
            is FirRegularClass -> buildClassSymbol(fir)
            is FirSimpleFunction -> buildFunctionSymbol(fir)
            is FirProperty -> buildVariableSymbol(fir)
            is FirValueParameterImpl -> buildParameterSymbol(fir)
            is FirConstructor -> buildConstructorSymbol(fir)
            is FirTypeParameter -> buildTypeParameterSymbol(fir)
            is FirTypeAlias -> buildTypeAliasSymbol(fir)
            is FirEnumEntry -> buildEnumEntrySymbol(fir)
            is FirField -> buildFieldSymbol(fir)
            is FirAnonymousFunction -> buildAnonymousFunctionSymbol(fir)
            else ->
                TODO(fir::class.toString())
        }
    }

    // TODO Handle all relevant cases
    fun buildCallableSymbol(fir: FirCallableDeclaration<*>): KtCallableSymbol = buildSymbol(fir) as KtCallableSymbol

    fun buildClassLikeSymbol(fir: FirClassLikeDeclaration<*>): KtClassLikeSymbol = when (fir) {
        is FirRegularClass -> buildClassSymbol(fir)
        is FirTypeAlias -> buildTypeAliasSymbol(fir)
        else ->
            TODO(fir::class.toString())
    }

    fun buildClassSymbol(fir: FirRegularClass) = symbolsCache.cache(fir) { KtFirClassOrObjectSymbol(fir, token, this) }

    // TODO it can be a constructor parameter, which may be split into parameter & property
    // we should handle them both
    fun buildParameterSymbol(fir: FirValueParameterImpl) = symbolsCache.cache(fir) { KtFirFunctionValueParameterSymbol(fir, token, this) }
    fun buildFirConstructorParameter(fir: FirValueParameterImpl) =
        symbolsCache.cache(fir) { KtFirConstructorValueParameterSymbol(fir, token, this) }

    fun buildFunctionSymbol(fir: FirSimpleFunction, forcedOrigin: FirDeclarationOrigin? = null) = symbolsCache.cache(fir) {
        KtFirFunctionSymbol(fir, token, this, forcedOrigin)
    }

    fun buildConstructorSymbol(fir: FirConstructor) = symbolsCache.cache(fir) { KtFirConstructorSymbol(fir, token, this) }
    fun buildTypeParameterSymbol(fir: FirTypeParameter) = symbolsCache.cache(fir) { KtFirTypeParameterSymbol(fir, token) }

    fun buildTypeAliasSymbol(fir: FirTypeAlias) = symbolsCache.cache(fir) { KtFirTypeAliasSymbol(fir, token) }
    fun buildEnumEntrySymbol(fir: FirEnumEntry) = symbolsCache.cache(fir) { KtFirEnumEntrySymbol(fir, token, this) }
    fun buildFieldSymbol(fir: FirField) = symbolsCache.cache(fir) { KtFirJavaFieldSymbol(fir, token, this) }
    fun buildAnonymousFunctionSymbol(fir: FirAnonymousFunction) = symbolsCache.cache(fir) { KtFirAnonymousFunctionSymbol(fir, token, this) }

    fun buildVariableSymbol(fir: FirProperty, forcedOrigin: FirDeclarationOrigin? = null): KtVariableSymbol = symbolsCache.cache(fir) {
        when {
            fir.isLocal -> KtFirLocalVariableSymbol(fir, token, this)
            else -> KtFirPropertySymbol(fir, token, this, forcedOrigin)
        }
    }

    fun buildClassLikeSymbolByLookupTag(lookupTag: ConeClassLikeLookupTag): KtClassLikeSymbol? = withValidityAssertion {
        firProvider.getSymbolByLookupTag(lookupTag)?.fir?.let(::buildClassLikeSymbol)
    }

    fun buildTypeParameterSymbolByLookupTag(lookupTag: ConeTypeParameterLookupTag): KtTypeParameterSymbol? = withValidityAssertion {
        (firProvider.getSymbolByLookupTag(lookupTag) as? FirTypeParameterSymbol)?.fir?.let(::buildTypeParameterSymbol)
    }


    fun createPackageSymbolIfOneExists(packageFqName: FqName): KtFirPackageSymbol? {
        val exists = PackageIndexUtil.packageExists(packageFqName, GlobalSearchScope.allScope(project), project)
        if (!exists) {
            return null
        }
        return KtFirPackageSymbol(packageFqName, project, token)
    }

    fun buildTypeArgument(coneType: ConeTypeProjection): KtTypeArgument = when (coneType) {
        is ConeStarProjection -> KtStarProjectionTypeArgument
        is ConeKotlinTypeProjection -> KtFirTypeArgumentWithVariance(
            buildKtType(coneType.type),
            coneType.kind.toVariance()
        )
    }

    private fun ProjectionKind.toVariance() = when (this) {
        ProjectionKind.OUT -> KtTypeArgumentVariance.COVARIANT
        ProjectionKind.IN -> KtTypeArgumentVariance.CONTRAVARIANT
        ProjectionKind.INVARIANT -> KtTypeArgumentVariance.INVARIANT
        ProjectionKind.STAR -> error("KtStarProjectionTypeArgument be directly created")
    }


    fun buildKtType(coneType: FirTypeRef): KtType = buildKtType(coneType.coneTypeUnsafe<ConeKotlinType>())

    fun buildKtType(coneType: ConeKotlinType): KtType = typesCache.cache(coneType) {
        when (coneType) {
            is ConeClassLikeTypeImpl -> KtFirClassType(coneType, typeCheckerContext, token, this)
            is ConeTypeParameterType -> KtFirTypeParameterType(coneType, typeCheckerContext, token, this)
            is ConeClassErrorType -> KtFirErrorType(coneType, typeCheckerContext, token)
            is ConeFlexibleType -> KtFirFlexibleType(coneType, typeCheckerContext, token, this)
            is ConeIntersectionType -> KtFirIntersectionType(coneType, typeCheckerContext, token, this)
            else -> TODO()
        }
    }
}

private class BuilderCache<From, To> private constructor(
    private val cache: ConcurrentMap<From, To>,
    private val isReadOnly: Boolean
) {
    constructor() : this(cache = MapMaker().weakKeys().makeMap(), isReadOnly = false)

    fun createReadOnlyCopy(): BuilderCache<From, To> {
        check(!isReadOnly) { "Cannot create readOnly BuilderCache from a readonly one" }
        return BuilderCache(cache, isReadOnly = true)
    }

    inline fun <reified S : To> cache(key: From, calculation: () -> S): S {
        if (isReadOnly) {
            return (cache[key] ?: calculation()) as S
        }
        return cache.getOrPut(key, calculation) as S
    }
}

internal fun FirElement.buildSymbol(builder: KtSymbolByFirBuilder) =
    (this as? FirDeclaration)?.let(builder::buildSymbol)

internal fun FirDeclaration.buildSymbol(builder: KtSymbolByFirBuilder) =
    builder.buildSymbol(this)
