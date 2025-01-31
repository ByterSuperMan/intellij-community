// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.kotlin.idea.k2.refactoring.rename

import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiReference
import com.intellij.psi.search.searches.MethodReferencesSearch
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.usageView.UsageInfo
import org.jetbrains.kotlin.analysis.api.KtAnalysisSession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.calls.KtCallableMemberCall
import org.jetbrains.kotlin.analysis.api.calls.KtExplicitReceiverValue
import org.jetbrains.kotlin.analysis.api.calls.KtImplicitReceiverValue
import org.jetbrains.kotlin.analysis.api.calls.singleCallOrNull
import org.jetbrains.kotlin.analysis.api.calls.successfulCallOrNull
import org.jetbrains.kotlin.analysis.api.calls.symbol
import org.jetbrains.kotlin.analysis.api.scopes.KtScope
import org.jetbrains.kotlin.analysis.api.symbols.KtAnonymousObjectSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KtClassKind
import org.jetbrains.kotlin.analysis.api.symbols.KtClassOrObjectSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KtClassifierSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KtDeclarationSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KtNamedClassOrObjectSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KtReceiverParameterSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KtSyntheticJavaPropertySymbol
import org.jetbrains.kotlin.analysis.api.symbols.markers.KtNamedSymbol
import org.jetbrains.kotlin.analysis.api.types.KtErrorType
import org.jetbrains.kotlin.idea.base.psi.copied
import org.jetbrains.kotlin.idea.base.resources.KotlinBundle
import org.jetbrains.kotlin.idea.refactoring.conflicts.findSiblingsByName
import org.jetbrains.kotlin.idea.refactoring.conflicts.renderDescription
import org.jetbrains.kotlin.idea.refactoring.rename.BasicUnresolvableCollisionUsageInfo
import org.jetbrains.kotlin.idea.refactoring.rename.UsageInfoWithFqNameReplacement
import org.jetbrains.kotlin.idea.refactoring.rename.UsageInfoWithReplacement
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtCallableReferenceExpression
import org.jetbrains.kotlin.psi.KtClassLikeDeclaration
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtExpressionCodeFragment
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.jetbrains.kotlin.psi.KtSimpleNameExpression
import org.jetbrains.kotlin.psi.KtTypeReference
import org.jetbrains.kotlin.psi.createExpressionByPattern
import org.jetbrains.kotlin.psi.psiUtil.getQualifiedElementSelector
import org.jetbrains.kotlin.psi.psiUtil.getStrictParentOfType
import org.jetbrains.kotlin.utils.addIfNotNull
import kotlin.collections.mutableSetOf

fun checkClassNameShadowing(
    declaration: KtClassLikeDeclaration,
    newName: String,
    originalUsages: MutableList<UsageInfo>,
    newUsages: MutableList<UsageInfo>
) {

    val newFqName = declaration.fqName?.parent()?.let { it.child(Name.identifier(newName)) }

    if (newFqName != null) {
        val usageIterator = originalUsages.listIterator()
        while (usageIterator.hasNext()) {
            val usage = usageIterator.next()
            val refElement = usage.element as? KtSimpleNameExpression ?: continue
            val typeReference = refElement.getStrictParentOfType<KtTypeReference>() ?: continue

            fun createTypeFragment(type: String): KtExpressionCodeFragment {
                return KtPsiFactory(declaration.project).createExpressionCodeFragment("__foo__ as $type", typeReference)
            }

            val shortNameFragment = createTypeFragment(newName)
            val hasConflict = analyze(shortNameFragment) {
                val typeByShortName = shortNameFragment.getContentElement()?.getKtType()
                typeByShortName != null && typeByShortName !is KtErrorType
            }

            if (hasConflict) {
                usageIterator.set(UsageInfoWithFqNameReplacement(refElement, declaration, newFqName))
            }
        }
    }

    checkClassLikeNameShadowing(declaration, newName, newUsages)
}

fun checkClassLikeNameShadowing(declaration: KtNamedDeclaration, newName: String, newUsages: MutableList<UsageInfo>) {
    analyze(declaration) {
        //check outer classes hiding/hidden by rename
        val processedClasses = mutableSetOf<PsiElement>()
        retargetExternalDeclarations(declaration, newName) {
            val klass = it.psi
            val newFqName = (klass as? KtClassOrObject)?.fqName ?: (klass as? PsiClass)?.qualifiedName?.let { FqName.fromSegments(it.split(".")) }
            if (newFqName != null && klass != null && processedClasses.add(klass)) {
                for (ref in ReferencesSearch.search(klass, declaration.useScope)) {
                    val refElement = ref.element as? KtSimpleNameExpression ?: continue //todo cross language conflicts
                    if (refElement.getStrictParentOfType<KtTypeReference>() != null) {
                        //constructor (also implicit) calls would be processed together with other callables
                        newUsages.add(UsageInfoWithFqNameReplacement(refElement, declaration, newFqName))
                    }
                }
            }
        }
    }
}

