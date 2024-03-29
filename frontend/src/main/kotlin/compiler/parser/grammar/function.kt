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
import compiler.ast.CodeChunk
import compiler.ast.Expression
import compiler.ast.FunctionDeclaration
import compiler.ast.ParameterList
import compiler.ast.TypeParameterBundle
import compiler.ast.VariableDeclaration
import compiler.ast.type.FunctionModifier
import compiler.ast.type.TypeParameter
import compiler.ast.type.TypeReference
import compiler.lexer.IdentifierToken
import compiler.lexer.Keyword
import compiler.lexer.KeywordToken
import compiler.lexer.Operator
import compiler.lexer.OperatorToken
import compiler.lexer.Token
import compiler.parser.grammar.dsl.astTransformation
import compiler.parser.grammar.dsl.sequence
import java.util.LinkedList

val Parameter = sequence("parameter declaration") {
    optional {
        keyword(Keyword.VAR)
    }

    identifier()

    optional {
        operator(Operator.COLON)
        ref(Type)
    }

    optional {
        operator(Operator.ASSIGNMENT)
        ref(Expression)
    }
}
    .astTransformation { tokens ->
        val varKeywordToken: KeywordToken?
        var next: Any? = tokens.next()!!

        if (next is KeywordToken) {
            varKeywordToken = next
            next = tokens.next()!!
        } else {
            varKeywordToken = null
        }
        val name = next as IdentifierToken
        next = tokens.next() as OperatorToken?

        var type: TypeReference? = null
        if (next != null && next.operator == Operator.COLON) {
            type = tokens.next() as TypeReference
            next = tokens.next()
        } else {
            type = null
        }

        val defaultValue: Expression? = if (next != null) {
            tokens.next() as Expression
        } else {
            null
        }

        VariableDeclaration(
            name.sourceLocation,
            varKeywordToken,
            name,
            type,
            defaultValue,
        )
    }

val ParameterList = sequence("parenthesised parameter list") {
    operator(Operator.PARANT_OPEN)

    optionalWhitespace()

    optional {
        ref(Parameter)

        optionalWhitespace()

        repeating {
            operator(Operator.COMMA)
            optionalWhitespace()
            ref(Parameter)
        }
    }

    optionalWhitespace()
    optional {
        operator(Operator.COMMA)
    }
    operator(Operator.PARANT_CLOSE)
}
    .astTransformation { tokens ->
        // skip PARANT_OPEN
        tokens.next()!!

        val parameters: MutableList<VariableDeclaration> = LinkedList()

        while (tokens.hasNext()) {
            var next = tokens.next()!!
            if (next == OperatorToken(Operator.PARANT_CLOSE)) {
                return@astTransformation ParameterList(parameters)
            }

            parameters.add(next as VariableDeclaration)

            tokens.mark()

            next = tokens.next()!!
            if (next == OperatorToken(Operator.PARANT_CLOSE)) {
                tokens.commit()
                return@astTransformation ParameterList(parameters)
            }

            if (next == OperatorToken(Operator.COMMA)) {
                tokens.commit()
            }
            else if (next !is VariableDeclaration) {
                tokens.rollback()
                next as Token
                throw InternalCompilerError("Unexpected ${next.toStringWithoutLocation()} in parameter list, expecting ${Operator.PARANT_CLOSE.text} or ${Operator.COMMA.text}")
            }
        }

        throw InternalCompilerError("This line should never have been reached :(")
    }

