package emerge.core

import emerge.core.reflection.ReflectionBaseType
import emerge.platform.collectStackTrace
import emerge.std.collections.ArrayList

class CastError : Error {
    constructor {
        mixin ThrowableTrait("The cast failed; the value is not of the expected type.") as Error
    }
}