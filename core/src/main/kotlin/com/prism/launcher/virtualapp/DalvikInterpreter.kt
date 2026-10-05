package com.prism.launcher.virtualapp

import com.prism.core.PrismPlatform

/**
 * Running Dalvik bytecode. PHASE 111.
 *
 * ## Why an interpreter and not a translator
 *
 * The phase's own framing: "Points one to three are ordinary JVM work and survive a move to desktop
 * unchanged. Point four is the whole problem: the services an Android app talks to ... do not exist on
 * a PC, so hooking them is not enough. They have to be PROVIDED."
 *
 * There were two ways to get an app's code running on a JVM. TRANSLATE the dex to JVM bytecode --
 * which is what `dex2jar` does, needs a bytecode writer, and still leaves every `invoke` pointing at
 * an `android.*` class that does not exist. Or INTERPRET it, which needs no code generation and puts
 * the dispatch for every call in one place -- which is exactly where the framework surface has to be
 * provided from.
 *
 * The second is chosen. The interpreter is a few hundred lines and the dispatch point is one function,
 * [invoke], which is where an `android.*` call is routed to [AndroidSurface] instead of to a class
 * that is not there. A translator would have needed the same routing woven through generated code.
 *
 * ## REGISTERS, NOT A STACK, AND THE ARGUMENTS ARE AT THE TOP
 *
 * Dalvik is register-based. A method declares how many registers it has, and its incoming arguments
 * occupy the HIGHEST ones: a method with 8 registers and 3 arguments receives them in v5, v6, v7.
 * Getting that backwards is the first thing that breaks and it breaks silently, because v0 usually
 * holds something plausible. [DexFile.Code.firstArgumentRegister] is the only place that arithmetic
 * lives.
 *
 * ## WIDE VALUES OCCUPY TWO REGISTERS
 *
 * A `long` or `double` in vN uses vN and vN+1, and the instruction set has separate `-wide` forms for
 * every move, get, put and return. Storing a wide into one register leaves the high half of whatever
 * was in vN+1, which produces a number that is wrong by a multiple of 2^32 -- large enough to be
 * obvious and small enough to look like an arithmetic bug somewhere else.
 *
 * ## WHAT THIS DOES NOT DO, STATED RATHER THAN DISCOVERED
 *
 * - No JIT. Everything is interpreted, which is roughly two orders of magnitude slower than ART. Fine
 *   for an `Application.onCreate`, hopeless for a game loop.
 * - No verifier. A hostile dex can crash the interpreter. That is one of the reasons the vault and
 *   the process boundary matter and is why [DesktopAppVault] is explicit about what it does not
 *   protect.
 * - The opcode set below is the one real application startup code uses. An opcode outside it throws
 *   [UnsupportedOpcode] with its name, so an unimplemented instruction is a legible failure rather
 *   than a wrong answer.
 */
object DalvikInterpreter {

    private const val TAG = "PrismDalvik"

    class UnsupportedOpcode(val opcode: Int, val address: Int, method: String) :
        RuntimeException(
            "Opcode 0x" + Integer.toHexString(opcode) + " (" + nameOf(opcode) + ") at " +
                address + " in " + method + " is not implemented.",
        )

    /**
     * An exception the app threw, carrying the app's own throwable.
     *
     * ## THE MESSAGE DESCRIBES THE VALUE, which the first version did not
     *
     * It read "An exception was thrown in dex code." for every failure, which made six different
     * `<clinit>` failures in one startup look like one problem and gave nothing to act on. An
     * interpreted throwable keeps its message in a `detailMessage` field exactly as `java.lang.
     * Throwable` does, so the type and the message are both available -- and printing them turned
     * six identical warnings into six specific ones.
     */
    class DalvikThrow(val value: Any?) : RuntimeException(describe(value)) {

        /**
         * The interpreted frames the throw passed through, innermost first.
         *
         * ## WITHOUT THIS THERE IS NO STACK TRACE AT ALL
         *
         * A JVM exception's `stackTrace` is the INTERPRETER's frames -- `execute`, `invoke`,
         * `execute` -- repeated as many times as the app's call depth. It says nothing about the
         * app. Six `<clinit>` failures all reported "index N out of bounds for length N" and there
         * was no way to tell which method in which class was doing the indexing.
         *
         * Each `execute` adds its own frame as the exception leaves it, which costs nothing on the
         * normal path and produces the app's own trace on the failing one.
         */
        val frames = ArrayList<String>()

        fun trace(): String =
            if (frames.isEmpty()) "(no interpreted frames)" else frames.joinToString(NEWLINE + "    at ")

        companion object {
            private const val NEWLINE = "\n"

            fun describe(value: Any?): String = when (value) {
                null -> "The app threw null."
                is DexObject -> {
                    val message = value.fields["detailMessage"]
                        ?: value.fields["message"]
                    value.type.name + (if (message == null) "" else ": " + message)
                }
                is Throwable -> value.javaClass.simpleName + ": " + value.message
                else -> value.toString()
            }
        }
    }

    /**
     * The `java.lang.Class` of a class the JVM does not have.
     *
     * `SomeAppType::class.java` has to put SOMETHING in a register, and for a type that exists only
     * in the app's dex there is no real `Class` to put there. A bare name string was the first
     * answer and it was ambiguous with a real String: `javaClass.name` on it arrived at the surface
     * as `java.lang.Class.getName()` with a `java.lang.String` receiver, which cannot be answered
     * without guessing whether the app meant the string or the class.
     *
     * A distinct type can be answered exactly, and it is also what `getClass()` on an interpreted
     * object returns -- so `x.javaClass.name` works for an app's own object, which is how most apps
     * build a log tag.
     */
    class DexClass(val name: String) {
        val simpleName: String get() = name.substringAfterLast('.').substringAfterLast('$')
        override fun toString(): String = "class " + name
        override fun equals(other: Any?): Boolean = other is DexClass && other.name == name
        override fun hashCode(): Int = name.hashCode()
    }

    /**
     * A primitive that is being held as an OBJECT REFERENCE.
     *
     * ## THE ONE AMBIGUITY A REGISTER-BASED INTERPRETER CANNOT AVOID
     *
     * A dex register is 32 bits with no type. The same bits are an `int`, a `float`, or a reference,
     * and the instruction that reads them does not say which -- `if-eqz` tests an int against zero
     * AND a reference against null, with one opcode.
     *
     * That is usually harmless, because `int 0` and `null` behave the same way under every
     * instruction that can legally read either. It stops being harmless the moment a BOXED ZERO
     * exists: `Integer.valueOf(0)` is a perfectly ordinary non-null object, and storing it as a plain
     * `0` makes `if-nez` take the null branch on a value that is not null.
     *
     * Which is not a hypothetical. `kotlinx.coroutines` does exactly this:
     *
     * ```kotlin
     * internal fun threadContextElements(context: CoroutineContext): Any = context.fold(0, countAll)!!
     * ```
     *
     * An empty context folds to `Integer.valueOf(0)`, the `!!` compiles to
     * `Intrinsics.checkNotNull`, and the interpreter threw a NullPointerException on a value that
     * was plainly there -- eighteen frames below `PrismApp.onCreate`, in a method the app never
     * wrote. There is no way to get that right by looking at the register alone.
     *
     * ## HOW THE TYPE IS RECOVERED
     *
     * From the METHOD DESCRIPTOR, which does carry the type. Every boxed value in a register came out
     * of a call -- `Integer.valueOf`, `Map.get`, `List.get` -- whose return descriptor is `L...;` or
     * `[...`. [AndroidSurface.call] wraps a primitive answer in this when the descriptor says the
     * answer is a reference, and unwraps it when the descriptor says it is a primitive. One place,
     * no guessing.
     *
     * Everything that reads a register numerically -- [asInt], [asLong], [asDouble] -- looks through
     * this, so an unboxed use still works. Only [isNumeric] refuses it, which is the whole point:
     * to `if-eqz` and `if-eq` this is a reference, and a reference is never zero.
     */
    class Boxed(val value: Any) {
        override fun toString(): String = value.toString()
        override fun equals(other: Any?): Boolean =
            value == (if (other is Boxed) other.value else other)
        override fun hashCode(): Int = value.hashCode()

