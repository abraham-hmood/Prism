package com.prism.launcher.virtualapp

import com.prism.core.PrismPlatform
import java.io.File
import java.lang.reflect.Modifier

/**
 * The Android framework, as much of it as an app's startup touches. PHASE 111.
 *
 * ## THIS IS THE PART THE PHASE SAID WAS THE WHOLE PROBLEM
 *
 * "Point four is the whole problem: the services an Android app talks to -- ActivityManager,
 * PackageManager, WindowManager, ContentResolver -- do not exist on a PC, so hooking them is not
 * enough. They have to be PROVIDED. So the desktop port is: Prism's own minimal Android runtime,
 * hosting the app's dex on the JVM, with the framework surface the app touches implemented against
 * desktop primitives."
 *
 * This is that surface. Every call the interpreter cannot answer from dex code arrives here, and this
 * decides whether it is something to implement, something to fake, or something to refuse.
 *
 * ## IT GROWS APP BY APP, WHICH THE PHASE ALSO SAID
 *
 * "Everything else is the framework surface, and the surface can grow app by app rather than all at
 * once -- an app that only needs Activity, View and SharedPreferences should run long before one that
 * needs a ContentProvider."
 *
 * So an unimplemented call does NOT crash: it is recorded in [missing] and answered with a type-
 * appropriate zero. That is a deliberate choice with a real cost -- an app that depends on the answer
 * behaves wrongly rather than failing -- and the mitigation is that every one is recorded with its
 * full signature, so [report] is a precise list of what the next app needs. Refusing instead would
 * mean the first unimplemented call anywhere stops a startup that was otherwise working, and nothing
 * would ever be learned past that point.
 *
 * ## java.* IS REAL, NOT FAKED
 *
 * `StringBuilder`, `ArrayList`, `HashMap`, `Integer.parseInt` are the JVM's own. Faking those would
 * be re-implementing the standard library for no reason: the dex references them by the same names
 * the JVM has, so they are reflected into directly. Only `android.*` is synthesised.
 */
