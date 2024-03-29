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

package compiler.reportings

import compiler.InternalCompilerError
import compiler.ast.type.TypeMutability
import compiler.binding.type.BoundTypeReference
import compiler.binding.type.isAssignableTo
import compiler.lexer.SourceLocation

/**
 * Reported when a value of tye [sourceType] is to be written to a storage of type [targetType] and the types
 * are not compatible.
 */
open class ValueNotAssignableReporting(
    /** The type of the storage area a value is to be written to; in an assignment its the type of the target variable */
    val targetType: BoundTypeReference,

    /** The type of the value that is to be written; in an assignments its the type of the expression to be written to a variable */
    val sourceType: BoundTypeReference,

    val reason: String,

    assignmentLocation: SourceLocation
) : Reporting(
    Level.ERROR,
    "Cannot assign a value of type $sourceType to a reference of type $targetType: $reason",
    assignmentLocation
) {
    /**
     * When [true], [message] will return a simplified text in case the problem arises only from the
     * [BoundTypeReference.mutability] in [sourceType]. E.g. would turn the bulky
     *
     *     Cannot assign a value of type immutable String to a reference of type mutable String: cannot assign an immutable value to a mutable reference
     *
     * into
     *
     *     Cannot mutate this value. In fact, this is an immutable value.
     */
    var simplifyMessageWhenCausedSolelyByMutability: Boolean = false

    override val message: String get() {
        val mutabilityUnconflictedSourceType = sourceType.withMutability(targetType.mutability)
        if (!(mutabilityUnconflictedSourceType isAssignableTo targetType)) {
            // error due to more than mutability => standard message is fine
            return super.message
        }

        when (sourceType.mutability) {
            TypeMutability.MUTABLE -> when (targetType.mutability) {
                TypeMutability.IMMUTABLE -> return "An immutable value is needed here, this one is immutable."
                TypeMutability.READONLY,
                TypeMutability.MUTABLE -> throw InternalCompilerError("This should not have happened")
            }
            TypeMutability.READONLY -> when (targetType.mutability) {
                TypeMutability.MUTABLE -> return "Cannot mutate this value, this is a readonly reference."
                TypeMutability.READONLY -> throw InternalCompilerError("This should not have happened")
                TypeMutability.IMMUTABLE -> return "An immutable value is needed here. This is a readonly reference, immutability is not guaranteed."
            }
            TypeMutability.IMMUTABLE -> when (targetType.mutability) {
                TypeMutability.MUTABLE -> return "Cannot mutate this value. In fact, this is an immutable value."
                TypeMutability.READONLY,
                TypeMutability.IMMUTABLE -> throw InternalCompilerError("This should not have happened")
            }
        }
    }
}