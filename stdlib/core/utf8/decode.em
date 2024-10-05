package emerge.core.utf8

import emerge.std.collections.ArrayList
import emerge.platform.collectStackTrace

// decodes the codepoint in [data] at [index].
// @return first: the codepoint, second: the index of the next codepoint, or null if the end of data has been reached
// @throws InvalidUtf8Exception
export fn getNextCodepointAt(borrow data: Array<S8>, index: UWord) -> Pair<U32, UWord?> {
    byte1 = data[index]
    if byte1 >= 0 {
        nextIndex = index + 1
        hasNext = data.size > nextIndex
        return Pair(byte1.asU8().toU32(), if hasNext nextIndex else null)
    }
    
    // 11000000 = -64
    // 11011111 = -33
    // 11100000 = -32
    // 11101111 = -17
    // 11110000 = -16
    // 11110111 = -9
    nBytesInCodepoint: UWord = if byte1 >= -8 {
        throw InvalidUtf8Exception("Invalid bit pattern in first byte of codepoint at index " + index.toString())
    } else if byte1 >= -16 {
        4
    } else if byte1 >= -32 {
        3
    } else if byte1 >= -64 {
        2
    } else {
        throw InvalidUtf8Exception("Invalid bit pattern in first byte of codepoint at index " + index.toString())
    }
    
    if data.size < index + nBytesInCodepoint {
        throw InvalidUtf8Exception("Data ends in the middle of a " + nBytesInCodepoint.toString() + "-byte codepoint; the faulty codepoint starts at index " + index.toString())
    }
    indexOfNextCodepoint = if data.size > index + nBytesInCodepoint {
        index + nBytesInCodepoint
    } else {
        null
    }
    
    if nBytesInCodepoint == 2 {
        fromFirst = (byte1 and 0b00011111).asU8().toU32()
        fromSecond = (data[index + 1] and 0b00111111).asU8().toU32()
        codepoint = (fromFirst.bitShiftLeft(6) or fromSecond)
        return Pair(codepoint, indexOfNextCodepoint)
    }
    
    if nBytesInCodepoint == 3 {
        fromFirst = (byte1 and 0b00001111).asU8().toU32()
        fromSecond = (data[index + 1] and 0b00111111).asU8().toU32()
        fromThird = (data[index + 2] and 0b00111111).asU8().toU32()        
        codepoint = (fromFirst.bitShiftLeft(12) or fromSecond.bitShiftLeft(6) or fromThird)
        return Pair(codepoint, indexOfNextCodepoint)
    }
    
    // TODO: assert nBytesInCodepoint == 4
    fromFirst = (byte1 and 0b00000111).asU8().toU32()
    fromSecond = (data[index + 1] and 0b00111111).asU8().toU32()
    fromThird = (data[index + 2] and 0b00111111).asU8().toU32()
    fromFourth = (data[index + 3] and 0b00111111).asU8().toU32()
    codepoint = (fromFirst.bitShiftLeft(18) or fromSecond.bitShiftLeft(12) or fromThird.bitShiftLeft(6) or fromFourth)
    return Pair(codepoint, indexOfNextCodepoint)
}

export fn rejectInvalidUtf8(borrow bytes: Array<S8>) {
    var index = 0 as UWord
    while true {
        codepointAndNextIndex = getNextCodepointAt(bytes, index)
        set index = codepointAndNextIndex.second ?: break
    }
}

export class InvalidUtf8Exception : Throwable {
    private message: String = init
    constructor {
        mixin ThrowableTrait(self.message) as Throwable
    }
}