        companion object {
            /** The value itself, whether or not it is boxed. */
            fun unwrap(value: Any?): Any? = if (value is Boxed) value.value else value

            /** True for the primitives that have a box class. */
            fun boxable(value: Any?): Boolean =
                value is Number || value is Boolean || value is Char
        }
    }

    /**
     * An object of a dex-defined class.
     *
     * A map rather than a generated class: the interpreter never needs the JVM to know the shape, and
     * generating one per class would be the code generation this design exists to avoid.
     */
    class DexObject(val type: DexFile.ClassDef, val runtime: DexRuntime) {
        val fields = HashMap<String, Any?>()

        init {
            // AN UNWRITTEN FIELD IS ZERO, NOT NULL, which Dalvik and the JVM both guarantee and a
            // map does not. Without this every `int` field read 0 by luck -- `asInt(null)` is 0 --
            // and every `long` and `double` field read null, which is a different thing entirely
            // once the value leaves the interpreter.
            //
            // How that failed: a null in a `long` register reached `Math.min(JJ)J` at the framework
            // surface, reflection refused null for a primitive parameter, and the call was recorded
            // as an unimplemented method on `java.lang.Math`. The report blamed the JDK for an
            // uninitialised field in the app.
            //
            // The whole hierarchy is prefilled, not just this class, because a field declared on a
            // superclass is read through the same map.
            var current: DexFile.ClassDef? = type
            while (current != null) {
                current.instanceFields.forEach { field ->
                    fields.putIfAbsent(field.ref.name, zeroOf(field.ref.type))
                }
                current = current.superClass?.let { runtime.find(it) }
            }
        }

        override fun toString(): String = type.name + "@" + Integer.toHexString(hashCode())
    }

    /**
     * The default value of a field or array element of this type.
     *
     * A reference is null and every primitive is its own zero -- and the WIDTH matters: a `long`
     * field defaulting to an `Int` 0 would be read by `asLong` as 0 and by anything checking the
     * value's type as the wrong width.
     */
    internal fun zeroOf(descriptor: String): Any? = when (descriptor) {
        "I", "S", "B" -> 0
        "J" -> 0L
        "Z" -> 0
        "C" -> 0
        "F" -> 0.0f
        "D" -> 0.0
        else -> null
    }

    /**
     * Everything loaded, and where a call goes when it leaves dex code.
     *
     * [surface] is the framework: a call to `android.*` or `java.*` that is not a dex class is handed
     * to it. That is the seam the phase described as "the framework surface, implemented against
     * desktop primitives".
     */
    class DexRuntime(
        val dexes: List<DexFile>,
        val surface: AndroidSurface,
    ) {
        private val byDescriptor = HashMap<String, DexFile.ClassDef>()
        private val statics = HashMap<String, Any?>()
        private val initialised = HashSet<String>()

        /** How many dex instructions have been executed, for the page to report. */
        var executed: Long = 0L
            internal set

        init {
            // First definition wins, which is multidex's own rule: `classes.dex` takes precedence
            // over `classes2.dex` for a duplicated class.
            dexes.forEach { dex ->
                dex.classes.forEach { def -> byDescriptor.putIfAbsent(def.descriptor, def) }
            }
        }

        fun classes(): Collection<DexFile.ClassDef> = byDescriptor.values

        fun find(descriptor: String): DexFile.ClassDef? = byDescriptor[descriptor]

        fun findByName(name: String): DexFile.ClassDef? =
            byDescriptor[DexFile.nameToDescriptor(name)]

        fun staticGet(key: String): Any? = statics[key]

        /** Whether a static has been ASSIGNED, as opposed to holding null. See staticGet's caller. */
        fun staticHas(key: String): Boolean = statics.containsKey(key)

        fun staticPut(key: String, value: Any?) {
            statics[key] = value
        }

        /**
         * Runs a class's `<clinit>` once, if it has one.
         *
         * ONCE, tracked here rather than by a flag on the class, because a class initialiser that ran
         * twice would double every static it accumulates -- and a registry built in `<clinit>` is a
         * common pattern in the code this will meet.
         */
        fun ensureInitialised(def: DexFile.ClassDef) {
            if (!initialised.add(def.descriptor)) return
            val clinit = def.directMethods.firstOrNull { it.ref.name == "<clinit>" } ?: return
            runCatching { execute(this, def, clinit, emptyList()) }
                .onFailure {
                    PrismPlatform.log.warn(
                        TAG,
                        "<clinit> of " + def.name + " failed: " + it.message +
                            (if (it is DalvikThrow) "\n    at " + it.trace()
                                else "\n    " + it.stackTrace.take(12).joinToString("\n    ")),
                    )
                }
        }

        /**
         * Resolves a virtual call against an object's actual class, walking up the hierarchy.
         *
         * Dalvik's `invoke-virtual` names a method on a DECLARED type and the runtime dispatches on
         * the receiver's real one. Skipping the walk calls the superclass's version of an overridden
         * method, which for an `Application` subclass means its own `onCreate` never runs -- the exact
         * thing this phase is trying to do.
         */
        fun resolveVirtual(
            receiver: Any?,
            name: String,
            descriptor: String,
        ): Pair<DexFile.ClassDef, DexFile.EncodedMethod>? {
            var current = when (receiver) {
                is DexObject -> receiver.type
                else -> return null
            }
            while (true) {
                current.method(name, descriptor)?.let { if (!it.isAbstract) return current to it }
                val superDescriptor = current.superClass ?: return null
                current = find(superDescriptor) ?: return null
            }
        }
    }

