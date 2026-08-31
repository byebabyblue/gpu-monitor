import java.awt.Dimension
import java.awt.EventQueue
import java.awt.Font
import java.awt.Frame
import java.awt.GraphicsEnvironment
import java.awt.Insets
import java.awt.Point
import java.awt.Rectangle
import java.awt.Toolkit
import java.awt.Window
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import javax.swing.JDialog
import javax.swing.JMenuItem
import javax.swing.JPopupMenu
import javax.swing.WindowConstants
import javax.swing.event.PopupMenuEvent
import javax.swing.event.PopupMenuListener

internal object TrayMenuText {
    const val Open = "\u6253\u5F00 Xerver"
    const val Exit = "\u9000\u51FA Xerver"
    const val Sample = Open + Exit
}

internal fun trayMenuFont(): Font {
    // Family names returned using the user's locale can be Chinese rather than
    // English. Match locale-independent names, then test the actual glyphs.
    val available = GraphicsEnvironment.getLocalGraphicsEnvironment()
        .getAvailableFontFamilyNames(Locale.ROOT).toList()
    val preferred = listOf(
        "Microsoft YaHei UI", "Microsoft YaHei", "DengXian", "SimSun",
        "Noto Sans CJK SC", "Noto Sans SC", Font.DIALOG,
    )
    return (preferred + available).asSequence().distinct()
        .filter { it == Font.DIALOG || it in available }
        .map { Font(it, Font.PLAIN, 14) }
        .firstOrNull { it.canDisplayUpTo(TrayMenuText.Sample) == -1 }
        ?: Font(Font.DIALOG, Font.PLAIN, 14)
}

internal fun createTrayPopup(onOpen: () -> Unit, onExit: () -> Unit): JPopupMenu {
    check(EventQueue.isDispatchThread()) { "Tray menus must be created on the AWT event thread" }
    val selectedFont = trayMenuFont()
    val chineseSupported = selectedFont.canDisplayUpTo(TrayMenuText.Sample) == -1
    return JPopupMenu().apply {
        font = selectedFont
        isLightWeightPopupEnabled = false
        accessibleContext.accessibleName = "Xerver tray menu"
        fun item(label: String, action: () -> Unit) = JMenuItem(label).apply {
            font = selectedFont
            margin = Insets(6, 12, 6, 12)
            addActionListener { action() }
        }
        // If a machine genuinely has no CJK font, readable English is preferable
        // to missing-glyph squares. Chinese-capable systems keep Chinese labels.
        add(item(if (chineseSupported) TrayMenuText.Open else "Open Xerver", onOpen))
        addSeparator()
        add(item(if (chineseSupported) TrayMenuText.Exit else "Exit Xerver", onExit))
    }
}

internal fun trayPopupPosition(pointer: Point, menu: Dimension, workArea: Rectangle): Point = Point(
    pointer.x.coerceIn(workArea.x, workArea.x + (workArea.width - menu.width).coerceAtLeast(0)),
    pointer.y.coerceIn(workArea.y, workArea.y + (workArea.height - menu.height).coerceAtLeast(0)),
)

internal fun <T> onAwtEventThread(action: () -> T): T {
    if (EventQueue.isDispatchThread()) return action()
    val result = AtomicReference<Result<T>>()
    EventQueue.invokeAndWait { result.set(runCatching(action)) }
    return result.get().getOrThrow()
}

/** Native tray icon, but Unicode text is painted by Swing instead of AWT's
 * legacy native MenuItem font peer. The temporary owner also works when the
 * main Compose window has been hidden to the tray. */
internal class TrayContextMenu(onOpen: () -> Unit, onExit: () -> Unit) : AutoCloseable {
    private var anchor: JDialog? = null
    private var disposed = false
    private val popup = createTrayPopup(
        onOpen = { hide(); onOpen() },
        onExit = { hide(); onExit() },
    )
    val fontName: String = popup.font.getFamily(Locale.ROOT)
    val chineseSupported: Boolean = popup.font.canDisplayUpTo(TrayMenuText.Sample) == -1
    val isVisible: Boolean get() = popup.isVisible

    init {
        popup.addPopupMenuListener(object : PopupMenuListener {
            override fun popupMenuWillBecomeVisible(event: PopupMenuEvent) = Unit
            override fun popupMenuWillBecomeInvisible(event: PopupMenuEvent) { anchor?.isVisible = false }
            override fun popupMenuCanceled(event: PopupMenuEvent) { anchor?.isVisible = false }
        })
    }

    fun show(pointer: Point) {
        check(EventQueue.isDispatchThread())
        if (disposed || popup.isVisible) return
        val environment = GraphicsEnvironment.getLocalGraphicsEnvironment()
        val configuration = environment.screenDevices.map { it.defaultConfiguration }
            .firstOrNull { it.bounds.contains(pointer) } ?: environment.defaultScreenDevice.defaultConfiguration
        val insets = Toolkit.getDefaultToolkit().getScreenInsets(configuration)
        val screen = configuration.bounds
        val workArea = Rectangle(
            screen.x + insets.left, screen.y + insets.top,
            screen.width - insets.left - insets.right, screen.height - insets.top - insets.bottom,
        )
        val owner = anchor ?: JDialog(null as Frame?, "Xerver tray menu", false, configuration).apply {
            isUndecorated = true
            type = Window.Type.POPUP
            defaultCloseOperation = WindowConstants.DO_NOTHING_ON_CLOSE
            isAlwaysOnTop = true
            focusableWindowState = true
            setSize(1, 1)
            addWindowFocusListener(object : WindowAdapter() {
                override fun windowLostFocus(event: WindowEvent) {
                    val belongsToPopup = generateSequence(event.oppositeWindow) { it.owner }
                        .any { it === this@apply }
                    if (!belongsToPopup) this@TrayContextMenu.hide()
                }
            })
            anchor = this
        }
        owner.location = trayPopupPosition(pointer, popup.preferredSize, workArea)
        owner.isVisible = true
        owner.toFront()
        owner.requestFocus()
        popup.show(owner.contentPane, 0, 0)
        popup.requestFocusInWindow()
        StartupDiagnostics.log("托盘菜单已打开 renderer=Swing font=$fontName chinese=$chineseSupported")
    }

    fun hide() {
        popup.isVisible = false
        anchor?.isVisible = false
    }

    override fun close() {
        check(EventQueue.isDispatchThread())
        disposed = true
        hide()
        anchor?.dispose()
        anchor = null
    }
}