class AndroidSurface(
    /** The package being virtualized, for anything that asks its own name. */
    val packageName: String,
    /** Where the app's files go. The vault's live directory. */
    val filesDir: File,
    /** Where its cache goes. */
    val cacheDir: File,
    /** The manifest, for a `PackageManager` question about the app itself. */
    val manifest: ApkManifest?,
) {

    private companion object {
        const val TAG = "PrismAndroidSurface"
    }

    /** Calls this surface could not answer, with their full signatures. See the class comment. */
    private val missingCalls = LinkedHashMap<String, Int>()

    /** Everything the app wrote through `android.util.Log`, so a startup can be read afterwards. */
    private val logLines = ArrayList<String>()

    val missing: Map<String, Int> get() = missingCalls

    fun logcat(): List<String> = synchronized(logLines) { logLines.toList() }

    /**
     * A fake framework object.
     *
     * A named bag rather than one class per Android type: the interpreter only ever reaches these
     * through [call] and [fieldGet], so what matters is that the surface can tell them apart.
     */
    class Fake(val type: String) {
        val fields = HashMap<String, Any?>()
        override fun toString(): String = DexFile.descriptorToName(type) + "(faked)"
    }

    /**
     * An `AtomicReferenceFieldUpdater` over an interpreted object's field.
     *
     * ## WHY THE REAL JVM CLASS CANNOT BE USED
     *
     * `AtomicReferenceFieldUpdater.newUpdater(Holder::class.java, ...)` needs a real
     * `java.lang.Class` whose real field it can address through `Unsafe`. An interpreted object is a
     * [DalvikInterpreter.DexObject] -- a name-to-value map -- and its "class" is a dex `ClassDef`
     * the JVM has never heard of. There is no field for `Unsafe` to find, so the real call throws and
     * the surface recorded it as a miss, which returned null.
     *
     * THAT NULL WAS THE BUG BEHIND THE WHOLE FIRST STARTUP FAILURE. `kotlinx.coroutines` keeps its
     * updaters in `static final` fields initialised in `<clinit>`; a null updater meant a null from
     * every later `get`, and the first non-null Kotlin parameter it reached threw inside
     * `Intrinsics.createParameterIsNullExceptionMessage` -- seventeen frames away from the cause.
     *
     * ## Atomicity is real, not pretended
     *
     * Every operation synchronizes on the target object. That is a heavier guarantee than a CAS and a
     * weaker one than the real updater only in that two different updaters over the same object
     * serialise against each other -- which is correctness-preserving. Pretending instead, with a
     * plain read and write, would make `compareAndSet` lie, and a `compareAndSet` that lies is how a
     * coroutine gets resumed twice.
     */
    class FieldUpdater(val fieldName: String, val kind: Kind) {

        enum class Kind { REFERENCE, INT, LONG }

        private fun zero(): Any? = when (kind) {
            Kind.REFERENCE -> null
            Kind.INT -> 0
            Kind.LONG -> 0L
        }

        fun get(target: Any?): Any? = when (target) {
            null -> zero()
            is DalvikInterpreter.DexObject -> synchronized(target) {
                if (target.fields.containsKey(fieldName)) target.fields[fieldName] else zero()
            }
            is Fake -> synchronized(target) { target.fields[fieldName] ?: zero() }
            else -> synchronized(target) {
                runCatching {
                    target.javaClass.getDeclaredField(fieldName)
                        .apply { isAccessible = true }.get(target)
                }.getOrElse { zero() }
            }
        }

        fun set(target: Any?, value: Any?) {
            when (target) {
                null -> Unit
                is DalvikInterpreter.DexObject -> synchronized(target) {
                    target.fields[fieldName] = value
                }
                is Fake -> synchronized(target) { target.fields[fieldName] = value }
                else -> synchronized(target) {
                    runCatching {
                        target.javaClass.getDeclaredField(fieldName)
                            .apply { isAccessible = true }.set(target, value)
                    }
                }
            }
        }

        /** The whole reason this class exists rather than a pair of accessors. */
        fun compareAndSet(target: Any?, expect: Any?, update: Any?): Boolean {
            if (target == null) return false
            synchronized(target) {
                val current = getUnsynchronized(target)
                // IDENTITY FIRST, THEN EQUALITY. The real reference updater compares by identity
                // only, but the interpreter boxes every int afresh, so two Integers holding 5 are
                // `!==` -- and a numeric CAS that always failed would spin forever.
                val same = current === expect ||
                    (current is Number && expect is Number && current == expect) ||
                    (current == null && expect == null)
                if (!same) return false
                setUnsynchronized(target, update)
                return true
            }
        }

        fun getAndSet(target: Any?, update: Any?): Any? {
            if (target == null) return zero()
            synchronized(target) {
                val previous = getUnsynchronized(target)
                setUnsynchronized(target, update)
                return previous
            }
        }

        fun getAndAdd(target: Any?, delta: Any?): Any? {
            if (target == null) return zero()
            synchronized(target) {
                val previous = getUnsynchronized(target)
                val sum: Any = when (kind) {
                    Kind.LONG -> ((previous as? Number)?.toLong() ?: 0L) +
                        ((delta as? Number)?.toLong() ?: 0L)
                    else -> ((previous as? Number)?.toInt() ?: 0) +
                        ((delta as? Number)?.toInt() ?: 0)
                }
                setUnsynchronized(target, sum)
                return previous
            }
        }

        private fun getUnsynchronized(target: Any): Any? = when (target) {
            is DalvikInterpreter.DexObject ->
                if (target.fields.containsKey(fieldName)) target.fields[fieldName] else zero()
            is Fake -> target.fields[fieldName] ?: zero()
            else -> runCatching {
                target.javaClass.getDeclaredField(fieldName)
                    .apply { isAccessible = true }.get(target)
            }.getOrElse { zero() }
        }

        private fun setUnsynchronized(target: Any, value: Any?) {
            when (target) {
                is DalvikInterpreter.DexObject -> target.fields[fieldName] = value
                is Fake -> target.fields[fieldName] = value
                else -> runCatching {
                    target.javaClass.getDeclaredField(fieldName)
                        .apply { isAccessible = true }.set(target, value)
                }
            }
        }

        override fun toString(): String = "FieldUpdater(" + fieldName + ", " + kind + ")"
    }

    /**
     * A `Looper`/`Handler` that runs everything inline.
     *
     * ## INLINE, NOT QUEUED, AND THAT IS A DECISION RATHER THAN A SHORTCUT
     *
     * A real `Handler.post` queues onto a thread that is already running a `Looper.loop()`. A
     * virtualized app on the desktop has no such thread: Prism's own AWT event thread is the nearest
     * equivalent, and dispatching an interpreted `Runnable` onto it would run app code on the thread
     * that paints Prism's window -- so an app in an infinite loop would freeze the host, which is
     * exactly what the sandbox exists to prevent.
     *
     * Running inline keeps app code on the app's own thread. The observable difference is ordering:
     * `post` from inside a posted task runs before the outer task finishes rather than after. That
     * breaks an app relying on post to defer past the current frame, which is recorded here as a
     * known limitation rather than hidden.
     *
     * `postDelayed` IGNORES THE DELAY rather than sleeping. Sleeping would hold the app's thread for
     * a delay an app may well set to minutes, and a startup that takes minutes is indistinguishable
     * from a hang.
     */
    class VirtualHandler(val runtime: () -> DalvikInterpreter.DexRuntime?) {
        var posted: Int = 0
            private set

        fun post(task: Any?): Boolean {
            posted++
            runCatching { callRunnable(task) }
            return true
        }

        private fun callRunnable(task: Any?) {
            when (task) {
                null -> Unit
                is Runnable -> task.run()
                is DalvikInterpreter.DexObject -> {
                    val live = runtime() ?: return
                    DalvikInterpreter.invokeVirtual(live, task, "run", "()V", listOf(task))
                }
                else -> Unit
            }
        }
    }

    /** Set once the interpreter exists, so a posted `Runnable` can be run. See [VirtualHandler]. */
    var runtime: DalvikInterpreter.DexRuntime? = null

    /**
     * One JVM proxy per interpreted object, so the same app object is the same JVM object.
     *
     * Identity matters: an app that registers a listener and later removes it passes the same
     * reference both times, and `removeListener` compares by identity. A fresh proxy each time would
     * make every removal silently fail.
     */
    private val proxies = HashMap<DalvikInterpreter.DexObject, Any>()

    /**
     * The reverse of [proxies], so a proxy coming BACK can become the app's object again.
     *
     * ## A PROXY MUST NEVER REACH INTERPRETED CODE
     *
     * `Collections.sort(list)` calls `a.compareTo(b)` on two proxies. The receiver is translated back
     * -- the handler knows which object it belongs to -- but the ARGUMENT arrived as the other proxy
     * and was passed straight into the interpreter, where `iget-object this.data` found no field on
     * a `jdk.proxy2.$Proxy15` and read null.
     *
     * The failure surfaced as "NullPointerException reading array length" inside `okio.ByteString`,
     * four frames from the sort, with a trace that showed `$Proxy15` as an argument and gave no clue
     * that it should have been unwrapped. Keeping both directions of the mapping is what makes the
     * boundary symmetric.
     *
     * IDENTITY-KEYED, because a proxy's `equals` is answered by the app's own code and two distinct
     * objects may well compare equal.
     */
    private val proxyOwners = java.util.IdentityHashMap<Any, DalvikInterpreter.DexObject>()

    /**
     * The real JVM object standing behind an app object that extends a JVM class.
     *
     * ## PROXIES COVER INTERFACES; THIS COVERS CLASSES
     *
     * `java.lang.reflect.Proxy` cannot extend a class, so `class Worker : Thread()` has no proxy. But
     * the app still calls `setName`, `setDaemon` and `start` on it, and those are `Thread`'s methods
     * -- there is nothing in the app's dex to dispatch them to.
     *
     * So the app object and a real instance of its JVM superclass exist side by side: the app object
     * holds the fields and the overridden methods, the host instance holds whatever state the
     * superclass keeps, and a call the dex cannot answer is tried against the host. For a `Thread`
     * that is the difference between a scheduler that starts and one that does not.
     */
    private val hosts = HashMap<DalvikInterpreter.DexObject, Any>()

    /** Called by the interpreter when an app object's constructor calls a JVM superclass's. */
    fun adoptHost(target: Any?, host: Any?) {
        val app = target as? DalvikInterpreter.DexObject ?: return
        if (host == null || host is DalvikInterpreter.DexObject) return
        hosts.putIfAbsent(app, host)
    }

    /**
     * An interpreted object, as something a real JVM method will accept.
     *
     * ## THE LAST THING THE SURFACE COULDN'T DO
     *
     * An app hands its own objects to the framework constantly: a `Runnable` to an executor, a
     * `ThreadFactory` to `Executors`, a `Comparator` to `sort`, an `UncaughtExceptionHandler` to a
     * thread. Those are interpreted objects -- field maps -- and a JVM method with a declared
     * parameter type will not take one. Every such call failed, and the failures were the confusing
     * kind: `Executors.newScheduledThreadPool(2, factory)` was recorded as an unimplemented call on
     * `java.lang.Integer`, which is neither the class nor the problem.
     *
     * `java.lang.reflect.Proxy` is the answer and it is exact. A proxy implementing the interfaces
     * the app's class declares is a real JVM object of those types, and every call on it comes back
     * here and goes into the interpreter. Real `ExecutorService`, real `Thread`, real app code.
     *
     * ## ONLY INTERFACES, WHICH IS A REAL LIMIT AND NOT A HIDDEN ONE
     *
     * `Proxy` cannot extend a class, so an interpreted subclass of an abstract JVM class -- a
     * `TimerTask`, an `InputStream` -- cannot be proxied. Those arrive as a recorded miss naming the
     * method, which is the honest outcome: faking them would hand the JVM an object that answers
     * every call with zero.
     */
    private fun proxyFor(target: DalvikInterpreter.DexObject): Any? {
        proxies[target]?.let { return it }
        val live = runtime ?: return null

        // The JVM interfaces the app's class declares, walking its own hierarchy: an interface the
        // app defined itself is not one the JVM has, so it is skipped rather than refused.
        val interfaces = LinkedHashSet<Class<*>>()
        var current: DexFile.ClassDef? = target.type
        while (current != null) {
            current.interfaces.forEach { descriptor ->
                runCatching { Class.forName(DexFile.descriptorToName(descriptor)) }
                    .getOrNull()
                    ?.takeIf { it.isInterface }
                    ?.let { interfaces.add(it) }
            }
            current = current.superClass?.let { live.find(it) }
        }
        if (interfaces.isEmpty()) return null

        val proxy = runCatching {
            java.lang.reflect.Proxy.newProxyInstance(
                AndroidSurface::class.java.classLoader,
                interfaces.toTypedArray(),
            ) { _, method, args ->
                // BOTH DIRECTIONS ARE TRANSLATED: proxies coming in become app objects again, and
                // the answer going out is conformed to the declared return type.
                val values = listOf<Any?>(target) +
                    (args?.map { fromJvm(it) } ?: emptyList<Any?>())
                val descriptor = "(" +
                    method.parameterTypes.joinToString("") { descriptorOf(it) } +
                    ")" + descriptorOf(method.returnType)
                // Object's own methods are answered here rather than dispatched: an app class that
                // does not override toString has no `toString` in its dex, and dispatching would
                // return null where the JVM requires a String.
                when {
                    method.name == "hashCode" && values.size == 1 ->
                        System.identityHashCode(target)
                    method.name == "equals" && values.size == 2 ->
                        args?.getOrNull(0) === proxies[target] || args?.getOrNull(0) === target
                    method.name == "toString" && values.size == 1 -> target.toString()
                    else -> {
                        // AN INTERPRETED RETURN VALUE IS CONFORMED AND, IF IT IS AN APP OBJECT,
                        // PROXIED. `EmptyList.iterator()` returns the app's own iterator object and
                        // JVM code casts it to `java.util.Iterator` straight away -- which a field
                        // map is not. Translating only the arguments got the collection in and then
                        // failed one call later with a ClassCastException naming a JDK interface.
                        conform(
                            DalvikInterpreter.invokeVirtual(
                                live, target, method.name, descriptor, values,
                            ),
                            method.returnType,
                        ) ?: defaultFor(method.returnType)
                    }
                }
            }
        }.getOrNull() ?: return null

        proxies[target] = proxy
        proxyOwners[proxy] = target
        return proxy
    }

    /**
     * A value from JVM code, as something interpreted code can use.
     *
     * The inverse of [forJvm]: a proxy becomes the app object it stands for, and an array holding
     * proxies is copied with each element translated. See [proxyOwners].
     */
    private fun fromJvm(value: Any?): Any? = unproxy(value)

    /**
     * The app object a proxy stands for, or the value unchanged.
     *
     * ## SCALAR ONLY, AND THAT IS THE CORRECTION OF A WRONG FIRST ATTEMPT
     *
     * The first version also rewrote the elements of a returned array, and that broke more than it
     * fixed. `Arrays.copyOf(proxies)` returns an array of proxies which is then handed to
     * `ArrayList`, sorted, and iterated by JVM code -- so unwrapping the elements on the way out put
     * raw app objects inside a JVM collection and `Collections.sort` threw
     * "DexObject cannot be cast to Comparable" again, from the other direction.
     *
     * A JVM container keeps whatever was put into it, and nothing at the return boundary knows which
     * side will read it next. So containers are left alone and the translation happens where
     * interpreted code ACTUALLY touches a value: the receiver and arguments of an `invoke`, and a
     * field read. Those are choke points the interpreter already goes through.
     */
    fun unproxy(value: Any?): Any? {
        if (value == null || proxyOwners.isEmpty()) return value
        return proxyOwners[value] ?: value
    }

    /** True once any app object has been proxied, so the common case costs one field read. */
    val hasProxies: Boolean get() = proxyOwners.isNotEmpty()

    /**
     * An interpreted value as the type a JVM method signature declares.
     *
     * ## THE INTERPRETER HAS NO TYPES AND A PROXY'S CALLER DOES
     *
     * An app's `hasNext()` returns `1`, because that is how a dex register holds true. A proxy
     * declared to return `boolean` must hand back a `java.lang.Boolean`, and the JVM enforces that
     * at the proxy boundary -- so returning the Int threw
     * `ClassCastException: Integer cannot be cast to Boolean`, reported against
     * `kotlin.collections.IndexingIterator.hasNext`, a method that is perfectly correct.
     *
     * Every primitive needs this, not just boolean: a `char` return is an Int in the register, and a
     * `long` method whose body happened to produce an Int would fail the same way.
     */
    private fun conform(value: Any?, type: Class<*>): Any? {
        val raw = DalvikInterpreter.Boxed.unwrap(value)
        if (!type.isPrimitive) {
            return if (raw is DalvikInterpreter.DexObject) proxyFor(raw) ?: raw else raw
        }
        if (type == Void.TYPE) return null
        val number = raw as? Number
        return when (type) {
            Boolean::class.javaPrimitiveType ->
                raw as? Boolean ?: ((number?.toInt() ?: 0) != 0)
            Char::class.javaPrimitiveType ->
                raw as? Char ?: Char(number?.toInt()?.and(0xFFFF) ?: 0)
            Byte::class.javaPrimitiveType -> number?.toByte() ?: 0.toByte()
            Short::class.javaPrimitiveType -> number?.toShort() ?: 0.toShort()
            Int::class.javaPrimitiveType -> number?.toInt() ?: 0
            Long::class.javaPrimitiveType -> number?.toLong() ?: 0L
            // BITS WHEN IT CAME FROM A `const` -- the same rule as everywhere else a float crosses
            // this boundary. See DalvikInterpreter.asFloat.
            Float::class.javaPrimitiveType ->
                (raw as? Int)?.let { Float.fromBits(it) } ?: number?.toFloat() ?: 0.0f
            Double::class.javaPrimitiveType ->
                (raw as? Long)?.let { Double.fromBits(it) } ?: number?.toDouble() ?: 0.0
            else -> raw
        }
    }

    /**
     * A `java.lang.Class` as a dex type descriptor.
     *
     * `Class.getName()` is NOT a source name for every type: an array comes back already in
     * descriptor form (`[Ljava.lang.String;`) with dots instead of slashes, so running it through
     * the source-name converter produces `L[Ljava.lang.String;;` -- a descriptor that matches no
     * method and makes every array-taking interface method fail to dispatch.
     */
    private fun descriptorOf(type: Class<*>): String = when {
        type.isArray -> "[" + descriptorOf(type.componentType)
        type.isPrimitive -> DexFile.nameToDescriptor(type.name)
        else -> "L" + type.name.replace('.', '/') + ";"
    }

    /**
     * A value from a register, as something a real JVM method will accept.
     *
     * Unwraps a [DalvikInterpreter.Boxed], proxies an interpreted object, and -- because an
     * `Object[]` is how `vararg`, `toArray` and `Arrays.copyOf` move interpreted objects into JVM
     * code -- proxies an array's ELEMENTS as well.
     *
     * Missing the array case failed one call later and somewhere else: okhttp's `<clinit>` builds a
     * list from an `Array<ByteString>` and sorts it, and `Collections.sort` threw
     * "DexObject cannot be cast to Comparable" -- a JDK interface named in the error, nothing in the
     * trace pointing at the array that carried them in.
     *
     * The array is COPIED rather than converted in place: the app may still be holding it, and
     * replacing its elements with proxies would mean the app's own code sees proxies where it put
     * its objects.
     */
    private fun forJvm(argument: Any?): Any? =
        when (val unwrapped = DalvikInterpreter.Boxed.unwrap(argument)) {
            // THE HOST INSTANCE COMES FIRST, where there is one. An app's `Thread` subclass has a
            // real `java.lang.Thread` standing behind it (see [hosts]) and no proxy, because Proxy
            // cannot extend a class -- so `LockSupport.unpark(thread)` was handed the field map and
            // refused it. The host IS a Thread, which is exactly what such a call wants.
            is DalvikInterpreter.DexObject ->
                hosts[unwrapped] ?: proxyFor(unwrapped) ?: unwrapped
            is Array<*> ->
                if (unwrapped.any { it is DalvikInterpreter.DexObject }) {
                    Array(unwrapped.size) { index -> forJvm(unwrapped[index]) }
                } else {
                    unwrapped
                }
            else -> unwrapped
        }

    /** What a proxied method returns when the app's own method returned nothing. */
    private fun defaultFor(type: Class<*>): Any? = when {
        !type.isPrimitive -> null
        type == Void.TYPE -> null
        type == Boolean::class.javaPrimitiveType -> false
        type == Long::class.javaPrimitiveType -> 0L
        type == Float::class.javaPrimitiveType -> 0.0f
        type == Double::class.javaPrimitiveType -> 0.0
        type == Char::class.javaPrimitiveType -> Char(0)
        type == Byte::class.javaPrimitiveType -> 0.toByte()
        type == Short::class.javaPrimitiveType -> 0.toShort()
        else -> 0
    }

    private val mainLooper = Fake("Landroid/os/Looper;")

    // ── Construction ────────────────────────────────────────────────────────

    /**
     * `new-instance` of a class the app did not define.
     *
     * The JVM's own where there is one, a [Fake] where there is not. `java.*` and `kotlin.*` resolve
     * by reflection against the real class, which is why an app's `StringBuilder` behaves exactly as
     * it would on a device.
     */
    fun construct(descriptor: String): Any? {
        val name = DexFile.descriptorToName(descriptor)
        if (name.startsWith("android.") || name.startsWith("androidx.")) {
            return Fake(descriptor)
        }
        return runCatching {
            val type = Class.forName(name)
            // No-argument constructor only. A dex `new-instance` is always followed by an
            // `invoke-direct` to the constructor, so the arguments arrive separately -- and calling
            // a parameterised constructor here would construct the object twice.
            type.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        }.getOrElse { Fake(descriptor) }
    }

    // ── Calls ───────────────────────────────────────────────────────────────

    /**
     * Answers a call into the framework.
     *
     * Ordered: the handful of `android.*` calls that genuinely matter for a startup, then real
     * reflection into `java.*`, then the recorded miss.
     */
    fun call(
        definingClass: String,
        name: String,
        descriptor: String,
        arguments: List<Any?>,
    ): Any? {
        // THE DESCRIPTOR IS THE ONLY PLACE THE RETURN TYPE EXISTS, so the boxing decision is made
        // here and nowhere else. See DalvikInterpreter.Boxed for why it has to be made at all.
        val returnDescriptor = descriptor.substringAfterLast(')')
        return retype(answer(definingClass, name, descriptor, arguments), returnDescriptor)
    }

    /**
     * Matches a value to what the method's descriptor says it returns.
     *
     * A reference return holding a primitive is wrapped so `if-eqz` sees a reference; a primitive
     * return holding a wrapper is unwrapped so the arithmetic opcodes see a number. Both directions
     * are needed: reflection hands back `Integer` for an `int` method and for an `Integer` method
     * alike, and only the descriptor distinguishes them.
     */
    private fun retype(rawValue: Any?, returnDescriptor: String): Any? {
        // A PROXY COMING BACK OUT OF A JVM METHOD IS STILL A PROXY. `list.get(0)` returns whatever
        // was put in, which for an app's own object is the proxy -- and handing that to interpreted
        // code puts a `$Proxy` where the dex expects its own object. See [fromJvm].
        val value = fromJvm(rawValue)
        if (value == null) return null
        val isReference = returnDescriptor.startsWith("L") || returnDescriptor.startsWith("[")
        return when {
            isReference && DalvikInterpreter.Boxed.boxable(value) &&
                value !is DalvikInterpreter.Boxed -> DalvikInterpreter.Boxed(value)
            !isReference -> DalvikInterpreter.Boxed.unwrap(value)
            else -> value
        }
    }

    private fun answer(
        definingClass: String,
        name: String,
        descriptor: String,
        arguments: List<Any?>,
    ): Any? {
        val owner = DexFile.descriptorToName(definingClass)

        // java.lang.Object's constructor, which every constructor in every class calls first.
        if (owner == "java.lang.Object" && name == "<init>") return null

        intrinsics(owner, name, arguments)?.let { return it.value }
        refused(owner, name)?.let { return it.value }
        objectCall(owner, name, arguments)?.let { return it.value }
        updaterCall(owner, name, arguments)?.let { return it.value }
        androidCall(owner, name, descriptor, arguments)?.let { return it.value }

        // Everything in java.* and kotlin.* is the JVM's own.
        if (!owner.startsWith("android.") && !owner.startsWith("androidx.")) {
            reflectCall(owner, name, arguments, descriptor)?.let { return it.value }
        }

        // THE RECEIVER'S REAL TYPE IS IN THE REPORT, because the signature alone does not say why
        // the call missed. "CoroutineContext.get missed" could mean the interface is unimplemented
        // or that virtual dispatch failed to find the override the receiver actually has -- entirely
        // different fixes, and the receiver's type is what distinguishes them.
        val receiverType = arguments.firstOrNull()?.let {
            when (it) {
                is DalvikInterpreter.DexObject -> it.type.name + " (interpreted)"
                is Fake -> it.toString()
                else -> it.javaClass.name
            }
        }
        record(
            owner + "." + name + descriptor +
                (if (receiverType == null) "" else "   on " + receiverType),
        )
        return zeroFor(descriptor.substringAfterLast(')'))
    }

    /** Open preference files, by name. See the getSharedPreferences branch above. */
    private val preferences = HashMap<String, VirtualPreferences>()

    private fun preferencesNamed(rawName: String?): VirtualPreferences {
        // A preferences name reaches the filesystem, so a name containing a separator or a `..`
        // would put the file outside the vault. Everything but word characters, dash and dot is
        // replaced -- which keeps ordinary names readable and makes a hostile one harmless.
        val safe = (rawName ?: "default").replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "default" }
        return preferences.getOrPut(safe) {
            VirtualPreferences(File(filesDir, "shared_prefs/" + safe + ".xml"))
        }
    }

    /** A box, so a legitimate null answer is not mistaken for "not handled". */
    private class Answer(val value: Any?)

    /**
     * Kotlin's `Intrinsics`, answered here even though the app ships its own copy.
     *
     * ## THE APP'S OWN COPY CANNOT WORK IN AN INTERPRETER
     *
     * `Intrinsics.checkNotNullParameter(value, "name")` throws when `value` is null, and it builds
     * the message by READING ITS OWN STACK TRACE to find the method whose parameter was null:
     *
     * ```java
     * StackTraceElement[] stackTrace = new Throwable().getStackTrace();
     * StackTraceElement caller = stackTrace[1];      // and then element 1's class and method
     * ```
     *
     * Interpreted code has no JVM stack of its own. The stack at that point is `execute` and
     * `invoke` alternating, as deep as the app's call stack happens to be -- so the index is
     * meaningless and, when the app's stack is shallow, out of bounds. Every genuine null-check
     * failure therefore arrived as `ArrayIndexOutOfBoundsException: index 51 out of bounds for
     * length 51` from inside Kotlin's message builder, which hid what was actually null behind an
     * exception from an unrelated place. Four of the failures in one startup were this.
     *
     * Answering here throws the NullPointerException the app expects, with the parameter name the
     * dex already carries as a string constant -- which is the information Kotlin was trying to
     * recover from the stack in the first place.
     *
     * The rest of `Intrinsics` is arithmetic and comparison helpers that interpret perfectly well;
     * only the ones that throw, and the one that compares, are taken over here.
     */
    private fun intrinsics(owner: String, name: String, arguments: List<Any?>): Answer? {
        if (owner != "kotlin.jvm.internal.Intrinsics") return null
        val first = DalvikInterpreter.Boxed.unwrap(arguments.getOrNull(0))
        val second = DalvikInterpreter.Boxed.unwrap(arguments.getOrNull(1))

        return when (name) {
            // The null checks. The second argument is the name, which is what makes the message
            // useful -- and the reason the real implementation reads the stack is to get it.
            "checkNotNullParameter", "checkParameterIsNotNull" ->
                if (first == null) {
                    throw DalvikInterpreter.DalvikThrow(
                        "NullPointerException: parameter '" + second + "' was null",
                    )
                } else {
                    Answer(null)
                }
            "checkNotNullExpressionValue", "checkExpressionValueIsNotNull" ->
                if (first == null) {
                    throw DalvikInterpreter.DalvikThrow(
                        "NullPointerException: " + second + " must not be null",
                    )
                } else {
                    Answer(null)
                }
            "checkNotNull", "checkReturnedValueIsNotNull", "checkFieldIsNotNull" ->
                if (first == null) {
                    throw DalvikInterpreter.DalvikThrow(
                        "NullPointerException" + (if (second == null) "" else ": " + second),
                    )
                } else {
                    Answer(null)
                }
            "throwNpe", "throwJavaNpe" ->
                throw DalvikInterpreter.DalvikThrow("NullPointerException")
            "throwUninitializedProperty", "throwUninitializedPropertyAccessException" ->
                throw DalvikInterpreter.DalvikThrow(
                    "UninitializedPropertyAccessException: lateinit property " + first +
                        " has not been initialized",
                )
            "throwIllegalArgument", "throwIllegalArgumentException" ->
                throw DalvikInterpreter.DalvikThrow("IllegalArgumentException: " + first)
            "throwIllegalState", "throwIllegalStateException" ->
                throw DalvikInterpreter.DalvikThrow("IllegalStateException: " + first)
            "throwAssert" -> throw DalvikInterpreter.DalvikThrow("AssertionError: " + first)
            "throwUndefinedForReified" ->
                throw DalvikInterpreter.DalvikThrow(
                    "UnsupportedOperationException: a reified type parameter was not inlined",
                )

            // areEqual compares by `equals`, and an interpreted object's `equals` is in its own dex
            // -- so this is NOT answered here, to let the app's override run.
            "areEqual" -> if (first is DalvikInterpreter.DexObject ||
                second is DalvikInterpreter.DexObject
            ) {
                null
            } else {
                Answer(if (first == second) 1 else 0)
            }

            // The message builder itself, in case the app calls it directly.
            "createParameterIsNullExceptionMessage" ->
                Answer("Parameter specified as non-null is null: parameter " + first)

            else -> null
        }
    }

    /**
     * The members an interpreted object inherits from `java.lang.Object` and `java.lang.Enum`.
     *
     * ## AN INTERPRETED ENUM HAS NO JVM CLASS TO ASK
     *
     * `java.lang.Enum` is where `name()`, `ordinal()` and `compareTo` live, and an app's enum does
     * not define them -- it inherits them. The interpreted object is a field map, so there is nothing
     * for reflection to call and the surface has to be the superclass.
     *
     * The two fields come from `Enum`'s own constructor, which every enum's `<clinit>` calls with the
     * constant's name and position. Storing them there and reading them back here is exactly what
     * the real `Enum` does.
     *
     * Getting this wrong is quiet and far-reaching: Kotlin compiles a `when` over an enum into a
     * lookup table indexed by `ordinal()`, built in a synthetic `WhenMappings.<clinit>`. A missing
     * `ordinal` made `values()` return null, `WhenMappings` threw on `array-length`, and every `when`
     * over that enum afterwards failed -- which first showed up as a NullPointerException inside a
     * class the app never wrote.
     */
    private fun objectCall(owner: String, name: String, arguments: List<Any?>): Answer? {
        val receiver = arguments.firstOrNull()

        // Enum's constructor: the name and ordinal of the constant being built.
        if (owner == "java.lang.Enum" && name == "<init>") {
            val target = receiver as? DalvikInterpreter.DexObject ?: return Answer(null)
            target.fields["\$name"] = arguments.getOrNull(1)
            target.fields["\$ordinal"] = arguments.getOrNull(2)
            return Answer(null)
        }

        // Array.clone(), which is how every generated `values()` returns a private $VALUES array
        // without letting the caller modify it. Reflection cannot do this: clone() is protected on
        // Object and declared public only on the array types themselves.
        if (name == "clone" && receiver != null && receiver.javaClass.isArray) {
            return Answer(
                when (receiver) {
                    is Array<*> -> receiver.copyOf()
                    is IntArray -> receiver.copyOf()
                    is LongArray -> receiver.copyOf()
                    is ByteArray -> receiver.copyOf()
                    is CharArray -> receiver.copyOf()
                    is ShortArray -> receiver.copyOf()
                    is BooleanArray -> receiver.copyOf()
                    is FloatArray -> receiver.copyOf()
                    is DoubleArray -> receiver.copyOf()
                    else -> receiver
                },
            )
        }

        // A class the JVM does not have. See DalvikInterpreter.DexClass.
        (receiver as? DalvikInterpreter.DexClass)?.let { type ->
            return when (name) {
                "getName", "getTypeName", "getCanonicalName" -> Answer(type.name)
                "getSimpleName" -> Answer(type.simpleName)
                "toString" -> Answer(type.toString())
                "hashCode" -> Answer(type.hashCode())
                "equals" -> Answer(if (type == arguments.getOrNull(1)) 1 else 0)
                // A dex class has no JVM ClassLoader, and an app asking for one is almost always
                // about to look up a resource -- which this surface answers elsewhere. Null is what
                // a bootstrap class returns, so it is a value the app already has to handle.
                "getClassLoader" -> Answer(null)
                "isInterface", "isArray", "isPrimitive", "isEnum", "isAnnotation" -> Answer(0)
                else -> null
            }
        }

        val target = receiver as? DalvikInterpreter.DexObject ?: return null

        // The JVM superclass's own method, against the host instance. Tried BEFORE the Object/Enum
        // fallbacks below so a real `Thread.getName` wins over the generic one.
        hosts[target]?.let { host ->
            if (name != "<init>") {
                reflectCall(host.javaClass.name, name, listOf(host) + arguments.drop(1))
                    ?.let { return it }
            }
        }

        val ordinal = (target.fields["\$ordinal"] as? Number)?.toInt()
        val constantName = target.fields["\$name"]?.toString()

        return when (name) {
            "ordinal" -> Answer(ordinal ?: 0)
            "name" -> Answer(constantName ?: target.type.name.substringAfterLast('.'))
            "toString" -> Answer(constantName ?: target.toString())
            "hashCode" -> Answer(System.identityHashCode(target))
            // IDENTITY, which is right for an enum and right for the default Object.equals -- and a
            // DexObject never overrides it here, because a class that DID override equals has the
            // method in its own dex and the call never reaches this surface.
            "equals" -> Answer(if (target === arguments.getOrNull(1)) 1 else 0)
            "compareTo" -> {
                val other = arguments.getOrNull(1) as? DalvikInterpreter.DexObject
                val otherOrdinal = (other?.fields?.get("\$ordinal") as? Number)?.toInt() ?: 0
                Answer((ordinal ?: 0).compareTo(otherOrdinal))
            }
            // A REAL CLASS-SHAPED ANSWER, not the name. `x.javaClass.name` is how most apps build a
            // log tag, and returning the name here made the following `getName()` a call on a String.
            "getClass" -> Answer(DalvikInterpreter.DexClass(target.type.name))
            else -> null
        }
    }

    /**
     * Calls that are answered by DOING NOTHING, on purpose, because doing them would escape the
     * sandbox.
     *
     * These are not gaps and must not be reported as gaps -- a report is a request for work, and the
     * work here would be a hole. The app gets the success it expects and the host is untouched.
     *
     *  * `Thread.setDefaultUncaughtExceptionHandler` would put an interpreted handler on the JVM
     *    PRISM ITSELF runs on, so a crash anywhere in Prism would call into the virtualized app.
     *    An app's crash reporter installing this is completely ordinary, which is why it needs an
     *    answer rather than a miss.
     *  * `System.exit` and `Runtime.halt` would take Prism down with the app.
     *  * `System.setProperty`, `setOut` and `setErr` are host-global state.
     *  * `Runtime.exec` and `ProcessBuilder.start` would run a real process with Prism's privileges,
     *    which is the sandbox escape, not a step towards one.
     */
    private fun refused(owner: String, name: String): Answer? = when {
        owner == "java.lang.Thread" && name == "setDefaultUncaughtExceptionHandler" -> Answer(null)
        owner == "java.lang.System" && (name == "exit" || name == "setProperty" ||
            name == "setOut" || name == "setErr" || name == "setSecurityManager") -> Answer(null)
        owner == "java.lang.Runtime" && (name == "exit" || name == "halt" || name == "exec" ||
            name == "addShutdownHook" || name == "load" || name == "loadLibrary") -> Answer(null)
        owner == "java.lang.ProcessBuilder" && name == "start" -> Answer(null)
        owner == "java.lang.System" && name == "load" -> Answer(null)
        owner == "java.lang.System" && name == "loadLibrary" -> Answer(null)
        else -> null
    }

    /**
     * The `android.*` calls that are worth implementing rather than faking.
     *
     * Chosen by what an `Application.onCreate` actually does: it logs, it reads preferences, it asks
     * its own package name, and it looks at `Build.VERSION`. Everything beyond that is per-app and
     * arrives through [missing] as a request for the next piece of work.
     */
    private fun androidCall(
        owner: String,
        name: String,
        descriptor: String,
        arguments: List<Any?>,
    ): Answer? = when {
        // android.util.Log -- the single most useful thing to implement, because it is how an app
        // says what it is doing and the whole point is to find out.
        owner == "android.util.Log" -> {
            val tag = arguments.getOrNull(0)?.toString().orEmpty()
            val message = arguments.getOrNull(1)?.toString().orEmpty()
            val level = when (name) {
                "v" -> "V"; "d" -> "D"; "i" -> "I"; "w" -> "W"; "e" -> "E"; else -> "?"
            }
            synchronized(logLines) {
                if (logLines.size > 500) logLines.removeAt(0)
                logLines.add(level + "/" + tag + ": " + message)
            }
            PrismPlatform.log.debug(TAG, packageName + " " + level + "/" + tag + ": " + message)
            Answer(0)
        }

        // Context and its subclasses. The app's own identity and its directories, which is most of
        // what a Context is asked for during startup.
        name == "getPackageName" -> Answer(packageName)
        name == "getFilesDir" -> Answer(filesDir.apply { mkdirs() })
        name == "getCacheDir" -> Answer(cacheDir.apply { mkdirs() })
        name == "getApplicationContext" || name == "getBaseContext" ->
            Answer(arguments.firstOrNull())
        // getSharedPreferences(name, mode). ARGUMENT 1 IS THE NAME; ARGUMENT 0 IS THE RECEIVER.
        //
        // This read argument 0, so the file was named after the Context object -- whose toString
        // carries its identity hash. The effect was subtle enough to look like it worked: the hash
        // is stable across runs whose allocation order is identical, so an app's preferences
        // persisted for several runs and then silently started again from empty after an unrelated
        // code change. A sample app's run counter went 1, 2, 3, 1, 2, 1.
        //
        // Two further consequences of the same line: every named preferences file in an app was a
        // DIFFERENT file per Context rather than per name, so two components sharing a name did not
        // share state; and the vault grew by a fresh file every time the hash changed.
        //
        // CACHED PER FILE, because `getSharedPreferences` with the same name must return the same
        // state -- an app that writes through one handle and reads through another expects to see
        // its own write, and a fresh object would read the file from before the write was flushed.
        name == "getSharedPreferences" -> Answer(
            preferencesNamed(arguments.getOrNull(1)?.toString()),
        )
        name == "getResources" || name == "getAssets" || name == "getContentResolver" ||
            name == "getSystemService" || name == "getPackageManager" ->
            // FAKED RATHER THAN MISSING, because an app that gets null here crashes immediately and
            // an app that gets a Fake carries on until it actually needs something from it -- which
            // is then recorded with the specific method name, which is far more useful.
            Answer(Fake(DexFile.nameToDescriptor(owner.ifEmpty { "android.content.Context" })))

        // SharedPreferences, through the real implementation below. Wrapped in an Answer because
        // a legitimate null -- getString on a missing key with no default -- must not read as
        // "this surface did not handle the call".
        arguments.firstOrNull() is VirtualPreferences ->
            Answer((arguments.first() as VirtualPreferences).call(name, arguments.drop(1)))

        // SharedPreferences.Editor, which is the same object -- see VirtualPreferences.
        owner.endsWith("SharedPreferences\$Editor") -> Answer(arguments.firstOrNull())

        // android.os.Build.VERSION.SDK_INT is read through a field, not a call; this is for the
        // handful of methods on Build itself.
        owner == "android.os.Build" -> Answer(zeroFor(descriptor.substringAfterLast(')')))

        // ── Application, the class a startup begins in ──────────────────────
        //
        // NO-OPS, WHICH IS WHAT ANDROID'S OWN ARE. `Application.onCreate` in the framework is an
        // empty method that exists to be overridden, and its constructor only stores a null base
        // context. An app's own subclass is found in the dex and interpreted; what arrives here is
        // the `super` call, and answering it with a miss made every startup look broken at the
        // first line of its own onCreate.
        // AN OWNER-MATCHED BRANCH WITH AN `else -> null` SWALLOWS EVERY LATER BRANCH, which is how
        // `Context.registerReceiver` stayed unimplemented after it was implemented: this branch
        // matched on the owner, its inner `when` fell to `else`, and androidCall returned null
        // without ever reaching the `name == "registerReceiver"` case further down. Anything matched
        // by owner here has to be complete for that owner.
        owner == "android.app.Application" || owner == "android.content.ContextWrapper" ||
            owner == "android.content.Context" ->
            when (name) {
                "<init>", "onCreate", "onTerminate", "onLowMemory", "onTrimMemory",
                "attachBaseContext", "registerActivityLifecycleCallbacks",
                "unregisterActivityLifecycleCallbacks", "registerComponentCallbacks",
                "unregisterComponentCallbacks",
                -> Answer(null)
                // Registration succeeds and nothing is ever delivered -- see the IntentFilter
                // branch below for why that is the honest answer on a desktop.
                "registerReceiver", "unregisterReceiver", "sendBroadcast", "sendOrderedBroadcast",
                "startActivity", "startService", "stopService", "bindService", "unbindService",
                -> Answer(null)
                else -> null
            }

        // ── Looper and Handler ─────────────────────────────────────────────
        owner == "android.os.Looper" -> when (name) {
            "getMainLooper", "myLooper" -> Answer(mainLooper)
            "prepare", "prepareMainLooper", "loop", "quit", "quitSafely" -> Answer(null)
            "getThread" -> Answer(Thread.currentThread())
            "isCurrentThread" -> Answer(1)
            else -> null
        }

        owner == "android.os.Handler" && name == "<init>" ->
            // The receiver is replaced rather than mutated, which the interpreter supports: it keeps
            // whatever the surface returns in the receiver's register. See reflectCall's <init>.
            Answer(VirtualHandler { runtime })

        arguments.firstOrNull() is VirtualHandler -> {
            val handler = arguments.first() as VirtualHandler
            when (name) {
                "post", "postAtFrontOfQueue" -> Answer(if (handler.post(arguments.getOrNull(1))) 1 else 0)
                // THE DELAY IS DROPPED ON PURPOSE -- see VirtualHandler.
                "postDelayed", "postAtTime" -> Answer(if (handler.post(arguments.getOrNull(1))) 1 else 0)
                "removeCallbacks", "removeCallbacksAndMessages" -> Answer(null)
                "getLooper" -> Answer(mainLooper)
                "hasMessages" -> Answer(0)
                else -> Answer(zeroFor(descriptor.substringAfterLast(')')))
            }
        }

        // android.os.SystemClock, which an app uses for timing and which has exact JVM equivalents.
        owner == "android.os.SystemClock" -> when (name) {
            "uptimeMillis", "elapsedRealtime" -> Answer(System.nanoTime() / 1_000_000L)
            "elapsedRealtimeNanos" -> Answer(System.nanoTime())
            "currentThreadTimeMillis" -> Answer(System.currentTimeMillis())
            "sleep" -> Answer(null)
            else -> null
        }

        // android.os.Process and android.os.Trace: identity and profiling, both harmless to answer.
        owner == "android.os.Process" -> when (name) {
            "myPid", "myUid" -> Answer(Thread.currentThread().hashCode() and 0xFFFF)
            "myTid" -> Answer(Thread.currentThread().hashCode() and 0xFFFF)
            else -> Answer(zeroFor(descriptor.substringAfterLast(')')))
        }
        owner == "android.os.Trace" || owner == "android.os.StrictMode" ->
            Answer(zeroFor(descriptor.substringAfterLast(')')))

        // ── IntentFilter and BroadcastReceiver ─────────────────────────────
        //
        // ACCEPTED AND NEVER DELIVERED TO, which is a decision rather than a stub. A receiver
        // registered for `ACTION_SCREEN_OFF` or `ACTION_BATTERY_CHANGED` is waiting for an Android
        // system broadcast, and a desktop has no such thing to deliver -- Prism's own screen-lock
        // and power events are not the same events and firing them at an app that asked for
        // Android's would be inventing data.
        //
        // So registration SUCCEEDS and nothing arrives. That is what a device with the event
        // disabled looks like, which the app already has to cope with; failing the registration
        // instead makes apps that register in onCreate die at startup.
        owner == "android.content.IntentFilter" -> when (name) {
            "<init>" -> Answer(Fake("Landroid/content/IntentFilter;"))
            else -> Answer(zeroFor(descriptor.substringAfterLast(')')))
        }
        arguments.firstOrNull() is Fake &&
            (arguments.first() as Fake).type == "Landroid/content/IntentFilter;" ->
            Answer(zeroFor(descriptor.substringAfterLast(')')))
        owner == "android.content.BroadcastReceiver" -> Answer(null)
        name == "registerReceiver" || name == "unregisterReceiver" ->
            // registerReceiver returns the sticky Intent for the filter, or null when there is none.
            // Null is the honest answer and apps handle it, because most broadcasts are not sticky.
            Answer(null)

        // registerActivityLifecycleCallbacks on the app's OWN Application subclass, which the dex
        // does not define -- it inherits it. There are no activities here to report, so the
        // callbacks are accepted and never invoked, for the same reason as the receivers above.
        name == "registerActivityLifecycleCallbacks" ||
            name == "unregisterActivityLifecycleCallbacks" ||
            name == "registerComponentCallbacks" || name == "unregisterComponentCallbacks" ->
            Answer(null)

        // android.app.ActivityManager. AN EMPTY PROCESS LIST, not a fabricated one: an app asking
        // what else is running is usually checking whether IT is in the foreground, and inventing
        // entries would answer that question wrongly in whichever direction the fake happened to
        // fall. getMemoryInfo is answered from the real JVM figures, which are true.
        owner == "android.app.ActivityManager" || name == "getRunningAppProcesses" ->
            when (name) {
                "getRunningAppProcesses", "getRunningServices", "getRunningTasks",
                "getAppTasks", "getProcessesInErrorState",
                -> Answer(ArrayList<Any?>())
                "isLowRamDevice" -> Answer(0)
                "getMemoryClass", "getLargeMemoryClass" ->
                    Answer((Runtime.getRuntime().maxMemory() shr 20).toInt())
                "isBackgroundRestricted", "isUserAMonkey" -> Answer(0)
                else -> Answer(zeroFor(descriptor.substringAfterLast(')')))
            }

        // android.os.Environment -- REDIRECTED INTO THE VAULT, never to the real disk.
        //
        // An app asking for "external storage" on a device gets a shared directory it may read and
        // write freely. Answering with a real desktop path -- the user's home, say -- would put the
        // app's files outside the vault, where they are neither encrypted at rest nor removed when
        // the app is forgotten. Both of those are promises this phase made, so the answer is a
        // directory INSIDE the vault that happens to be named like external storage.
        owner == "android.os.Environment" -> when (name) {
            "getExternalStorageDirectory", "getExternalStoragePublicDirectory",
            "getDataDirectory", "getDownloadCacheDirectory",
            -> Answer(File(filesDir, "external").apply { mkdirs() })
            "getExternalStorageState" -> Answer("mounted")
            "isExternalStorageEmulated", "isExternalStorageRemovable" -> Answer(1)
            "isExternalStorageManager" -> Answer(0)
            else -> null
        }

        else -> null
    }

    /**
     * The field updaters, which are `java.*` by name and interpreted by nature.
     *
     * Handled BEFORE [reflectCall] because the owner is a real JVM class and reflection would find a
     * real `newUpdater` to call -- which then throws on an interpreted class, is swallowed, and
     * becomes the null described in [FieldUpdater].
     */
    private fun updaterCall(owner: String, name: String, arguments: List<Any?>): Answer? {
        val kind = when (owner) {
            "java.util.concurrent.atomic.AtomicReferenceFieldUpdater" -> FieldUpdater.Kind.REFERENCE
            "java.util.concurrent.atomic.AtomicIntegerFieldUpdater" -> FieldUpdater.Kind.INT
            "java.util.concurrent.atomic.AtomicLongFieldUpdater" -> FieldUpdater.Kind.LONG
            else -> null
        }

        if (kind != null && name == "newUpdater") {
            // newUpdater(holderClass, [fieldType,] fieldName) -- the reference form has the extra
            // field-type argument, so the NAME IS THE LAST argument in both.
            val fieldName = arguments.lastOrNull()?.toString().orEmpty()
            return Answer(FieldUpdater(fieldName, kind))
        }

        val updater = arguments.firstOrNull() as? FieldUpdater ?: return null
        val target = arguments.getOrNull(1)
        return when (name) {
            "get" -> Answer(updater.get(target))
            "set", "lazySet" -> {
                updater.set(target, arguments.getOrNull(2))
                Answer(null)
            }
            "compareAndSet", "weakCompareAndSet" -> Answer(
                if (updater.compareAndSet(target, arguments.getOrNull(2), arguments.getOrNull(3))) 1 else 0,
            )
            "getAndSet" -> Answer(updater.getAndSet(target, arguments.getOrNull(2)))
            "getAndAdd" -> Answer(updater.getAndAdd(target, arguments.getOrNull(2)))
            "getAndIncrement" -> Answer(updater.getAndAdd(target, 1))
            "getAndDecrement" -> Answer(updater.getAndAdd(target, -1))
            "incrementAndGet" -> Answer(updater.getAndAdd(target, 1).let { next(it, 1, updater.kind) })
            "decrementAndGet" -> Answer(updater.getAndAdd(target, -1).let { next(it, -1, updater.kind) })
            "addAndGet" -> {
                val delta = arguments.getOrNull(2)
                Answer(updater.getAndAdd(target, delta).let { next(it, delta, updater.kind) })
            }
            else -> null
        }
    }

    private fun next(previous: Any?, delta: Any?, kind: FieldUpdater.Kind): Any =
        if (kind == FieldUpdater.Kind.LONG) {
            ((previous as? Number)?.toLong() ?: 0L) + ((delta as? Number)?.toLong() ?: 0L)
        } else {
            ((previous as? Number)?.toInt() ?: 0) + ((delta as? Number)?.toInt() ?: 0)
        }

    /**
     * A real call into a real JVM class.
     *
     * By NAME AND ARGUMENT COUNT rather than by exact signature, because a dex descriptor's types do
     * not map one-to-one onto the JVM's -- an `int` argument arrives from the interpreter as an
     * `Integer`, a `boolean` as an `Integer` too, and matching exactly would miss almost everything.
     * Ambiguity is resolved by taking the first method that accepts the arguments, which is wrong for
     * a heavily overloaded method and right for the ones an app's startup calls.
     */
    private fun reflectCall(
        owner: String,
        name: String,
        rawArguments: List<Any?>,
        descriptor: String? = null,
    ): Answer? {
        val type = runCatching { Class.forName(owner) }.getOrNull() ?: return null
        // A REAL JVM CALL TAKES THE VALUE, NOT THE BOX. The wrapper exists so the interpreter's
        // branch opcodes can tell a reference from a zero; reflection has real types and does not
        // need it, and passing it would fail every argument match.
        //
        // An interpreted object becomes a proxy of the interfaces it implements -- see [proxyFor] --
        // so an app's own Runnable or ThreadFactory can be handed to real JVM code.
        val arguments = rawArguments.map { argument -> forJvm(argument) }

        if (name == "<init>" && owner == "java.lang.Thread") {
            // A THREAD'S WHOLE PURPOSE IS TO RUN SOMETHING, so the host Thread is built around a
            // Runnable that calls back into the interpreter. A plain `Thread()` host would start,
            // run `Thread.run` -- which does nothing without a target -- and exit, so `start()`
            // would appear to work and the app's `run` would never execute. kotlinx.coroutines'
            // scheduler is exactly this shape, and a worker that starts and does nothing is a
            // coroutine dispatcher that accepts work and never completes it.
            val app = arguments.firstOrNull() as? DalvikInterpreter.DexObject
            if (app != null) {
                val host = Thread {
                    val live = runtime
                    if (live != null) {
                        runCatching {
                            DalvikInterpreter.invokeVirtual(live, app, "run", "()V", listOf(app))
                        }.onFailure {
                            PrismPlatform.log.warn(TAG, packageName + " thread failed: " + it.message)
                        }
                    }
                }
                // DAEMON, ALWAYS. A virtualized app's thread must never be the reason Prism cannot
                // exit, and an app has no way to ask for that here.
                host.isDaemon = true
                arguments.getOrNull(1)?.toString()?.let { host.name = it }
                return Answer(host)
            }
        }

        if (name == "<init>") {
            // The constructor, called on an object `new-instance` already made. The JVM cannot
            // re-run a constructor on an existing object, so a parameterised one is handled by
            // CONSTRUCTING AGAIN and handing the result back -- which the interpreter then puts in
            // the receiver's register. See DalvikInterpreter.adoptConstructed.
            val parameters = arguments.drop(1)
            val constructors = type.declaredConstructors
                .filter { it.parameterCount == parameters.size }
                .sortedBy { if (accepts(it.parameterTypes, parameters)) 0 else 1 }
            constructors.forEach { constructor ->
                try {
                    constructor.isAccessible = true
                    return Answer(
                        constructor.newInstance(*coerce(constructor.parameterTypes, parameters)),
                    )
                } catch (thrown: java.lang.reflect.InvocationTargetException) {
                    rethrow(thrown)
                } catch (mismatch: Throwable) {
                    if (mismatch is DalvikInterpreter.DalvikThrow) throw mismatch
                }
            }
            return Answer(arguments.firstOrNull())
        }

        // STATIC OR NOT IS THE METHOD'S OWN PROPERTY, not a guess from the receiver. The first
        // version decided by `type.isInstance(arguments[0])`, which is wrong in both directions: an
        // instance method called on an INTERPRETED receiver -- a DexObject implementing a JVM
        // interface -- is not an instance of the JVM type, so it was invoked as a static and failed;
        // and a static whose first argument happens to be of the owning type was invoked as an
        // instance method on it. Both failures were swallowed into a recorded miss, which returned
        // null, which is the hardest kind of bug to trace back.
        val receiver = arguments.firstOrNull()
        // THE DEX DESCRIPTOR IS THE TRUTH ABOUT THE OVERLOAD, and it was being ignored.
        //
        // `StringBuilder.append` has a dozen one-argument forms. Choosing between them from the
        // VALUE in the register cannot work, because the register has no type: a float constant is
        // an Int holding IEEE bits, and `append(int)` accepts an Int perfectly happily. So
        // `"ratio " + 133.33333f` printed **1124422997** -- the bit pattern, as a decimal integer,
        // from a method that did exactly what it was asked.
        //
        // The call site's descriptor says `(F)`, which settles it with no guessing at all. The
        // heuristic below is kept only for the case where a descriptor type is one the JVM does not
        // have, which is where an exact match is impossible by definition.
        val wanted = descriptor?.let { parameterClasses(it) }
        val candidates = type.methods.filter { it.name == name }

        // ORDERED BY WHETHER THE ARGUMENTS FIT, then tried in turn.
        //
        // Taking the first overload by arity alone is how `StringBuilder.append("x")` failed: it has
        // a dozen one-argument overloads, `append(char[])` or `append(int)` comes first in the
        // reflective order, coercing a String to either throws, and the whole call was recorded as
        // an unimplemented `StringBuilder.append` -- on one of the best-supported classes in the JVM.
        val statics = candidates
            .filter { Modifier.isStatic(it.modifiers) && it.parameterCount == arguments.size }
            .sortedBy { rank(it.parameterTypes, arguments, wanted) }
        statics.forEach { candidate ->
            try {
                candidate.isAccessible = true
                return Answer(candidate.invoke(null, *coerce(candidate.parameterTypes, arguments)))
            } catch (thrown: java.lang.reflect.InvocationTargetException) {
                // THE METHOD RAN AND THREW, which is the app's exception and not a gap in this
                // surface -- see [rethrow].
                rethrow(thrown)
            } catch (mismatch: Throwable) {
                // The arguments did not fit this overload; the next one may.
                if (mismatch is DalvikInterpreter.DalvikThrow) throw mismatch
            }
        }

        // An instance method needs a receiver the JVM will accept. An interpreted one cannot be
        // passed to a JVM method at all, so it is reported as a miss WITH ITS NAME rather than
        // silently answered -- that report is what says which method to implement next.
        if (receiver == null || !type.isInstance(receiver)) return null
        val parameters = arguments.drop(1)
        val instances = candidates
            .filter { !Modifier.isStatic(it.modifiers) && it.parameterCount == parameters.size }
            .sortedBy { rank(it.parameterTypes, parameters, wanted) }
        instances.forEach { candidate ->
            try {
                candidate.isAccessible = true
                return Answer(
                    candidate.invoke(receiver, *coerce(candidate.parameterTypes, parameters)),
                )
            } catch (thrown: java.lang.reflect.InvocationTargetException) {
                rethrow(thrown)
            } catch (mismatch: Throwable) {
                if (mismatch is DalvikInterpreter.DalvikThrow) throw mismatch
            }
        }
        return null
    }

    /**
     * Turns a JVM method's own exception into one the app can catch.
     *
     * ## A THROW IS NOT A GAP, AND REPORTING IT AS ONE IS ACTIVELY MISLEADING
     *
     * `list.set(9, x)` on a list of three throws `IndexOutOfBoundsException`, which is the app's bug
     * and exactly what a device would do. Swallowing it made the surface record
     * "java.util.List.set -- unimplemented", so the report -- whose whole job is to say what to
     * implement next -- pointed at `java.util.List`, a class the JVM has implemented since 1998. Five
     * of the entries in one run were this.
     *
     * Worse than the bad report: the app did not get its exception, so its `catch` never ran and it
     * carried on with whatever zero the surface returned.
     */
    private fun rethrow(thrown: java.lang.reflect.InvocationTargetException): Nothing {
        val cause = thrown.targetException
        if (cause is DalvikInterpreter.DalvikThrow) throw cause
        throw DalvikInterpreter.DalvikThrow(cause ?: thrown)
    }

    /**
     * Whether these arguments plausibly fit these declared types.
     *
     * Used only to ORDER the overloads, never to reject one: the interpreter's values are untyped, so
     * a confident "no" would be wrong as often as it was right. A wrong order costs a failed
     * reflective call and then the next candidate; a wrong rejection costs the whole call.
     */
    /**
     * The parameter types a dex method descriptor declares, or null if it names one the JVM lacks.
     *
     * Null rather than a partial list: a descriptor is only useful here if EVERY parameter resolves,
     * because the point is an exact match against a JVM signature.
     */
    private fun parameterClasses(descriptor: String): Array<Class<*>>? {
        val inside = descriptor.substringAfter('(', "").substringBefore(')')
        val types = ArrayList<Class<*>>()
        var index = 0
        while (index < inside.length) {
            val start = index
            while (index < inside.length && inside[index] == '[') index++
            index += if (index < inside.length && inside[index] == 'L') {
                val end = inside.indexOf(';', index)
                if (end < 0) return null
                end - index + 1
            } else {
                1
            }
            types.add(DexFile.classOf(inside.substring(start, index)) ?: return null)
        }
        return types.toTypedArray()
    }

    /**
     * How good a candidate this overload is: 0 exact by descriptor, 1 plausible, 2 unlikely.
     *
     * See the comment at the call site for why the descriptor outranks everything else.
     */
    private fun rank(
        types: Array<Class<*>>,
        arguments: List<Any?>,
        wanted: Array<Class<*>>?,
    ): Int = when {
        wanted != null && types.size == wanted.size && types.indices.all { types[it] == wanted[it] } -> 0
        accepts(types, arguments) -> 1
        else -> 2
    }

    private fun accepts(types: Array<Class<*>>, arguments: List<Any?>): Boolean =
        types.withIndex().all { (index, want) ->
            val have = DalvikInterpreter.Boxed.unwrap(arguments.getOrNull(index))
            when {
                have == null -> !want.isPrimitive
                want.isInstance(have) -> true
                // The interpreter has one Int for int, short, byte, char and boolean, so a Number
                // fits any numeric primitive -- but not boolean, where 0 and 1 are the only values
                // and a wrong guess silently inverts a flag.
                want.isPrimitive && have is Number -> want != Boolean::class.javaPrimitiveType
                want.isPrimitive && have is Char -> true
                want.isPrimitive && have is Boolean -> want == Boolean::class.javaPrimitiveType
                else -> false
            }
        }

    /**
     * Widens the interpreter's values to what a JVM method wants.
     *
     * The interpreter has no types: a `boolean` is an Int, a `char` is an Int, and every integer is
     * an Int regardless of what the dex called it. A reflective call with an Int where a `boolean` is
     * declared throws `IllegalArgumentException`, so each argument is converted to the declared type.
     */
    private fun coerce(types: Array<Class<*>>, arguments: List<Any?>): Array<Any?> =
        Array(types.size) { index ->
            val want = types[index]
            val have = DalvikInterpreter.Boxed.unwrap(arguments.getOrNull(index))
            when {
                have == null -> null
                want == Boolean::class.javaPrimitiveType || want == java.lang.Boolean::class.java ->
                    (have as? Number)?.let { it.toInt() != 0 } ?: have
                want == Char::class.javaPrimitiveType || want == Character::class.java ->
                    (have as? Number)?.toInt()?.toChar() ?: have
                want == Byte::class.javaPrimitiveType -> (have as? Number)?.toByte() ?: have
                want == Short::class.javaPrimitiveType -> (have as? Number)?.toShort() ?: have
                want == Int::class.javaPrimitiveType -> (have as? Number)?.toInt() ?: have
                want == Long::class.javaPrimitiveType -> (have as? Number)?.toLong() ?: have
                // BITS, WHEN THE VALUE CAME FROM A `const`. A register in a float position holds an
                // IEEE pattern -- see DalvikInterpreter.asFloat -- so an Int here is reinterpreted
                // rather than converted. Without this, `String.valueOf(0.75f)` printed
                // 1.06115891E9.
                want == Float::class.javaPrimitiveType ->
                    (have as? Int)?.let { Float.fromBits(it) } ?: (have as? Number)?.toFloat() ?: have
                want == Double::class.javaPrimitiveType ->
                    (have as? Long)?.let { Double.fromBits(it) }
                        ?: (have as? Number)?.toDouble() ?: have
                want == String::class.java && have !is String -> have.toString()
                else -> have
            }
        }

    // ── Fields ──────────────────────────────────────────────────────────────

    fun fieldGet(rawTarget: Any?, name: String): Any? = fieldGetOf(unproxy(rawTarget), name)

    private fun fieldGetOf(target: Any?, name: String): Any? = when (target) {
        is Fake -> target.fields[name]
        // getDeclaredField, not getField: an interpreted object's field is almost always private,
        // and getField only sees public ones -- so every read returned null.
        is DalvikInterpreter.DexObject -> target.fields[name]
        is VirtualPreferences -> null
        null -> null
        else -> runCatching {
            target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)
        }.recoverCatching {
            target.javaClass.getField(name).apply { isAccessible = true }.get(target)
        }.getOrNull()
    }

    fun fieldPut(rawTarget: Any?, name: String, value: Any?) {
        when (val target = unproxy(rawTarget)) {
            is Fake -> target.fields[name] = value
            is DalvikInterpreter.DexObject -> target.fields[name] = value
            null -> Unit
            else -> runCatching {
                target.javaClass.getDeclaredField(name).apply { isAccessible = true }
                    .set(target, value)
            }.recoverCatching {
                target.javaClass.getField(name).apply { isAccessible = true }.set(target, value)
            }
        }
    }

    /**
     * A static field on a framework class.
     *
     * `Build.VERSION.SDK_INT` is the one that genuinely matters: an app branches on it constantly, and
     * a zero makes every version check take the oldest path -- which is usually the one with the most
     * compatibility shims and the least chance of needing something this surface lacks. A high value
     * would be worse: it would take paths that need APIs that are not here.
     */
    fun staticGet(definingClass: String, name: String): Any? {
        val owner = DexFile.descriptorToName(definingClass)
        return when {
            owner == "android.os.Build\$VERSION" && name == "SDK_INT" -> reportedSdkInt
            owner == "android.os.Build\$VERSION" && name == "RELEASE" -> "14"
            owner.startsWith("android.") || owner.startsWith("androidx.") -> {
                record(owner + "." + name)
                null
            }
            else -> runCatching {
                Class.forName(owner).getField(name).apply { isAccessible = true }.get(null)
            }.getOrNull()
        }
    }

    /**
     * What API level the app is told it is running on.
     *
     * 24 (Nougat) deliberately, and the reasoning is worth recording: it is high enough that an app
     * does not take the pre-Marshmallow permission paths, which assume a permission model this
     * surface has no concept of, and low enough to avoid the scoped-storage, foreground-service and
     * notification-channel paths added later -- every one of which needs framework this does not
     * provide. It is the level with the smallest surface requirement.
     *
     * A property rather than a `const val` because this is a class, not an object: the surface is
     * per-app so that two virtualized apps cannot see each other's preferences or logcat.
     */
    val reportedSdkInt: Int get() = 24

    fun isInstance(value: Any?, descriptor: String): Boolean {
        if (value == null) return false
        if (value is Fake) return value.type == descriptor
        // `instance-of` on a boxed primitive asks about its BOX CLASS -- an Integer is an Integer,
        // a Number and an Object -- so the question is answered against the unwrapped value, whose
        // JVM class is already Integer.
        val subject = DalvikInterpreter.Boxed.unwrap(value) ?: return false
        val name = DexFile.descriptorToName(descriptor)
        return runCatching { Class.forName(name).isInstance(subject) }.getOrDefault(false)
    }

    // ── Reporting ───────────────────────────────────────────────────────────

    private fun record(signature: String) {
        synchronized(missingCalls) {
            missingCalls[signature] = (missingCalls[signature] ?: 0) + 1
        }
    }

    private fun zeroFor(returnDescriptor: String): Any? = when (returnDescriptor) {
        "V" -> null
        "Z", "B", "S", "C", "I" -> 0
        "J" -> 0L
        "F" -> 0.0f
        "D" -> 0.0
        else -> null
    }

    /**
     * What this surface was asked for and could not give.
     *
     * The honest output of the phase: a precise list of the framework an app needed, which is what
     * the next increment implements. A startup that "worked" would be less informative than this.
     */
    fun report(): String = buildString {
        append("Surface for ").append(packageName).append(": ")
        if (missingCalls.isEmpty()) {
            append("every call was answered.")
        } else {
            append(missingCalls.size).append(" unimplemented call(s), ")
            append(missingCalls.values.sum()).append(" invocation(s):")
            missingCalls.entries.sortedByDescending { it.value }.take(40).forEach { (call, count) ->
                append("\n  ").append(count).append("x  ").append(call)
            }
        }
    }
}

