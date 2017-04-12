package org.rust.ide.annotator

import com.intellij.codeInsight.daemon.impl.HighlightRangeExtension
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.lang.annotation.Annotation
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.AnnotationSession
import com.intellij.lang.annotation.Annotator
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import org.rust.cargo.project.workspace.cargoWorkspace
import org.rust.ide.annotator.fixes.*
import org.rust.ide.utils.isNullOrEmpty
import org.rust.lang.core.psi.*
import org.rust.lang.core.psi.ext.*
import org.rust.lang.core.resolve.Namespace
import org.rust.lang.core.resolve.namespaces
import org.rust.lang.core.types.type
import org.rust.lang.core.types.types.RustReferenceType
import org.rust.lang.core.types.types.RustUnknownType

class RsErrorAnnotator : Annotator, HighlightRangeExtension {
    override fun isForceHighlightParents(file: PsiFile): Boolean = file is RsFile

    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        val visitor = object : RsVisitor() {
            override fun visitBaseType(o: RsBaseType) = checkBaseType(holder, o)
            override fun visitConstant(o: RsConstant) = checkConstant(holder, o)
            override fun visitValueArgumentList(o: RsValueArgumentList) = checkValueArgumentList(holder, o)
            override fun visitStructItem(o: RsStructItem) = checkStructItem(holder, o)
            override fun visitEnumItem(o: RsEnumItem) = checkDuplicates(holder, o)
            override fun visitEnumVariant(o: RsEnumVariant) = checkDuplicates(holder, o)
            override fun visitFunction(o: RsFunction) = checkFunction(holder, o)
            override fun visitImplItem(o: RsImplItem) = checkImpl(holder, o)
            override fun visitLabel(o: RsLabel) = checkLabel(holder, o)
            override fun visitLifetime(o: RsLifetime) = checkLifetime(holder, o)
            override fun visitLifetimeDecl(o: RsLifetimeDecl) = checkLifetimeDecl(holder, o)
            override fun visitModDeclItem(o: RsModDeclItem) = checkModDecl(holder, o)
            override fun visitModItem(o: RsModItem) = checkDuplicates(holder, o)
            override fun visitPatBinding(o: RsPatBinding) = checkPatBinding(holder, o)
            override fun visitPath(o: RsPath) = checkPath(holder, o)
            override fun visitFieldDecl(o: RsFieldDecl) = checkDuplicates(holder, o)
            override fun visitRefLikeType(o: RsRefLikeType) = checkRefLikeType(holder, o)
            override fun visitRetExpr(o: RsRetExpr) = checkRetExpr(holder, o)
            override fun visitTraitItem(o: RsTraitItem) = checkDuplicates(holder, o)
            override fun visitTypeAlias(o: RsTypeAlias) = checkTypeAlias(holder, o)
            override fun visitTypeParameter(o: RsTypeParameter) = checkTypeParameter(holder, o)
            override fun visitLifetimeParameter(o: RsLifetimeParameter) = checkDuplicates(holder, o)
            override fun visitValueParameterList(o: RsValueParameterList) = checkValueParameterList(holder, o)
            override fun visitValueParameter(o: RsValueParameter) = checkValueParameter(holder, o)
            override fun visitVis(o: RsVis) = checkVis(holder, o)
            override fun visitBinaryExpr(o: RsBinaryExpr) = checkBinary(holder, o)
            override fun visitCallExpr(o: RsCallExpr) = checkCallExpr(holder, o)
            override fun visitMethodCallExpr(o: RsMethodCallExpr) = checkMethodCallExpr(holder, o)
            override fun visitUnaryExpr(o: RsUnaryExpr) = checkUnaryExpr(holder, o)
        }

