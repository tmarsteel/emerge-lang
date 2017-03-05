package compiler.ast.expression

import compiler.ast.context.CTContext
import compiler.ast.context.Function
import compiler.ast.context.filterAndSortByMatchForInvocationTypes
import compiler.ast.type.BaseTypeReference
import compiler.ast.type.FunctionModifier
import compiler.lexer.OperatorToken

class BinaryExpression(
    val first: Expression,
    val op: OperatorToken,
    val second: Expression
) : Expression {
    override fun determineType(context: CTContext): BaseTypeReference {
        return getOperatorFunction(context)?.returnType ?: compiler.ast.type.Any.baseReference(context)
    }

    /**
     * Attempts to resolve the operator function in the given context.
     *
     * @return The operator function to use to evaluate this expression or null the given context does not contain
     *         a suitable function.
     */
    private fun getOperatorFunction(context: CTContext): Function? {
        val typeFirst = first.determineType(context)
        val typeSecond = second.determineType(context)

        val opFunName = "op" + op.operator.name[0].toUpperCase() + op.operator.name.substring(1).toLowerCase()

        val receiverOperatorFuns =
            context.resolveAnyFunctions(opFunName)
            .filterAndSortByMatchForInvocationTypes(typeFirst, listOf(typeSecond))
            .filter { FunctionModifier.OPERATOR in it.declaration.modifiers}

        return receiverOperatorFuns.firstOrNull()
    }
}

private fun <T, R> Iterable<T>.attachMapNotNull(transform: (T) -> R): Iterable<Pair<T, R>> = map{ it to transform(it) }.filter { it.second != null }