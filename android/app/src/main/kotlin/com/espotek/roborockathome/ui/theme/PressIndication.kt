package com.espotek.roborockathome.ui.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.Interaction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.currentValueOf
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The kit's press and focus feedback, in place of Material's ripple: a wash of the content colour
 * that comes and goes in [RahMotion.PRESS]. It is the theme's LocalIndication, so a plain
 * Modifier.clickable gets it too. It draws over the whole node, so clip the node to its shape first.
 */
internal object PressIndication : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = PressNode(interactionSource)
    override fun equals(other: Any?) = other === this
    override fun hashCode() = System.identityHashCode(this)
}

private class PressNode(private val source: InteractionSource) :
    Modifier.Node(), DrawModifierNode, CompositionLocalConsumerModifierNode {
    private val wash = Animatable(0f)

    override fun onAttach() {
        coroutineScope.launch {
            val held = mutableSetOf<Interaction>()
            var arriving: Job? = null
            source.interactions.collect { interaction ->
                when (interaction) {
                    is PressInteraction.Press, is FocusInteraction.Focus -> held += interaction
                    is PressInteraction.Release -> held -= interaction.press
                    is PressInteraction.Cancel -> held -= interaction.press
                    is FocusInteraction.Unfocus -> held -= interaction.focus
                }
                if (held.isNotEmpty()) {
                    arriving = launch { wash.animateTo(1f, tween(RahMotion.PRESS)) }
                } else {
                    // A quick tap still shows: the wash finishes arriving before it leaves, unless
                    // the control has been pressed again by then.
                    val waitFor = arriving
                    launch {
                        waitFor?.join()
                        if (held.isEmpty()) wash.animateTo(0f, tween(RahMotion.PRESS))
                    }
                }
            }
        }
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        drawRect(currentValueOf(LocalContentColor), alpha = 0.12f * wash.value)
    }
}
