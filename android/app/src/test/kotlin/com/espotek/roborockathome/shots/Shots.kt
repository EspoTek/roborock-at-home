package com.espotek.roborockathome.shots

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runAndroidComposeUiTest
import com.espotek.roborockathome.fixtures.TestFrame
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.robolectric.RuntimeEnvironment
import java.io.File

/** Window sizes for [shot], in dp. Pictures are drawn at 2 px per dp. */
enum class ShotSize(val widthDp: Int, val heightDp: Int, internal val tag: String) {
    /** The designer's reference phone. */
    Phone(412, 915, ""),
    SmallPhone(360, 640, "-360x640"),

    /** A phone on its side: wider than the 600 dp column the shell keeps to. */
    Landscape(915, 412, "-landscape"),

    /** The phone's width with room for a long page, such as a gallery page at large text. */
    Tall(412, 2400, "-tall"),
}

/**
 * Draws [content] light and dark into `app/build/shots/<name>-light.png` and `<name>-dark.png`, on
 * the JVM with no emulator. Call it from a test in a class annotated
 * `@RunWith(RobolectricTestRunner::class)`; run them with
 * `./gradlew :app:testDebugUnitTest`.
 *
 * A [size] other than Phone and a [fontScale] other than 1 add to the file name
 * (`<name>-360x640-text2x-light.png`), so variants never overwrite each other. [content] is drawn
 * in a [TestFrame], at the moment the fixtures were recorded. [act] runs once the content is
 * showing and before the picture is taken: click, type, or [holdDown] to show a pressed state.
 */
// Roborazzi marks screen capture experimental; it is the call that also takes dialogs and popups.
@OptIn(ExperimentalRoborazziApi::class)
fun shot(
    name: String,
    size: ShotSize = ShotSize.Phone,
    fontScale: Float = 1f,
    act: ComposeUiTest.() -> Unit = {},
    content: @Composable () -> Unit,
) {
    val dir = File(checkNotNull(System.getProperty("rah.shots.dir")) { "Run the shots through Gradle, which says where they go" })
    val orientation = if (size.widthDp > size.heightDp) "land" else "port"
    RuntimeEnvironment.setQualifiers("w${size.widthDp}dp-h${size.heightDp}dp-$orientation-xhdpi")
    RuntimeEnvironment.setFontScale(fontScale)
    val text = if (fontScale == 1f) "" else "-text" + fontScale.toString().removeSuffix(".0") + "x"
    for (dark in listOf(false, true)) {
        val file = File(dir, "$name${size.tag}$text-${if (dark) "dark" else "light"}.png")
        runAndroidComposeUiTest<ComponentActivity> {
            setContent { TestFrame(dark, content = content) }
            act()
            waitForIdle()
            captureScreenRoboImage(file)
        }
        println("Wrote $file")
    }
}

/** Puts a finger down, and leaves it there, on every node tagged [tag]: their pressed state. */
fun ComposeUiTest.holdDown(tag: String) {
    val nodes = onAllNodesWithTag(tag)
    val count = nodes.fetchSemanticsNodes().size
    check(count > 0) { "Nothing is tagged $tag" }
    for (i in 0 until count) nodes[i].performTouchInput { down(i, center) }
    mainClock.advanceTimeBy(500)
}
