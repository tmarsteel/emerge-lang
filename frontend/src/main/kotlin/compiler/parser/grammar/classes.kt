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
import compiler.ast.ClassDeclaration
import compiler.ast.ClassMemberDeclaration
import compiler.ast.ClassMemberVariableDeclaration
import compiler.ast.FunctionDeclaration
import compiler.ast.TypeParameterBundle
import compiler.ast.VariableDeclaration
import compiler.ast.type.TypeParameter
import compiler.lexer.IdentifierToken
import compiler.lexer.Keyword.CLASS_DEFINITION
import compiler.lexer.KeywordToken
import compiler.lexer.Operator.CBRACE_CLOSE
import compiler.lexer.Operator.CBRACE_OPEN
import compiler.lexer.Operator.NEWLINE
import compiler.lexer.OperatorToken
import compiler.parser.grammar.dsl.astTransformation
import compiler.parser.grammar.dsl.sequence

val ClassMemberVariableDeclaration = sequence("class member variable declaration") {
    optional {
        ref(VisibilityModifier)
    }

    ref(VariableDeclaration)
    operator(NEWLINE)
}
    .astTransformation { tokens ->
        var next = tokens.next()
        val visibility: ASTVisibilityModifier?
        if (next is ASTVisibilityModifier) {
            visibility = next
            next = tokens.next()
        } else {
            visibility = null
        }

        ClassMemberVariableDeclaration(
            visibility,
            next as VariableDeclaration,
        )
    }

val ClassMemberFunctionDeclaration = sequence("class member function declaration") {
    ref(StandaloneFunctionDeclaration)
}
    .astTransformation { tokens ->
        val decl = tokens.next() as FunctionDeclaration
        decl.copy(
            /* TODO: apply semantic differences for member fns, e.g. allow untyped self parameter */
        )
    }

val ClassDefinition = sequence("class definition") {
    keyword(CLASS_DEFINITION)
    identifier()
    optional {
        ref(BracedTypeParameters)
    }
    optionalWhitespace()
    operator(CBRACE_OPEN)
    optionalWhitespace()
    repeating {
        eitherOf {
            ref(ClassMemberVariableDeclaration)
            ref(ClassMemberFunctionDeclaration)
        }
    }
    optionalWhitespace()
    operator(CBRACE_CLOSE)
    eitherOf {
        operator(NEWLINE)
        endOfInput()
    }
}
    .astTransformation { tokens ->
        val declarationKeyword = tokens.next()!! as KeywordToken // class keyword

        val name = tokens.next()!! as IdentifierToken

        var next: Any? = tokens.next()!!
        val typeParameters: List<TypeParameter>
        if (next is TypeParameterBundle) {
            typeParameters = next.parameters
            tokens.next()!! // skip CBRACE_OPEN
        } else {
            check(next is OperatorToken)
            typeParameters = emptyList()
        }

        val memberDeclarations = ArrayList<ClassMemberDeclaration>()

        next = tokens.next()!! // until CBRACE_CLOSE
        while (next is ClassMemberDeclaration) {
            memberDeclarations += next
            next = tokens.next()
        }

        ClassDeclaration(
            declarationKeyword.sourceLocation,
            name,
            memberDeclarations,
            typeParameters,
        )
    }