        element.accept(visitor)
    }

    private fun checkMethodCallExpr(holder: AnnotationHolder, o: RsMethodCallExpr) {
        val fn = o.reference.resolve() as? RsFunction ?: return
        if (!o.expr.isMutable() &&
            fn.selfParameter != null &&
            fn.selfParameter?.isMut ?: false &&
            fn.selfParameter?.isRef ?: false) {
            createImmutableErrorAnnotation(holder, o.expr)
        } else if (fn.unsafe != null) {
            checkUnsafeCall(holder, o)
        }
    }

    private fun checkCallExpr(holder: AnnotationHolder, o: RsCallExpr) {
        val path = (o.expr as? RsPathExpr)?.path ?: return
        val fn = path.reference.resolve() as? RsFunction ?: return
        if (fn.unsafe != null) {
            checkUnsafeCall(holder, o)
        }
    }

    private fun checkUnsafeCall(holder: AnnotationHolder, o: RsExpr) {
        val parent = o.ancestors
            .drop(1)    // skip the expression itself
            .find {
                when (it) {
                    is RsBlockExpr -> it.unsafe != null
                    is RsFunction -> true
                    else -> false
                }
            } ?: return

        if (parent is RsBlock || (parent is RsFunction && parent.unsafe == null)) {
            val annotation = holder.createErrorAnnotation(o, "Call to unsafe function requires unsafe function or block [E0133]")
            annotation.registerFix(SurroundWithUnsafeFix(o))
            val block = o.parentOfType<RsBlock>()?.parent ?: return
            annotation.registerFix(AddUnsafeFix(block))
        }
    }

    private fun checkUnaryExpr(holder: AnnotationHolder, unaryExpr: RsUnaryExpr) {
        val expr = unaryExpr.expr ?: return
        if (unaryExpr.operatorType == UnaryOperator.REF_MUT && !expr.isMutable()) {
            createImmutableErrorAnnotation(holder, expr)
        }
    }

    private fun checkBaseType(holder: AnnotationHolder, type: RsBaseType) {
        checkBaseTypeUnderscore(holder, type)

        // Don't apply generic declaration checks to Fn-traits and `Self`
        if (type.path?.valueParameterList != null) return
        if (type.path?.cself != null) return

        val paramsDecl = type.path?.reference?.resolve() as? RsGenericDeclaration ?: return
        checkBaseTypeLifetimes(holder, type, paramsDecl)
        checkBaseTypeTypeArguments(holder, type, paramsDecl)
    }

    private fun checkBaseTypeUnderscore(holder: AnnotationHolder, type: RsBaseType) {
        if (type.underscore == null) return
        val owner = type.ancestors.drop(1).dropWhile { it is RsTupleType }.first()
        if ((owner is RsValueParameter || owner is RsRetType) && owner.parent.parent !is RsLambdaExpr || owner is RsConstant) {
            holder.createErrorAnnotation(type, "The type placeholder `_` is not allowed within types on item signatures [E0121]")
        }
    }

    private fun checkBaseTypeLifetimes(holder: AnnotationHolder, type: RsBaseType, paramsDecl: RsGenericDeclaration) {
        val expectedLifetimes = paramsDecl.typeParameterList?.lifetimeParameterList?.size ?: 0
        val actualLifetimes = type.path?.typeArgumentList?.lifetimeList?.size ?: 0
        if (expectedLifetimes == actualLifetimes) return
        if (actualLifetimes == 0 && !type.lifetimeElidable) {
            holder.createErrorAnnotation(type, "Missing lifetime specifier [E0106]")
        } else if (actualLifetimes > 0) {
            holder.createErrorAnnotation(type, "Wrong number of lifetime parameters: expected $expectedLifetimes, found $actualLifetimes [E0107]")
        }
    }

    private fun checkBaseTypeTypeArguments(holder: AnnotationHolder, type: RsBaseType, paramsDecl: RsGenericDeclaration) {
        val expectedRequiredParams = paramsDecl.typeParameterList?.typeParameterList?.filter { it.eq == null }?.size ?: 0
        val expectedTotalParams = paramsDecl.typeParameterList?.typeParameterList?.size ?: 0
        val actualArgs = type.path?.typeArgumentList?.typeReferenceList?.size ?: 0
        val (code, expectedText) = when {
            actualArgs < expectedRequiredParams -> ("E0243" to if (expectedRequiredParams != expectedTotalParams) "at least $expectedRequiredParams" else "$expectedTotalParams")
            actualArgs > expectedTotalParams -> ("E0244" to if (expectedRequiredParams != expectedTotalParams) "at most $expectedTotalParams" else "$expectedTotalParams")
            else -> null
        } ?: return
        holder.createErrorAnnotation(type, "Wrong number of type arguments: expected $expectedText, found $actualArgs [$code]")
    }

    private fun checkPatBinding(holder: AnnotationHolder, binding: RsPatBinding) {
        binding.parentOfType<RsValueParameterList>()?.let { checkDuplicates(holder, binding, it, recursively = true) }
    }

    private fun checkPath(holder: AnnotationHolder, path: RsPath) {
        fun isValidSelfSuper(path: RsPath): Boolean {
            if (path.self == null && path.`super` == null) return true
            if (path.path == null && path.coloncolon != null) return false
            if (path.self != null && path.path != null) return false
            if (path.`super` != null) {
                val q = path.path ?: return true
                return q.self != null || q.`super` != null
            }
            return true
        }

        val child = path.path
        if ((child == null || isValidSelfSuper(child)) && !isValidSelfSuper(path)) {
            holder.createErrorAnnotation(path, "Invalid path: self and super are allowed only at the beginning")
            return
        }

        if (path.self != null && path.parent !is RsPath) {
            val function = path.parentOfType<RsFunction>()
            if (function == null) {
                holder.createErrorAnnotation(path, "self value is not available in this context")
                return
            }

            if (function.selfParameter == null) {
                val error = "The self keyword was used in a static method [E0424]"
                val annotation = holder.createErrorAnnotation(path, error)
                if (function.role != RsFunctionRole.FREE) {
                    annotation.registerFix(AddSelfFix(function))
                }
            }
        }
    }

    private fun checkVis(holder: AnnotationHolder, vis: RsVis) {
        if (vis.parent is RsImplItem || vis.parent is RsForeignModItem || isInTraitImpl(vis) || isInEnumVariantField(vis)) {
            holder.createErrorAnnotation(vis, "Unnecessary visibility qualifier [E0449]")
        }
    }

    private fun checkConstant(holder: AnnotationHolder, const: RsConstant) {
        checkDuplicates(holder, const)
        val title = if (const.static != null) "Static constant `${const.identifier.text}`" else "Constant `${const.identifier.text}`"
        when (const.role) {
            RsConstantRole.FREE -> {
                deny(const.default, holder, "$title cannot have the `default` qualifier")
                require(const.expr, holder, "$title must have a value", const)
            }
            RsConstantRole.TRAIT_CONSTANT -> {
                deny(const.vis, holder, "$title cannot have the `pub` qualifier")
                deny(const.default, holder, "$title cannot have the `default` qualifier")
                deny(const.static, holder, "Static constants are not allowed in traits")
            }
            RsConstantRole.IMPL_CONSTANT -> {
                deny(const.static, holder, "Static constants are not allowed in impl blocks")
                require(const.expr, holder, "$title must have a value", const)
            }
            RsConstantRole.FOREIGN -> {
                deny(const.default, holder, "$title cannot have the `default` qualifier")
                require(const.static, holder, "Only static constants are allowed in extern blocks", const.const)
                deny(const.expr, holder, "Static constants in extern blocks cannot have values", const.eq, const.expr)
            }
        }
    }

    private fun checkValueParameter(holder: AnnotationHolder, param: RsValueParameter) {
        val fn = param.parent.parent as? RsFunction ?: return
        when (fn.role) {
            RsFunctionRole.FREE,
            RsFunctionRole.IMPL_METHOD,
            RsFunctionRole.FOREIGN -> {
                require(param.pat, holder, "${fn.title} cannot have anonymous parameters", param)
            }
            RsFunctionRole.TRAIT_METHOD -> {
                denyType<RsPatTup>(param.pat, holder, "${fn.title} cannot have tuple parameters", param)
            }
        }
    }

    private fun checkValueParameterList(holder: AnnotationHolder, params: RsValueParameterList) {
        val fn = params.parent as? RsFunction ?: return
        when (fn.role) {
            RsFunctionRole.FREE -> {
                deny(params.selfParameter, holder, "${fn.title} cannot have `self` parameter")
                deny(params.dotdotdot, holder, "${fn.title} cannot be variadic")
            }
            RsFunctionRole.TRAIT_METHOD,
            RsFunctionRole.IMPL_METHOD -> {
                deny(params.dotdotdot, holder, "${fn.title} cannot be variadic")
            }
            RsFunctionRole.FOREIGN -> {
                deny(params.selfParameter, holder, "${fn.title} cannot have `self` parameter")
                checkDot3Parameter(holder, params.dotdotdot)
            }
        }
    }

    private fun checkDot3Parameter(holder: AnnotationHolder, dot3: PsiElement?) {
        if (dot3 == null) return
        dot3.rightVisibleLeaves
            .first {
                if (it.text != ")") {
                    holder.createErrorAnnotation(it, "`...` must be last in argument list for variadic function")
                }
                return
            }
    }

    private fun checkFunction(holder: AnnotationHolder, fn: RsFunction) {
        checkDuplicates(holder, fn)
        when (fn.role) {
            RsFunctionRole.FREE -> {
                require(fn.block, holder, "${fn.title} must have a body", fn.lastChild)
                deny(fn.default, holder, "${fn.title} cannot have the `default` qualifier")
            }
            RsFunctionRole.TRAIT_METHOD -> {
                deny(fn.default, holder, "${fn.title} cannot have the `default` qualifier")
                deny(fn.vis, holder, "${fn.title} cannot have the `pub` qualifier")
                deny(fn.const, holder, "Trait functions cannot be declared const [E0379]")
            }
            RsFunctionRole.IMPL_METHOD -> {
                require(fn.block, holder, "${fn.title} must have a body", fn.lastChild)
                if (fn.default != null) {
                    deny(fn.vis, holder, "Default ${fn.title.firstLower} cannot have the `pub` qualifier")
                }
            }
            RsFunctionRole.FOREIGN -> {
                deny(fn.default, holder, "${fn.title} cannot have the `default` qualifier")
                deny(fn.block, holder, "${fn.title} cannot have a body")
                deny(fn.const, holder, "${fn.title} cannot have the `const` qualifier")
                deny(fn.unsafe, holder, "${fn.title} cannot have the `unsafe` qualifier")
                deny(fn.externAbi, holder, "${fn.title} cannot have an extern ABI")
            }
        }
    }

    private fun checkStructItem(holder: AnnotationHolder, struct: RsStructItem) {
        checkDuplicates(holder, struct)
        if (struct.kind == RsStructKind.UNION && struct.tupleFields != null) {
            deny(struct.tupleFields, holder, "Union cannot be tuple-like")
        }
    }

    private fun checkLabel(holder: AnnotationHolder, label: RsLabel) =
        requireResolve(holder, label, "Use of undeclared label `${label.text}` [E0426]")

    private fun checkLifetime(holder: AnnotationHolder, lifetime: RsLifetime) {
        if (lifetime.isPredefined) return
        requireResolve(holder, lifetime, "Use of undeclared lifetime name `${lifetime.text}` [E0261]")
    }

    private fun checkLifetimeDecl(holder: AnnotationHolder, lifetimeDecl: RsLifetimeDecl) =
        checkUnusedTypeParameter(holder, lifetimeDecl)

    private fun checkModDecl(holder: AnnotationHolder, modDecl: RsModDeclItem) {
        checkDuplicates(holder, modDecl)
        val pathAttribute = modDecl.pathAttribute

        // mods inside blocks require explicit path  attribute
        // https://github.com/rust-lang/rust/pull/31534
        if (modDecl.isLocal && pathAttribute == null) {
            val message = "Cannot declare a non-inline module inside a block unless it has a path attribute"
            holder.createErrorAnnotation(modDecl, message)
            return
        }

        if (!modDecl.containingMod.ownsDirectory && pathAttribute == null) {
            // We don't want to show the warning if there is no cargo project
            // associated with the current module. Without it we can't know for
            // sure that a mod is not a directory owner.
            if (modDecl.module?.cargoWorkspace != null) {
                holder.createErrorAnnotation(modDecl, "Cannot declare a new module at this location")
                    .registerFix(AddModuleFileFix(modDecl, expandModuleFirst = true))
            }
            return
        }

        if (modDecl.reference.resolve() == null) {
            holder.createErrorAnnotation(modDecl, "Unresolved module")
                .registerFix(AddModuleFileFix(modDecl, expandModuleFirst = false))
        }
    }

    private fun checkImpl(holder: AnnotationHolder, impl: RsImplItem) {
        val trait = impl.traitRef?.resolveToTrait ?: return
        val traitName = trait.name ?: return

        val implemented = impl.functionList.associateBy { it.name }

        val (toImplement, toOverride) = impl.toImplementOverride()
            ?: listOf<RsNamedElement>() to listOf<RsNamedElement>()
        val notImplemented = toImplement.map { it.name }
        val canImplement = toOverride.associateBy { it.name }

        if (notImplemented.isNotEmpty()) {
            val implHeaderTextRange = TextRange.create(
                impl.textRange.startOffset,
                impl.typeReference?.textRange?.endOffset ?: impl.textRange.endOffset
            )

            holder.createErrorAnnotation(implHeaderTextRange,
                "Not all trait items implemented, missing: ${notImplemented.namesList} [E0046]"
            ).registerFix(ImplementMethodsFix(impl))
        }

        val notMembers = implemented.filterKeys { it !in canImplement }
        for (method in notMembers.values) {
            holder.createErrorAnnotation(method.identifier,
                "Method `${method.name}` is not a member of trait `$traitName` [E0407]")
        }

        implemented
            .map { it.value to canImplement[it.key] }
            .mapNotNull { it.first to (it.second as? RsFunction ?: return@mapNotNull null) }
            .forEach { checkTraitFnImplParams(holder, it.first, it.second, traitName) }
    }

    private fun checkTypeAlias(holder: AnnotationHolder, ta: RsTypeAlias) {
        checkDuplicates(holder, ta)
        val title = "Type `${ta.identifier.text}`"
        when (ta.role) {
            RsTypeAliasRole.FREE -> {
                deny(ta.default, holder, "$title cannot have the `default` qualifier")
                deny(ta.typeParamBounds, holder, "$title cannot have type parameter bounds")
                require(ta.typeReference, holder, "Aliased type must be provided for type `${ta.identifier.text}`", ta)
            }
            RsTypeAliasRole.TRAIT_ASSOC_TYPE -> {
                deny(ta.default, holder, "$title cannot have the `default` qualifier")
                deny(ta.vis, holder, "$title cannot have the `pub` qualifier")
                deny(ta.typeParameterList, holder, "$title cannot have generic parameters")
                deny(ta.whereClause, holder, "$title cannot have `where` clause")
            }
            RsTypeAliasRole.IMPL_ASSOC_TYPE -> {
                val impl = ta.parent as? RsImplItem ?: return
                if (impl.`for` == null) {
                    holder.createErrorAnnotation(ta, "Associated types are not allowed in inherent impls [E0202]")
                } else {
                    deny(ta.typeParameterList, holder, "$title cannot have generic parameters")
                    deny(ta.whereClause, holder, "$title cannot have `where` clause")
                    deny(ta.typeParamBounds, holder, "$title cannot have type parameter bounds")
                    require(ta.typeReference, holder, "Aliased type must be provided for type `${ta.identifier.text}`", ta)
                }
            }
        }
    }

    private fun checkTypeParameter(holder: AnnotationHolder, parameter: RsTypeParameter) {
        checkDuplicates(holder, parameter)
        checkUnusedTypeParameter(holder, parameter)
    }

    private fun checkTraitFnImplParams(holder: AnnotationHolder, fn: RsFunction, superFn: RsFunction, traitName: String) {
        val params = fn.valueParameterList ?: return
        val selfArg = fn.selfParameter

        if (selfArg != null && superFn.selfParameter == null) {
            holder.createErrorAnnotation(selfArg,
                "Method `${fn.name}` has a `${selfArg.canonicalDecl}` declaration in the impl, but not in the trait [E0185]")
        } else if (selfArg == null && superFn.selfParameter != null) {
            holder.createErrorAnnotation(params,
                "Method `${fn.name}` has a `${superFn.selfParameter?.canonicalDecl}` declaration in the trait, but not in the impl [E0186]")
        }

        val paramsCount = fn.valueParameters.size
        val superParamsCount = superFn.valueParameters.size
        if (paramsCount != superParamsCount) {
            holder.createErrorAnnotation(params,
                "Method `${fn.name}` has $paramsCount ${pluralise(paramsCount, "parameter", "parameters")} but the declaration in trait `$traitName` has $superParamsCount [E0050]")
        }
    }

    private fun checkBinary(holder: AnnotationHolder, o: RsBinaryExpr) {
        if (o.isComparisonBinaryExpr() && (o.left.isComparisonBinaryExpr() || o.right.isComparisonBinaryExpr())) {
            val annotator = holder.createErrorAnnotation(o, "Chained comparison operator require parentheses")
            annotator.registerFix(AddTurbofishFix())
        } else if (o.isAssignBinaryExpr() && !o.left.isMutable()) {
            createImmutableErrorAnnotation(holder, o.left, true)
        }
    }

    private fun checkValueArgumentList(holder: AnnotationHolder, args: RsValueArgumentList) {
        val parent = args.parent
        val expectedCount = when (parent) {
            is RsCallExpr -> parent.expectedParamsCount()
            is RsMethodCallExpr -> parent.expectedParamsCount()
            else -> null
        } ?: return

        val realCount = args.exprList.size
        if (realCount != expectedCount) {
            holder.createErrorAnnotation(args,
                "This function takes $expectedCount ${pluralise(expectedCount, "parameter", "parameters")}"
                    + " but $realCount ${pluralise(realCount, "parameter", "parameters")}"
                    + " ${pluralise(realCount, "was", "were")} supplied [E0061]")
        }
    }

    private fun checkRefLikeType(holder: AnnotationHolder, type: RsRefLikeType) {
        if (type.mul == null && !type.lifetimeElidable) {
            require(type.lifetime, holder, "Missing lifetime specifier [E0106]", type.and ?: type)
        }
    }

    private fun checkRetExpr(holder: AnnotationHolder, ret: RsRetExpr) {
        if (ret.expr != null) return
        val fn = ret.ancestors.find { it is RsFunction || it is RsLambdaExpr } as? RsFunction ?: return
        val retType = fn.retType?.typeReference ?: return
        if (retType is RsTupleType && retType.isUnitType) return
        holder.createErrorAnnotation(ret, "`return;` in a function whose return type is not `()` [E0069]")
    }

    private fun checkUnusedTypeParameter(holder: AnnotationHolder, parameter: RsNamedElement) {
        val owner = parameter.parentOfType<RsTypeParameterList>()?.parent ?: return
        if (owner !is RsStructItem && owner !is RsEnumItem) return
        if (ReferencesSearch.search(parameter, parameter.useScope).findFirst() != null) return
        holder.createErrorAnnotation(parameter, "Parameter `${parameter.name}` is never used [E0392]")
    }

    private fun requireResolve(holder: AnnotationHolder, el: RsReferenceElement, message: String) {
        if (el.reference.resolve() != null || el.reference.multiResolve().size > 1) return
        holder.createErrorAnnotation(el.textRange, message)
            .highlightType = ProblemHighlightType.LIKE_UNKNOWN_SYMBOL
    }

    private fun require(el: PsiElement?, holder: AnnotationHolder, message: String, vararg highlightElements: PsiElement?): Annotation? =
        if (el != null) null else holder.createErrorAnnotation(highlightElements.combinedRange ?: TextRange.EMPTY_RANGE, message)

    private fun deny(el: PsiElement?, holder: AnnotationHolder, message: String, vararg highlightElements: PsiElement?): Annotation? =
        if (el == null) null else holder.createErrorAnnotation(highlightElements.combinedRange ?: el.textRange, message)

    private inline fun <reified T : RsCompositeElement> denyType(el: PsiElement?, holder: AnnotationHolder, message: String, vararg highlightElements: PsiElement?): Annotation? =
        if (el !is T) null else holder.createErrorAnnotation(highlightElements.combinedRange ?: el.textRange, message)

    private fun isInTraitImpl(o: RsVis): Boolean {
        val impl = o.parent?.parent
        return impl is RsImplItem && impl.traitRef != null
    }

    private val RsTypeReference.lifetimeElidable: Boolean get() {
        val typeOwner = topmostType.parent
        return typeOwner !is RsFieldDecl && typeOwner !is RsTupleFieldDecl && typeOwner !is RsTypeAlias
    }

    private fun isInEnumVariantField(o: RsVis): Boolean {
        val field = o.parent as? RsFieldDecl
            ?: o.parent as? RsTupleFieldDecl
            ?: return false
        return field.parent.parent is RsEnumVariant
    }

    private fun pluralise(count: Int, singular: String, plural: String): String =
        if (count == 1) singular else plural

    private val Array<out PsiElement?>.combinedRange: TextRange?
        get() = if (isEmpty())
            null
        else filterNotNull()
            .map { it.textRange }
            .reduce(TextRange::union)

    private val Collection<String?>.namesList: String
        get() = mapNotNull { "`$it`" }.joinToString(", ")

    private val RsSelfParameter.canonicalDecl: String
        get() = buildString {
            if (isRef) append('&')
            if (isMut) append("mut ")
            append("self")
        }

    private val PsiElement.rightVisibleLeaves: Sequence<PsiElement>
        get() = generateSequence(PsiTreeUtil.nextVisibleLeaf(this), { el -> PsiTreeUtil.nextVisibleLeaf(el) })

    private val String.firstLower: String
        get() = if (isEmpty()) this else this[0].toLowerCase() + substring(1)


    private fun createImmutableErrorAnnotation(holder: AnnotationHolder, expr: RsExpr, binary: Boolean = false) {
        val message = when {
            expr is RsPathExpr && binary -> "Reassigning an immutable variable [E0384]"
            binary -> "Cannot assign to immutable field `${expr.text}`"
            else -> "Cannot borrow immutable local variable `${expr.text}` as mutable"
        }
        val annotation = holder.createErrorAnnotation(expr, message)
        val declaration = expr.declaration() ?: return
        if (declaration is RsSelfParameter || declaration is RsPatBinding) {
            annotation.registerFix(AddMutableFix(declaration as RsNamedElement))
        }
    }
}

