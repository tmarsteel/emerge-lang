package emerge.core

import emerge.platform.panic
import emerge.platform.collectStackTrace
import emerge.std.io.PrintStream
import emerge.std.collections.ArrayList
import emerge.core.StackTraceElement

export class Unit {
    private constructor {}

    export nothrow intrinsic fn instance() -> Unit
}

export interface Any {}

export class Nothing {
    private constructor {}
}

export class F32 {
    private constructor {}
}

export class F64 {
    private constructor {}
}

export class S8 : Printable {
    private constructor {}

    export intrinsic nothrow operator fn unaryMinus(self) -> S8
    export intrinsic nothrow operator fn negate(self) -> S8

    export intrinsic operator fn plus(self, summand: S8) -> S8
    export intrinsic operator fn minus(self, operand: S8) -> S8
    export intrinsic operator fn times(self, factor: S8) -> S8
    export intrinsic operator fn divideBy(self, divisor: S8) -> S8
    export intrinsic nothrow operator fn rem(self, divisor: S8) -> S8
    
    export intrinsic nothrow operator fn bitShiftLeft(self, amount: U8) -> S8
    export intrinsic nothrow operator fn logicalBitShiftRight(self, amount: U8) -> S8
    export intrinsic nothrow operator fn arithmeticBitShiftRight(self, amount: U8) -> S8
    export intrinsic nothrow operator fn `and`(self, other: S8) -> S8
    export intrinsic nothrow operator fn `or`(self, other: S8) -> S8
    export intrinsic nothrow operator fn `xor`(self, other: S8) -> S8

    export intrinsic nothrow operator fn compareTo(self, other: S8) -> S8
    export intrinsic nothrow operator fn equals(self, other: S8) -> Bool

    export nothrow fn abs(self) = if self < 0 -self else self

    export intrinsic nothrow fn asU8(self) -> U8

    export intrinsic nothrow fn toS16(self) -> S16
    export intrinsic nothrow fn toS32(self) -> S32
    export intrinsic nothrow fn toS64(self) -> S64

    export fn toString(self) = self.toS64().toString()
    export override fn printTo(self, borrow stream: mut PrintStream) {
        self.toS64().printTo(stream)
    }
}

export class U8 : Printable {
    private constructor {}

    export intrinsic nothrow operator fn negate(self) -> U8

    export intrinsic operator fn plus(self, summand: U8) -> U8
    export intrinsic operator fn minus(self, operand: U8) -> U8
    export intrinsic operator fn times(self, factor: U8) -> U8
    export intrinsic operator fn divideBy(self, divisor: U8) -> U8
    export intrinsic nothrow operator fn rem(self, divisor: U8) -> U8
    
    export intrinsic nothrow operator fn bitShiftLeft(self, amount: U8) -> U8
    export intrinsic nothrow operator fn logicalBitShiftRight(self, amount: U8) -> U8
    export intrinsic nothrow operator fn `and`(self, other: U8) -> U8
    export intrinsic nothrow operator fn `or`(self, other: U8) -> U8
    export intrinsic nothrow operator fn `xor`(self, other: U8) -> U8

    export intrinsic nothrow operator fn compareTo(self, other: U8) -> S8
    export intrinsic nothrow operator fn equals(self, other: U8) -> Bool

    export intrinsic nothrow fn asS8(self) -> S8

    export intrinsic nothrow fn toU16(self) -> U16
    export intrinsic nothrow fn toU32(self) -> U32
    export intrinsic nothrow fn toU64(self) -> U64

    export fn toString(self) = self.toU64().toString()
    export override fn printTo(self, borrow stream: mut PrintStream) {
        self.toU64().printTo(stream)
    }
}

export class S16 : Printable {
    private constructor {}

    export intrinsic nothrow operator fn unaryMinus(self) -> S16
    export intrinsic nothrow operator fn negate(self) -> S16