    /**
     * Executes one method.
     *
     * [arguments] are in declaration order, with the receiver first for an instance method. They are
     * placed into the high registers -- see the class comment.
     */
    fun execute(
        runtime: DexRuntime,
        owner: DexFile.ClassDef,
        method: DexFile.EncodedMethod,
        arguments: List<Any?>,
    ): Any? {
        val code = method.code
            ?: throw IllegalStateException(method.ref.toString() + " has no code (abstract or native).")

        val registers = arrayOfNulls<Any?>(code.registers.coerceAtLeast(1))
        var slot = code.firstArgumentRegister
        arguments.forEach { argument ->
            if (slot >= registers.size) return@forEach
            registers[slot] = argument
            // A wide value takes two slots. The high half is left null: nothing reads it directly,
            // because every wide access goes through the pair.
            slot += if (argument is Long || argument is Double) 2 else 1
        }

        val code16 = code.instructions
        var pc = 0
        var result: Any? = null

        fun u(index: Int): Int = code16[index].toInt() and 0xFFFF
        fun byteA(index: Int): Int = (u(index) shr 8) and 0x0F
        fun byteB(index: Int): Int = (u(index) shr 12) and 0x0F
        fun aa(index: Int): Int = (u(index) shr 8) and 0xFF

        while (pc < code16.size) {
            runtime.executed++
            val unit = u(pc)
            val opcode = unit and 0xFF
            // Captured for the diagnostic below: by the time an index error is caught, `pc` may
            // already have moved.
            val at = pc

            try {
            when (opcode) {
                // nop
                0x00 -> pc += 1

                // move vA, vB / move-wide / move-object
                0x01, 0x04, 0x07 -> {
                    registers[byteA(pc)] = registers[byteB(pc)]
                    pc += 1
                }
                // move/from16, move-wide/from16, move-object/from16
                0x02, 0x05, 0x08 -> {
                    registers[aa(pc)] = registers[u(pc + 1)]
                    pc += 2
                }
                // move/16, move-wide/16, move-object/16
                0x03, 0x06, 0x09 -> {
                    registers[u(pc + 1)] = registers[u(pc + 2)]
                    pc += 3
                }

                // move-result, move-result-wide, move-result-object
                0x0A, 0x0B, 0x0C -> {
                    registers[aa(pc)] = result
                    pc += 1
                }
                // move-exception
                0x0D -> {
                    registers[aa(pc)] = result
                    pc += 1
                }

                // return-void
                0x0E -> return null
                // return vAA / return-wide / return-object
                0x0F, 0x10, 0x11 -> return registers[aa(pc)]

                // const/4
                0x12 -> {
                    // FOUR BITS, SIGNED. Read unsigned it is 0..15 and every negative constant --
                    // including the -1 that `const/4 v0, -1` puts in a loop counter -- becomes 15.
                    val value = (u(pc) shr 12) and 0x0F
                    registers[byteA(pc)] = if (value >= 8) value - 16 else value
                    pc += 1
                }
                // const/16
                0x13 -> {
                    registers[aa(pc)] = code16[pc + 1].toInt()
                    pc += 2
                }
                // const
                0x14 -> {
                    registers[aa(pc)] = (u(pc + 1)) or (u(pc + 2) shl 16)
                    pc += 3
                }
                // const/high16
                0x15 -> {
                    registers[aa(pc)] = u(pc + 1) shl 16
                    pc += 2
                }
                // const-wide/16
                0x16 -> {
                    registers[aa(pc)] = code16[pc + 1].toLong()
                    pc += 2
                }
                // const-wide/32
                0x17 -> {
                    registers[aa(pc)] = ((u(pc + 1)) or (u(pc + 2) shl 16)).toLong()
                    pc += 3
                }
                // const-wide
                0x18 -> {
                    var value = 0L
                    for (i in 0 until 4) {
                        value = value or (u(pc + 1 + i).toLong() shl (16 * i))
                    }
                    registers[aa(pc)] = value
                    pc += 5
                }
                // const-wide/high16
                0x19 -> {
                    registers[aa(pc)] = u(pc + 1).toLong() shl 48
                    pc += 2
                }
                // const-string
                0x1A -> {
                    registers[aa(pc)] = stringAt(runtime, owner, u(pc + 1))
                    pc += 2
                }
                // const-string/jumbo
                0x1B -> {
                    registers[aa(pc)] = stringAt(runtime, owner, (u(pc + 1)) or (u(pc + 2) shl 16))
                    pc += 3
                }
                // const-class
                //
                // A REAL java.lang.Class WHERE THERE IS ONE, AND A DexClass WHERE THERE IS NOT.
                //
                // The first version put the class's NAME in the register, so every
                // `SomeType::class.java` became a String -- and the first thing Kotlin does with one
                // is call `getName()`, which arrived at the surface as an unimplemented call on
                // java.lang.Class with a java.lang.String receiver. A name string is ambiguous with
                // a real string and cannot be answered without guessing which the app meant, so a
                // dex-only type now gets its own type. See DexClass.
                //
                // DexFile.classOf rather than Class.forName: an array descriptor needs a different
                // spelling and a primitive has no forName name at all.
                0x1C -> {
                    val descriptor = descriptorAt(runtime, owner, u(pc + 1))
                    registers[aa(pc)] = DexFile.classOf(descriptor)
                        ?: DexClass(DexFile.descriptorToName(descriptor))
                    pc += 2
                }

                // monitor-enter / monitor-exit. ACCEPTED AND IGNORED.
                //
                // THE ORIGINAL REASON NO LONGER HOLDS AND THE DECISION STILL DOES. It used to be
                // that the interpreter ran one thread, so a monitor had nothing to protect. It can
                // now start real threads -- an app's `Thread` subclass gets a real host thread -- so
                // two interpreted threads really can race.
                //
                // They are still ignored, because honouring them would be WORSE until exception
                // handling is implemented. Dalvik guarantees a matching `monitor-exit` in a
                // compiler-generated catch-all handler; this interpreter does not run handlers yet,
                // so an exception thrown inside a `synchronized` block would leave the lock held
                // forever and the app would deadlock on its next entry. A lost race is recoverable
                // and a deadlock is not.
                //
                // WHAT TO DO WHEN HANDLERS LAND: a re-entrant lock per object, keyed by identity,
                // taken here and released there. Not before.
                0x1D, 0x1E -> pc += 1

                // check-cast
                0x1F -> {
                    // ACCEPTED WITHOUT CHECKING, deliberately: a full check needs the whole
                    // hierarchy including framework classes the surface fakes, and a false negative
                    // here throws ClassCastException in code that was correct. A missed cast error
                    // surfaces later as a wrong call, which is the better failure of the two.
                    pc += 2
                }
                // instance-of
                0x20 -> {
                    val value = registers[byteB(pc)]
                    val descriptor = descriptorAt(runtime, owner, u(pc + 1))
                    registers[byteA(pc)] = if (isInstance(runtime, value, descriptor)) 1 else 0
                    pc += 2
                }
                // array-length
                0x21 -> {
                    registers[byteA(pc)] = lengthOf(registers[byteB(pc)])
                    pc += 1
                }
                // new-instance
                0x22 -> {
                    val descriptor = descriptorAt(runtime, owner, u(pc + 1))
                    registers[aa(pc)] = instantiate(runtime, descriptor)
                    pc += 2
                }
                // new-array
                0x23 -> {
                    val size = asInt(registers[byteB(pc)])
                    val descriptor = descriptorAt(runtime, owner, u(pc + 1))
                    registers[byteA(pc)] = newArray(descriptor, size)
                    pc += 2
                }

                // filled-new-array {vC..vG}, type
                //
                // UNAVOIDABLE RATHER THAN EXOTIC: every Kotlin or Java `enum` compiles a `$values()`
                // that builds its array with this, so an interpreter without it cannot initialise a
                // single enum -- which is what every `<clinit>` failure in the first run turned out
                // to be.
                0x24 -> {
                    val count = (u(pc) shr 12) and 0x0F
                    val descriptor = descriptorAt(runtime, owner, u(pc + 1))
                    val word = u(pc + 2)
                    val slots = (0 until count).map { index ->
                        when (index) {
                            0 -> word and 0x0F
                            1 -> (word shr 4) and 0x0F
                            2 -> (word shr 8) and 0x0F
                            3 -> (word shr 12) and 0x0F
                            else -> (u(pc) shr 8) and 0x0F
                        }
                    }
                    val array = newArray(descriptor, count)
                    slots.forEachIndexed { index, slot ->
                        arraySet(array, index, registers.getOrNull(slot))
                    }
                    result = array
                    pc += 3
                }
                // filled-new-array/range
                0x25 -> {
                    val count = aa(pc)
                    val descriptor = descriptorAt(runtime, owner, u(pc + 1))
                    val first = u(pc + 2)
                    val array = newArray(descriptor, count)
                    for (index in 0 until count) {
                        arraySet(array, index, registers.getOrNull(first + index))
                    }
                    result = array
                    pc += 3
                }
                // fill-array-data vAA, +BBBBBBBB
                //
                // The payload is an array-data pseudo-instruction somewhere else in the method, at a
                // SIGNED 32-bit offset from this instruction. It holds an element width and a count,
                // then the raw elements -- which is how a `byte[] = {1,2,3}` initialiser is encoded.
                0x26 -> {
                    val offset = (u(pc + 1)) or (u(pc + 2) shl 16)
                    val payload = pc + offset
                    val array = registers[aa(pc)]
                    if (payload >= 0 && payload + 3 < code16.size) {
                        val width = u(payload + 1)
                        val count = (u(payload + 2)) or (u(payload + 3) shl 16)
                        var unit = payload + 4
                        var bitOffset = 0
                        for (index in 0 until count) {
                            val value: Any = when (width) {
                                1 -> {
                                    val byte = (u(unit) shr bitOffset) and 0xFF
                                    bitOffset += 8
                                    if (bitOffset == 16) { bitOffset = 0; unit += 1 }
                                    byte.toByte().toInt()
                                }
                                2 -> code16[unit++].toInt()
                                4 -> {
                                    val v = (u(unit)) or (u(unit + 1) shl 16)
                                    unit += 2
                                    v
                                }
                                8 -> {
                                    var v = 0L
                                    for (i in 0 until 4) v = v or (u(unit + i).toLong() shl (16 * i))
                                    unit += 4
                                    v
                                }
                                else -> 0
                            }
                            runCatching { arraySet(array, index, value) }
                        }
                    }
                    pc += 3
                }

                // packed-switch vAA, +BBBBBBBB
                //
                // The payload is a first key and a list of relative targets. A `when` over a dense
                // range of ints compiles to this, and without it every such `when` falls through to
                // its else -- which is a wrong answer rather than a crash, and therefore worse.
                0x2B -> {
                    val value = asInt(registers[aa(pc)])
                    val offset = (u(pc + 1)) or (u(pc + 2) shl 16)
                    val payload = pc + offset
                    var jump = 3
                    if (payload >= 0 && payload + 3 < code16.size) {
                        val size = u(payload + 1)
                        val firstKey = (u(payload + 2)) or (u(payload + 3) shl 16)
                        val index = value - firstKey
                        if (index in 0 until size) {
                            val base = payload + 4 + index * 2
                            jump = (u(base)) or (u(base + 1) shl 16)
                        }
                    }
                    pc += if (jump == 0) 3 else jump
                }
                // sparse-switch vAA, +BBBBBBBB
                //
                // Keys and targets as two parallel arrays, sorted by key. A `when` over scattered
                // constants -- a resource id, a message code -- compiles to this one.
                0x2C -> {
                    val value = asInt(registers[aa(pc)])
                    val offset = (u(pc + 1)) or (u(pc + 2) shl 16)
                    val payload = pc + offset
                    var jump = 3
                    if (payload >= 0 && payload + 1 < code16.size) {
                        val size = u(payload + 1)
                        for (index in 0 until size) {
                            val keyAt = payload + 2 + index * 2
                            val key = (u(keyAt)) or (u(keyAt + 1) shl 16)
                            if (key == value) {
                                val targetAt = payload + 2 + size * 2 + index * 2
                                jump = (u(targetAt)) or (u(targetAt + 1) shl 16)
                                break
                            }
                        }
                    }
                    pc += if (jump == 0) 3 else jump
                }

                // goto
                0x28 -> {
                    val offset = aa(pc).toByte().toInt()
                    pc += if (offset == 0) 1 else offset
                }
                // goto/16
                0x29 -> {
                    val offset = code16[pc + 1].toInt()
                    pc += if (offset == 0) 2 else offset
                }
                // goto/32
                0x2A -> {
                    val offset = (u(pc + 1)) or (u(pc + 2) shl 16)
                    pc += if (offset == 0) 3 else offset
                }

                // cmp-long, and cmpl/cmpg for float and double.
                //
                // FORMAT 23x: vAA is the DESTINATION and the operands are vBB and vCC, both in the
                // second word. This read vAA as the left operand and vBB as the right, which
                // compared the result register against the first operand -- wrong for every
                // comparison and out of bounds whenever vAA was the last register.
                //
                // The `l` and `g` suffixes differ only in how a NaN compares: cmpl yields -1 and
                // cmpg yields 1. A single implementation would make one of `a < b` and `a > b`
                // wrong for NaN, which is how a sort over doubles silently stops terminating.
                0x2D, 0x2E, 0x2F, 0x30, 0x31 -> {
                    val left = registers[(u(pc + 1)) and 0xFF]
                    val right = registers[(u(pc + 1) shr 8) and 0xFF]
                    registers[aa(pc)] = when (opcode) {
                        0x31 -> asLong(left).compareTo(asLong(right))
                        else -> {
                            // 0x2D/0x2E are the FLOAT comparisons and 0x2F/0x30 the double ones, so
                            // they read their registers differently -- see asFloat.
                            val isFloat = opcode == 0x2D || opcode == 0x2E
                            val a = if (isFloat) asFloat(left).toDouble() else asDoubleBits(left)
                            val b = if (isFloat) asFloat(right).toDouble() else asDoubleBits(right)
                            when {
                                a.isNaN() || b.isNaN() ->
                                    // cmpl-float/double are 0x2D and 0x2F.
                                    if (opcode == 0x2D || opcode == 0x2F) -1 else 1
                                else -> a.compareTo(b)
                            }
                        }
                    }
                    pc += 2
                }

                // if-eq, if-ne, if-lt, if-ge, if-gt, if-le  (two registers)
                // if-eq/ne/lt/ge/gt/le vA, vB, +CCCC
                //
                // ## ONE OPCODE FOR NUMBERS AND FOR REFERENCES
                //
                // Dalvik uses `if-eq` both for `a == b` on ints and for `a === b` on references, and
                // the instruction does not say which. Converting both sides with `asInt` -- as the
                // first version did -- turns EVERY pair of object references into `0 == 0`, so
                // `if-eq` on two unrelated objects was taken and `if-ne` never was. Deciding by what
                // the registers actually hold is the only way: there is no type information in the
                // instruction to decide it from.
                in 0x32..0x37 -> {
                    val leftValue = registers[byteA(pc)]
                    val rightValue = registers[byteB(pc)]
                    val taken = if (isNumeric(leftValue) && isNumeric(rightValue)) {
                        val left = asInt(leftValue)
                        val right = asInt(rightValue)
                        when (opcode) {
                            0x32 -> left == right
                            0x33 -> left != right
                            0x34 -> left < right
                            0x35 -> left >= right
                            0x36 -> left > right
                            else -> left <= right
                        }
                    } else {
                        // Only if-eq and if-ne are legal on references. IDENTITY, not equality,
                        // which is what Dalvik does: dex code that wants value equality emits a call
                        // to `equals`. String constants come from the dex's own pool, so two reads of
                        // the same constant are the same object and identity is right for them too.
                        val same = leftValue === rightValue
                        when (opcode) {
                            0x32 -> same
                            0x33 -> !same
                            else -> false
                        }
                    }
                    val offset = code16[pc + 1].toInt()
                    pc += if (taken && offset != 0) offset else 2
                }

                // if-eqz .. if-lez  (one register against zero)
                // if-eqz/nez/ltz/gez/gtz/lez vAA, +BBBB
                in 0x38..0x3D -> {
                    val value = registers[aa(pc)]
                    // A NULL COMPARES AS ZERO AND EVERY OTHER REFERENCE AS NON-ZERO.
                    //
                    // ## THIS WAS THE BUG BEHIND THE WHOLE COROUTINES FAILURE
                    //
                    // `if-eqz` is how Dalvik tests a reference for null, and the first version read
                    // the register with `asInt`, whose fallback for anything it does not recognise is
                    // 0. So an interpreted object tested as null: `if (x != null)` took the null
                    // branch for EVERY non-null interpreted object.
                    //
                    // What that looked like from the outside: `Intrinsics.checkNotNullParameter`
                    // threw on a value that was plainly present -- the frame trace showed
                    // `checkNotNullParameter(ContinuationInterceptor$Key, "baseKey")` calling
                    // `throwParameterIsNullNPE` -- and the real crash was three frames further on,
                    // inside Kotlin's message builder, where it indexed a stack trace that does not
                    // exist here. Six `<clinit>` failures and a dead startup, all from this line.
                    val number = when {
                        value == null -> 0
                        isNumeric(value) -> asInt(value)
                        // A reference. Non-zero, so if-nez is taken and if-eqz is not.
                        else -> 1
                    }
                    val taken = when (opcode) {
                        0x38 -> number == 0
                        0x39 -> number != 0
                        0x3A -> number < 0
                        0x3B -> number >= 0
                        0x3C -> number > 0
                        else -> number <= 0
                    }
                    val offset = code16[pc + 1].toInt()
                    pc += if (taken && offset != 0) offset else 2
                }

                // aget family. Format 23x: vAA destination, vBB array, vCC index.
                in 0x44..0x4A -> {
                    val array = registers[(u(pc + 1)) and 0xFF]
                    val index = asInt(registers[(u(pc + 1) shr 8) and 0xFF])
                    // THE APP'S OWN BOUNDS ERROR IS THE APP'S EXCEPTION. Letting the JVM's
                    // IndexOutOfBoundsException escape made it look like an interpreter bug -- the
                    // first real run reported "Register index out of range" for an app indexing past
                    // the end of a stack trace, which sent the investigation to entirely the wrong
                    // place. A DalvikThrow is catchable by the app, which is what would happen on a
                    // device.
                    registers[aa(pc)] = try {
                        arrayGet(array, index)
                    } catch (bounds: IndexOutOfBoundsException) {
                        throw DalvikThrow(
                            "ArrayIndexOutOfBoundsException: index " + index + ", " +
                                bounds.message,
                        )
                    }
                    pc += 2
                }
                // aput family
                in 0x4B..0x51 -> {
                    val array = registers[(u(pc + 1)) and 0xFF]
                    val index = asInt(registers[(u(pc + 1) shr 8) and 0xFF])
                    try {
                        arraySet(array, index, registers[aa(pc)])
                    } catch (bounds: IndexOutOfBoundsException) {
                        throw DalvikThrow(
                            "ArrayIndexOutOfBoundsException: index " + index + ", " +
                                bounds.message,
                        )
                    }
                    pc += 2
                }

                // iget family
                in 0x52..0x58 -> {
                    val target = registers[byteB(pc)]
                    val field = fieldAt(runtime, owner, u(pc + 1))
                    registers[byteA(pc)] = instanceGet(runtime, target, field)
                    pc += 2
                }
                // iput family
                in 0x59..0x5F -> {
                    val target = registers[byteB(pc)]
                    val field = fieldAt(runtime, owner, u(pc + 1))
                    instancePut(runtime, target, field, registers[byteA(pc)])
                    pc += 2
                }

                // sget family
                in 0x60..0x66 -> {
                    val field = fieldAt(runtime, owner, u(pc + 1))
                    registers[aa(pc)] = staticGet(runtime, field)
                    pc += 2
                }
                // sput family
                in 0x67..0x6D -> {
                    val field = fieldAt(runtime, owner, u(pc + 1))
                    staticPut(runtime, field, registers[aa(pc)])
                    pc += 2
                }

                // invoke-virtual, -super, -direct, -static, -interface
                in 0x6E..0x72 -> {
                    val argumentCount = (u(pc) shr 12) and 0x0F
                    val methodIndex = u(pc + 1)
                    val argumentWord = u(pc + 2)
                    val slots = (0 until argumentCount).map { index ->
                        when (index) {
                            0 -> argumentWord and 0x0F
                            1 -> (argumentWord shr 4) and 0x0F
                            2 -> (argumentWord shr 8) and 0x0F
                            3 -> (argumentWord shr 12) and 0x0F
                            else -> (u(pc) shr 8) and 0x0F
                        }
                    }
                    result = invoke(runtime, owner, methodIndex, registers, slots, opcode)
                    adoptConstructed(runtime, owner, methodIndex, opcode, registers, slots, result)
                    pc += 3
                }

                // invoke-*/range
                in 0x74..0x78 -> {
                    val argumentCount = aa(pc)
                    val methodIndex = u(pc + 1)
                    val first = u(pc + 2)
                    val slots = (0 until argumentCount).map { first + it }
                    result = invoke(runtime, owner, methodIndex, registers, slots, opcode - 6)
                    adoptConstructed(
                        runtime, owner, methodIndex, opcode - 6, registers, slots, result,
                    )
                    pc += 3
                }

                // neg-int .. int-to-short: the unary conversions
                in 0x7B..0x8F -> {
                    val source = registers[byteB(pc)]
                    registers[byteA(pc)] = unary(opcode, source)
                    pc += 1
                }

                // add-int .. rem-double: binary, three registers
                in 0x90..0xAF -> {
                    val left = registers[(u(pc + 1)) and 0xFF]
                    val right = registers[(u(pc + 1) shr 8) and 0xFF]
                    registers[aa(pc)] = binary(opcode, left, right)
                    pc += 2
                }

                // add-int/2addr .. rem-double/2addr
                in 0xB0..0xCF -> {
                    val target = byteA(pc)
                    registers[target] = binary(opcode - 0x20, registers[target], registers[byteB(pc)])
                    pc += 1
                }

                // add-int/lit16 .. rsub-int and the rest of the lit16 group
                in 0xD0..0xD7 -> {
                    val literal = code16[pc + 1].toInt()
                    registers[byteA(pc)] = literalOp(opcode, registers[byteB(pc)], literal)
                    pc += 2
                }
                // the lit8 group
                in 0xD8..0xE2 -> {
                    val literal = (u(pc + 1) shr 8).toByte().toInt()
                    registers[aa(pc)] = literalOp(opcode, registers[(u(pc + 1)) and 0xFF], literal)
                    pc += 2
                }

                // throw
                0x27 -> throw DalvikThrow(registers[aa(pc)])

                else -> throw UnsupportedOpcode(opcode, pc, method.ref.toString())
            }
            } catch (thrown: DalvikThrow) {
                // THE ARGUMENTS ARE IN THE FRAME, which is the part that actually finds the bug: a
                // trace of method names says a null was passed somewhere, and a trace with the
                // arguments says which one and what the caller had instead.
                thrown.frames.add(
                    method.ref.toString() + " +" + at +
                        " (opcode 0x" + Integer.toHexString(opcode) + ")" +
                        " args=" + arguments.joinToString(", ") { brief(it) },
                )
                throw thrown
            } catch (bounds: IndexOutOfBoundsException) {
                // WHAT IS LEFT HERE IS A REGISTER INDEX, which is an interpreter bug: an app's own
                // array overrun is caught at the aget/aput above and rethrown as a DalvikThrow. The
                // message names the opcode and address, because the first version of this did not
                // and a decoding bug is otherwise indistinguishable from an app's own fault.
                throw IllegalStateException(
                    "Register index out of range executing opcode 0x" +
                        Integer.toHexString(opcode) + " at " + at + " in " +
                        method.ref.toString() + " (" + code.registers + " registers, " +
                        code.ins + " incoming): " + bounds.message,
                    bounds,
                )
            }
        }
        return null
    }