private fun RsExpr?.isComparisonBinaryExpr(): Boolean {
    val op = this as? RsBinaryExpr ?: return false
    return op.operatorType in RS_COMPARISON_OPERATOR
}

private fun RsExpr?.isAssignBinaryExpr(): Boolean {
    val op = this as? RsBinaryExpr ?: return false
    return op.operatorType in RS_ASSIGN_OPERATOR
}

private fun checkDuplicates(holder: AnnotationHolder, element: RsNamedElement, scope: PsiElement = element.parent, recursively: Boolean = false) {
    val duplicates = holder.currentAnnotationSession.duplicatesByNamespace(scope, recursively)
    val ns = element.namespaces.find { element in duplicates[it].orEmpty() }
        ?: return
    val name = element.name!!
    val message = when {
        element is RsFieldDecl -> "Field `$name` is already declared [E0124]"
        element is RsEnumVariant -> "Enum variant `$name` is already declared [E0428]"
        element is RsLifetimeParameter -> "Lifetime name `$name` declared twice in the same scope [E0263]"
        element is RsPatBinding && scope is RsValueParameterList -> "Identifier `$name` is bound more than once in this parameter list [E0415]"
        element is RsTypeParameter -> "The name `$name` is already used for a type parameter in this type parameter list [E0403]"
        scope is RsImplItem -> "Duplicate definitions with name `$name` [E0201]"
        else -> {
            val scopeType = when (scope) {
                is RsBlock -> "block"
                is RsMod, is RsForeignModItem -> "module"
                is RsTraitItem -> "trait"
                else -> "scope"
            }
            "A ${ns.itemName} named `$name` has already been defined in this $scopeType [E0428]"
        }
    }

    holder.createErrorAnnotation(element.navigationElement, message)
}


