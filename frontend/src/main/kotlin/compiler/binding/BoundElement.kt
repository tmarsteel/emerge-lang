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

package compiler.binding

import compiler.binding.context.CTContext

/**
 * Anything that resulted from AST and that can have compile-time checks.
 */
interface BoundElement<out AstNode> : SemanticallyAnalyzable {
    /**
     * The context this element has been bound to.
     */
    val context: CTContext

    /**
     * The node that was bound to [context].
     */
    val declaration: AstNode
}