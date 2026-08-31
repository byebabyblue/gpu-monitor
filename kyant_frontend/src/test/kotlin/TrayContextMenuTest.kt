import java.awt.Dimension
import java.awt.GraphicsEnvironment
import java.awt.Point
import java.awt.Rectangle
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import javax.swing.JMenuItem
import javax.swing.JPopupMenu
import org.junit.jupiter.api.Assumptions.assumeFalse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TrayContextMenuTest {
    @Test
    fun labelsAreUnicodeNotMojibake() {
        assertEquals(listOf(0x6253, 0x5F00), TrayMenuText.Open.take(2).map { it.code })
        assertEquals(listOf(0x9000, 0x51FA), TrayMenuText.Exit.take(2).map { it.code })
    }

    @Test
    fun menuUsesSwingAndAFontThatCanRenderEveryVisibleCharacter() = onAwtEventThread {
        val popup = createTrayPopup({}, {})
        assertEquals(JPopupMenu::class.java, popup.javaClass)
        popup.components.filterIsInstance<JMenuItem>().forEach {
            assertEquals(-1, it.font.canDisplayUpTo(it.text), "Missing glyphs in ${it.font.family}: ${it.text}")
        }
        if (popup.font.canDisplayUpTo(TrayMenuText.Sample) == -1) {
            assertEquals(TrayMenuText.Open, (popup.getComponent(0) as JMenuItem).text)
            assertEquals(TrayMenuText.Exit, (popup.getComponent(2) as JMenuItem).text)
        }
    }

    @Test
    fun menuActionsRemainFunctional() = onAwtEventThread {
        var opened = 0
        var exited = 0
        val popup = createTrayPopup({ opened++ }, { exited++ })
        (popup.getComponent(0) as JMenuItem).doClick(0)
        (popup.getComponent(2) as JMenuItem).doClick(0)
        assertEquals(1, opened)
        assertEquals(1, exited)
    }

    @Test
    fun menuCanOpenAndCloseWithoutAnyMainWindow() {
        assumeFalse(GraphicsEnvironment.isHeadless())
        val root = Files.createTempDirectory("xerver tray test ").toFile()
        val previous = System.getProperty("xerver.appdata.root")
        try {
            System.setProperty("xerver.appdata.root", root.absolutePath)
            onAwtEventThread {
                val menu = TrayContextMenu({}, {})
                try {
                    menu.show(Point(80, 80))
                    assertTrue(menu.isVisible)
                    menu.hide()
                    assertFalse(menu.isVisible)
                    menu.show(Point(100, 100))
                    assertTrue(menu.isVisible)
                } finally { menu.close() }
                assertFalse(menu.isVisible)
                menu.show(Point(80, 80))
                assertFalse(menu.isVisible, "A disposed menu must not leave a hidden owner window behind")
            }
        } finally {
            if (previous == null) System.clearProperty("xerver.appdata.root")
            else System.setProperty("xerver.appdata.root", previous)
            root.deleteRecursively()
        }
    }

    @Test
    fun popupStaysAboveTaskbarAndWithinRightEdge() {
        assertEquals(Point(1720, 930), trayPopupPosition(Point(1900, 1060), Dimension(200, 110), Rectangle(0, 0, 1920, 1040)))
    }

    @Test
    fun popupSupportsNegativeMonitorCoordinatesAndOversizedMenus() {
        assertEquals(Point(-1920, 0), trayPopupPosition(Point(-2000, -10), Dimension(200, 100), Rectangle(-1920, 0, 1920, 1040)))
        assertEquals(Point(-1920, 0), trayPopupPosition(Point(-20, 900), Dimension(2200, 1200), Rectangle(-1920, 0, 1920, 1040)))
    }

    @Test
    fun renderTheActualMenuForVisualReview() = onAwtEventThread {
        val popup = createTrayPopup({}, {})
        popup.size = popup.preferredSize
        popup.doLayout()
        val scale = 2
        val image = BufferedImage(popup.width * scale, popup.height * scale, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try {
            graphics.scale(scale.toDouble(), scale.toDouble())
            popup.printAll(graphics)
        } finally { graphics.dispose() }
        val output = File("build/reports/tray-menu-preview.png")
        output.parentFile.mkdirs()
        assertTrue(ImageIO.write(image, "png", output))
    }
}
