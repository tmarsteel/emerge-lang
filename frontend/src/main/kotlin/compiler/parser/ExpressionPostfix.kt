package compiler.parser

import compiler.InternalCompilerError
import compiler.ast.AstSemanticOperator
import compiler.ast.Expression
import compiler.ast.expression.*
import compiler.ast.type.TypeArgument
import compiler.ast.type.TypeReference
import compiler.lexer.*

/**
 * Given the expression itself, it returns a new expression that contains the information about the postfix.
 * E.g. matching the !! postfix results in a [NotNullExpressionPostfix] which in turn will wrap any given
 * [Expression] in a [NotNullExpression].
 */
interface ExpressionPostfix<out OutExprType: Expression> {
    fun modify(expr: Expression): OutExprType
}

class NotNullExpressionPostfix(
    /** The notnull operator for reference; whether the operator is actually [Operator.NOTNULL] is never checked.*/
    val notNullOperator: OperatorToken
) : ExpressionPostfix<NotNullExpression> {
    override fun modify(expr: Expression) = NotNullExpression(expr, notNullOperator)
}

class InvocationExpressionPostfix(
    val typeArguments: List<TypeArgument>?,
    val valueParameterExpressions: List<Expression>,
    val closingParenthesis: OperatorToken,
) : ExpressionPostfix<InvocationExpression> {
    override fun modify(expr: Expression): InvocationExpression {
        val startLocation = when (expr) {
            is MemberAccessExpression -> expr.memberName.span
            else -> expr.span
        }
        val invocationLocation = startLocation .. closingParenthesis.span
        return InvocationExpression(expr, typeArguments, valueParameterExpressions, invocationLocation)
    }
}

class MemberAccessExpressionPostfix(
    /** must be either [Operator.DOT] or [Operator.SAFEDOT] */
    val accessOperatorToken: OperatorToken,
    val memberName: IdentifierToken
) : ExpressionPostfix<MemberAccessExpression> {
    override fun modify(expr: Expression) = MemberAccessExpression(expr, accessOperatorToken, memberName)
}

class IndexAccessExpressionPostfix(
    val sBraceOpen: OperatorToken,
    val indexExpr: Expression,
    val sBraceClose: OperatorToken,
) : ExpressionPostfix<AstIndexAccessExpression> {
    override fun modify(expr: Expression) = AstIndexAccessExpression(expr, sBraceOpen, indexExpr, sBraceClose)
}

class CastExpressionPostfix(
    val asToken: KeywordToken,
    val isSafe: Boolean,
    val toType: TypeReference,
) : ExpressionPostfix<AstCastExpression> {
    override fun modify(expr: Expression) = AstCastExpression(expr, asToken, isSafe, toType)
}

class InstanceOfExpressionPostfix(
    val operator: KeywordToken,
    val typeToCheck: TypeReference,
) : ExpressionPostfix<AstInstanceOfExpression> {
    override fun modify(expr: Expression): AstInstanceOfExpression {
        return AstInstanceOfExpression(expr, operator, typeToCheck)
    }
}

class BinaryExpressionPostfix(
    val operatorsOrExpressions: List<OperatorOrExpression>,
) : ExpressionPostfix<Expression> {
    override fun modify(expr: Expression): Expression {
        throw UnsupportedOperationException("Use ${Companion::buildBinaryExpression} instead")
    }

    companion object {
        fun buildBinaryExpression(first: Expression, postfixes: List<BinaryExpressionPostfix>): Expression {
            val operatorsOrExprs = ArrayList<OperatorOrExpression>(1 + postfixes.sumOf { it.operatorsOrExpressions.size })
            operatorsOrExprs.add(first)
            postfixes.forEach {
                it.operatorsOrExpressions.forEach { opOrExpr ->
                    operatorsOrExprs.add(opOrExpr)
                }
            }

            return buildBinaryExpressionAst(operatorsOrExprs)
        }
    }
}

private typealias OperatorOrExpression = Any // kotlin does not have a union type; if it had, this would be = AstSemanticOperator | Expression

/**
 * Operator priority/precedence. The higher the priority, the more aggressively the operator
 * binds to terms around it. `*` and `/` have a higher priority than `+` or `-`, as per
 * the rules of mathematics.
 */
private val AstSemanticOperator.priority: Int
    get() = when(operatorElement) {
        Operator.NULL_COALESCE -> 10

        Keyword.AND -> 20
        Keyword.OR -> 21

        Operator.LESS_THAN,
        Operator.LESS_THAN_OR_EQUALS,
        Operator.GREATER_THAN,
        Operator.GREATER_THAN_OR_EQUALS,
        Operator.EQUALS,
        Operator.NOT_EQUALS -> 30

        Operator.PLUS,
        Operator.MINUS -> 40

        Operator.TIMES,
        Operator.DIVIDE -> 50
        else -> throw InternalCompilerError("$this is not a binary operator")
    }

/**
 * Takes as input a list of alternating [Expression]s and [OperatorToken]s, e.g.:
 *     [Identifier(a), Operator(+), Identifier(b), Operator(*), Identifier(c)]
 * Builds the AST with respect to operator precedence defined in [Operator.priority]
 *
 * **Operator Precedence**
 *
 * Consider this input: `a * (b + c) + e * f + g`
 * Of those operators with the lowest precedence the rightmost will form the toplevel expression (the one
 * returned from this function). In this case it's the second `+`:
 *
 *                                   +
 *                                  / \
 *      [a, *, (b + c), +, e, *, f]    g
 *
 * This process is then recursively repeated for both sides of the node:
 *
 *                        +
 *                       / \
 *                      +   g
 *                     / \
 *     [a, *, (b + c)]    [e, *, f]
 *
 *     ---
 *               +
 *              / \
 *             +   g
 *            / \
 *           /   *
 *          /   / \
 *         *   e   f
 *        / \
 *       a  (b + c)
 */
private fun buildBinaryExpressionAst(rawExpression: List<OperatorOrExpression>): Expression {
    if (rawExpression.size == 1) {
        return rawExpression[0] as? Expression
            ?: throw InternalCompilerError("List with one item that is not an expression.. bug!")
    }

    @Suppress("UNCHECKED_CAST") // the type check is in the filter {}
    val operatorsWithIndex = rawExpression
        .mapIndexed { index, item -> Pair(index, item) }
        .filter { it.second is AstSemanticOperator } as List<Pair<Int, AstSemanticOperator>>

    val rightmostWithLeastPriority = operatorsWithIndex
        .reversed()
        .minByOrNull { it.second.priority }
        ?: throw InternalCompilerError("No operator in the list... how can this even be?")

    val leftOfOperator = rawExpression.subList(0, rightmostWithLeastPriority.first)
    val rightOfOperator = rawExpression.subList(rightmostWithLeastPriority.first + 1, rawExpression.size)

    return BinaryExpression(
        leftHandSide = buildBinaryExpressionAst(leftOfOperator),
        operator = rightmostWithLeastPriority.second,
        rightHandSide = buildBinaryExpressionAst(rightOfOperator)
    )
}