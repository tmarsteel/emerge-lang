package emerge.core

import emerge.std.collections.ArrayList
import emerge.core.StackTraceElement
import emerge.platform.collectStackTrace

export class ArithmeticError : Error {
    private message: String = init
    
    constructor {
        mixin ThrowableTrait(self.message) as Error
    }
}