package com.prism.desktop.vm

import com.prism.core.PrismPlatform
import com.prism.launcher.virtualapp.AndroidSurface
import com.prism.launcher.virtualapp.ApkManifest
import com.prism.launcher.virtualapp.DalvikInterpreter
import com.prism.launcher.virtualapp.DexFile
import java.io.File

/**
 * Running an Android app on the desktop JVM. PHASE 111.
 *
 * ## NO EMULATOR, WHICH WAS THE INSTRUCTION
 *
 * The phase was amended on instruction: "NO QEMU, NO EMULATOR, NO ANDROID-x86 IMAGE. The desktop is
 * to use the SAME TECHNIQUE the mobile app uses -- VirtualApp/VirtualXposed style -- and the
 * difficulty of that is accepted." An emulator remains the mechanism for OS virtualization, which is
 * a different page and a different feature.
 *
 * So this is the VirtualApp technique: parse the manifest, load the dex, stand up a fake package
 * context, and provide the framework the app calls. Points one to three were ordinary JVM work; point
 * four -- providing the framework -- is [AndroidSurface].
 *
 * ## The four steps, and where each one lives
 *
 *  1. PARSE THE MANIFEST YOURSELF. `ApkManifest` reads the binary XML out of the APK. It was already
 *     free of Android -- a binary format parser has no reason not to be -- and moved to `:core`
 *     unchanged.
 *  2. STAND UP A FAKE PACKAGE CONTEXT. [AndroidSurface] answers `getPackageName`, `getFilesDir`,
 *     `getSharedPreferences` and the rest from the vault's directories.
 *  3. LOAD THE APK'S DEX. `DexFile` reads it; `DalvikInterpreter` runs it. A `ClassLoader` cannot,
 *     because dex is not JVM bytecode -- which is the single biggest difference from what the phase
 *     called "ordinary JVM work".
 *  4. HOOK THE SYSTEM SERVICES. There is nothing to hook, so they are provided. That is the whole of
 *     [AndroidSurface] and the reason an interpreter was chosen over a translator: every call leaving
 *     the app's own code passes through one function.
 *
 * ## THE VAULT CAME FIRST, as the phase required
 *
 * [DesktopAppVault] is unsealed before a single instruction runs and sealed when the app stops. The
 * app's data exists in plaintext only while it is running, and only under the cache directory.
 */
object DesktopVirtualApp {

    private const val TAG = "PrismVirtualApp"

    /** How many instructions a startup is allowed, so a runaway app cannot hang Prism. */
    private const val INSTRUCTION_BUDGET = 50_000_000L

    /**
     * What an imported APK declares.
     *
     * NO LABEL AND NO VERSION NAME, and that is the manifest's fault rather than an omission here.
     * `android:label` and `android:versionName` are almost always RESOURCE REFERENCES in a compiled
     * manifest -- `@string/app_name` becomes an integer id, and resolving it means parsing
     * `resources.arsc` and picking a locale. `ApkManifest` reads the binary XML and does not do that,
     * so the honest fields are the ones that are literal strings: the package name, the Application
     * class and the activities.
     */
    data class Imported(
        val packageName: String,
        val applicationClass: String?,
        val launchActivity: String?,
        val activityCount: Int,
        val dexCount: Int,
        val classCount: Int,
        val apkBytes: Long,
    )

    data class RunResult(
        val packageName: String,
        val ran: Boolean,
        val applicationClass: String?,
        val instructions: Long,
        val millis: Long,
        val logcat: List<String>,
        val surfaceReport: String,
        val failure: String?,
    )

    // ── Importing ───────────────────────────────────────────────────────────

