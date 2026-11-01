package com.prism.core

import com.prism.launcher.minigames.EchoAssets
import com.prism.launcher.minigames.EchoMonster
import java.awt.BasicStroke
import java.awt.Color
import java.awt.RenderingHints
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Not an assertion about beauty: this renders the same sprite and tentacle ribbons the game uses
 * so the art can be inspected without launching either front end.
 */
class EchoMonsterPreviewTest {

    @Test
    fun `render a hunter preview`() {
        val pack = EchoAssets.pack
        val image = BufferedImage(1000, 900, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        graphics.color = Color(2, 1, 6)
        graphics.fillRect(0, 0, 1000, 900)

        val sprite = BufferedImage(EchoMonster.SPRITE_SIZE, EchoMonster.SPRITE_SIZE, BufferedImage.TYPE_INT_ARGB)
        sprite.setRGB(0, 0, EchoMonster.SPRITE_SIZE, EchoMonster.SPRITE_SIZE, pack.enemySprite, 0, EchoMonster.SPRITE_SIZE)

        val body = EchoMonster.Billboard(500f, 470f, 230f, 1.0)
        EchoMonster.tentacles(body, gait = 2.4, clock = 3.1, seed = 4).forEach { tentacle ->
            val ribbon = Path2D.Float()
            ribbon.moveTo(tentacle.leftX[0], tentacle.leftY[0])
            for (i in 1 until tentacle.leftX.size) ribbon.lineTo(tentacle.leftX[i], tentacle.leftY[i])
            for (i in tentacle.rightX.indices.reversed()) ribbon.lineTo(tentacle.rightX[i], tentacle.rightY[i])
            ribbon.closePath()
            graphics.color = if (tentacle.accent) Color(0x52, 0x63, 0x2A) else Color(0x4A, 0x24, 0x68)
            graphics.fill(ribbon)
            graphics.color = if (tentacle.accent) Color(0x2E, 0x34, 0x14) else Color(0x24, 0x10, 0x2E)
            graphics.stroke = BasicStroke(body.radius * 0.014f)
            graphics.draw(ribbon)

            val center = Path2D.Float()
            center.moveTo(tentacle.centerX[0], tentacle.centerY[0])
            for (i in 1 until tentacle.centerX.size) center.lineTo(tentacle.centerX[i], tentacle.centerY[i])
            graphics.color = if (tentacle.accent) Color(0xB3, 0xC6, 0x6A) else Color(0xB7, 0x7B, 0xD8)
            graphics.stroke = BasicStroke(tentacle.width[tentacle.width.size / 2] * 0.20f)
            graphics.draw(center)

            for (i in 2..6 step 2) {
                val radius = tentacle.width[i] * 0.24f
                graphics.color = if (tentacle.accent) Color(0xD7, 0xE2, 0x8E) else Color(0xE0, 0xA2, 0xEB)
                graphics.fillOval(
                    (tentacle.centerX[i] - radius).toInt(),
                    (tentacle.centerY[i] - radius).toInt(),
                    (radius * 2).toInt().coerceAtLeast(2),
                    (radius * 2).toInt().coerceAtLeast(2),
                )
                graphics.color = Color(0x2A, 0x12, 0x38)
                val inner = radius * 0.43f
                graphics.fillOval(
                    (tentacle.centerX[i] - inner).toInt(),
                    (tentacle.centerY[i] - inner).toInt(),
                    (inner * 2).toInt().coerceAtLeast(1),
                    (inner * 2).toInt().coerceAtLeast(1),
                )
            }
        }

        val side = EchoMonster.spriteSide(body.radius)
        graphics.drawImage(
            sprite,
            (body.centerX - side / 2f).toInt(),
            (body.centerY - side / 2f).toInt(),
            side.toInt(),
            side.toInt(),
            null,
        )
        graphics.dispose()

        val output = File("build/echo-hunter-preview.png")
        output.parentFile?.mkdirs()
        ImageIO.write(image, "png", output)
        assertTrue(output.isFile && output.length() > 10_000)
    }
}
