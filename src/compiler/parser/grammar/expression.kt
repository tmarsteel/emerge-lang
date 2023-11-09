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

import compiler.ast.expression.Expression
import compiler.lexer.IdentifierToken
import compiler.lexer.Keyword.ELSE
import compiler.lexer.Keyword.IF
import compiler.lexer.Operator
import compiler.lexer.OperatorToken
import compiler.lexer.TokenType
import compiler.matching.ResultCertainty.DEFINITIVE
import compiler.matching.ResultCertainty.MATCHED
import compiler.matching.ResultCertainty.OPTIMISTIC
import compiler.parser.grammar.dsl.eitherOf
import compiler.parser.grammar.dsl.postprocess
import compiler.parser.grammar.dsl.sequence
import compiler.parser.postproc.BinaryExpressionPostProcessor
import compiler.parser.postproc.BracedCodeOrSingleStatementPostProcessor
import compiler.parser.postproc.ExpressionPostfixModifier
import compiler.parser.postproc.ExpressionPostprocessor
import compiler.parser.postproc.IdentifierExpressionPostProcessor
import compiler.parser.postproc.IfExpressionPostProcessor
import compiler.parser.postproc.InvocationExpressionPostfixModifier
import compiler.parser.postproc.LiteralExpressionPostProcessor
import compiler.parser.postproc.MemberAccessExpressionPostfixModifier
import compiler.parser.postproc.NotNullExpressionPostfixModifier
import compiler.parser.postproc.ParanthesisedExpressionPostProcessor
import compiler.parser.postproc.UnaryExpressionPostProcessor
import compiler.parser.postproc.flatten
import compiler.parser.postproc.mapResult
import compiler.parser.rule.Rule

val Expression: Rule<Expression<*>> by lazy {
    sequence("expression") {
        eitherOf {
            ref(BinaryExpression)
            ref(UnaryExpression)
            ref(ValueExpression)
            ref(ParanthesisedExpression)
            ref(IfExpression)
        }
        certainty = MATCHED
        atLeast(0) {
            ref(ExpressionPostfix)
        }
        certainty = DEFINITIVE
    }
        .postprocess(::ExpressionPostprocessor)
}

val LiteralExpression = sequence("literal") {
    eitherOf {
        tokenOfType(TokenType.NUMERIC_LITERAL)
        // TODO: string literal, function literal
        // literals that the lexer treats as identifiers (booleans, ...?) are handled in ValueExpression
    }
    certainty = MATCHED
}
    .postprocess(::LiteralExpressionPostProcessor)

val IdentifierExpression = sequence("identifier") {
    identifier()
    certainty = DEFINITIVE
}
    .postprocess(::IdentifierExpressionPostProcessor)

val ValueExpression = eitherOf("value expression") {
    ref(LiteralExpression)
    ref(IdentifierExpression)
}

val ParanthesisedExpression: Rule<Expression<*>> = sequence("paranthesised expression") {
    operator(Operator.PARANT_OPEN)
    ref(Expression)
    certainty = MATCHED
    operator(Operator.PARANT_CLOSE)
    certainty = DEFINITIVE
}
    .postprocess(::ParanthesisedExpressionPostProcessor)

val UnaryExpression = sequence("unary expression") {
    eitherOf(Operator.PLUS, Operator.MINUS, Operator.NEGATE)
    // TODO: tilde, ... what else?

    eitherOf {
        // TODO: reorder these to comply to the defined operator precedence (e.g. DOT before MINUS)
        ref(ValueExpression)
        ref(ParanthesisedExpression)
    }
    certainty = DEFINITIVE
}
    .postprocess(::UnaryExpressionPostProcessor)

val binaryOperators = arrayOf(
    // Arithmetic
    Operator.PLUS, Operator.MINUS, Operator.TIMES, Operator.DIVIDE,
    // Comparison
    Operator.EQUALS, Operator.GREATER_THAN, Operator.LESS_THAN, Operator.GREATER_THAN_OR_EQUALS, Operator.LESS_THAN_OR_EQUALS,
    Operator.IDENTITY_EQ, Operator.IDENTITY_NEQ,
    // MISC
    Operator.CAST, Operator.TRYCAST, Operator.ELVIS
)

val BinaryExpression = sequence("ary operator expression") {
    eitherOf {
        ref(UnaryExpression)
        ref(ValueExpression)
        ref(ParanthesisedExpression)
    }
    atLeast(1) {
        eitherOf(*binaryOperators) // TODO: arbitrary infix ops
        certainty = MATCHED
        eitherOf {
            ref(UnaryExpression)
            ref(ValueExpression)
            ref(ParanthesisedExpression)
        }
    }
    certainty = DEFINITIVE
}
    .postprocess(::BinaryExpressionPostProcessor)

val BracedCodeOrSingleStatement = sequence("curly braced code or single statement") {
    eitherOf {
        sequence {
            operator(Operator.CBRACE_OPEN)
            certainty = MATCHED
            optionalWhitespace()
            optional {
                ref(CodeChunk)
                certainty = DEFINITIVE
            }
            optionalWhitespace()
            operator(Operator.CBRACE_CLOSE)
            certainty = DEFINITIVE
        }
        ref(Expression)
    }
    certainty = DEFINITIVE
}
    .postprocess(::BracedCodeOrSingleStatementPostProcessor)

val IfExpression = sequence("if-expression") {
    keyword(IF)
    certainty = MATCHED
    ref(Expression)

    ref(BracedCodeOrSingleStatement)
    certainty = OPTIMISTIC

    optionalWhitespace()

    optional {
        keyword(ELSE)
        certainty = MATCHED
        optionalWhitespace()
        ref(BracedCodeOrSingleStatement)
        certainty = DEFINITIVE
    }

    certainty = DEFINITIVE
}
    .postprocess(::IfExpressionPostProcessor)

val ExpressionPostfixNotNull = sequence(OperatorToken(Operator.NOTNULL).toStringWithoutLocation()) {
    operator(Operator.NOTNULL)
    certainty = DEFINITIVE
    optionalWhitespace()
}
    .flatten()
    .mapResult { NotNullExpressionPostfixModifier(it.next()!! as OperatorToken) }

val ExpressionPostfixInvocation = sequence("function invocation") {
    operator(Operator.PARANT_OPEN)
    optionalWhitespace()

    optional {
        ref(Expression)
        optionalWhitespace()

        atLeast(0) {
            operator(Operator.COMMA)
            optionalWhitespace()
            ref(Expression)
        }
    }

    optionalWhitespace()
    operator(Operator.PARANT_CLOSE)
    certainty = MATCHED
}
    .flatten()
    .mapResult(InvocationExpressionPostfixModifier.Companion::fromMatchedTokens)

val ExpressionPostfixMemberAccess = sequence("member access") {
    eitherOf(Operator.DOT, Operator.SAFEDOT)
    certainty = MATCHED
    identifier()
    certainty = OPTIMISTIC
    optionalWhitespace()
}
    .flatten()
    .mapResult {
        val accessOperator = it.next() as OperatorToken
        val memberNameToken = it.next() as IdentifierToken
        MemberAccessExpressionPostfixModifier(accessOperator, memberNameToken)
    }

val ExpressionPostfix = sequence {
    eitherOf {
        ref(ExpressionPostfixNotNull)
        ref(ExpressionPostfixInvocation)
        ref(ExpressionPostfixMemberAccess)
    }
    certainty = OPTIMISTIC
}
    .flatten()
    .mapResult { it.next()!! as ExpressionPostfixModifier<Expression<*>> }