    /**
     * Calls a method on an interpreted object from OUTSIDE the interpreter.
     *
     * The framework surface needs this: a `Runnable` handed to `Handler.post` is a `DexObject`, and
     * running it means dispatching `run()` back into dex code. Without this entry point the surface
     * could only receive calls, never make them -- so a posted task would be dropped silently, which
     * for an app that initialises itself from a posted Runnable means nothing happens at all.
     *
     * Returns null, without throwing, when the receiver has no such method: a surface callback is not
     * a place where an interpreter bug should become an app crash.
     */
    fun invokeVirtual(
        runtime: DexRuntime,
        receiver: Any?,
        name: String,
        descriptor: String,
        arguments: List<Any?>,
    ): Any? {
        val resolved = runtime.resolveVirtual(receiver, name, descriptor) ?: return null
        val (definer, method) = resolved
        if (method.code == null) return null
        return execute(runtime, definer, method, arguments)
    }

    // ── Dispatch ────────────────────────────────────────────────────────────

    /**
     * A call leaving the current method.
     *
     * ## THIS IS THE SEAM THE WHOLE DESIGN TURNS ON
     *
     * A dex `invoke` names a method on a class. If that class is one the app shipped, the call goes
     * back into the interpreter. If it is `android.*` or `java.*`, there is nothing to interpret and
     * the call is handed to [AndroidSurface] -- which is the "framework surface implemented against
     * desktop primitives" the phase described, and the reason interpreting beats translating.
     */
    private fun invoke(
        runtime: DexRuntime,
        owner: DexFile.ClassDef,
        methodIndex: Int,
        registers: Array<Any?>,
        slots: List<Int>,
        opcode: Int,
    ): Any? {
        val resolvedRef = methodRefAt(runtime, owner, methodIndex)
        // THE DESCRIPTOR SAYS WHICH ARGUMENTS ARE WIDE, which is the only reliable way to know.
        //
        // The register list names both halves of a `long` or a `double`, and the callee must receive
        // the value once. The first version decided by LOOKING AT THE VALUE -- skip the next slot if
        // this one holds a Long or a Double -- which is right whenever the register holds what the
        // signature says and wrong otherwise. A wide register holding null, or an Int because
        // something upstream returned the wrong width, shifted every later argument by one.
        //
        // It did not fail loudly. `Math.min(JJ)J` arrived at the framework surface with three or four
        // arguments instead of two, no overload matched, and it was recorded as an unimplemented call
        // on `java.lang.Math` -- a class the JVM has had since version 1.
        val collected = collect(
            registers,
            slots,
            resolvedRef?.proto?.parameters,
            // Every invoke except invoke-static passes the receiver first, and a receiver is never
            // wide.
            receiverFirst = opcode != 0x71,
        )
        // A PROXY NEVER REACHES DEX CODE. A JVM collection holds whatever was put into it, so a
        // proxy an app object was wrapped in can come back out of `list.get` or an iterator and be
        // invoked on. Unwrapping here -- at the one point every call passes through -- means
        // interpreted code only ever sees the app's own objects, and `iget-object` can never find a
        // `$Proxy` where a field map should be.
        //
        // The check is a field read until something has actually been proxied, which for an app that
        // hands nothing to the framework is every call.
        val arguments = if (runtime.surface.hasProxies) {
            collected.map { runtime.surface.unproxy(it) }
        } else {
            collected
        }
        val ref = resolvedRef
            ?: throw IllegalStateException("Unresolvable method index " + methodIndex)
        val descriptor = "(" + ref.proto.parameters.joinToString("") + ")" + ref.proto.returnType

        // ── A SHORT LIST THE SURFACE ANSWERS EVEN THOUGH THE APP SHIPS IT ──
        //
        // Normally a class in the app's dex is interpreted and that is the whole point. These are the
        // exception: code that cannot work under an interpreter because it inspects the JVM stack it
        // is running on. `kotlin.jvm.internal.Intrinsics` builds its null-check messages by reading
        // `new Throwable().getStackTrace()[1]`, and an interpreted call has no frame there -- so
        // every genuine null check failed with an ArrayIndexOutOfBoundsException from inside Kotlin
        // instead of the NullPointerException the app was about to catch.
        //
        // Kept deliberately short. Taking over anything the interpreter CAN run would mean the app
        // no longer runs its own code, which is the one thing this design exists to do.
        if (ref.definingClass == "Lkotlin/jvm/internal/Intrinsics;") {
            return runtime.surface.call(ref.definingClass, ref.name, descriptor, arguments)
        }

        // A dex-defined class: back into the interpreter.
        val target = runtime.find(ref.definingClass)
        if (target != null) {
            runtime.ensureInitialised(target)
            // 0x6E is invoke-virtual and 0x72 invoke-interface; both dispatch on the receiver's
            // real type. direct, static and super do not.
            val resolved = if (opcode == 0x6E || opcode == 0x72) {
                runtime.resolveVirtual(arguments.firstOrNull(), ref.name, descriptor)
                    ?: target.method(ref.name, descriptor)?.let { target to it }
            } else {
                target.method(ref.name, descriptor)?.let { target to it }
                    // A direct call to a constructor the class does not declare is a call to its
                    // superclass's -- which is what `invoke-direct {p0}, Ljava/lang/Object;-><init>()V`
                    // compiles to in every constructor ever written.
                    ?: walkUp(runtime, target, ref.name, descriptor)
            }
            if (resolved != null) {
                val (definer, method) = resolved
                if (method.code != null) {
                    return execute(runtime, definer, method, arguments)
                }
            }
        }

        // Everything else is the framework.
        return runtime.surface.call(ref.definingClass, ref.name, descriptor, arguments)
    }

