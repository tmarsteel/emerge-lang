package compiler.reportings

import compiler.binding.BoundOverloadSet

class OverloadSetHasNoDisjointParameterReporting(
    val overloadSet: BoundOverloadSet<*>,
) : Reporting(
    Level.ERROR,
    "This overload-set is ambiguous. The types of least one parameter must be disjoint, this set has none.",
    overloadSet.overloads.first().declaredAt,
) {
    val overloadSetName = overloadSet.overloads.first().canonicalName
    val overloadSetParameterCount = overloadSet.overloads.first().parameters.parameters.size

    override fun toString(): String {
        var str = "${levelAndMessage}\n"
        str += illustrateSourceLocations(overloadSet.overloads.map { it.declaredAt })
        return str
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is OverloadSetHasNoDisjointParameterReporting) return false

        if (overloadSetName != other.overloadSetName) return false
        if (overloadSetParameterCount != other.overloadSetParameterCount) return false

        return true
    }

    override fun hashCode(): Int {
        var result = overloadSetName.hashCode()
        result = 31 * result + overloadSetParameterCount
        return result
    }
}