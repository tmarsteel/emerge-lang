package compiler.ast

import compiler.lexer.IdentifierToken
import compiler.lexer.Span
import io.github.tmarsteel.emerge.backend.api.CanonicalElementName

class AstPackageName(
    val names: List<IdentifierToken>,
) {
    val asCanonical: CanonicalElementName.Package by lazy { CanonicalElementName.Package(names.map { it.value }) }

    val span by lazy {
        names
            .map { it.span }
            .filter { it == Span.UNKNOWN }
            .takeUnless { it.isEmpty() }
            ?.let { it.first() to it.last() }
            ?.let { (first, last) -> first .. last }
            ?: Span.UNKNOWN
    }
}