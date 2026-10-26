package com.prism.desktop

import com.prism.launcher.pdb.JvmPdbHost
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.Robot
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.io.File
import javax.imageio.ImageIO

/**
 * The desktop capabilities PDB cannot express with files and processes alone.
 *
 * Input and screen capture are AWT's job, and AWT is not on Android's classpath, which is why they
 * live in the desktop module rather than in the core host. Everything else -- package handling,
 * logs, package listing -- stays in [JvmPdbHost] so both desktop platforms share one definition.
 */
class DesktopPdbHost : JvmPdbHost() {

    override fun input(args: List<String>): String? {
        if (args.isEmpty()) return null
        val robot = runCatching { Robot() }.getOrNull() ?: return null
        return when (args.first().lowercase()) {
            "tap" -> {
                val x = args.getOrNull(1)?.toIntOrNull() ?: return usage("input tap <x> <y>")
                val y = args.getOrNull(2)?.toIntOrNull() ?: return usage("input tap <x> <y>")
                robot.mouseMove(x, y)
                robot.mousePress(InputEvent.BUTTON1_DOWN_MASK)
                robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
                "Tap at $x,$y sent."
            }
            "swipe" -> {
                val x1 = args.getOrNull(1)?.toIntOrNull() ?: return usage("input swipe <x1> <y1> <x2> <y2> [ms]")
                val y1 = args.getOrNull(2)?.toIntOrNull() ?: return usage("input swipe <x1> <y1> <x2> <y2> [ms]")
                val x2 = args.getOrNull(3)?.toIntOrNull() ?: return usage("input swipe <x1> <y1> <x2> <y2> [ms]")
                val y2 = args.getOrNull(4)?.toIntOrNull() ?: return usage("input swipe <x1> <y1> <x2> <y2> [ms]")
                val duration = args.getOrNull(5)?.toLongOrNull()?.coerceIn(50L, 10_000L) ?: 300L
                robot.mouseMove(x1, y1)
                robot.mousePress(InputEvent.BUTTON1_DOWN_MASK)
                val steps = (duration / 15).coerceAtLeast(1).toInt()
                for (step in 1..steps) {
                    val t = step.toDouble() / steps
                    robot.mouseMove((x1 + (x2 - x1) * t).toInt(), (y1 + (y2 - y1) * t).toInt())
                    Thread.sleep(15)
                }
                robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
                "Swipe sent."
            }
            "text" -> {
                val value = args.drop(1).joinToString(" ")
                if (value.isEmpty()) return usage("input text <text>")
                typeText(robot, value)
                "Text sent."
            }
            "keyevent" -> {
                val code = args.getOrNull(1) ?: return usage("input keyevent <code|NAME>")
                pressKey(robot, code) ?: return "Unknown key event: $code"
                "Key event $code sent."
            }
            else -> usage("input <tap|swipe|text|keyevent> ...")
        }
    }

    override fun display(args: List<String>): String? {
        if (args.isEmpty()) return null
        val bounds = runCatching {
            GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration.bounds
        }.getOrNull() ?: return null
        val dpi = runCatching { Toolkit.getDefaultToolkit().screenResolution }.getOrDefault(96)
        return when (args.first().lowercase()) {
            "size" -> when {
                args.size == 1 || args[1] == "reset" -> "Physical size: " + bounds.width + "x" + bounds.height
                else -> "Changing the desktop resolution is controlled by the operating system, not PDB."
            }
            "density" -> when {
                args.size == 1 -> "Physical density: $dpi"
                args[1] == "reset" -> "Physical density: $dpi"
                else -> "Changing the desktop density is controlled by the operating system, not PDB."
            }
            else -> usage("wm <size|density> [value|reset]")
        }
    }

    override fun screenshot(file: File): String? {
        file.parentFile?.mkdirs()
        return runCatching {
            val robot = Robot()
            val bounds = Rectangle(Toolkit.getDefaultToolkit().screenSize)
            val image = robot.createScreenCapture(bounds)
            ImageIO.write(image, "png", file)
            "Screen capture saved to " + file.absolutePath
        }.getOrNull()
    }

    override fun screenRecord(file: File, args: List<String>): String? {
        file.parentFile?.mkdirs()
        val seconds = args.firstOrNull { it.startsWith("--time-limit=") }
            ?.substringAfter('=')?.toIntOrNull()
            ?: 10
        val duration = seconds.coerceIn(1, 30)
        return runCatching {
            val encoder = org.jcodec.api.awt.AWTSequenceEncoder.createSequenceEncoder(file, 10)
            val robot = Robot()
            val bounds = Rectangle(Toolkit.getDefaultToolkit().screenSize)
            val end = System.currentTimeMillis() + duration * 1_000L
            while (System.currentTimeMillis() < end) {
                encoder.encodeImage(robot.createScreenCapture(bounds))
                Thread.sleep(100L)
            }
            encoder.finish()
            "Screen recording saved to " + file.absolutePath
        }.getOrElse {
            "Could not record the screen: " + (it.message ?: it.javaClass.simpleName)
        }
    }

