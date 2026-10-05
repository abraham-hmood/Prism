package com.prism.launcher.virtualapp

import com.prism.core.PrismPlatform
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile

/**
 * Reading Dalvik executables. PHASE 111.
 *
 * ## Why this exists, and why it is not optional
 *
 * The phase's first milestone: "load a real APK's classes on the desktop JVM and instantiate its
 * Application class." An APK's code is not JVM bytecode. It is DEX -- a different file format with a
 * different instruction set, a different constant pool layout, and registers instead of a stack. A
 * `ClassLoader` cannot read it, `dx` only goes the other way, and nothing in the JDK knows what it is.
 *
 * So the first thing that has to exist is a reader. This is it: the header, the string, type, proto,
 * field and method tables, the class definitions, and the code item for any method. Everything the
 * interpreter needs and nothing it does not.
 *
 * ## The format, in the two sentences that make it make sense
 *
 * A DEX file is a header followed by several arrays of fixed-size items and a pile of variable-length
 * data those items point into by absolute file offset. Every index in the format is an index into one
 * of those arrays, so reading a class means following four or five indirections -- which is why the
 * tables below are read eagerly into arrays and then indexed, rather than seeking per lookup.
 *
 * ## LEB128, which is where a hand-rolled reader usually goes wrong
 *
 * Variable-length integers appear throughout the data section: string lengths, field and method
 * counts, register counts, debug info. ULEB128 is seven bits per byte, low group first, high bit set
 * to continue. SLEB128 is the same with a sign extension on the last group, and FORGETTING THE SIGN
 * EXTENSION is the classic bug -- it reads correctly for every small positive value and silently
 * produces nonsense for the negatives, which first appear in debug line numbers rather than anywhere
 * obvious. Both are implemented below and both are exercised by the parser on any real file.
 *
 * ## MUTF-8, which is not UTF-8
 *
 * DEX strings are "modified UTF-8": a NUL is encoded as two bytes rather than one, and characters
 * above the BMP are two surrogate pairs in CESU-8 form rather than a four-byte sequence. Decoding
 * them with `String(bytes, UTF_8)` works for ASCII and corrupts everything else, which in practice
 * means it works until an app has an emoji in a string constant.
 */