    /**
     * Puts a framework object the surface built into the register that held the placeholder.
     *
     * ## WHY A CONSTRUCTOR NEEDS THIS AND NOTHING ELSE DOES
     *
     * Dex builds an object in two steps: `new-instance` makes it, then `invoke-direct` runs the
     * constructor on it. For a class the app defined that is fine -- the interpreter made a
     * `DexObject` and the constructor fills its fields. For a framework class it is not, because the
     * JVM cannot run a constructor on an object that already exists. [AndroidSurface] handles that by
     * CONSTRUCTING AGAIN with the real arguments and returning the result -- but a constructor's
     * result is not followed by `move-result-object`, so without this the new object was returned to
     * nobody and the register kept the placeholder.
     *
     * What that looked like: `new java.io.File(path)` left a `Fake` in the register, and every later
     * `exists()` and `getAbsolutePath()` on it arrived at the surface as an unimplemented call on a
     * faked File -- which is both wrong and misleading, since `java.io.File` is one of the classes
     * the JVM supports perfectly well.
     *
     * Only `invoke-direct` on a non-dex `<init>` is affected. Everything else either returns through
     * `move-result` or returns nothing.
     */
    private fun adoptConstructed(
        runtime: DexRuntime,
        owner: DexFile.ClassDef,
        methodIndex: Int,
        opcode: Int,
        registers: Array<Any?>,
        slots: List<Int>,
        result: Any?,
    ) {
        if (opcode != 0x70 || result == null) return
        val slot = slots.firstOrNull() ?: return
        if (slot >= registers.size) return
        val ref = methodRefAt(runtime, owner, methodIndex) ?: return
        if (ref.name != "<init>") return
        // A dex-defined class constructs properly in place; replacing its receiver would discard
        // the fields the constructor just set.
        if (runtime.find(ref.definingClass) != null) return
        if (result === registers[slot]) return
        // AN APP OBJECT IS NEVER REPLACED, even when the constructor it just called is a JVM one.
        //
        // `class Worker : Thread()` compiles to `new-instance Worker` then
        // `invoke-direct Thread;-><init>()V`, and the surface answers that by building a real
        // Thread. Putting the Thread in the register would throw away the app's object entirely --
        // its fields, its overridden `run`, its identity. That is what happened to
        // `kotlinx.coroutines`' scheduler workers: later calls arrived at the surface with a receiver
        // of `java.lang.Thread`, so the report named the app's own class as unimplemented while the
        // object it referred to no longer existed.
        //
        // The real object is kept BESIDE the app's, by AndroidSurface, so calls to the superclass's
        // methods have somewhere to go.
        if (registers[slot] is DexObject) {
            runtime.surface.adoptHost(registers[slot], result)
            return
        }
        registers[slot] = result
    }

