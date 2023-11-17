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

package compiler.ast.type

import compiler.binding.context.CTContext
import compiler.binding.type.BaseTypeReference
import compiler.lexer.IdentifierToken

open class TypeReference(
    val simpleName: String,
    val isNullable: Boolean,
    open val modifier: TypeModifier? = null,
    val isInferred: Boolean = false,
    val declaringNameToken: IdentifierToken? = null
) {
    constructor(declaringNameToken: IdentifierToken, isNullable: Boolean, modifier: TypeModifier? = null, isInferred: Boolean = false)
        : this(declaringNameToken.value, isNullable, modifier, isInferred, declaringNameToken)

    open fun modifiedWith(modifier: TypeModifier): TypeReference {
        // TODO: implement type modifiers
        return TypeReference(simpleName, isNullable, modifier)
    }

    open fun nonNull(): TypeReference = TypeReference(simpleName, false, modifier, isInferred, declaringNameToken)

    open fun nullable(): TypeReference = TypeReference(simpleName, true, modifier, isInferred, declaringNameToken);

    open fun asInferred(): TypeReference = TypeReference(simpleName, isNullable, modifier, true, declaringNameToken)

    open fun resolveWithin(context: CTContext): BaseTypeReference? {
        val baseType = context.resolveType(this)
        return if (baseType != null) BaseTypeReference(this, context, baseType) else null
    }

    private lateinit var _string: String
    override fun toString(): String {
        if (!this::_string.isInitialized) {
            val buffer = StringBuilder()

            buffer.append("TypeReference[")

            modifier?.let {
                buffer.append(it.name.lowercase())
                buffer.append(' ')
            }

            if (declaringNameToken != null) {
                buffer.append(declaringNameToken.value)
            } else {
                buffer.append(simpleName)
            }

            if (isNullable) {
                buffer.append('?')
            }

            buffer.append(", inferred=")
            buffer.append(isInferred)
            buffer.append(']')
            _string = buffer.toString()
        }

        return this._string
    }
}