package compiler.reportings

/**
 * Collects [Reporting]s. This is an ongoing refactoring: lots of code returns a `Collection<Reporting>` which
 * overall leads to lots and lots of copying and allocations. By inversing this and having [Reporting]s be
 * passed to a [Diagnosis], this can be made much more efficient.
 *
 * TODO: apply across the whole codebase
 */
interface Diagnosis {
    fun add(diagnostic: Reporting)

    companion object {
        /**
         * for the transition period, bridges `Collection<Reporting>` style to `Diagnosis`
         */
        fun addingTo(target: MutableCollection<in Reporting>): Diagnosis = object : Diagnosis {
            override fun add(diagnostic: Reporting) {
                target.add(diagnostic)
            }
        }
    }
}