    private fun walkUp(
        runtime: DexRuntime,
        from: DexFile.ClassDef,
        name: String,
        descriptor: String,
    ): Pair<DexFile.ClassDef, DexFile.EncodedMethod>? {
        var current: DexFile.ClassDef? = from.superClass?.let { runtime.find(it) }
        while (current != null) {
            current.method(name, descriptor)?.let { return current!! to it }
            current = current.superClass?.let { runtime.find(it) }
        }
        return null
    }

    /**
     * The argument values a call receives, from the register slots the instruction names.
     *
     * WIDE ARGUMENTS OCCUPY TWO SLOTS AND ARE PASSED ONCE -- see the comment in [invoke] for why
     * [parameters] rather than the values themselves decide which those are. [receiverFirst] is true
     * for every invoke except `invoke-static`, and the receiver is never wide.
     *
     * Falls back to inspecting the values when the descriptor is unavailable, which happens only for
     * an unresolvable method index -- a case that throws immediately afterwards anyway.
     */
    private fun collect(
        registers: Array<Any?>,
        slots: List<Int>,
        parameters: List<String>?,
        receiverFirst: Boolean,
    ): List<Any?> {
        val out = ArrayList<Any?>(slots.size)
        var index = 0
        if (receiverFirst && index < slots.size) {
            out.add(registers.getOrNull(slots[index]))
            index += 1
        }
        if (parameters == null) {
            while (index < slots.size) {
                val value = registers.getOrNull(slots[index])
                out.add(value)
                index += if (value is Long || value is Double) 2 else 1
            }
            return out
        }
        parameters.forEach { descriptor ->
            if (index >= slots.size) return@forEach
            out.add(registers.getOrNull(slots[index]))
            index += if (descriptor == "J" || descriptor == "D") 2 else 1
        }
        return out
    }

    // ── Constant pool access ────────────────────────────────────────────────
    //
    // An index in an instruction is into the pool of the DEX THE METHOD CAME FROM, which with
    // multidex is not necessarily the first one. The owning class is used to find the right file.

    private fun dexOf(runtime: DexRuntime, owner: DexFile.ClassDef): DexFile? =
        runtime.dexes.firstOrNull { dex -> dex.classes.any { it.descriptor == owner.descriptor } }

    private fun stringAt(runtime: DexRuntime, owner: DexFile.ClassDef, index: Int): String =
        dexOf(runtime, owner)?.strings?.getOrNull(index) ?: ""

    private fun descriptorAt(runtime: DexRuntime, owner: DexFile.ClassDef, index: Int): String =
        dexOf(runtime, owner)?.typeNames?.getOrNull(index) ?: "Ljava/lang/Object;"

    private fun typeAt(runtime: DexRuntime, owner: DexFile.ClassDef, index: Int): String =
        DexFile.descriptorToName(descriptorAt(runtime, owner, index))

    private fun fieldAt(runtime: DexRuntime, owner: DexFile.ClassDef, index: Int): DexFile.FieldRef? =
        dexOf(runtime, owner)?.fields?.getOrNull(index)

