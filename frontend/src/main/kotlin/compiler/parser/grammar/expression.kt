/*
 * Copyright 2018 Tobias Marstaller
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public License
 * as published by the Free Software Foundation; either version 3
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA 02111-1307, USA.
 */

package compiler.parser.grammar

import compiler.InternalCompilerError
import compiler.ast.*
import compiler.ast.expression.*
import compiler.ast.type.TypeArgument
import compiler.ast.type.TypeReference
import compiler.lexer.*
import compiler.lexer.Keyword.*
import compiler.parser.*
import compiler.parser.grammar.dsl.astTransformation
import compiler.parser.grammar.dsl.eitherOf
import compiler.parser.grammar.dsl.mapResult
import compiler.parser.grammar.dsl.sequence
import compiler.parser.grammar.rule.Rule
import compiler.transact.TransactionalSequence
import compiler.ast.Expression as AstExpression

private val ExpressionBase: Rule<AstExpression> = eitherOf("expression without postfixes") {
    ref(UnaryExpression)
    ref(ValueExpression)
    ref(ParanthesisedExpression)
    ref(ExecutionAbortingExpression)
    ref(IfExpression)
    ref(TryCatchExpression)
}
    .astTransformation { tokens -> tokens.next() as AstExpression }

val Expression: Rule<AstExpression> = sequence("expression") {
    ref(ExpressionBase)
    repeating {
        ref(ExpressionPostfix)
    }
}
    .astTransformation(false, ::astTransformOneExpressionWithOptionalPostfixes)

val ExpressionExcludingBinaryPostfix = sequence("expression (excluding binary expressions)") {
    ref(ExpressionBase)
    repeating {
        ref(ExpressionPostfixExcludingBinary)
    }
}
    .astTransformation(false, ::astTransformOneExpressionWithOptionalPostfixes)

val StringLiteralExpression = sequence("string literal") {
    operator(Operator.STRING_DELIMITER)
    stringLiteralContent()
    operator(Operator.STRING_DELIMITER)
}
    .astTransformation { tokens ->
        val startDelimiter = tokens.next() as OperatorToken
        val content = tokens.next() as StringLiteralContentToken
        val endDelimiter = tokens.next() as OperatorToken

        StringLiteralExpression(startDelimiter, content, endDelimiter)
    }

val ArrayLiteralExpression = sequence("array literal") {
    operator(Operator.SBRACE_OPEN)
    optional {
        ref(Expression)
        repeating {
            operator(Operator.COMMA)
            ref(Expression)
        }
    }
    operator(Operator.SBRACE_CLOSE)
}
    .astTransformation { tokens ->
        val openingBracket = tokens.next() as OperatorToken
        val elements = mutableListOf<AstExpression>()
        var next = tokens.next()
        while (next is AstExpression) {
            elements.add(next)
            next = tokens.next()
            check(next is OperatorToken)
            if (next.operator == Operator.SBRACE_CLOSE) {
                break
            }
            check(next.operator == Operator.COMMA)
            next = tokens.next()
        }
        val closingBracket = next as OperatorToken

        ArrayLiteralExpression(
            openingBracket,
            elements,
            closingBracket,
        )
    }

val LiteralExpression = sequence("literal") {
    eitherOf {
        numericLiteral()
        ref(StringLiteralExpression)
    }
}
    .astTransformation { tokens ->
        when (val valueToken = tokens.next()!!) {
            is NumericLiteralToken -> NumericLiteralExpression(valueToken)
            is StringLiteralExpression -> valueToken
            else -> throw InternalCompilerError("Unsupported literal value $valueToken")
        }
    }

val IdentifierExpression = sequence("identifier") {
    ref(Identifier)
}
    .astTransformation { tokens ->
        val identifier = tokens.next() as IdentifierToken
        when (identifier.value) {
            "true", "false" -> BooleanLiteralExpression(identifier.span, identifier.value == "true")
            "null" -> NullLiteralExpression(identifier.span)
            else -> IdentifierExpression(identifier)
        }
    }