    export intrinsic operator fn plus(self, summand: S16) -> S16
    export intrinsic operator fn minus(self, operand: S16) -> S16
    export intrinsic operator fn times(self, factor: S16) -> S16
    export intrinsic operator fn divideBy(self, divisor: S16) -> S16
    export intrinsic nothrow operator fn rem(self, divisor: S16) -> S16
    
    export intrinsic nothrow operator fn bitShiftLeft(self, amount: U8) -> S16
    export intrinsic nothrow operator fn logicalBitShiftRight(self, amount: U8) -> S16
    export intrinsic nothrow operator fn arithmeticBitShiftRight(self, amount: U8) -> S16
    export intrinsic nothrow operator fn `and`(self, other: S16) -> S16
    export intrinsic nothrow operator fn `or`(self, other: S16) -> S16
    export intrinsic nothrow operator fn `xor`(self, other: S16) -> S16

    export intrinsic nothrow operator fn compareTo(self, other: S16) -> S16
    export intrinsic nothrow operator fn equals(self, other: S16) -> Bool

    export nothrow fn abs(self) = if self < 0 -self else self

    export intrinsic nothrow fn asU16(self) -> U16

    export intrinsic nothrow fn toS32(self) -> S32
    export intrinsic nothrow fn toS64(self) -> S64

    export fn toString(self) = self.toS64().toString()
    export override fn printTo(self, borrow stream: mut PrintStream) {
        self.toS64().printTo(stream)
    }
}

export class U16 : Printable {
    private constructor {}

    export intrinsic nothrow operator fn negate(self) -> U16

    export intrinsic operator fn plus(self, summand: U16) -> U16
    export intrinsic operator fn minus(self, operand: U16) -> U16
    export intrinsic operator fn times(self, factor: U16) -> U16
    export intrinsic operator fn divideBy(self, divisor: U16) -> U16
    export intrinsic nothrow operator fn rem(self, divisor: U16) -> U16
    
    export intrinsic nothrow operator fn bitShiftLeft(self, amount: U8) -> S16
    export intrinsic nothrow operator fn logicalBitShiftRight(self, amount: U8) -> S16
    export intrinsic nothrow operator fn `and`(self, other: U16) -> U16
    export intrinsic nothrow operator fn `or`(self, other: U16) -> U16
    export intrinsic nothrow operator fn `xor`(self, other: U16) -> U16

    export intrinsic nothrow operator fn compareTo(self, other: U16) -> S16
    export intrinsic nothrow operator fn equals(self, other: U16) -> Bool

    export intrinsic nothrow fn asS16(self) -> S16

    export intrinsic nothrow fn toU32(self) -> U32
    export intrinsic nothrow fn toU64(self) -> U64

    export fn toString(self) = self.toU64().toString()
    export override fn printTo(self, borrow stream: mut PrintStream) {
        self.toU64().printTo(stream)
    }
}

export class S32 : Printable {
    private constructor {}

    export intrinsic nothrow operator fn unaryMinus(self) -> S32
    export intrinsic nothrow operator fn negate(self) -> S32

    export intrinsic operator fn plus(self, summand: S32) -> S32
    export intrinsic operator fn minus(self, operand: S32) -> S32
    export intrinsic operator fn times(self, factor: S32) -> S32
    export intrinsic operator fn divideBy(self, divisor: S32) -> S32
    export intrinsic nothrow operator fn rem(self, divisor: S32) -> S32
    
    export intrinsic nothrow operator fn bitShiftLeft(self, amount: U8) -> S32
    export intrinsic nothrow operator fn logicalBitShiftRight(self, amount: U8) -> S32
    export intrinsic nothrow operator fn arithmeticBitShiftRight(self, amount: U8) -> S32
    export intrinsic nothrow operator fn `and`(self, other: S32) -> S32
    export intrinsic nothrow operator fn `or`(self, other: S32) -> S32
    export intrinsic nothrow operator fn `xor`(self, other: S32) -> S32

    export intrinsic nothrow operator fn compareTo(self, other: S32) -> S32
    export intrinsic nothrow operator fn equals(self, other: S32) -> Bool