    /**
     * Copies an APK into the vault and reads what it declares.
     *
     * The APK is kept in the vault directory UNENCRYPTED, deliberately: it is the app's own installer
     * package, which the user already has and which is signed by its author. Encrypting it would cost
     * a decrypt per launch and protect something that is not secret. The app's DATA is what the vault
     * is for.
     */
    fun import(apk: File): Result<Imported> = runCatching {
        require(apk.isFile) { "No such file: " + apk.absolutePath }

        val manifest = ApkManifest.read(apk)
            ?: throw IllegalStateException(
                "That APK's manifest could not be read. It may be an app bundle (.aab) or a split " +
                    "APK rather than a base one.",
            )
        val packageName = manifest.packageName
        require(packageName.isNotBlank()) { "The manifest declares no package name." }

        val target = DesktopAppVault.apkFile(packageName)
        target.parentFile?.mkdirs()
        apk.copyTo(target, overwrite = true)

        val dexes = DexFile.readApk(target)
        if (dexes.isEmpty()) {
            throw IllegalStateException(
                "No classes.dex in that APK. A resource-only split has none, and a base APK always " +
                    "does.",
            )
        }

        val imported = Imported(
            packageName = packageName,
            applicationClass = manifest.applicationClass,
            launchActivity = manifest.launchActivity,
            activityCount = manifest.activities.size,
            dexCount = dexes.size,
            classCount = dexes.sumOf { it.classes.size },
            apkBytes = target.length(),
        )
        PrismPlatform.log.success(
            TAG,
            "Imported " + packageName + " — " + dexes.size + " dex, " +
                imported.classCount + " classes, Application=" +
                (imported.applicationClass ?: "none declared"),
        )
        imported
    }

    fun imported(): List<Imported> = DesktopAppVault.virtualizedPackages().mapNotNull { name ->
        runCatching {
            val apk = DesktopAppVault.apkFile(name)
            val manifest = ApkManifest.read(apk) ?: return@runCatching null
            val dexes = DexFile.readApk(apk)
            Imported(
                packageName = name,
                applicationClass = manifest.applicationClass,
                launchActivity = manifest.launchActivity,
                activityCount = manifest.activities.size,
                dexCount = dexes.size,
                classCount = dexes.sumOf { it.classes.size },
                apkBytes = apk.length(),
            )
        }.getOrNull()
    }

    // ── Running ─────────────────────────────────────────────────────────────

