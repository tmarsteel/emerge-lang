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

package compiler.parser.grammar.rule

import compiler.parser.TokenSequence

interface Rule<T> {
    val descriptionOfAMatchingThing: String
    fun tryMatch(context: Any, input: TokenSequence): RuleMatchingResult<T>

    /**
     * @return outer iterator: for each possible alternative in this rule (resolves [EitherOfRule] multitude of options):
     * an inner iterator of example tokens that describe the "prototype" of the ideal match to
     * this [Rule].
     * If this rule can produce a valid match without consuming a single token from the input, this method must return
     * `listOf(emptyList().iterator()).iterator()`.
     */
    val minimalMatchingSequence: Sequence<Sequence<ExpectedToken>>
}

/**
 * A placeholder for a matching token, e.g. an identifier, an operator, ...
 */
interface ExpectedToken {
    /**
     * Called on the first [ExpectedToken] in a sequence returned from [Rule.minimalMatchingSequence]
     * which is unique among the other options (see e.g. [EitherOfRule]). By consequence, once this
     * token is matched in any invocation of [Rule.tryMatch], the [RuleMatchingResult.isAmbiguous] returned
     * from then on must be `true`.
     * All of that is scoped to each unique context ([inContext]).
     */
    fun markAsRemovingAmbiguity(inContext: Any)

    /**
     * @return an [ExpectedToken] that directly models an expected token, rather than delegating/wrapping
     * another [ExpectedToken] instance. May return itself if that's already the case.
     */
    fun unwrap(): ExpectedToken = this

    fun matchesSameTokensAs(other: ExpectedToken) = unwrap() == other.unwrap()
}