    export nothrow fn abs(self) = if self < 0 -self else self

    export intrinsic nothrow fn asU32(self) -> U32

    export intrinsic nothrow fn toS64(self) -> S64

    export fn toString(self) = self.toS64().toString()
    export override fn printTo(self, borrow stream: mut PrintStream) {
        self.toS64().printTo(stream)
    }
}

export class U32 : Printable {
    private constructor {}

    export intrinsic nothrow operator fn negate(self) -> U32

    export intrinsic operator fn plus(self, summand: U32) -> U32
    export intrinsic operator fn minus(self, operand: U32) -> U32
    export intrinsic operator fn times(self, factor: U32) -> U32
    export intrinsic operator fn divideBy(self, divisor: U32) -> U32
    export intrinsic nothrow operator fn rem(self, divisor: U32) -> U32
    
    export intrinsic nothrow operator fn bitShiftLeft(self, amount: U8) -> U32
    export intrinsic nothrow operator fn logicalBitShiftRight(self, amount: U8) -> U32
    export intrinsic nothrow operator fn `and`(self, other: U32) -> U32
    export intrinsic nothrow operator fn `or`(self, other: U32) -> U32
    export intrinsic nothrow operator fn `xor`(self, other: U32) -> U32

    export intrinsic nothrow operator fn compareTo(self, other: U32) -> S32
    export intrinsic nothrow operator fn equals(self, other: U32) -> Bool

    export intrinsic nothrow fn asS32(self) -> S32

    export intrinsic nothrow fn toU64(self) -> U64

    export fn toString(self) = self.toU64().toString()
    export override fn printTo(self, borrow stream: mut PrintStream) {
        self.toU64().printTo(stream)
    }
}

export class S64 : Printable {
    private constructor {}

    export intrinsic nothrow operator fn unaryMinus(self) -> S64
    export intrinsic nothrow operator fn negate(self) -> S64

    export intrinsic operator fn plus(self, summand: S64) -> S64
    export intrinsic operator fn minus(self, operand: S64) -> S64
    export intrinsic operator fn times(self, factor: S64) -> S64
    export intrinsic operator fn divideBy(self, divisor: S64) -> S64
    export intrinsic nothrow operator fn rem(self, divisor: S64) -> S64
    
    export intrinsic nothrow operator fn bitShiftLeft(self, amount: U8) -> S64
    export intrinsic nothrow operator fn logicalBitShiftRight(self, amount: U8) -> S64
    export intrinsic nothrow operator fn arithmeticBitShiftRight(self, amount: U8) -> S64
    export intrinsic nothrow operator fn `and`(self, other: S64) -> S64
    export intrinsic nothrow operator fn `or`(self, other: S64) -> S64
    export intrinsic nothrow operator fn `xor`(self, other: S64) -> S64

    export intrinsic nothrow operator fn compareTo(self, other: S64) -> S64
    export intrinsic nothrow operator fn equals(self, other: S64) -> Bool

    export nothrow fn abs(self) = if self < 0 -self else self

    export intrinsic nothrow fn asU64(self) -> U64

    // converts this number to an SWord, loosing information if SWord is smaller than S64 on the target platform
    export intrinsic nothrow fn asSWord(self) -> SWord

    export fn toString(self) = if self >= 0 self.asU64().toString() else self.abs().asU64().toString(true)
    export override fn printTo(self, borrow stream: mut PrintStream) {
        if self < 0 {
            self.abs().asU64().printTo(stream, true)
        } else {
            self.asU64().printTo(stream, false)
        }
    }
}

export class U64 : Printable {
    private constructor {}

    export intrinsic nothrow operator fn negate(self) -> U64

    export intrinsic operator fn plus(self, summand: U64) -> U64
    export intrinsic operator fn minus(self, operand: U64) -> U64
    export intrinsic operator fn times(self, factor: U64) -> U64
    export intrinsic operator fn divideBy(self, divisor: U64) -> U64
    export intrinsic nothrow operator fn rem(self, divisor: U64) -> U64
    
