package compiler.binding

import compiler.ast.AstFunctionAttribute
import compiler.ast.AstVisibility
import compiler.binding.context.CTContext
import compiler.reportings.Reporting
import compiler.twoElementPermutationsUnordered

class BoundFunctionAttributeList(
    context: CTContext,
    val attributes: List<AstFunctionAttribute>
) : SemanticallyAnalyzable {
    private val reportings = ArrayList<Reporting>()
    override fun semanticAnalysisPhase1() = reportings // happens in init {} block
    override fun semanticAnalysisPhase2() = emptyList<Reporting>()
    override fun semanticAnalysisPhase3() = emptyList<Reporting>()

    val firstModifyingAttribute: AstFunctionAttribute?
    private val firstReadonlyAttribute: AstFunctionAttribute?
    private val firstPureAttribute: AstFunctionAttribute?
    val externalAttribute: AstFunctionAttribute.External?
    val visibility: BoundVisibility = attributes
        .filterIsInstance<AstVisibility>()
        .firstOrNull()
        ?.bindTo(context)
        ?: BoundVisibility.default(context)

    val firstOverrideAttribute: AstFunctionAttribute.Override?

    val impliesNoBody: Boolean
    var isDeclaredOperator: Boolean

    init {
        val attrSequence = attributes.asSequence()
        impliesNoBody = attributes.any { it.impliesNoBody }
        isDeclaredOperator = attributes.any { it is AstFunctionAttribute.Operator }
        firstModifyingAttribute = attributes.firstOrNull {
            it is AstFunctionAttribute.EffectCategory && it.value == AstFunctionAttribute.EffectCategory.Category.MODIFYING
        }
        firstReadonlyAttribute = attributes.firstOrNull {
            it is AstFunctionAttribute.EffectCategory && it.value == AstFunctionAttribute.EffectCategory.Category.READONLY
        }
        firstPureAttribute = attributes.firstOrNull {
            it is AstFunctionAttribute.EffectCategory && it.value == AstFunctionAttribute.EffectCategory.Category.PURE
        }
        if (firstPureAttribute != null) {
            reportings.add(Reporting.inefficientAttributes(
                "The attribute \"pure\" is superfluous, functions are pure by default.",
                listOf(firstPureAttribute),
            ))
        }

        externalAttribute = attrSequence.filterIsInstance<AstFunctionAttribute.External>().firstOrNull()
        firstOverrideAttribute = attrSequence.filterIsInstance<AstFunctionAttribute.Override>().firstOrNull()

        attributes.groupBy { it }.values
            .filter { it.size > 1 }
            .forEach { attrs ->
                reportings.add(Reporting.inefficientAttributes(
                    "These attributes are redundant",
                    attrs,
                ))
            }
        attributes.twoElementPermutationsUnordered()
            .filter { (a, b) -> conflictsWith(a, b) }
            .forEach { (a, b) ->
                reportings.add(Reporting.conflictingModifiers(listOf(a, b)))
            }

        attributes
            .filterIsInstance<AstFunctionAttribute.External>()
            .filter { it.ffiName.value !in SUPPORTED_EXTERNAL_CALLING_CONVENTIONS }
            .forEach {
                reportings.add(Reporting.unsupportedCallingConvention(it, SUPPORTED_EXTERNAL_CALLING_CONVENTIONS))
            }
    }

    /**
     * Whether this function is pure as per its declaration. Functions are pure by default, so this is the case
     * if neither [FunctionModifier.Readonly] nor [FunctionModifier.Modifying] is present.
     */
    private val isDeclaredPure: Boolean = firstPureAttribute != null || (firstReadonlyAttribute == null && firstModifyingAttribute == null)

    /**
     * Whether this function is readonly as per its declaration. Functions are pure by default, so this is `false`
     * by default. It is true iff the function is explicitly marked with [AstFunctionAttribute.EffectCategory.Category.READONLY].
     */
    private val isDeclaredReadonly: Boolean = firstReadonlyAttribute != null

    val purity: BoundFunction.Purity = when {
        isDeclaredPure -> BoundFunction.Purity.PURE
        isDeclaredReadonly -> BoundFunction.Purity.READONLY
        else -> BoundFunction.Purity.MODIFYING
    }

    val purityAttribute: AstFunctionAttribute? = when(purity) {
        BoundFunction.Purity.PURE -> firstPureAttribute
        BoundFunction.Purity.READONLY -> firstReadonlyAttribute
        BoundFunction.Purity.MODIFYING -> firstModifyingAttribute
    }

    companion object {
        val SUPPORTED_EXTERNAL_CALLING_CONVENTIONS = setOf("C")
        private fun conflictsWith(a: AstFunctionAttribute, b: AstFunctionAttribute): Boolean = when (a) {
            is AstVisibility -> b is AstVisibility && a != b
            is AstFunctionAttribute.EffectCategory -> {
                if (b is AstFunctionAttribute.EffectCategory) {
                    a.value != b.value
                    // if a == b it's an inefficiency, reported through the general inefficiency mechanism
                } else false
            }
            is AstFunctionAttribute.External -> {
                if (b is AstFunctionAttribute.External) {
                    a.ffiName != b.ffiName
                    // if a == b it's an inefficiency, reported through the general inefficiency mechanism
                } else false
            }
            is AstFunctionAttribute.Override,
            is AstFunctionAttribute.Operator,
            is AstFunctionAttribute.Intrinsic,
            is AstFunctionAttribute.Nothrow -> { false }
        }
    }
}