val ReflectExpression = sequence("reflection") {
    keyword(REFLECT)
    ref(Type)
}
    .astTransformation { tokens ->
        val keyword = tokens.next() as KeywordToken
        val type = tokens.next() as TypeReference
        AstReflectExpression(keyword, type)
    }

val ValueExpression = eitherOf("value expression") {
    ref(LiteralExpression)
    ref(IdentifierExpression)
    ref(ArrayLiteralExpression)
    ref(ReflectExpression)
}

val ParanthesisedExpression: Rule<AstExpression> = sequence("paranthesised expression") {
    operator(Operator.PARANT_OPEN)
    ref(Expression)
    operator(Operator.PARANT_CLOSE)
}
    .astTransformation { tokens ->
        val parantOpen = tokens.next()!! as OperatorToken
        val nested = tokens.next()!! as AstExpression

        ParenthesisedExpression(nested, parantOpen.span)
    }

val UnaryExpression = sequence("unary expression") {
    eitherOf {
        operator(Operator.MINUS)
        keyword(Keyword.NOT)
    }
    // There is no unary plus because it is a noop on all builtin types. Allowing overloading wiht a non-noop behavior
    // would just cause confusion

    ref(Expression)
}
    .astTransformation { tokens ->
        val operator = when(val operatorToken = tokens.next()!!) {
            is OperatorToken -> AstSemanticOperator(operatorToken)
            is KeywordToken -> AstSemanticOperator(operatorToken)
            else -> throw InternalCompilerError("Unsupported unary operator token type $operatorToken")
        }
        val expression = tokens.next()!! as AstExpression
        UnaryExpression(operator, expression)
    }

val ExecutionAbortingExpression = eitherOf {
    ref(ReturnStatement)
    ref(ThrowStatement)
    ref(ContinueStatement)
    ref(BreakStatement)
}
    .astTransformation { tokens -> tokens.next() as AstExpression }

val ReturnStatement = sequence("return statement") {
    keyword(Keyword.RETURN)
    optional {
        ref(Expression)
    }
}
    .astTransformation { tokens ->
        val keyword = tokens.next()!! as KeywordToken
        val expression = if (tokens.hasNext()) tokens.next()!! as AstExpression else null

        ReturnExpression(keyword, expression)
    }

val ThrowStatement = sequence("throw statement") {
    keyword(Keyword.THROW)
    ref(Expression)
}
    .astTransformation { tokens ->
        val keyword = tokens.next()!! as KeywordToken
        val expression = tokens.next()!! as AstExpression

        AstThrowExpression(keyword, expression)
    }

val BreakStatement = sequence("break statement") {
    keyword(Keyword.BREAK)
}
    .astTransformation { tokens ->
        AstBreakExpression(tokens.next()!! as KeywordToken)
    }

val ContinueStatement = sequence("continue statement") {
    keyword(Keyword.CONTINUE)
}
    .astTransformation { tokens ->
        AstContinueExpression(tokens.next()!! as KeywordToken)
    }

val BracedCodeOrSingleStatement = eitherOf("curly braced code or single statement") {
    sequence {
        operator(Operator.CBRACE_OPEN)
        ref(CodeChunk)
        operator(Operator.CBRACE_CLOSE)
    }
    ref(Expression)
}
    .astTransformation { tokens ->
        var next: Any? = tokens.next()

        if (next is Executable) {
            return@astTransformation next
        }

        if (next != OperatorToken(Operator.CBRACE_OPEN)) {
            throw InternalCompilerError("Unexpected $next, expecting ${Operator.CBRACE_OPEN} or executable")
        }

        next = tokens.next()

        if (next == OperatorToken(Operator.CBRACE_CLOSE)) {
            return@astTransformation AstCodeChunk(emptyList())
        }

        if (next !is AstCodeChunk) {
            throw InternalCompilerError("Unexpected $next, expecting code or ${Operator.CBRACE_CLOSE}")
        }

        return@astTransformation next
    }