/**
 * `SharedPreferences`, for real, backed by a file in the vault.
 *
 * ## One object for the preferences AND the editor, which is not laziness
 *
 * Android's API is `getSharedPreferences().edit().putString(...).apply()`, where `edit()` returns a
 * different type. Faking that as two objects means the interpreter has to know which methods belong
 * to which, and `edit()` returning `this` makes every one of those calls land in the same place. The
 * chaining still works because each `put` returns `this` too, which is what the real `Editor` does.
 *
 * ## Written through the vault's live directory, so it is sealed with everything else
 *
 * The file is under `filesDir`, which IS the unsealed vault -- so preferences are encrypted at rest
 * by the same archive as the rest of the app's data, with no separate mechanism. That is the whole
 * reason the vault packs a directory rather than encrypting per file.
 */
class VirtualPreferences(private val file: File) {

    private val values = LinkedHashMap<String, Any?>()

    init {
        runCatching {
            if (file.isFile) {
                file.readLines().forEach { line ->
                    val key = line.substringBefore('=', "")
                    val raw = line.substringAfter('=', "")
                    if (key.isNotEmpty()) values[key] = decode(raw)
                }
            }
        }
    }

    /** Dispatched from [AndroidSurface.call]. Returns null for a method this does not implement. */
    internal fun call(name: String, arguments: List<Any?>): Any? {
        val key = arguments.getOrNull(0)?.toString()
        return when (name) {
            "edit" -> this
            "apply", "commit" -> {
                flush()
                if (name == "commit") true else null
            }
            "putString", "putInt", "putLong", "putFloat", "putBoolean", "putStringSet" -> {
                if (key != null) values[key] = arguments.getOrNull(1)
                this
            }
            "remove" -> {
                if (key != null) values.remove(key)
                this
            }
            "clear" -> {
                values.clear()
                this
            }
            "contains" -> values.containsKey(key)
            "getAll" -> values.toMap()
            "getString" -> (values[key] ?: arguments.getOrNull(1))?.toString()
            "getInt" -> (values[key] as? Number)?.toInt() ?: (arguments.getOrNull(1) as? Number)?.toInt() ?: 0
            "getLong" -> (values[key] as? Number)?.toLong() ?: (arguments.getOrNull(1) as? Number)?.toLong() ?: 0L
            "getFloat" -> (values[key] as? Number)?.toFloat() ?: (arguments.getOrNull(1) as? Number)?.toFloat() ?: 0f
            "getBoolean" -> {
                val stored = values[key]
                when (stored) {
                    is Boolean -> if (stored) 1 else 0
                    is Number -> if (stored.toInt() != 0) 1 else 0
                    else -> arguments.getOrNull(1)
                }
            }
            else -> null
        }
    }

    private fun flush() {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(values.entries.joinToString("\n") { it.key + "=" + encode(it.value) })
        }
    }

    /**
     * A typed value as one line.
     *
     * The type tag is kept because a preference read back as the wrong type is a bug the app sees
     * rather than this code: `getBoolean` on a value stored as the string "true" returns the default,
     * and the app concludes its own setting was never saved.
     */
    private fun encode(value: Any?): String = when (value) {
        null -> "n:"
        is Boolean -> "b:" + value
        is Int -> "i:" + value
        is Long -> "l:" + value
        is Float -> "f:" + value
        is Double -> "f:" + value
        else -> "s:" + value.toString().replace("\n", "\\n")
    }

    private fun decode(raw: String): Any? {
        val body = raw.substringAfter(':', "")
        return when (raw.substringBefore(':', "")) {
            "n" -> null
            "b" -> body.toBoolean()
            "i" -> body.toIntOrNull() ?: 0
            "l" -> body.toLongOrNull() ?: 0L
            "f" -> body.toFloatOrNull() ?: 0f
            else -> body.replace("\\n", "\n")
        }
    }

    override fun toString(): String = "SharedPreferences(" + file.name + ", " + values.size + " entries)"
}
