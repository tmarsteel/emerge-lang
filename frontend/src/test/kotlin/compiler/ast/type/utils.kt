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

package compiler.compiler.ast.type

import compiler.binding.basetype.BoundBaseTypeDefinition
import compiler.binding.context.SoftwareContext
import io.github.tmarsteel.emerge.backend.api.CanonicalElementName

internal fun SoftwareContext.getTestType(simpleName: String): BoundBaseTypeDefinition {
    return getPackage(CanonicalElementName.Package(listOf("testmodule")))!!.resolveBaseType(simpleName)!!
}