val IfExpression = sequence("if-expression") {
    keyword(IF)
    ref(Expression)

    ref(BracedCodeOrSingleStatement)

    optional {
        keyword(ELSE)
        ref(BracedCodeOrSingleStatement)
    }
}
    .astTransformation { tokens ->
        val ifKeyword = tokens.next() as KeywordToken

        @Suppress("UNCHECKED_CAST")
        val condition = tokens.next() as AstExpression
        val thenCode: Executable = tokens.next() as Executable
        val elseCode: Executable? = if (tokens.hasNext()) {
            // skip ELSE keyword
            tokens.next()
            tokens.next() as Executable
        } else {
            null
        }

        IfExpression(
            ifKeyword.span,
            condition,
            thenCode,
            elseCode
        )
    }

val TryCatchExpression = sequence("try-catch expression") {
    keyword(TRY)
    ref(BracedCodeOrSingleStatement)

    keyword(CATCH)
    ref(Identifier)
    ref(BracedCodeOrSingleStatement)
}
    .astTransformation { tokens ->
        val tryKeyword = tokens.next() as KeywordToken
        val fallibleCode = tokens.next() as Executable
        val catchKeyword = tokens.next() as KeywordToken
        val throwableNameToken = tokens.next() as IdentifierToken
        val catchCode = tokens.next() as Executable

        AstTryCatchExpression(
            tryKeyword.span .. catchCode.span,
            fallibleCode,
            AstCatchBlockExpression(
                catchKeyword.span .. catchCode.span,
                throwableNameToken,
                catchCode,
            ),
        )
    }

val ExpressionPostfixNotNull = sequence("not null assertion") {
    operator(Operator.NOTNULL)
}
    .astTransformation { NotNullExpressionPostfix(it.next()!! as OperatorToken) }

val InvocationTypeArguments = sequence {
    /* this is the turbofish, stolen from Rust. Without the two COLONs, this source has two valid parse trees:
    A<Int>(false)

    Option 1:
    Binary(A, <, Binary(Int, >, Parenthesized(false)))

    Option 2:
    Invocation(name=A, typeArgs=[Int], args=[false])
    */
    operator(Operator.COLON)
    operator(Operator.COLON)
    ref(BracedTypeArguments)
}
    .astTransformation { tokens ->
        tokens.next()
        tokens.next()
        tokens.next() as TypeArgumentBundle
    }

val ExpressionPostfixInvocation = sequence("function invocation") {
    optional {
        ref(InvocationTypeArguments)
    }
    operator(Operator.PARANT_OPEN)

    optional {
        ref(Expression)

        repeating {
            operator(Operator.COMMA)
            ref(Expression)
        }
    }

    operator(Operator.PARANT_CLOSE)
}
    .astTransformation { tokens ->
        val typeArguments: List<TypeArgument>?
        val next = tokens.next()!!
        if (next is TypeArgumentBundle) {
            typeArguments = next.arguments
            // skip PARANT_OPEN
            tokens.next() as OperatorToken
        } else {
            typeArguments = null
            // skip PARANT_OPEN
            next as OperatorToken
        }

        val valueArguments = mutableListOf<AstExpression>()
        var lastToken: OperatorToken? = null
        while (tokens.peek() is AstExpression) {
            valueArguments.add(tokens.next()!! as AstExpression)

            // skip COMMA or PARANT_CLOSE
            lastToken = tokens.next() as OperatorToken
        }
        if (tokens.hasNext()) {
            // PARANT_CLOSE in case of 0 arguments
            lastToken = tokens.next() as OperatorToken
        }

        InvocationExpressionPostfix(typeArguments, valueArguments, lastToken!!)
    }

val ExpressionPostfixMemberAccess = sequence("member access") {
    eitherOf(Operator.DOT, Operator.SAFEDOT)
    ref(Identifier)
}
    .astTransformation { tokens ->
        val accessOperator = tokens.next() as OperatorToken
        val memberNameToken = tokens.next() as IdentifierToken
        MemberAccessExpressionPostfix(accessOperator, memberNameToken)
    }