    export intrinsic nothrow operator fn bitShiftLeft(self, amount: U8) -> U64
    export intrinsic nothrow operator fn logicalBitShiftRight(self, amount: U8) -> U64
    export intrinsic nothrow operator fn `and`(self, other: U64) -> U64
    export intrinsic nothrow operator fn `or`(self, other: U64) -> U64
    export intrinsic nothrow operator fn `xor`(self, other: U64) -> U64

    export intrinsic nothrow operator fn compareTo(self, other: U64) -> S64
    export intrinsic nothrow operator fn equals(self, other: U64) -> Bool

    export intrinsic nothrow fn asS64(self) -> S64

    // converts this number to a UWord, loosing information if UWord is smaller than U64 on the target platform
    export intrinsic nothrow fn asUWord(self) -> UWord

    export fn toString(self) = self.toString(false)

    export override fn printTo(self, borrow stream: mut PrintStream) {
        self.printTo(stream, false)
    }

    private fn printTo(self, borrow stream: mut PrintStream, addMinusSign: Bool) {
        stream.put(self.toString(addMinusSign))
    }
    
    private fn toString(self: U64, addMinusSign: Bool) -> const String {
        if self == 0 {
            return "0"
        }

        // 2^64-1 plus sign is at MOST 21 chars
        var buf = Array.new::<S8>(21, SPACE_CODEPOINT)
        var insertionIndex: UWord = buf.size - 1
        var remainingNumber = self + 0
        while remainingNumber != 0 {
            digitAsNumber = remainingNumber.rem(10)
            digitCodepoint = DIGIT_CODEPOINTS_FOR_TO_STRING[digitAsNumber.asUWord()]
            set buf[insertionIndex] = digitCodepoint

            set remainingNumber = remainingNumber / (10 as U64)
            set insertionIndex = insertionIndex - 1
        }

        if addMinusSign {
            set buf[insertionIndex] = MINUS_SIGN_CODEPOINT
            set insertionIndex = insertionIndex - 1
        }

        // trim utf8 data
        var finalBuf: exclusive _ = Array.new::<S8>(buf.size - insertionIndex - 1, SPACE_CODEPOINT)
        Array.copy(buf, insertionIndex + 1, finalBuf, 0, finalBuf.size)
        return String(finalBuf)
    }
}

export class SWord : Printable {
    private constructor {}

    export intrinsic nothrow operator fn unaryMinus(self) -> SWord
    export intrinsic nothrow operator fn negate(self) -> SWord

    export intrinsic operator fn plus(self, summand: SWord) -> SWord
    export intrinsic operator fn minus(self, operand: SWord) -> SWord
    export intrinsic operator fn times(self, factor: SWord) -> SWord
    export intrinsic operator fn divideBy(self, divisor: SWord) -> SWord
    export intrinsic nothrow operator fn rem(self, divisor: SWord) -> SWord
    
    export intrinsic nothrow operator fn bitShiftLeft(self, amount: U8) -> SWord
    export intrinsic nothrow operator fn logicalBitShiftRight(self, amount: U8) -> SWord
    export intrinsic nothrow operator fn arithmeticBitShiftRight(self, amount: U8) -> SWord
    export intrinsic nothrow operator fn `and`(self, other: SWord) -> SWord
    export intrinsic nothrow operator fn `or`(self, other: SWord) -> SWord
    export intrinsic nothrow operator fn `xor`(self, other: SWord) -> SWord

    export intrinsic nothrow operator fn compareTo(self, other: SWord) -> SWord
    export intrinsic nothrow operator fn equals(self, other: SWord) -> Bool

    export intrinsic nothrow fn asUWord(self) -> UWord

    export nothrow fn abs(self) = if self < 0 -self else self

    export intrinsic nothrow fn asS64(self) -> S64
    export intrinsic nothrow fn asU64(self) -> U64

    export fn toString(self) = self.asS64().toString()
    export override fn printTo(self, borrow stream: mut PrintStream) {
        self.asS64().printTo(stream)
    }
}