    /**
     * The phase's completion criterion: the Application class loads and runs out of the vault.
     *
     * ## What "runs" means here, precisely
     *
     * The declared `Application` subclass is instantiated, its constructor chain is executed, and
     * `onCreate()` is called. Everything it does that this surface implements happens for real --
     * logging, preferences, its own package name, arithmetic, collections. Everything it does that
     * the surface does not implement is recorded in [AndroidSurface.report] and answered with a zero.
     *
     * ## AN APP WITH NO DECLARED Application IS NOT A FAILURE
     *
     * Most apps do not declare one; Android instantiates the base `android.app.Application` for them
     * and there is nothing of the app's own to run. That is reported as such rather than as an error,
     * because treating it as one would make the majority case look broken.
     */
    fun runApplication(packageName: String): RunResult {
        val started = System.currentTimeMillis()
        val apk = DesktopAppVault.apkFile(packageName)
        if (!apk.isFile) {
            return RunResult(
                packageName, false, null, 0, 0, emptyList(), "",
                "Not imported: there is no APK for " + packageName + ".",
            )
        }

        // THE VAULT FIRST. Nothing runs until the app's data is unsealed, and it is sealed again in
        // the finally below whatever happens -- including a failure partway through startup, which
        // would otherwise leave the plaintext behind.
        val live = runCatching { DesktopAppVault.unseal(packageName) }.getOrElse {
            return RunResult(
                packageName, false, null, 0, 0, emptyList(), "",
                "The vault would not unseal: " + it.message,
            )
        }

        val manifest = ApkManifest.read(apk)
        val surface = AndroidSurface(
            packageName = packageName,
            filesDir = File(live, "files").apply { mkdirs() },
            cacheDir = File(live, "cache").apply { mkdirs() },
            manifest = manifest,
        )

        try {
            val dexes = DexFile.readApk(apk)
            val runtime = DalvikInterpreter.DexRuntime(dexes, surface)
            // The surface needs a way BACK IN: a Runnable handed to Handler.post is dex code, and
            // without this the surface could only answer calls, never make them.
            surface.runtime = runtime

            val applicationClass = manifest?.applicationClass
            if (applicationClass.isNullOrBlank()) {
                return RunResult(
                    packageName, true, null, runtime.executed,
                    System.currentTimeMillis() - started, surface.logcat(), surface.report(),
                    null,
                )
            }

            val def = runtime.findByName(applicationClass)
                ?: return RunResult(
                    packageName, false, applicationClass, runtime.executed,
                    System.currentTimeMillis() - started, surface.logcat(), surface.report(),
                    "The manifest declares " + applicationClass + " but no dex defines it. That " +
                        "happens with an app bundle whose code is in a split APK.",
                )

            runtime.ensureInitialised(def)
            val instance = DalvikInterpreter.DexObject(def, runtime)

            // The constructor chain. An Application's own constructor is usually empty and its
            // superclass's is Object's, but a class that initialises fields there would otherwise
            // reach onCreate with them unset.
            val constructor = def.directMethods.firstOrNull {
                it.ref.name == "<init>" && it.ref.proto.parameters.isEmpty()
            }
            if (constructor?.code != null) {
                DalvikInterpreter.execute(runtime, def, constructor, listOf(instance))
            }

            // onCreate, which is what an app actually does at startup.
            val onCreate = def.virtualMethods.firstOrNull {
                it.ref.name == "onCreate" && it.ref.proto.parameters.isEmpty()
            } ?: def.directMethods.firstOrNull { it.ref.name == "onCreate" }

            var failure: String? = null
            if (onCreate?.code == null) {
                failure = applicationClass + " declares no onCreate of its own, so there was " +
                    "nothing of the app's to run beyond its constructor."
            } else {
                runCatching {
                    DalvikInterpreter.execute(runtime, def, onCreate, listOf(instance))
                }.onFailure { thrown ->
                    failure = when (thrown) {
                        is DalvikInterpreter.UnsupportedOpcode -> thrown.message
                        // THE APP'S OWN FRAMES, not the interpreter's. A JVM stack trace here is
                        // `execute` and `invoke` alternating as deep as the app's call stack, which
                        // says nothing about the app; DalvikThrow collects the interpreted frames as
                        // it propagates and they are the only thing that locates a failure.
                        is DalvikInterpreter.DalvikThrow ->
                            "The app threw: " + thrown.message + "\n      at " + thrown.trace()
                        else -> thrown.javaClass.simpleName + ": " + thrown.message
                    }
                }
            }

            val runtimeInfo = Runtime.getRuntime()
            PrismPlatform.log.info(
                TAG,
                "heap after the run: used " +
                    ((runtimeInfo.totalMemory() - runtimeInfo.freeMemory()) shr 20) + " MiB of " +
                    (runtimeInfo.maxMemory() shr 20) + " MiB",
            )
            return RunResult(
                packageName = packageName,
                ran = failure == null,
                applicationClass = applicationClass,
                instructions = runtime.executed,
                millis = System.currentTimeMillis() - started,
                logcat = surface.logcat(),
                surfaceReport = surface.report(),
                failure = failure,
            )
        } catch (failure: Throwable) {
            return RunResult(
                packageName, false, manifest?.applicationClass, 0,
                System.currentTimeMillis() - started, surface.logcat(), surface.report(),
                failure.javaClass.simpleName + ": " + failure.message,
            )
        } finally {
            // SEALED WHATEVER HAPPENED. The plaintext is the app's whole data directory, and leaving
            // it behind after a crash would make the encryption decorative.
            runCatching { DesktopAppVault.seal(packageName) }
        }
    }