private fun AnnotationSession.duplicatesByNamespace(owner: PsiElement, recursively: Boolean): Map<Namespace, Set<PsiElement>> {
    val fileMap = fileDuplicatesMap()
    fileMap[owner]?.let { return it }

    val duplicates: Map<Namespace, Set<PsiElement>> =
        owner.namedChildren(recursively)
            .filter { it !is RsExternCrateItem } // extern crates can have aliases.
            .filter { it.name != null }
            .flatMap { it.namespaced }
            .groupBy { it.first }       // Group by namespace
            .map { entry ->
                val (namespace, items) = entry
                namespace to items.asSequence()
                    .map { it.second }
                    .groupBy { it.name }
                    .map { it.value }
                    .filter {
                        it.size > 1 &&
                            it.any { !(it is RsDocAndAttributeOwner && it.queryAttributes.hasCfgAttr()) }
                    }
                    .flatten()
                    .toSet()
            }
            .toMap()

    fileMap[owner] = duplicates
    return duplicates
}

private fun PsiElement.namedChildren(recursively: Boolean): Sequence<RsNamedElement> =
    if (recursively) {
        descendantsOfType<RsNamedElement>().asSequence()
    } else {
        children.asSequence().filterIsInstance<RsNamedElement>()
    }

private val DUPLICATES_BY_SCOPE = Key<MutableMap<
    PsiElement,
    Map<Namespace, Set<PsiElement>>>>("org.rust.ide.annotator.RsErrorAnnotator.duplicates")