export class UWord : Printable {
    private constructor {}

    export intrinsic nothrow operator fn negate(self) -> UWord

    export intrinsic operator fn plus(self, summand: UWord) -> UWord
    export intrinsic operator fn minus(self, operand: UWord) -> UWord
    export intrinsic operator fn times(self, factor: UWord) -> UWord
    export intrinsic operator fn divideBy(self, dividend: UWord) -> UWord
    export intrinsic nothrow operator fn rem(self, divisor: UWord) -> UWord
    
    export intrinsic nothrow operator fn bitShiftLeft(self, amount: U8) -> UWord
    export intrinsic nothrow operator fn logicalBitShiftRight(self, amount: U8) -> UWord
    export intrinsic nothrow operator fn `and`(self, other: UWord) -> UWord
    export intrinsic nothrow operator fn `or`(self, other: UWord) -> UWord
    export intrinsic nothrow operator fn `xor`(self, other: UWord) -> UWord

    export intrinsic nothrow operator fn compareTo(self, other: UWord) -> SWord
    export intrinsic nothrow operator fn equals(self, other: UWord) -> Bool

    export intrinsic nothrow fn asSWord(self) -> SWord

    export intrinsic nothrow fn asS64(self) -> S64
    export intrinsic nothrow fn asU64(self) -> U64

    export fn toString(self) = self.asU64().toString()
    export override fn printTo(self, borrow stream: mut PrintStream) {
        self.asU64().printTo(stream)
    }
}

export class Bool {
    private constructor {}

    export intrinsic nothrow operator fn negate(self) -> Bool

    export intrinsic nothrow operator fn `and`(self, other: Bool) -> Bool
    export intrinsic nothrow operator fn `or`(self, other: Bool) -> Bool
    export intrinsic nothrow operator fn `xor`(self, other: Bool) -> Bool
}

export class Array<Element> {
    export size: UWord = init

    private constructor {}

    export operator intrinsic fn `get`(self: read _<out Element>, index: UWord) -> Element
    export operator intrinsic fn `set`(self: mut _<in Element>, index: UWord, value: Element) -> Unit
    
    export nothrow intrinsic fn getOrPanic(self: read _<out Element>, index: UWord) -> Element
    export nothrow intrinsic fn setOrPanic(self: mut _<in Element>, index: UWord, value: Element) -> Unit

    export intrinsic fn new<T>(size: UWord, initialValue: T) -> exclusive Array<T>

    export fn copy<T>(borrow source: read Array<out T>, sourceOffset: UWord, borrow dest: mut Array<in T>, destOffset: UWord, length: UWord) {
        if sourceOffset + length > source.size {
            panic("length overflows source")
        }

        if destOffset + length > dest.size {
            panic("length overflows dest")
        }

        var sourceIndex = sourceOffset
        var destIndex = destOffset
        var nCopied: UWord = 0
        while nCopied < length {
            element: T = source[sourceIndex]
            set dest[destIndex] = element
            set sourceIndex = sourceIndex + 1
            set destIndex = destIndex + 1
            set nCopied = nCopied + 1
        }
    }
    
    // TODO: equals. Due to lack of a Any::equals (and its good that this isn't present!!)
    // this needs the equals fn as a parameter. The signature would be
    // fn Array<T>.equals(self, borrow other: Array<T>, equals: (T, T) -> Bool) -> Bool
    // and could be invoked like so:
    // str1.utf8Bytes.equals(str2.utf8Bytes, U8::equals)
}

export class ArrayIndexOutOfBoundsError : Error {
    export invalidIndex: UWord = init
    
    export constructor {
        mixin ThrowableTrait(null) as Error
    }
}

private SPACE_CODEPOINT: S8 = 32
private MINUS_SIGN_CODEPOINT: S8 = 45
private DIGIT_CODEPOINTS_FOR_TO_STRING: Array<S8> = [48, 49, 50, 51, 52, 53, 54, 55, 56, 57, 97, 98, 99, 100, 101, 102]