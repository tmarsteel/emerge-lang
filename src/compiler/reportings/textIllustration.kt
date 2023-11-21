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

import compiler.lexer.SourceContentAwareSourceDescriptor
import compiler.lexer.SourceLocation
import kotlin.math.min

/**
 * @param highlights must all be from the receiver
 * @param nLinesContext Around each line obtained from [highlights], this number of lines will additionally be shown
 * above and below. So e.g. `nLinesOfContext == 1` and line 5 has a highlight then lines 4 and 6 will also be shown.
 */
fun SourceContentAwareSourceDescriptor.getIllustrationForHighlightedLines(
    highlights: Collection<SourceLocation>,
    nLinesContext: Int = 1,
): String {
    if (highlights.isEmpty()) {
        throw IllegalArgumentException("No locations given to highlight")
    }

    highlights.find { it.sourceLine < 1 || it.sourceLine > sourceLines.size }?.let {
        throw IllegalArgumentException("Source lines out of range: $it")
    }
    highlights.find { it.sD is SourceContentAwareSourceDescriptor && it.sD !== this }?.let {
        throw IllegalArgumentException("All locations-to-highlight must be from the same ${SourceContentAwareSourceDescriptor::class.simpleName}, this one isn't: $it")
    }

    val highlightedColumnsByLine: Map<Int, List<Int>> = highlights
        .groupBy { it.sourceLine }
        .mapValues { (_, highlights) -> highlights.map { it.sourceColumn }.toSet().sorted() }

    val lineNumbersToOutput = mutableSetOf<Int>()
    for (desiredLine in highlightedColumnsByLine.keys) {
        lineNumbersToOutput.add(desiredLine)
        for (i in 1 .. nLinesContext) {
            lineNumbersToOutput.add(desiredLine - i)
            lineNumbersToOutput.add(desiredLine + i)
        }
    }

    val lineNumbersToOutputSorted = lineNumbersToOutput.sorted()
    val lineCounterLength = min(3, lineNumbersToOutputSorted.max().toString(10).length)

    val linesToOutputWithNormalizedTabs: Map<Int, String> = lineNumbersToOutputSorted.associateWith {
        sourceLines[it - 1].replace("\t", "    ")
    }

    val commonNumberOfLeadingSpaces =
        linesToOutputWithNormalizedTabs.values.minOf { it.takeWhile { char -> char == ' ' }.length }

    fun StringBuilder.appendUnnumberedLinePrefix() {
        repeat(lineCounterLength + 1) {
            append(' ')
        }
        append("|  ")
    }

    val out = StringBuilder()
    out.appendUnnumberedLinePrefix()
    out.append('\n')
    val lineSkippingIndicatorLine = "...".padStart(lineCounterLength, ' ') + "\n"

    for (index in 0 .. lineNumbersToOutputSorted.lastIndex) {
        val lineNumber = lineNumbersToOutputSorted[index]
        out.append(lineNumber.toString(10).padStart(lineCounterLength, ' '))
        out.append(" |  ")
        out.append(linesToOutputWithNormalizedTabs[lineNumber]!!.substring(commonNumberOfLeadingSpaces))
        out.append("\n")
        highlightedColumnsByLine[lineNumber]?.let { colsToHighlight ->
            out.appendUnnumberedLinePrefix()
            colsToHighlight.fold(commonNumberOfLeadingSpaces) { previousCol: Int, col: Int ->
                repeat(col - previousCol - 1) {
                    out.append(' ')
                }
                out.append('^')
                col
            }
            out.append('\n')
        }

        if (index != lineNumbersToOutputSorted.lastIndex) {
            val nextLineNumber = lineNumbersToOutputSorted[index + 1]
            if (lineNumber + 1 != nextLineNumber) {
                // we are skipping some lines
                out.append(lineSkippingIndicatorLine)
            }
        }
    }

    out.appendUnnumberedLinePrefix()
    out.append('\n')

    return out.toString()
}