    private fun methodRefAt(
        runtime: DexRuntime,
        owner: DexFile.ClassDef,
        index: Int,
    ): DexFile.MethodRef? = dexOf(runtime, owner)?.methods?.getOrNull(index)

    // ── Objects and fields ──────────────────────────────────────────────────

    private fun instantiate(runtime: DexRuntime, descriptor: String): Any? {
        val def = runtime.find(descriptor)
        if (def != null) {
            runtime.ensureInitialised(def)
            return DexObject(def, runtime)
        }
        // A framework class. The surface decides what it is -- a StringBuilder is a real
        // StringBuilder, an ArrayList a real ArrayList, and an android.* type whatever the surface
        // fakes.
        return runtime.surface.construct(descriptor)
    }

    private fun instanceGet(runtime: DexRuntime, target: Any?, field: DexFile.FieldRef?): Any? {
        if (field == null) return null
        return when (target) {
            is DexObject -> target.fields[field.name]
            null -> throw DalvikThrow("NullPointerException reading " + field.name)
            else -> runtime.surface.fieldGet(target, field.name)
        }
    }

    private fun instancePut(
        runtime: DexRuntime,
        target: Any?,
        field: DexFile.FieldRef?,
        value: Any?,
    ) {
        if (field == null) return
        when (target) {
            is DexObject -> target.fields[field.name] = value
            null -> throw DalvikThrow("NullPointerException writing " + field.name)
            else -> runtime.surface.fieldPut(target, field.name, value)
        }
    }

    /** One short line for a value in a frame trace. See the DalvikThrow catch in [execute]. */
    private fun brief(value: Any?): String = when (value) {
        null -> "null"
        is Boxed -> "boxed " + value.value
        is DexObject -> value.type.name.substringAfterLast('.')
        is String -> "\"" + value.take(24) + "\""
        is Array<*> -> "Object[" + value.size + "]"
        is IntArray -> "int[" + value.size + "]"
        is ByteArray -> "byte[" + value.size + "]"
        is CharArray -> "char[" + value.size + "]"
        else -> value.javaClass.simpleName + "(" + value.toString().take(24) + ")"
    }

    private fun staticGet(runtime: DexRuntime, field: DexFile.FieldRef?): Any? {
        if (field == null) return null
        runtime.find(field.definingClass)?.let { runtime.ensureInitialised(it) }
        val key = field.toString()
        if (runtime.find(field.definingClass) != null) {
            // THE SAME RULE AS INSTANCE FIELDS, for the same reason: a static the class's `<clinit>`
            // has not assigned yet is zero, not null. `<clinit>` reads its own statics constantly
            // while it is building them.
            val stored = runtime.staticGet(key)
            return if (stored == null && !runtime.staticHas(key)) zeroOf(field.type) else stored
        }
        return runtime.surface.staticGet(field.definingClass, field.name)
    }

    private fun staticPut(runtime: DexRuntime, field: DexFile.FieldRef?, value: Any?) {
        if (field == null) return
        runtime.staticPut(field.toString(), value)
    }

    private fun isInstance(runtime: DexRuntime, value: Any?, descriptor: String): Boolean {
        if (value == null) return false
        if (value !is DexObject) {
            // A framework object. Compared by the surface, which knows what it handed out.
            return runtime.surface.isInstance(value, descriptor)
        }
        var current: DexFile.ClassDef? = value.type
        while (current != null) {
            if (current.descriptor == descriptor) return true
            if (current.interfaces.contains(descriptor)) return true
            current = current.superClass?.let { runtime.find(it) }
        }
        return false
    }

    // ── Arrays ──────────────────────────────────────────────────────────────

    private fun newArray(descriptor: String, size: Int): Any = when (descriptor) {
        "[I" -> IntArray(size)
        "[J" -> LongArray(size)
        "[Z" -> BooleanArray(size)
        "[B" -> ByteArray(size)
        "[S" -> ShortArray(size)
        "[C" -> CharArray(size)
        "[F" -> FloatArray(size)
        "[D" -> DoubleArray(size)
        else -> arrayOfNulls<Any?>(size)
    }

    private fun lengthOf(array: Any?): Int = when (array) {
        is IntArray -> array.size
        is LongArray -> array.size
        is BooleanArray -> array.size
        is ByteArray -> array.size
        is ShortArray -> array.size
        is CharArray -> array.size
        is FloatArray -> array.size
        is DoubleArray -> array.size
        is Array<*> -> array.size
        is Collection<*> -> array.size
        null -> throw DalvikThrow("NullPointerException reading array length")
        else -> 0
    }

    private fun arrayGet(array: Any?, index: Int): Any? = when (array) {
        is IntArray -> array[index]
        is LongArray -> array[index]
        is BooleanArray -> if (array[index]) 1 else 0
        is ByteArray -> array[index].toInt()
        is ShortArray -> array[index].toInt()
        is CharArray -> array[index].code
        is FloatArray -> array[index]
        is DoubleArray -> array[index]
        is Array<*> -> array[index]
        null -> throw DalvikThrow("NullPointerException reading an array")
        else -> null
    }

    private fun arraySet(array: Any?, index: Int, value: Any?) {
        when (array) {
            is IntArray -> array[index] = asInt(value)
            is LongArray -> array[index] = asLong(value)
            is BooleanArray -> array[index] = asInt(value) != 0
            is ByteArray -> array[index] = asInt(value).toByte()
            is ShortArray -> array[index] = asInt(value).toShort()
            is CharArray -> array[index] = asInt(value).toChar()
            is FloatArray -> array[index] = asFloat(value)
            is DoubleArray -> array[index] = asDoubleBits(value)
            is Array<*> -> {
                @Suppress("UNCHECKED_CAST")
                (array as Array<Any?>)[index] = value
            }
            null -> throw DalvikThrow("NullPointerException writing an array")
        }
    }

    // ── Arithmetic ──────────────────────────────────────────────────────────

    private fun unary(opcode: Int, value: Any?): Any? = when (opcode) {
        0x7B -> -asInt(value)                       // neg-int
        0x7C -> asInt(value).inv()                  // not-int
        0x7D -> -asLong(value)                      // neg-long
        0x7E -> asLong(value).inv()                 // not-long
        // EACH CONVERSION NAMES BOTH TYPES, and the reader has to match the SOURCE while the
        // result is stored as the DESTINATION. `float-to-int` reads bits and writes a number;
        // `int-to-float` reads a number and writes a Float. See asFloat.
        0x7F -> -asFloat(value)                     // neg-float
        0x80 -> -asDoubleBits(value)                // neg-double
        0x81 -> asInt(value).toLong()               // int-to-long
        0x82 -> asInt(value).toFloat()              // int-to-float
        0x83 -> asInt(value).toDouble()             // int-to-double
        0x84 -> asLong(value).toInt()               // long-to-int
        0x85 -> asLong(value).toFloat()             // long-to-float
        0x86 -> asLong(value).toDouble()            // long-to-double
        0x87 -> asFloat(value).toInt()              // float-to-int
        0x88 -> asFloat(value).toLong()             // float-to-long
        0x89 -> asFloat(value).toDouble()           // float-to-double
        0x8A -> asDoubleBits(value).toInt()         // double-to-int
        0x8B -> asDoubleBits(value).toLong()        // double-to-long
        0x8C -> asDoubleBits(value).toFloat()       // double-to-float
        0x8D -> asInt(value).toByte().toInt()       // int-to-byte
        0x8E -> asInt(value).toChar().code          // int-to-char
        0x8F -> asInt(value).toShort().toInt()      // int-to-short
        else -> value
    }