private fun AnnotationSession.fileDuplicatesMap(): MutableMap<PsiElement, Map<Namespace, Set<PsiElement>>> {
    var map = getUserData(DUPLICATES_BY_SCOPE)
    if (map == null) {
        map = mutableMapOf()
        putUserData(DUPLICATES_BY_SCOPE, map)
    }
    return map
}

private val RsNamedElement.namespaced: Sequence<Pair<Namespace, RsNamedElement>>
    get() = namespaces.asSequence().map { Pair(it, this) }

private fun RsCallExpr.expectedParamsCount(): Int? {
    val path = (expr as? RsPathExpr)?.path ?: return null
    val el = path.reference.resolve()
    if (el is RsDocAndAttributeOwner && el.queryAttributes.hasCfgAttr()) return null
    return when (el) {
        is RsFieldsOwner -> el.tupleFields?.tupleFieldDeclList?.size
        is RsFunction -> {
            if (el.role == RsFunctionRole.IMPL_METHOD && !el.isInherentImpl) return null
            val count = el.valueParameterList?.valueParameterList?.size ?: return null
            // We can call foo.method(1), or Foo::method(&foo, 1), so need to take coloncolon into account
            val s = if (path.coloncolon != null && el.selfParameter != null) 1 else 0
            count + s
        }
        else -> null
    }
}