fun checkCallableShadowing(
    declaration: KtNamedDeclaration,
    newName: String,
    originalUsages: MutableList<UsageInfo>,
    newUsages: MutableList<UsageInfo>
) {
    val psiFactory = KtPsiFactory(declaration.project)
    val externalDeclarations = mutableSetOf<PsiElement>()
    val usageIterator = originalUsages.listIterator()
    while (usageIterator.hasNext()) {

        val usage = usageIterator.next()
        val refElement = usage.element as? KtSimpleNameExpression ?: continue
        if (refElement.getStrictParentOfType<KtTypeReference>() != null) continue

        val callExpression = refElement.parent as? KtCallExpression ?: refElement.parent as? KtQualifiedExpression ?: refElement
        val copied = callExpression.copied()
        copied.referenceExpression().replace(psiFactory.createNameIdentifier(newName))
        val codeFragment = psiFactory.createExpressionCodeFragment(if (copied.isValid) copied.text else newName, callExpression)
        val contentElement = codeFragment.getContentElement()
        if (contentElement != null) {
            analyze(codeFragment) {
                val resolveCall = contentElement.resolveCall()?.singleCallOrNull<KtCallableMemberCall<*, *>>()
                val resolvedSymbol = resolveCall?.partiallyAppliedSymbol?.symbol
                val newDeclaration = if (resolvedSymbol is KtSyntheticJavaPropertySymbol) {
                    val getter = resolvedSymbol.javaGetterSymbol.psi
                    externalDeclarations.addIfNotNull(getter)
                    externalDeclarations.addIfNotNull(resolvedSymbol.javaSetterSymbol?.psi)
                    getter
                } else {
                    val element = resolvedSymbol?.psi
                    externalDeclarations.addIfNotNull(element)
                    element
                }
                if (newDeclaration != null && (declaration !is KtParameter || declaration.hasValOrVar()) && !PsiTreeUtil.isAncestor(newDeclaration, declaration, true)) {
                    val expression = refElement.parent as? KtCallExpression ?: refElement
                    val qualifiedExpression = createQualifiedExpression(expression, newName)
                    if (qualifiedExpression != null) {
                        usageIterator.set(UsageInfoWithReplacement(expression, declaration, qualifiedExpression))
                    } else {
                        reportShadowing(declaration, declaration, newDeclaration, refElement, newUsages)
                    }
                } else {
                    //k1 fails to compile otherwise
                }
            }
        }
    }

    fun retargetExternalDeclaration(externalDeclaration: PsiElement) {
        val processor: (PsiReference) -> Unit = processor@ { ref ->
            val refElement = ref.element as? KtSimpleNameExpression ?: return@processor
            if (refElement.getStrictParentOfType<KtTypeReference>() != null) return@processor
            val expression = refElement.parent as? KtCallExpression ?: refElement
            val qualifiedExpression = createQualifiedExpression(expression, newName)
            if (qualifiedExpression != null) {
                newUsages.add(UsageInfoWithReplacement(expression, declaration, qualifiedExpression))
            }
        }
        if (externalDeclaration is PsiMethod) {
            MethodReferencesSearch.search(externalDeclaration, declaration.useScope, true).forEach(processor)
        }
        else {
            ReferencesSearch.search(externalDeclaration, declaration.useScope).forEach(processor)
        }
    }

    for (externalDeclaration in externalDeclarations) {
        retargetExternalDeclaration(externalDeclaration)
    }

    analyze(declaration) {
        //check outer callables hiding/hidden by rename
        val processedDeclarations = mutableSetOf<PsiElement>()
        processedDeclarations.addAll(externalDeclarations)
        retargetExternalDeclarations(declaration, newName) {
            val callableDeclaration = it.psi as? KtNamedDeclaration
            if (callableDeclaration != null && processedDeclarations.add(callableDeclaration)) {
                retargetExternalDeclaration(callableDeclaration)
            }
        }
    }
}

private fun KtExpression.referenceExpression(): KtExpression {
    return (this as? KtCallExpression)?.calleeExpression ?: (this as? KtQualifiedExpression)?.selectorExpression ?: this
}