    data class CallResult(
        val signature: String,
        val returned: Any?,
        val instructions: Long,
        val millis: Long,
        val failure: String?,
    )

    /**
     * Runs one method out of an imported app's dex.
     *
     * ## Why this exists beside [runApplication]
     *
     * An `Application.onCreate` in a modern app is the WORST case for a young interpreter: it touches
     * coroutines, dependency injection and half the framework before it does anything of its own, so
     * how far it gets measures the framework surface rather than the interpreter. A pure method
     * measures the interpreter -- the decoding, the register allocation, the arithmetic, the branches
     * and the calls -- against real R8-compiled code with a known right answer.
     *
     * Both matter. This one is the one that can be checked.
     *
     * ## Arguments are parsed from strings, with the dex descriptor deciding the type
     *
     * The method's own parameter descriptors say what each argument is, so `"5"` becomes an `Int` for
     * an `I` and a `Long` for a `J`. Guessing from the text instead would pass an Int where a method
     * expects a Long, and the interpreter's `asLong` would silently widen it -- producing a right
     * answer for the wrong reason, which is the worst outcome for a verification path.
     */
    fun callMethod(
        packageName: String,
        className: String,
        methodName: String,
        rawArguments: List<String>,
    ): CallResult {
        val started = System.currentTimeMillis()
        val apk = DesktopAppVault.apkFile(packageName)
        if (!apk.isFile) {
            return CallResult("", null, 0, 0, "Not imported: " + packageName)
        }

        val live = runCatching { DesktopAppVault.unseal(packageName) }.getOrElse {
            return CallResult("", null, 0, 0, "The vault would not unseal: " + it.message)
        }

        val surface = AndroidSurface(
            packageName = packageName,
            filesDir = File(live, "files").apply { mkdirs() },
            cacheDir = File(live, "cache").apply { mkdirs() },
            manifest = ApkManifest.read(apk),
        )

        try {
            val dexes = DexFile.readApk(apk)
            val runtime = DalvikInterpreter.DexRuntime(dexes, surface)
            // The surface needs a way BACK IN: a Runnable handed to Handler.post is dex code, and
            // without this the surface could only answer calls, never make them.
            surface.runtime = runtime
            val def = runtime.findByName(className)
                ?: return CallResult("", null, 0, System.currentTimeMillis() - started,
                    "No class " + className + " in that app's dex.")

            val candidates = def.allMethods().filter { it.ref.name == methodName && it.code != null }
            if (candidates.isEmpty()) {
                return CallResult("", null, 0, System.currentTimeMillis() - started,
                    methodName + " is not a method of " + className + " with code. It has: " +
                        def.allMethods().joinToString(", ") { it.ref.name }.take(400))
            }
            // The overload whose parameter count matches. Ambiguity beyond that is resolved by
            // taking the first, which is reported in the signature so there is no doubt which ran.
            val method = candidates.firstOrNull { it.ref.proto.parameters.size == rawArguments.size }
                ?: candidates.first()

            val arguments = buildList {
                if (!method.isStatic) add(DalvikInterpreter.DexObject(def, runtime))
                method.ref.proto.parameters.forEachIndexed { index, descriptor ->
                    add(parseArgument(rawArguments.getOrNull(index), descriptor))
                }
            }

            runtime.ensureInitialised(def)
            val returned = runCatching {
                DalvikInterpreter.execute(runtime, def, method, arguments)
            }
            return CallResult(
                signature = className + "." + method.ref.name + method.descriptor(),
                returned = returned.getOrNull(),
                instructions = runtime.executed,
                millis = System.currentTimeMillis() - started,
                failure = returned.exceptionOrNull()?.let { thrown ->
                    val inner = (thrown as? DalvikInterpreter.DalvikThrow)?.value as? Throwable
                    thrown.javaClass.simpleName + ": " + thrown.message +
                        (if (thrown is DalvikInterpreter.DalvikThrow) {
                            "\n      at " + thrown.trace()
                        } else {
                            ""
                        }) +
                        (inner?.stackTrace?.take(10)?.joinToString(
                            "\n      jvm ", prefix = "\n      jvm ",
                        ) ?: "")
                },
            )
        } finally {
            runCatching { DesktopAppVault.seal(packageName) }
        }
    }

