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

package kotlinext

operator fun <T> Array<T>.get(range: IntRange): Iterable<T> = asList()[range]

operator fun <T> Collection<T>.get(range: IntRange): List<T> {
    if (range.first < 0 || range.last < 0) throw IllegalArgumentException("Range first and last must be >= 0")

    if (range.first > size - 1 || range.last > size - 1) {
        throw ArrayIndexOutOfBoundsException()
    }

    var list = ArrayList<T>(range.last - range.first + 1)
    val iterator = iterator()

    for (i in 0 .. range.first - 1) iterator.next()
    for (i in range) {
        list.add(iterator.next())
    }
    return list
}

val <T> Iterable<T>.allEqual: Boolean
    get() {
        val iterator = iterator()
        if (!iterator.hasNext()) return true
        val pivot = iterator.next()
        while (iterator.hasNext()) {
            val item = iterator.next()
            if (pivot != item) return false
        }

        return true
    }