    private fun binary(opcode: Int, left: Any?, right: Any?): Any? = when (opcode) {
        0x90 -> asInt(left) + asInt(right)
        0x91 -> asInt(left) - asInt(right)
        0x92 -> asInt(left) * asInt(right)
        0x93 -> asInt(right).let { if (it == 0) throw DalvikThrow("/ by zero") else asInt(left) / it }
        0x94 -> asInt(right).let { if (it == 0) throw DalvikThrow("% by zero") else asInt(left) % it }
        0x95 -> asInt(left) and asInt(right)
        0x96 -> asInt(left) or asInt(right)
        0x97 -> asInt(left) xor asInt(right)
        // THE SHIFT DISTANCE IS MASKED TO FIVE BITS for an int and six for a long, which is what
        // both Dalvik and the JVM do. Unmasked, `shl 32` is a no-op on the JVM and zero in some
        // other languages, so an app relying on the wrap would quietly compute a different number.
        0x98 -> asInt(left) shl (asInt(right) and 0x1F)
        0x99 -> asInt(left) shr (asInt(right) and 0x1F)
        0x9A -> asInt(left) ushr (asInt(right) and 0x1F)
        0x9B -> asLong(left) + asLong(right)
        0x9C -> asLong(left) - asLong(right)
        0x9D -> asLong(left) * asLong(right)
        0x9E -> asLong(right).let { if (it == 0L) throw DalvikThrow("/ by zero") else asLong(left) / it }
        0x9F -> asLong(right).let { if (it == 0L) throw DalvikThrow("% by zero") else asLong(left) % it }
        0xA0 -> asLong(left) and asLong(right)
        0xA1 -> asLong(left) or asLong(right)
        0xA2 -> asLong(left) xor asLong(right)
        0xA3 -> asLong(left) shl (asInt(right) and 0x3F)
        0xA4 -> asLong(left) shr (asInt(right) and 0x3F)
        0xA5 -> asLong(left) ushr (asInt(right) and 0x3F)
        // FLOAT ARITHMETIC IS DONE IN FLOAT, not in double and rounded. `a / b` computed as doubles
        // and narrowed afterwards is not always the same number as the float division: the double
        // result can round to a different float than the correctly-rounded float result would be.
        // An app that hashes or serialises a float would disagree with the device.
        0xA6 -> asFloat(left) + asFloat(right)
        0xA7 -> asFloat(left) - asFloat(right)
        0xA8 -> asFloat(left) * asFloat(right)
        // NO DIVISION-BY-ZERO CHECK, which is correct for floats and only for floats: IEEE-754
        // division by zero yields an infinity or a NaN, and `x / 0f` throwing would be wrong.
        0xA9 -> asFloat(left) / asFloat(right)
        0xAA -> asFloat(left) % asFloat(right)
        0xAB -> asDoubleBits(left) + asDoubleBits(right)
        0xAC -> asDoubleBits(left) - asDoubleBits(right)
        0xAD -> asDoubleBits(left) * asDoubleBits(right)
        0xAE -> asDoubleBits(left) / asDoubleBits(right)
        0xAF -> asDoubleBits(left) % asDoubleBits(right)
        else -> null
    }

    private fun literalOp(opcode: Int, value: Any?, literal: Int): Any? = when (opcode) {
        0xD0, 0xD8 -> asInt(value) + literal                    // add-int/lit
        // RSUB IS REVERSED, which is the whole reason it exists: `literal - value`, not the other
        // way round. Writing it the obvious way makes every `100 - x` compute `x - 100`.
        0xD1, 0xD9 -> literal - asInt(value)                    // rsub-int
        0xD2, 0xDA -> asInt(value) * literal                    // mul-int/lit
        0xD3, 0xDB -> if (literal == 0) throw DalvikThrow("/ by zero") else asInt(value) / literal
        0xD4, 0xDC -> if (literal == 0) throw DalvikThrow("% by zero") else asInt(value) % literal
        0xD5, 0xDD -> asInt(value) and literal
        0xD6, 0xDE -> asInt(value) or literal
        0xD7, 0xDF -> asInt(value) xor literal
        0xE0 -> asInt(value) shl (literal and 0x1F)
        0xE1 -> asInt(value) shr (literal and 0x1F)
        0xE2 -> asInt(value) ushr (literal and 0x1F)
        else -> null
    }

    /**
     * Whether a register holds something an arithmetic or ordering comparison can read.
     *
     * The interpreter has no type information, so the ONLY way to tell `if-eq` on two ints from
     * `if-eq` on two references is to look at what is in the registers. Anything numeric -- an Int,
     * a Long, a Char, a Boolean, a narrower integer, a float -- compares numerically; anything else
     * is a reference and compares by identity. See the branch opcodes.
     */
    private fun isNumeric(value: Any?): Boolean =
        // A Boxed is deliberately NOT numeric here: it is a reference, and a reference is never
        // zero. See the Boxed class comment -- this single line is the fix for it.
        value !is Boxed && (value is Number || value is Boolean || value is Char)

    /**
     * A register as a `float`.
     *
     * ## A DEX REGISTER IN A FLOAT INSTRUCTION HOLDS IEEE BITS, NOT A NUMBER
     *
     * `const v0, 0x3f400000` is how `0.75f` reaches a register: the instruction loads a raw 32-bit
     * pattern and says nothing about its type. Only the instruction that READS it knows -- `add-int`
     * means 1,061,158,912 and `add-float` means 0.75.
     *
     * Reading it numerically is therefore wrong by an enormous margin, and the way it failed is worth
     * recording because nothing about it looked like a float problem. Kotlin's
     * `mapCapacity(expectedSize)` computes `((expectedSize / 0.75F) + 1.0F).toInt()`; the interpreter
     * read `0.75f`'s bit pattern as an integer, and `mapCapacity(8)` returned **1,065,353,216** --
     * which is `1.0f`'s bit pattern, the same mistake made on the way back out. Every
     * `setOf`/`mapOf` in the app then asked `LinkedHashSet` for a billion-bucket table, and the
     * failure surfaced as `OutOfMemoryError` from `HashMap.resize` inside an app's `<clinit>`, with
     * an eight-element set of file extensions as the apparent cause.
     *
     * A value this interpreter's own arithmetic produced is already a `Float`, so both forms have to
     * be accepted: bits when it came from a `const`, a number when it came from a calculation or
     * from the framework surface.
     */
    private fun asFloat(value: Any?): Float = when (value) {
        is Boxed -> asFloat(value.value)
        is Float -> value
        // The ONLY reinterpreting case. An Int in a float instruction is a bit pattern.
        is Int -> Float.fromBits(value)
        is Double -> value.toFloat()
        is Long -> value.toFloat()
        is Number -> value.toFloat()
        is Char -> value.code.toFloat()
        is Boolean -> if (value) 1f else 0f
        else -> 0f
    }

    /**
     * A register pair as a `double`, by the same rule as [asFloat].
     *
     * A `const-wide` loads 64 bits, which this interpreter keeps as a `Long`; `add-double` reads
     * those bits as a double and `add-long` reads them as a long, from the same register.
     */
    private fun asDoubleBits(value: Any?): Double = when (value) {
        is Boxed -> asDoubleBits(value.value)
        is Double -> value
        is Long -> Double.fromBits(value)
        is Float -> value.toDouble()
        is Int -> value.toDouble()
        is Number -> value.toDouble()
        is Char -> value.code.toDouble()
        is Boolean -> if (value) 1.0 else 0.0
        else -> 0.0
    }

    private fun asInt(value: Any?): Int = when (value) {
        is Boxed -> asInt(value.value)
        is Int -> value
        is Long -> value.toInt()
        is Boolean -> if (value) 1 else 0
        is Byte -> value.toInt()
        is Short -> value.toInt()
        is Char -> value.code
        is Float -> value.toInt()
        is Double -> value.toInt()
        null -> 0
        else -> 0
    }

    private fun asLong(value: Any?): Long = when (value) {
        is Boxed -> asLong(value.value)
        is Long -> value
        is Int -> value.toLong()
        is Double -> value.toLong()
        is Float -> value.toLong()
        null -> 0L
        else -> 0L
    }

    private fun asDouble(value: Any?): Double = when (value) {
        is Boxed -> asDouble(value.value)
        is Double -> value
        is Float -> value.toDouble()
        is Int -> value.toDouble()
        is Long -> value.toDouble()
        null -> 0.0
        else -> 0.0
    }

    /** Opcode names, for a failure message that says which instruction. */
    private fun nameOf(opcode: Int): String = when (opcode) {
        0x24 -> "filled-new-array"
        0x25 -> "filled-new-array/range"
        0x26 -> "fill-array-data"
        0x2B -> "packed-switch"
        0x2C -> "sparse-switch"
        in 0x2D..0x30 -> "cmpl/cmpg float or double"
        0x73 -> "unused"
        in 0xFA..0xFF -> "invoke-polymorphic or custom"
        else -> "unnamed"
    }
}