    data class Check(
        val signature: String,
        val arguments: String,
        val interpreted: String,
        val expected: String,
        val agreed: Boolean,
    )

    /**
     * Checks the interpreter against Prism's own compiled code.
     *
     * ## WHY THIS IS THE VERIFICATION THAT MATTERS
     *
     * "It ran and did not crash" is not evidence an interpreter is correct -- a decoding bug in an
     * arithmetic opcode produces a wrong NUMBER, not a crash, and a `when` whose packed-switch is
     * mis-decoded quietly takes the else branch. Both are silent, and both are the kind of bug that
     * surfaces months later as "the game awards the wrong level".
     *
     * The Prism APK makes an exact check possible. Its dex contains R8-compiled `com.prism.launcher`
     * code, and Prism's own `:core` contains the SAME FUNCTIONS compiled to JVM bytecode from the
     * same source. So: run the method out of the APK through the interpreter, run Prism's own
     * compiled version through reflection, and compare. Any disagreement is an interpreter bug, with
     * the arguments that expose it.
     *
     * The methods chosen are pure integer and long arithmetic with loops, branches, `coerceIn`,
     * multiplication and a `while` -- which exercises const, move, arithmetic, comparison, branch,
     * invoke and return. They are not toy cases: `levelForXp` is a loop whose trip count depends on
     * its own accumulator.
     */
    fun verifyAgainstHost(packageName: String): List<Check> {
        val cases = listOf(
            Triple("com.prism.launcher.minigames.Era", "clampLevel", listOf("-5")),
            Triple("com.prism.launcher.minigames.Era", "clampLevel", listOf("3")),
            Triple("com.prism.launcher.minigames.Era", "clampLevel", listOf("9999")),
            Triple("com.prism.launcher.minigames.Era", "trainingCamps", listOf("1")),
            Triple("com.prism.launcher.minigames.Era", "trainingCamps", listOf("12")),
            Triple("com.prism.launcher.minigames.Era", "trainingCamps", listOf("200")),
            Triple("com.prism.launcher.minigames.Era", "soldiersPerCamp", listOf("7")),
            Triple("com.prism.launcher.minigames.Era", "armyCapacity", listOf("9")),
            Triple("com.prism.launcher.minigames.Era", "armyCapacity", listOf("40")),
            Triple("com.prism.launcher.minigames.Era", "plotSizeFor", listOf("0")),
            Triple("com.prism.launcher.minigames.Era", "plotSizeFor", listOf("6")),
            Triple("com.prism.launcher.minigames.Era", "modernisationStep", listOf("15")),
            Triple("com.prism.launcher.minigames.Era", "unlockTierOf", listOf("23")),
            Triple("com.prism.launcher.minigames.Era", "aiPlotSizeAt", listOf("11")),
            Triple("com.prism.launcher.minigames.Era", "plotExpansionsUnlockedAt", listOf("30")),
            Triple("com.prism.launcher.minigames.Era", "xpToNext", listOf("1")),
            Triple("com.prism.launcher.minigames.Era", "xpToNext", listOf("17")),
            Triple("com.prism.launcher.minigames.Era", "xpForLevel", listOf("1")),
            Triple("com.prism.launcher.minigames.Era", "xpForLevel", listOf("8")),
            Triple("com.prism.launcher.minigames.Era", "xpForLevel", listOf("25")),
            // The loop whose trip count depends on its own accumulator -- and the inverse of the
            // line above it, so a disagreement here with agreement above localises the bug.
            Triple("com.prism.launcher.minigames.Era", "levelForXp", listOf("0")),
            Triple("com.prism.launcher.minigames.Era", "levelForXp", listOf("500")),
            Triple("com.prism.launcher.minigames.Era", "levelForXp", listOf("250000")),
            Triple("com.prism.launcher.minigames.Era", "levelForXp", listOf("99999999")),

            // FLOAT AND DOUBLE, which the first version of this suite had none of -- and the
            // interpreter was reading a float register as an integer the whole time. Twenty-four
            // integer cases agreed perfectly while every float in the app was wrong by nine orders
            // of magnitude. A verification suite proves what it covers and nothing else.
            Triple("com.prism.launcher.protein.ProteinChemistry", "distogramBin", listOf("2.0")),
            Triple("com.prism.launcher.protein.ProteinChemistry", "distogramBin", listOf("7.5")),
            Triple("com.prism.launcher.protein.ProteinChemistry", "distogramBin", listOf("21.9")),
            Triple("com.prism.launcher.protein.ProteinChemistry", "distogramBin", listOf("100.0")),
            Triple("com.prism.launcher.protein.ProteinChemistry", "distogramBin", listOf("-3.0")),
            Triple("com.prism.launcher.protein.ProteinChemistry", "distogramCentre", listOf("0")),
            Triple("com.prism.launcher.protein.ProteinChemistry", "distogramCentre", listOf("17")),
            Triple("com.prism.launcher.protein.ProteinChemistry", "distogramCentre", listOf("63")),
        )

        return cases.map { (className, methodName, rawArguments) ->
            val ran = callMethod(packageName, className, methodName, rawArguments)
            val interpreted = ran.failure?.let { "FAILED: " + it } ?: show(ran.returned)
            val expected = hostAnswer(className, methodName, rawArguments)
            Check(
                signature = className.substringAfterLast('.') + "." + methodName,
                arguments = rawArguments.joinToString(", "),
                interpreted = interpreted,
                expected = expected,
                // COMPARED AS TEXT, which is deliberate: the interpreter has no types, so an Int 5
                // and a Long 5 are both "5" and both right. Comparing the boxes would report a
                // disagreement where the arithmetic agreed.
                agreed = interpreted == expected,
            )
        }
    }