private fun createQualifiedExpression(callExpression: KtExpression, newName: String): KtExpression? {
    val psiFactory = KtPsiFactory(callExpression.project)
    val qualifiedExpression = analyze(callExpression) {
        val appliedSymbol = callExpression.resolveCall()?.successfulCallOrNull<KtCallableMemberCall<*, *>>()?.partiallyAppliedSymbol
        val receiver = appliedSymbol?.extensionReceiver ?: appliedSymbol?.dispatchReceiver
        if (receiver is KtImplicitReceiverValue) {
            val symbol = receiver.symbol
            if ((symbol as? KtClassOrObjectSymbol)?.classKind == KtClassKind.COMPANION_OBJECT) {
                //specify companion name to avoid clashes with enum entries
                symbol.name!!.asString()
            }
            else if (symbol is KtClassifierSymbol && symbol !is KtAnonymousObjectSymbol) {
                "this@" + symbol.name!!.asString()
            }
            else if (symbol is KtReceiverParameterSymbol && symbol.owningCallableSymbol is KtNamedSymbol) {
                receiver.type.expandedClassSymbol?.name?.let { "this@$it" } ?: "this"
            }
            else {
                "this"
            }
        } else if (receiver == null) {
            val symbol = appliedSymbol?.symbol
            val containingSymbol = symbol?.getContainingSymbol()
            val containerFQN =
                if (containingSymbol is KtClassOrObjectSymbol) {
                    containingSymbol.classIdIfNonLocal?.asSingleFqName()?.parent()
                } else {
                    (symbol?.psi as? KtElement)?.containingKtFile?.packageFqName
                }
            containerFQN?.asString()?.takeIf { it.isNotEmpty() }
        }
        else if (receiver is KtExplicitReceiverValue) {
            val containingSymbol = appliedSymbol?.symbol?.getContainingSymbol()
            val enumClassSymbol = containingSymbol?.getContainingSymbol()
            //add companion qualifier to avoid clashes with enum entries
            if (containingSymbol is KtNamedClassOrObjectSymbol && containingSymbol.classKind == KtClassKind.COMPANION_OBJECT &&
                enumClassSymbol is KtNamedClassOrObjectSymbol && enumClassSymbol.classKind == KtClassKind.ENUM_CLASS &&
                (receiver.expression as? KtNameReferenceExpression)?.mainReference?.resolve() == containingSymbol.psi
            ) {
                containingSymbol.name.asString()
            } else null
        }
        else null
    }?.let { psiFactory.createExpressionByPattern("$it.$0", callExpression) } ?: callExpression.copied()
    val newCallee = if (qualifiedExpression is KtCallableReferenceExpression) {
        qualifiedExpression.callableReference
    } else {
        qualifiedExpression.getQualifiedElementSelector() as? KtSimpleNameExpression
    }
    newCallee?.getReferencedNameElement()?.replace(psiFactory.createNameIdentifier(newName))
    return qualifiedExpression
}

context(KtAnalysisSession)
private fun reportShadowing(
    declaration: PsiNamedElement,
    elementToBindUsageInfoTo: PsiElement,
    candidate: PsiElement,
    refElement: PsiElement,
    result: MutableList<UsageInfo>
) {
    val candidate = candidate as? PsiNamedElement ?: return
    val message = KotlinBundle.message(
        "text.0.will.be.shadowed.by.1",
        declaration.renderDescription(),
        candidate.renderDescription()
    ).capitalize()
    result += BasicUnresolvableCollisionUsageInfo(refElement, elementToBindUsageInfoTo, message)
}

context(KtAnalysisSession)
private fun retargetExternalDeclarations(declaration: KtNamedDeclaration, name: String, retargetJob: (KtDeclarationSymbol) -> Unit) {
    val declarationSymbol = declaration.getSymbol()

    val nameAsName = Name.identifier(name)
    fun KtScope.processScope(containingSymbol: KtDeclarationSymbol?) {
        findSiblingsByName(declarationSymbol, nameAsName, containingSymbol).forEach(retargetJob)
    }

    var classOrObjectSymbol = declarationSymbol.getContainingSymbol()
    while (classOrObjectSymbol != null) {
        (classOrObjectSymbol as? KtClassOrObjectSymbol)?.getMemberScope()?.processScope(classOrObjectSymbol)

        val companionObject = (classOrObjectSymbol as? KtNamedClassOrObjectSymbol)?.companionObject
        companionObject?.getMemberScope()?.processScope(companionObject)

        classOrObjectSymbol = classOrObjectSymbol.getContainingSymbol()
    }

    val file = declaration.containingKtFile
    getPackageSymbolIfPackageExists(file.packageFqName)?.getPackageScope()?.processScope(null)
    file.getImportingScopeContext().getCompositeScope().processScope(null)
}