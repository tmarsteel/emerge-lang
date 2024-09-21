package compiler.reportings

import compiler.ast.AstMixinStatement

class MixinNotAllowedReporting(
    val mixinStatement: AstMixinStatement,
) : Reporting(
    Level.ERROR,
    "Mixins are only allowed in class constructors",
    mixinStatement.span,
)