    /** Prism's own compiled implementation of the same function, for the comparison above. */
    private fun hostAnswer(className: String, methodName: String, rawArguments: List<String>): String {
        val type = runCatching { Class.forName(className) }.getOrNull()
            ?: return "no host class"
        // A Kotlin `object` compiles to a class with an INSTANCE field; the methods are instance
        // methods on it.
        val receiver = runCatching { type.getField("INSTANCE").get(null) }.getOrNull()
        val method = type.methods.firstOrNull {
            it.name == methodName && it.parameterCount == rawArguments.size
        } ?: return "no host method"
        val converted = method.parameterTypes.mapIndexed { index, want ->
            val raw = rawArguments.getOrNull(index)
            when (want) {
                Long::class.javaPrimitiveType -> raw?.toLongOrNull() ?: 0L
                Int::class.javaPrimitiveType -> raw?.toIntOrNull() ?: 0
                Float::class.javaPrimitiveType -> raw?.toFloatOrNull() ?: 0f
                Double::class.javaPrimitiveType -> raw?.toDoubleOrNull() ?: 0.0
                Boolean::class.javaPrimitiveType -> raw == "true"
                else -> raw
            }
        }
        return runCatching {
            method.isAccessible = true
            show(method.invoke(receiver, *converted.toTypedArray()))
        }.getOrElse { "host threw: " + it.cause?.message }
    }

    /** One text form for both sides of the comparison. See [verifyAgainstHost]. */
    private fun show(value: Any?): String = when (value) {
        null -> "null"
        is Number -> value.toString()
        is CharSequence -> "\"" + value + "\""
        else -> value.toString()
    }

