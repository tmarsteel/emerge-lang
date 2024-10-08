package compiler.reportings

import compiler.lexer.Span
import io.github.tmarsteel.emerge.common.CanonicalElementName

data class UnresolvablePackageNameReporting(
    val name: CanonicalElementName.Package,
    val location: Span
) : Reporting(
    Level.ERROR,
    "Package $name could not be found",
    location,
) {
    override fun toString() = super.toString()
}