    override fun launch(request: List<String>): String? {
        fun value(flag: String): String? {
            val index = request.indexOf(flag)
            return if (index >= 0) request.getOrNull(index + 1) else null
        }
        val data = value("-d")
        if (data != null && (data.startsWith("http://") || data.startsWith("https://"))) {
            return runCatching {
                java.awt.Desktop.getDesktop().browse(java.net.URI(data))
                "Opened " + data
            }.getOrElse { super.launch(request) }
        }
        return super.launch(request)
    }

    override fun adbControl(args: List<String>): String? = AdbBridge.control(args)

    private fun typeText(robot: Robot, text: String) {
        val ascii = text.all { it.code in 32..126 }
        if (!ascii) {
            val clipboard = Toolkit.getDefaultToolkit().systemClipboard
            val previous = runCatching {
                clipboard.getContents(null)?.getTransferData(DataFlavor.stringFlavor) as? String
            }.getOrNull()
            clipboard.setContents(StringSelection(text), null)
            robot.keyPress(KeyEvent.VK_CONTROL)
            robot.keyPress(KeyEvent.VK_V)
            robot.keyRelease(KeyEvent.VK_V)
            robot.keyRelease(KeyEvent.VK_CONTROL)
            Thread.sleep(120)
            if (previous != null) clipboard.setContents(StringSelection(previous), null)
            return
        }
        for (char in text) {
            val code = KeyEvent.getExtendedKeyCodeForChar(char.code)
            if (code == KeyEvent.VK_UNDEFINED) continue
            val shift = char.isUpperCase() || char in "!@#\$%^&*()_+{}|:\"<>?~"
            if (shift) robot.keyPress(KeyEvent.VK_SHIFT)
            robot.keyPress(code)
            robot.keyRelease(code)
            if (shift) robot.keyRelease(KeyEvent.VK_SHIFT)
            Thread.sleep(8)
        }
    }

    private fun pressKey(robot: Robot, raw: String): String? {
        val code = raw.toIntOrNull() ?: when (raw.uppercase()) {
            "HOME", "KEYCODE_HOME" -> KeyEvent.VK_HOME
            "BACK", "KEYCODE_BACK" -> KeyEvent.VK_ESCAPE
            "ENTER", "KEYCODE_ENTER" -> KeyEvent.VK_ENTER
            "DEL", "BACKSPACE", "KEYCODE_DEL" -> KeyEvent.VK_BACK_SPACE
            "TAB", "KEYCODE_TAB" -> KeyEvent.VK_TAB
            "ESCAPE", "KEYCODE_ESCAPE" -> KeyEvent.VK_ESCAPE
            "MENU", "KEYCODE_MENU" -> KeyEvent.VK_CONTEXT_MENU
            "DPAD_UP", "KEYCODE_DPAD_UP" -> KeyEvent.VK_UP
            "DPAD_DOWN", "KEYCODE_DPAD_DOWN" -> KeyEvent.VK_DOWN
            "DPAD_LEFT", "KEYCODE_DPAD_LEFT" -> KeyEvent.VK_LEFT
            "DPAD_RIGHT", "KEYCODE_DPAD_RIGHT" -> KeyEvent.VK_RIGHT
            else -> null
        } ?: return null
        val awt = when (code) {
            3 -> KeyEvent.VK_HOME
            4 -> KeyEvent.VK_ESCAPE
            19 -> KeyEvent.VK_UP
            20 -> KeyEvent.VK_DOWN
            21 -> KeyEvent.VK_LEFT
            22 -> KeyEvent.VK_RIGHT
            23 -> KeyEvent.VK_ENTER
            24 -> KeyEvent.VK_PAGE_UP
            25 -> KeyEvent.VK_PAGE_DOWN
            61 -> KeyEvent.VK_TAB
            66 -> KeyEvent.VK_ENTER
            67 -> KeyEvent.VK_BACK_SPACE
            82 -> KeyEvent.VK_CONTEXT_MENU
            in 29..54 -> KeyEvent.VK_A + (code - 29)
            in 7..16 -> KeyEvent.VK_0 + ((code - 7) % 10)
            else -> code
        }
        robot.keyPress(awt)
        robot.keyRelease(awt)
        return raw
    }

    private fun usage(text: String): String = "Usage: $text"
}