    /** A command-line string as the type the dex descriptor declares. See [callMethod]. */
    private fun parseArgument(raw: String?, descriptor: String): Any? = when (descriptor) {
        "I", "S", "B", "C" -> raw?.toIntOrNull() ?: 0
        "J" -> raw?.toLongOrNull() ?: 0L
        "Z" -> if (raw == "true" || raw == "1") 1 else 0
        "F" -> raw?.toFloatOrNull() ?: 0f
        "D" -> raw?.toDoubleOrNull() ?: 0.0
        "Ljava/lang/String;" -> raw
        else -> null
    }

    /**
     * Methods in a class that this can call and check.
     *
     * Static, with code, and with only primitive or String parameters -- which is what "a pure method
     * with a known right answer" means in practice.
     */
    fun callableMethods(packageName: String, className: String): List<String> {
        val apk = DesktopAppVault.apkFile(packageName)
        if (!apk.isFile) return emptyList()
        val dexes = DexFile.readApk(apk)
        val def = dexes.firstNotNullOfOrNull { it.classNamed(className) } ?: return emptyList()
        return def.allMethods()
            .filter { it.code != null }
            .filter { method ->
                method.ref.proto.parameters.all { it.length == 1 || it == "Ljava/lang/String;" }
            }
            .map {
                (if (it.isStatic) "static " else "") + it.ref.name + it.descriptor() +
                    "  [" + (it.code?.instructions?.size ?: 0) + " units]"
            }
    }

    /**
     * What an app's dex contains, without running any of it.
     *
     * Useful on its own and the honest intermediate step: knowing that the classes parse and that the
     * declared `Application` is among them is most of the way to running it, and it is checkable on
     * any APK without a framework surface that happens to cover that app.
     */
    fun inspect(packageName: String): String {
        val apk = DesktopAppVault.apkFile(packageName)
        if (!apk.isFile) return "Not imported."
        val manifest = ApkManifest.read(apk)
        val dexes = DexFile.readApk(apk)
        return buildString {
            append("package ").append(manifest?.packageName ?: "?").append('\n')
            append("Application ").append(manifest?.applicationClass ?: "(none declared)").append('\n')
            append("launch activity ").append(manifest?.launchActivity ?: "(none)").append('\n')
            append("activities ").append(manifest?.activities?.size ?: 0).append('\n')
            dexes.forEachIndexed { index, dex ->
                append("dex ").append(index + 1).append(": ").append(dex.describe()).append('\n')
            }
            val application = manifest?.applicationClass
            if (application != null) {
                val found = dexes.firstNotNullOfOrNull { it.classNamed(application) }
                append("Application found in dex: ").append(found != null).append('\n')
                found?.let { def ->
                    append("  extends ").append(
                        def.superClass?.let { DexFile.descriptorToName(it) } ?: "?",
                    ).append('\n')
                    append("  ").append(def.allMethods().size).append(" method(s), ")
                    append(def.instanceFields.size).append(" field(s)").append('\n')
                    def.allMethods().take(12).forEach { method ->
                        append("    ").append(method.ref.name).append(method.descriptor())
                        // Bound locally: a smart cast cannot cross a module boundary, so
                        // method.code would be re-read as nullable on every access.
                        val body = method.code
                        append(if (body == null) "  (abstract or native)" else
                            "  " + body.instructions.size + " units, " +
                                body.registers + " registers")
                        append('\n')
                    }
                }
            }
        }
    }

    fun describe(): String {
        val apps = imported()
        if (apps.isEmpty()) {
            return "Nothing imported. An APK is imported, its data sealed in the vault, and its " +
                "Application class run on this JVM — no emulator anywhere."
        }
        return apps.size.toString() + " app(s): " + apps.joinToString(", ") {
            it.packageName + " (" + it.classCount + " classes)"
        }
    }
}
