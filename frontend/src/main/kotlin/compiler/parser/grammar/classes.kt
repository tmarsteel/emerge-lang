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

import compiler.ast.ASTVisibilityModifier
import compiler.ast.Expression
import compiler.ast.TypeParameterBundle
import compiler.ast.classdef.ClassDeclaration
import compiler.ast.classdef.ClassMemberDeclaration
import compiler.ast.type.TypeParameter
import compiler.ast.type.TypeReference
import compiler.lexer.IdentifierToken
import compiler.lexer.Keyword.CLASS_DEFINITION
import compiler.lexer.KeywordToken
import compiler.lexer.Operator
import compiler.lexer.Operator.ASSIGNMENT
import compiler.lexer.Operator.CBRACE_CLOSE
import compiler.lexer.Operator.CBRACE_OPEN
import compiler.lexer.Operator.COLON
import compiler.lexer.Operator.NEWLINE
import compiler.lexer.OperatorToken
import compiler.parser.grammar.dsl.astTransformation
import compiler.parser.grammar.dsl.sequence

val ClassMemberVariableDefinition = sequence("class member variable declaration") {
    optional {
        ref(VisibilityModifier)
    }

    optionalWhitespace()

    identifier()

    operator(COLON)
    ref(Type)

    optional {
        optionalWhitespace()
        operator(ASSIGNMENT)
        ref(Expression)
    }
}
    .astTransformation { tokens ->
        var next = tokens.next()!!

        val visibilityModifier = if (next is ASTVisibilityModifier) {
            val _t = next
            next = tokens.next()!!
            _t
        } else ASTVisibilityModifier.DEFAULT

        val name = next as IdentifierToken

        tokens.next()!! as OperatorToken // skip OPERATOR_COLON

        val type = tokens.next()!! as TypeReference

        val defaultValue = if (tokens.hasNext()) {
            // default value is present
            tokens.next()!! as OperatorToken // EQUALS
            tokens.next()!! as Expression?
        } else null

        ClassMemberDeclaration(
            name.sourceLocation,
            visibilityModifier,
            name,
            type,
            defaultValue
        )
    }

val ClassDefinition = sequence("class definition") {
    keyword(CLASS_DEFINITION)

    optionalWhitespace()

    identifier()

    optionalWhitespace()

    optional {
        ref(BracedTypeParameters)
    }

    operator(CBRACE_OPEN)

    optionalWhitespace()

    optional {
        ref(ClassMemberVariableDefinition)
    }
    repeating {
        operator(NEWLINE)
        ref(ClassMemberVariableDefinition)
    }

    optionalWhitespace()
    operator(CBRACE_CLOSE)
}
    .astTransformation { tokens ->
        val declarationKeyword = tokens.next()!! as KeywordToken // class keyword

        val name = tokens.next()!! as IdentifierToken

        var next = tokens.next()!!
        val typeParameters: List<TypeParameter>
        if (next is TypeParameterBundle) {
            typeParameters = next.parameters
            tokens.next()!! // skip CBRACE_OPEN
        } else {
            check(next is OperatorToken)
            typeParameters = emptyList()
        }

        val memberDeclarations = mutableSetOf<ClassMemberDeclaration>()

        next = tokens.next()!! // until CBRACE_CLOSE
        while (next is ClassMemberDeclaration) {
            memberDeclarations += next
            next = tokens.next()!! as OperatorToken

            if (next.operator == Operator.NEWLINE) {
                next = tokens.next()!!
            }
        }

        ClassDeclaration(
            declarationKeyword.sourceLocation,
            name,
            memberDeclarations,
            typeParameters,
        )
    }