val ExpressionPostfixIndexAccess = sequence("index") {
    operator(Operator.SBRACE_OPEN)
    ref(Expression)
    operator(Operator.SBRACE_CLOSE)
}
    .astTransformation { tokens ->
        val sBraceOpen = tokens.next() as OperatorToken
        val indexValue = tokens.next() as AstExpression
        val sBraceClose = tokens.next() as OperatorToken
        IndexAccessExpressionPostfix(sBraceOpen, indexValue, sBraceClose)
    }

val ExpressionPostfixCast = sequence("cast") {
    keyword(Keyword.AS)
    optional {
        operator(Operator.QUESTION_MARK)
    }
    ref(Type)
}
    .astTransformation { tokens ->
        val keyword = tokens.next()!! as KeywordToken
        val next = tokens.next()
        val isSafe: Boolean
        val toType: TypeReference
        if (next is OperatorToken && next.operator == Operator.QUESTION_MARK) {
            isSafe = true
            toType = tokens.next()!! as TypeReference
        } else {
            isSafe = false
            toType = next as TypeReference
        }
        CastExpressionPostfix(keyword, isSafe, toType)
    }

val ExpressionPostfixInstanceOf = sequence("instance-of") {
    keyword(Keyword.INSTANCEOF)
    ref(Type)
}
    .astTransformation { tokens ->
        val operator = tokens.next()!! as KeywordToken
        val typeToCheck = tokens.next()!! as TypeReference
        InstanceOfExpressionPostfix(operator, typeToCheck)
    }

val BinaryOperator = eitherOf {
    // Arithmetic
    operator(Operator.PLUS)
    operator(Operator.MINUS)
    operator(Operator.TIMES)
    operator(Operator.DIVIDE)

    // Comparison
    operator(Operator.EQUALS)
    operator(Operator.NOT_EQUALS)
    operator(Operator.GREATER_THAN)
    operator(Operator.LESS_THAN)
    operator(Operator.GREATER_THAN_OR_EQUALS)
    operator(Operator.LESS_THAN_OR_EQUALS)

    // Logic
    keyword(Keyword.AND)
    keyword(Keyword.OR)
    keyword(Keyword.XOR)

    // MISC
    operator(Operator.NULL_COALESCE)
}
    .astTransformation { tokens ->
        when (val element = tokens.next()!!) {
            is OperatorToken -> AstSemanticOperator(element)
            is KeywordToken -> AstSemanticOperator(element)
            else -> throw InternalCompilerError("Unsupported binary operator token $element")
        }
    }

val ExpressionPostfixBinaryOperation = sequence("binary expression") {
    ref(BinaryOperator)
    ref(ExpressionExcludingBinaryPostfix)
}
    .astTransformation { tokens ->
        BinaryExpressionPostfix(tokens.remainingToList())
    }

val ExpressionPostfixExcludingBinary = eitherOf {
    ref(ExpressionPostfixNotNull)
    ref(ExpressionPostfixInvocation)
    ref(ExpressionPostfixMemberAccess)
    ref(ExpressionPostfixIndexAccess)
    ref(ExpressionPostfixCast)
    ref(ExpressionPostfixInstanceOf)
}

val ExpressionPostfix = eitherOf {
    ref(ExpressionPostfixExcludingBinary)
    ref(ExpressionPostfixBinaryOperation)
}
    .mapResult { it as ExpressionPostfix<AstExpression> }

private fun astTransformOneExpressionWithOptionalPostfixes(tokens: TransactionalSequence<Any, *>): AstExpression {
    var expression = tokens.next()!! as AstExpression

    // we have to find runs of BinaryOperationPostfix to correctly account for operator precedence
    tokens@while (tokens.hasNext()) {
        val postfix = tokens.next() as ExpressionPostfix<*>
        if (postfix !is BinaryExpressionPostfix) {
            expression = postfix.modify(expression)
            continue@tokens
        }

        val binaryPostfixes = ArrayList<BinaryExpressionPostfix>()
        binaryPostfixes.add(postfix)
        while (tokens.peek() is BinaryExpressionPostfix) {
            binaryPostfixes.add(tokens.next() as BinaryExpressionPostfix)
        }

        expression = BinaryExpressionPostfix.buildBinaryExpression(expression, binaryPostfixes)
    }

    return expression
}