val FunctionModifier = sequence {
    eitherOf {
        keyword(Keyword.MUTABLE)
        keyword(Keyword.READONLY)
        keyword(Keyword.PURE)
        keyword(Keyword.NOTHROW)
        keyword(Keyword.OPERATOR)
        keyword(Keyword.INTRINSIC)
        sequence {
            keyword(Keyword.EXTERNAL)
            operator(Operator.PARANT_OPEN)
            identifier()
            operator(Operator.PARANT_CLOSE)
        }
    }
}
    .astTransformation { tokens -> when((tokens.next()!! as KeywordToken).keyword) {
        Keyword.MUTABLE   -> compiler.ast.type.FunctionModifier.Modifying
        Keyword.READONLY  -> compiler.ast.type.FunctionModifier.Readonly
        Keyword.PURE      -> compiler.ast.type.FunctionModifier.Pure
        Keyword.NOTHROW   -> compiler.ast.type.FunctionModifier.Nothrow
        Keyword.OPERATOR  -> compiler.ast.type.FunctionModifier.Operator
        Keyword.INTRINSIC -> compiler.ast.type.FunctionModifier.Intrinsic
        Keyword.EXTERNAL  -> {
            tokens.next()
            val nameToken = tokens.next() as IdentifierToken
            tokens.next()
            compiler.ast.type.FunctionModifier.External(nameToken)
        }
        else              -> throw InternalCompilerError("Keyword is not a function modifier")
    } }

val StandaloneFunctionDeclaration = sequence("function declaration") {
    repeating {
        ref(FunctionModifier)
    }

    keyword(Keyword.FUNCTION)

    optionalWhitespace()
    identifier(acceptedKeywords = Keyword.entries)
    optionalWhitespace()
    optional {
        ref(BracedTypeParameters)
    }
    optionalWhitespace()
    ref(ParameterList)
    optionalWhitespace()

    optional {
        operator(Operator.RETURNS)
        optionalWhitespace()
        ref(Type)
    }

    eitherOf {
        sequence {
            optionalWhitespace()
            operator(Operator.CBRACE_OPEN)
            ref(CodeChunk)
            optionalWhitespace()
            operator(Operator.CBRACE_CLOSE)
        }
        sequence {
            operator(Operator.ASSIGNMENT)
            ref(Expression)
            eitherOf {
                operator(Operator.NEWLINE)
                endOfInput()
            }
        }
        operator(Operator.NEWLINE)
        endOfInput()
    }
}
    .astTransformation { tokens ->
        val modifiers = mutableListOf<FunctionModifier>()
        var next: Any? = tokens.next()!!
        while (next is FunctionModifier) {
            modifiers.add(next)
            next = tokens.next()!!
        }

        val declarationKeyword = next as KeywordToken

        val name = tokens.next() as IdentifierToken

        next = tokens.next()!!
        val typeParameters: List<TypeParameter>
        if (next is TypeParameterBundle) {
            typeParameters = next.parameters
            next = tokens.next()!!
        } else {
            typeParameters = emptyList()
        }

        val parameterList = next as ParameterList

        next = tokens.next()

        var type: TypeReference? = null

        if (next == OperatorToken(Operator.RETURNS)) {
            type = tokens.next()!! as TypeReference
            next = tokens.next()
        }

        if (next == OperatorToken(Operator.CBRACE_OPEN)) {
            val code = tokens.next()!! as CodeChunk
            // ignore trailing CBRACE_CLOSE

            return@astTransformation FunctionDeclaration(
                declarationKeyword.sourceLocation,
                modifiers,
                name,
                typeParameters,
                parameterList,
                type ?: TypeReference("Unit", nullability = TypeReference.Nullability.UNSPECIFIED),
                FunctionDeclaration.Body.Full(code),
            )
        }

        if (next == OperatorToken(Operator.ASSIGNMENT)) {
            val singleExpression = tokens.next()!! as Expression

            return@astTransformation FunctionDeclaration(
                declarationKeyword.sourceLocation,
                modifiers,
                name,
                typeParameters,
                parameterList,
                type,
                FunctionDeclaration.Body.SingleExpression(singleExpression),
            )
        }

        if (next == OperatorToken(Operator.NEWLINE) || next == null) {
            // function without body with trailing newline or immediately followed by EOF
            return@astTransformation FunctionDeclaration(
                declarationKeyword.sourceLocation,
                modifiers,
                name,
                typeParameters,
                parameterList,
                type,
                null
            )
        }

        throw InternalCompilerError("Unexpected token when building AST: expected ${OperatorToken(Operator.CBRACE_OPEN)} or ${OperatorToken(Operator.ASSIGNMENT)} but got $next")
    }
