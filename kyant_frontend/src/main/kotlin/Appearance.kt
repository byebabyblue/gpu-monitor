import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.asComposeFontFamily
import androidx.compose.ui.unit.sp

@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
internal val XerverFontFamily = java.awt.Font("Microsoft YaHei UI", java.awt.Font.PLAIN, 16).asComposeFontFamily()
internal val XerverTextStyle = TextStyle(fontFamily = XerverFontFamily, fontWeight = FontWeight.Normal,
    fontSize = 16.sp, fontSynthesis = FontSynthesis.None)