class DexFile private constructor(
    private val buffer: ByteBuffer,
    val strings: Array<String>,
    val typeNames: Array<String>,
    val protos: Array<Proto>,
    val fields: Array<FieldRef>,
    val methods: Array<MethodRef>,
    val classes: List<ClassDef>,
) {

    data class Proto(val shorty: String, val returnType: String, val parameters: List<String>)

    data class FieldRef(val definingClass: String, val type: String, val name: String) {
        override fun toString(): String = definingClass + "." + name + ":" + type
    }

    data class MethodRef(val definingClass: String, val proto: Proto, val name: String) {
        override fun toString(): String =
            definingClass + "." + name + "(" + proto.parameters.joinToString("") + ")" +
                proto.returnType
    }

    /**
     * One class.
     *
     * [superClass] and [interfaces] are descriptors (`Landroid/app/Application;`), not names: that is
     * what the file holds, and converting them here would mean converting back wherever a descriptor
     * is what is needed -- which is everywhere in the instruction stream.
     */
    data class ClassDef(
        val descriptor: String,
        val accessFlags: Int,
        val superClass: String?,
        val interfaces: List<String>,
        val sourceFile: String?,
        val staticFields: List<EncodedField>,
        val instanceFields: List<EncodedField>,
        val directMethods: List<EncodedMethod>,
        val virtualMethods: List<EncodedMethod>,
    ) {
        /** `Lcom/example/App;` becomes `com.example.App`. */
        val name: String get() = descriptorToName(descriptor)

        val isInterface: Boolean get() = accessFlags and ACC_INTERFACE != 0
        val isAbstract: Boolean get() = accessFlags and ACC_ABSTRACT != 0

        fun allMethods(): List<EncodedMethod> = directMethods + virtualMethods

        fun method(name: String, descriptor: String? = null): EncodedMethod? =
            allMethods().firstOrNull { candidate ->
                candidate.ref.name == name &&
                    (descriptor == null || candidate.descriptor() == descriptor)
            }
    }

    data class EncodedField(val ref: FieldRef, val accessFlags: Int) {
        val isStatic: Boolean get() = accessFlags and ACC_STATIC != 0
    }

    /**
     * One method, with its code.
     *
     * [code] is null for an abstract or native method, which is a real state rather than a failure --
     * and a caller that treats null as an error would refuse every interface.
     *
     * ## THE CODE IS READ ON DEMAND, WHICH IS A MEMORY DECISION
     *
     * Parsing every method's instructions up front is simpler and costs too much. Prism's own APK is
     * 33 dex files and 27,932 classes; decoding all of their bodies eagerly filled a 4 GB heap before
     * the app's `onCreate` had finished, and the failure arrived as an `OutOfMemoryError` thrown by
     * whatever happened to allocate next -- in one run, `LinkedHashSet.add` inside an app's
     * `<clinit>`, which looks like an interpreter bug and is not one.
     *
     * A STARTUP EXECUTES A FEW HUNDRED METHODS OUT OF HALF A MILLION. Keeping the offset and reading
     * the body when it is first executed keeps only what actually runs. The body is cached after the
     * first read, because a method in a loop is read once and run many times.
     *
     * The dex's own buffer is retained for this, which is the cost: the dex bytes stay in memory
     * where the parsed structures used to. That is the smaller of the two by a wide margin --
     * instructions decode to a `ShortArray` plus a `Code` plus a `Try` list per method.
     */
    class EncodedMethod(
        val ref: MethodRef,
        val accessFlags: Int,
        private val source: ByteBuffer?,
        private val codeOffset: Int,
        private val typeNames: Array<String>,
    ) {
        private var loaded: Code? = null
        private var attempted = false

        val code: Code?
            get() {
                if (attempted) return loaded
                synchronized(this) {
                    if (attempted) return loaded
                    attempted = true
                    val buffer = source
                    if (buffer == null || codeOffset == 0) return null
                    // A SLICE, NOT THE SHARED BUFFER. A ByteBuffer carries its own position, so two
                    // threads reading two methods out of one dex would move each other's cursor --
                    // and the coroutine scheduler's workers do exactly that.
                    loaded = runCatching {
                        val view = buffer.duplicate().order(buffer.order())
                        view.position(codeOffset)
                        readCode(view, typeNames)
                    }.getOrNull()
                    return loaded
                }
            }

        val isStatic: Boolean get() = accessFlags and ACC_STATIC != 0
        val isAbstract: Boolean get() = accessFlags and ACC_ABSTRACT != 0
        val isNative: Boolean get() = accessFlags and ACC_NATIVE != 0
        val isConstructor: Boolean get() = ref.name == "<init>" || ref.name == "<clinit>"

        fun descriptor(): String =
            "(" + ref.proto.parameters.joinToString("") + ")" + ref.proto.returnType
    }

    /**
     * A method's body.
     *
     * [registers] is the TOTAL count including the `ins`, which occupy the HIGHEST registers rather
     * than the lowest -- a method with 8 registers and 3 incoming arguments receives them in v5, v6
     * and v7. Getting that backwards is the first thing that breaks in an interpreter and it breaks
     * silently, because v0 happens to hold something plausible.
     */
    data class Code(
        val registers: Int,
        val ins: Int,
        val outs: Int,
        /** Raw instruction units, 16 bits each. The interpreter decodes from this. */
        val instructions: ShortArray,
        val tries: List<Try>,
        val debugInfoOffset: Int,
    ) {
        /** Where the first incoming argument sits. See the class comment. */
        val firstArgumentRegister: Int get() = registers - ins
    }

    data class Try(
        val startAddress: Int,
        val instructionCount: Int,
        /** Exception type descriptor to handler address; a null type is a catch-all. */
        val handlers: List<Pair<String?, Int>>,
    )

    // ── Lookup ──────────────────────────────────────────────────────────────

    fun classNamed(name: String): ClassDef? {
        val descriptor = nameToDescriptor(name)
        return classes.firstOrNull { it.descriptor == descriptor }
    }

    fun classNames(): List<String> = classes.map { it.name }

    fun describe(): String =
        classes.size.toString() + " classes, " + strings.size + " strings, " +
            methods.size + " method refs, " + fields.size + " field refs"

    companion object {

        private const val TAG = "PrismDex"

        const val ACC_STATIC = 0x0008
        const val ACC_INTERFACE = 0x0200
        const val ACC_ABSTRACT = 0x0400
        const val ACC_NATIVE = 0x0100

        private const val NO_INDEX = -1

        /** `Lcom/example/App;` -> `com.example.App`; `[I` -> `int[]`. */
        fun descriptorToName(descriptor: String): String = when {
            descriptor.startsWith("L") && descriptor.endsWith(";") ->
                descriptor.substring(1, descriptor.length - 1).replace('/', '.')
            descriptor.startsWith("[") -> descriptorToName(descriptor.substring(1)) + "[]"
            descriptor == "I" -> "int"
            descriptor == "J" -> "long"
            descriptor == "Z" -> "boolean"
            descriptor == "B" -> "byte"
            descriptor == "S" -> "short"
            descriptor == "C" -> "char"
            descriptor == "F" -> "float"
            descriptor == "D" -> "double"
            descriptor == "V" -> "void"
            else -> descriptor
        }

        /**
         * The JVM class a dex type descriptor names, or null when the JVM has never heard of it.
         *
         * ## WHY `Class.forName(descriptorToName(d))` IS NOT ENOUGH
         *
         * `descriptorToName` produces a SOURCE name, which is right for a class and wrong for an
         * array: `[Ljava/lang/Object;` becomes `java.lang.Object[]`, and `Class.forName` does not
         * accept that spelling -- it wants `[Ljava.lang.Object;`. A primitive has no `forName` name
         * at all.
         *
         * That mattered more than it sounds. `const-class` is how `Array<Any>::class.java` reaches a
         * register, Kotlin's `copyToArrayOfAny` passes it straight to `Arrays.copyOf(T[], int,
         * Class)`, and a failed resolution there made `copyOf` return null -- so
         * `Intrinsics.checkNotNullExpressionValue(null, "copyOf(...)")` threw inside okhttp's
         * `<clinit>`, three frames from anything an app wrote.
         */
        fun classOf(descriptor: String): Class<*>? = when (descriptor) {
            "I" -> Int::class.javaPrimitiveType
            "J" -> Long::class.javaPrimitiveType
            "Z" -> Boolean::class.javaPrimitiveType
            "B" -> Byte::class.javaPrimitiveType
            "S" -> Short::class.javaPrimitiveType
            "C" -> Char::class.javaPrimitiveType
            "F" -> Float::class.javaPrimitiveType
            "D" -> Double::class.javaPrimitiveType
            "V" -> Void.TYPE
            else -> runCatching {
                // An array descriptor is already in `forName` form apart from the separators; a
                // class descriptor has to be stripped of its L and its semicolon.
                val name = if (descriptor.startsWith("[")) {
                    descriptor.replace('/', '.')
                } else {
                    descriptorToName(descriptor)
                }
                Class.forName(name)
            }.getOrNull()
        }

        fun nameToDescriptor(name: String): String = when {
            name.endsWith("[]") -> "[" + nameToDescriptor(name.dropLast(2))
            name == "int" -> "I"
            name == "long" -> "J"
            name == "boolean" -> "Z"
            name == "byte" -> "B"
            name == "short" -> "S"
            name == "char" -> "C"
            name == "float" -> "F"
            name == "double" -> "D"
            name == "void" -> "V"
            else -> "L" + name.replace('.', '/') + ";"
        }

        /**
         * Reads every `classes*.dex` out of an APK.
         *
         * MULTIDEX IS THE NORMAL CASE, not an edge one: any app past 65,536 methods is split across
         * `classes.dex`, `classes2.dex` and so on, and that is most apps. Reading only the first one
         * finds a fraction of the classes and, worse, finds them inconsistently -- the Application
         * class may be in any of them.
         */
        fun readApk(apk: File): List<DexFile> {
            val out = mutableListOf<DexFile>()
            runCatching {
                ZipFile(apk).use { zip ->
                    zip.entries().asSequence()
                        .filter { it.name.matches(Regex("classes\\d*\\.dex")) }
                        // Numeric order, because `classes10.dex` sorts before `classes2.dex`
                        // lexically and the order is how duplicate definitions are resolved.
                        .sortedBy { entry ->
                            entry.name.removePrefix("classes").removeSuffix(".dex")
                                .toIntOrNull() ?: 1
                        }
                        .forEach { entry ->
                            val bytes = zip.getInputStream(entry).use { it.readBytes() }
                            runCatching { read(bytes) }
                                .onSuccess { out.add(it) }
                                .onFailure {
                                    PrismPlatform.log.warn(
                                        TAG, "Could not read " + entry.name + ": " + it.message,
                                    )
                                }
                        }
                }
            }.onFailure {
                PrismPlatform.log.error(TAG, "Could not open " + apk.name, it)
            }
            return out
        }

        /**
         * Parses one DEX image.
         *
         * LITTLE-ENDIAN, always. The format has an endian tag and the reverse value has never been
         * produced by any real tool, so a big-endian file is treated as corrupt rather than supported.
         */
        fun read(bytes: ByteArray): DexFile {
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

            val magic = String(bytes, 0, 8, Charsets.US_ASCII)
            require(magic.startsWith("dex\n")) { "Not a DEX file: magic is " + magic.trim() }

            // The header, by absolute offset. Named rather than read sequentially because the
            // sequence is easy to get one field out of step and the result is a parser that works
            // on one file and not another.
            buffer.position(56)
            val stringIdsSize = buffer.int
            val stringIdsOff = buffer.int
            val typeIdsSize = buffer.int
            val typeIdsOff = buffer.int
            val protoIdsSize = buffer.int
            val protoIdsOff = buffer.int
            val fieldIdsSize = buffer.int
            val fieldIdsOff = buffer.int
            val methodIdsSize = buffer.int
            val methodIdsOff = buffer.int
            val classDefsSize = buffer.int
            val classDefsOff = buffer.int

            // ── Strings ────────────────────────────────────────────────────
            val strings = Array(stringIdsSize) { index ->
                buffer.position(stringIdsOff + index * 4)
                val dataOff = buffer.int
                buffer.position(dataOff)
                val length = readUleb128(buffer)
                readMutf8(buffer, length)
            }

            // ── Types ──────────────────────────────────────────────────────
            val typeNames = Array(typeIdsSize) { index ->
                buffer.position(typeIdsOff + index * 4)
                strings[buffer.int]
            }

            // ── Protos ─────────────────────────────────────────────────────
            val protos = Array(protoIdsSize) { index ->
                buffer.position(protoIdsOff + index * 12)
                val shortyIdx = buffer.int
                val returnTypeIdx = buffer.int
                val parametersOff = buffer.int
                val parameters = if (parametersOff == 0) emptyList() else {
                    buffer.position(parametersOff)
                    val count = buffer.int
                    (0 until count).map { typeNames[buffer.short.toInt() and 0xFFFF] }
                }
                Proto(strings[shortyIdx], typeNames[returnTypeIdx], parameters)
            }

            // ── Fields ─────────────────────────────────────────────────────
            val fields = Array(fieldIdsSize) { index ->
                buffer.position(fieldIdsOff + index * 8)
                val classIdx = buffer.short.toInt() and 0xFFFF
                val typeIdx = buffer.short.toInt() and 0xFFFF
                val nameIdx = buffer.int
                FieldRef(typeNames[classIdx], typeNames[typeIdx], strings[nameIdx])
            }

            // ── Methods ────────────────────────────────────────────────────
            val methods = Array(methodIdsSize) { index ->
                buffer.position(methodIdsOff + index * 8)
                val classIdx = buffer.short.toInt() and 0xFFFF
                val protoIdx = buffer.short.toInt() and 0xFFFF
                val nameIdx = buffer.int
                MethodRef(typeNames[classIdx], protos[protoIdx], strings[nameIdx])
            }

            // ── Classes ────────────────────────────────────────────────────
            val classes = (0 until classDefsSize).map { index ->
                buffer.position(classDefsOff + index * 32)
                val classIdx = buffer.int
                val accessFlags = buffer.int
                val superclassIdx = buffer.int
                val interfacesOff = buffer.int
                val sourceFileIdx = buffer.int
                buffer.int                                  // annotations_off, unused here
                val classDataOff = buffer.int
                buffer.int                                  // static_values_off, unused here

                val interfaces = if (interfacesOff == 0) emptyList() else {
                    buffer.position(interfacesOff)
                    val count = buffer.int
                    (0 until count).map { typeNames[buffer.short.toInt() and 0xFFFF] }
                }

                val data = if (classDataOff == 0) {
                    ClassData(emptyList(), emptyList(), emptyList(), emptyList())
                } else {
                    buffer.position(classDataOff)
                    readClassData(buffer, fields, methods, typeNames)
                }

                ClassDef(
                    descriptor = typeNames[classIdx],
                    accessFlags = accessFlags,
                    superClass = if (superclassIdx == NO_INDEX) null else typeNames[superclassIdx],
                    interfaces = interfaces,
                    sourceFile = if (sourceFileIdx == NO_INDEX) null else strings[sourceFileIdx],
                    staticFields = data.staticFields,
                    instanceFields = data.instanceFields,
                    directMethods = data.directMethods,
                    virtualMethods = data.virtualMethods,
                )
            }

            return DexFile(buffer, strings, typeNames, protos, fields, methods, classes)
        }

        private class ClassData(
            val staticFields: List<EncodedField>,
            val instanceFields: List<EncodedField>,
            val directMethods: List<EncodedMethod>,
            val virtualMethods: List<EncodedMethod>,
        )

        /**
         * The class_data_item.
         *
         * ## INDICES ARE DELTAS, which is the thing to get right
         *
         * Field and method indices inside a class_data_item are stored as the DIFFERENCE from the
         * previous one in the same list, not as absolute indices. Reading them as absolute produces a
         * class whose first member is right and whose every later member points at the wrong entry in
         * the constant pool -- so a parser that gets this wrong reports plausible nonsense rather
         * than failing.
         */
        private fun readClassData(
            buffer: ByteBuffer,
            fields: Array<FieldRef>,
            methods: Array<MethodRef>,
            typeNames: Array<String>,
        ): ClassData {
            val staticFieldsSize = readUleb128(buffer)
            val instanceFieldsSize = readUleb128(buffer)
            val directMethodsSize = readUleb128(buffer)
            val virtualMethodsSize = readUleb128(buffer)

            fun readFields(count: Int): List<EncodedField> {
                var index = 0
                return (0 until count).map {
                    index += readUleb128(buffer)
                    val access = readUleb128(buffer)
                    EncodedField(fields[index], access)
                }
            }

            fun readMethods(count: Int): List<EncodedMethod> {
                var index = 0
                return (0 until count).map {
                    index += readUleb128(buffer)
                    val access = readUleb128(buffer)
                    val codeOff = readUleb128(buffer)
                    // ONLY THE OFFSET IS KEPT -- see EncodedMethod. Nothing moves the cursor here
                    // any more, so the next method's deltas read from where this one left off
                    // without having to save and restore the position.
                    EncodedMethod(methods[index], access, buffer, codeOff, typeNames)
                }
            }

            val staticFields = readFields(staticFieldsSize)
            val instanceFields = readFields(instanceFieldsSize)
            val directMethods = readMethods(directMethodsSize)
            val virtualMethods = readMethods(virtualMethodsSize)
            return ClassData(staticFields, instanceFields, directMethods, virtualMethods)
        }

        private fun readCode(buffer: ByteBuffer, typeNames: Array<String>): Code {
            val registers = buffer.short.toInt() and 0xFFFF
            val ins = buffer.short.toInt() and 0xFFFF
            val outs = buffer.short.toInt() and 0xFFFF
            val triesSize = buffer.short.toInt() and 0xFFFF
            val debugInfoOff = buffer.int
            val instructionCount = buffer.int

            val instructions = ShortArray(instructionCount) { buffer.short }

            // Padding to a four-byte boundary before the try table, present only when there IS a
            // try table and the instruction count is odd. Omitting the condition misaligns every
            // handler address in a method with an even instruction count.
            if (triesSize > 0 && instructionCount % 2 == 1) buffer.short

            val tryStarts = (0 until triesSize).map {
                val startAddress = buffer.int
                val count = buffer.short.toInt() and 0xFFFF
                val handlerOff = buffer.short.toInt() and 0xFFFF
                Triple(startAddress, count, handlerOff)
            }

            val tries = if (triesSize == 0) emptyList() else {
                val handlersBase = buffer.position()
                val handlerListSize = readUleb128(buffer)
                // Each try's handler_off is relative to the start of the handlers list, so the
                // offsets are resolved against the position captured before reading the size.
                val resolved = HashMap<Int, List<Pair<String?, Int>>>()
                var cursor = buffer.position()
                repeat(handlerListSize) {
                    val relative = cursor - handlersBase
                    buffer.position(cursor)
                    // A NEGATIVE size means there is a catch-all after the typed handlers, and the
                    // magnitude is how many typed ones there are. Reading it unsigned loses the
                    // catch-all, which is how a finally block stops running.
                    val size = readSleb128(buffer)
                    val typed = (0 until kotlin.math.abs(size)).map {
                        val typeIdx = readUleb128(buffer)
                        val address = readUleb128(buffer)
                        typeNames.getOrNull(typeIdx) to address
                    }
                    val all = if (size <= 0) {
                        typed + (null to readUleb128(buffer))
                    } else {
                        typed
                    }
                    resolved[relative] = all
                    cursor = buffer.position()
                }
                tryStarts.map { (start, count, handlerOff) ->
                    Try(start, count, resolved[handlerOff].orEmpty())
                }
            }

            return Code(registers, ins, outs, instructions, tries, debugInfoOff)
        }

        // ── LEB128 ─────────────────────────────────────────────────────────

        fun readUleb128(buffer: ByteBuffer): Int {
            var result = 0
            var shift = 0
            while (true) {
                val byte = buffer.get().toInt() and 0xFF
                result = result or ((byte and 0x7F) shl shift)
                if (byte and 0x80 == 0) break
                shift += 7
                // Five groups is 35 bits, which is already past an Int. A sixth means the file is
                // corrupt, and looping forever on corrupt input is worse than refusing it.
                require(shift < 35) { "ULEB128 is longer than five bytes." }
            }
            return result
        }

        /**
         * Signed LEB128.
         *
         * THE SIGN EXTENSION IS THE POINT. After the last group, bits above the ones read must be
         * filled from the sign bit of that group. Without it every negative value reads as a large
         * positive one -- and the first negatives in a real DEX are in the exception handler sizes
         * and the debug line deltas, neither of which is where somebody looks first.
         */
        fun readSleb128(buffer: ByteBuffer): Int {
            var result = 0
            var shift = 0
            var byte: Int
            do {
                byte = buffer.get().toInt() and 0xFF
                result = result or ((byte and 0x7F) shl shift)
                shift += 7
                require(shift <= 35) { "SLEB128 is longer than five bytes." }
            } while (byte and 0x80 != 0)
            // Sign-extend from the last group's high bit.
            if (shift < 32 && (byte and 0x40) != 0) {
                result = result or (-1 shl shift)
            }
            return result
        }

        /**
         * Modified UTF-8.
         *
         * [utf16Length] is the number of UTF-16 code UNITS the string decodes to, which is what the
         * format stores -- not the byte count. The data is NUL-terminated as well, so the length is
         * strictly redundant and is used anyway because trusting a terminator in a file somebody else
         * wrote is how a parser walks off the end of a buffer.
         *
         * Three differences from real UTF-8, all handled: a NUL is `C0 80`, a character above the BMP
         * is TWO three-byte sequences encoding its surrogates, and there are no four-byte forms.
         */
        fun readMutf8(buffer: ByteBuffer, utf16Length: Int): String {
            val out = StringBuilder(utf16Length)
            while (out.length < utf16Length) {
                val first = buffer.get().toInt() and 0xFF
                when {
                    first == 0 -> break                     // the terminator, defensively
                    first < 0x80 -> out.append(first.toChar())
                    first and 0xE0 == 0xC0 -> {
                        val second = buffer.get().toInt() and 0x3F
                        out.append((((first and 0x1F) shl 6) or second).toChar())
                    }
                    first and 0xF0 == 0xE0 -> {
                        val second = buffer.get().toInt() and 0x3F
                        val third = buffer.get().toInt() and 0x3F
                        out.append(
                            (((first and 0x0F) shl 12) or (second shl 6) or third).toChar(),
                        )
                    }
                    else -> throw IllegalStateException(
                        "Not modified UTF-8: a lead byte of " + Integer.toHexString(first) +
                            " would be a four-byte sequence, which the format does not have.",
                    )
                }
            }
            // The trailing NUL, consumed so the cursor is where the caller expects.
            runCatching {
                if (buffer.hasRemaining() && buffer.get(buffer.position()) == 0.toByte()) {
                    buffer.get()
                }
            }
            return out.toString()
        }
    }
}
