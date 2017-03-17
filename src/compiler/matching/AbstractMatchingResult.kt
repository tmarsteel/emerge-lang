package compiler.matching


/**
 * When a [Matcher] is matched against an input it returns an [AbstractMatchingResult] that describes
 * the outcome of the matching process.
 *
 * If there was no doubt about the input is of the structure the matcher expects the [certainty] must be
 * [ResultCertainty.DEFINITIVE]; if the given input is ambigous (e.g. does not have properties unique to the
 * [Matcher]), the [certainty] should be [ResultCertainty.OPTIMISTIC].
 *
 * Along with the [item] of the match, an [AbstractMatchingResult] can provide the caller with additional reportings
 * about the matched input. If the input did not match the expectations of the [Matcher] that could be details on what
 * expectations were not met.
 *
 * The [item] may only be null if the given input did not contain enough information to construct a meaningful item.
 */
interface AbstractMatchingResult<out ResultType,ReportingType> {
    val certainty: ResultCertainty
    val item: ResultType?
    val reportings: Collection<ReportingType>

    companion object {
        fun <ResultType, ReportingType> ofResult(result: ResultType, certainty: ResultCertainty = ResultCertainty.DEFINITIVE): AbstractMatchingResult<ResultType, ReportingType> {
            return object : AbstractMatchingResult<ResultType, ReportingType> {
                override val certainty = certainty
                override val item = result
                override val reportings = emptySet<ReportingType>()
            }
        }

        fun <ResultType, ReportingType> ofError(error: ReportingType, certainty: ResultCertainty = ResultCertainty.DEFINITIVE): AbstractMatchingResult<ResultType, ReportingType> {
            return object : AbstractMatchingResult<ResultType, ReportingType> {
                override val certainty = certainty
                override val item = null
                override val reportings = setOf(error)
            }
        }

        inline fun <T, reified ResultType, reified ReportingType> of(thing: T, certainty: ResultCertainty = ResultCertainty.DEFINITIVE): AbstractMatchingResult<ResultType, ReportingType> {
            if (thing is ResultType) return ofResult(thing, certainty)
            if (thing is ReportingType) return ofError(thing, certainty)
            throw IllegalArgumentException("Given object is neither of item type nor of error type")
        }
    }
}