private fun RsMethodCallExpr.expectedParamsCount(): Int? {
    val fn = reference.resolve() as? RsFunction ?: return null
    if (fn.queryAttributes.hasCfgAttr()) return null
    return if (fn.isInherentImpl) fn.valueParameterList?.valueParameterList?.size else null
}

private val RsCallExpr.declaration: RsFunction?
    get() = (expr as? RsPathExpr)?.path?.reference?.resolve() as? RsFunction

private fun RsExpr.declaration(): RsCompositeElement? = when (this) {
    is RsPathExpr -> this.path.reference.resolve()
    is RsFieldExpr -> this.expr.declaration()
    else -> null
}

private fun RsExpr.isMutable(): Boolean {
    return when (this) {
        is RsPathExpr -> {
            val declaration = this.path.reference.resolve() ?: return true

            if (declaration is RsSelfParameter) {
                return declaration.mut != null
            }

            if (declaration is RsPatBinding && declaration.isMut) {
                return true
            }

            val type = this.type
            if (type is RustReferenceType) {
                return type.mutable
            }

            val letExpr = declaration.parentOfType<RsLetDecl>()
            if (letExpr != null && letExpr.eq == null) {
                return true
            }
            if (type is RustUnknownType) {
                return true
            }
            if (declaration is RsEnumVariant) return true

            false
        }
        is RsFieldExpr -> (this.expr.type as? RustReferenceType)?.mutable ?: true
        is RsUnaryExpr -> mul != null || (expr != null && expr?.isMutable() ?: true)
        else -> true
    }
}

private val RsTypeReference.topmostType: RsTypeReference
    get() = ancestors
        .drop(1)
        .filterNot { it is RsTypeArgumentList || it is RsPath }
        .takeWhile { it is RsBaseType || it is RsTupleType || it is RsRefLikeType }
        .lastOrNull() as? RsTypeReference ?: this

private val RsTupleType.isUnitType: Boolean get() = typeReferenceList.isNullOrEmpty()
