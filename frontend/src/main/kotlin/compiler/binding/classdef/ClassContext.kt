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

package compiler.binding.classdef

import compiler.ast.type.TypeParameter
import compiler.binding.context.CTContext
import compiler.binding.context.MutableCTContext
import compiler.binding.type.BoundTypeParameter

/**
 * The context within a class (where class members are defined and where class member default value
 * expressions are located)
 */
class ClassContext(
    fileContext: CTContext,
    typeParameters: List<TypeParameter>,
) : MutableCTContext(fileContext) {

    val typeParameters: List<BoundTypeParameter>

    init {
        this.typeParameters = typeParameters.map(this::addTypeParameter)
    }
}