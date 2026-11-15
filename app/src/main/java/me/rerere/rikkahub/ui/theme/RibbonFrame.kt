package me.rerere.rikkahub.ui.theme

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import me.rerere.rikkahub.R

private val RibbonTopHeight: Dp = 60.dp // 稍微加高到60dp，配合向上提6dp，实际露出54dp
private val RibbonTopOffsetY: Dp = (-6).dp // 往上提一丢丢，藏住顶上的黑边，让最上面不空着
private const val RIBBON_EDGE_ALPHA = 0.88f

@Composable
fun RibbonBackdrop(
    modifier: Modifier = Modifier,
) {
    val painter = painterResource(id = R.drawable.bg_ribbon_frame)

    Box(modifier = modifier.fillMaxSize().zIndex(10f)) {
        Image(
            painter = painter,
            contentDescription = null,
            contentScale = ContentScale.FillBounds,
            alignment = Alignment.TopCenter,
            modifier = Modifier
                .fillMaxWidth()
                .height(RibbonTopHeight)
                .offset(y = RibbonTopOffsetY)
                .align(Alignment.TopCenter)
                .alpha(RIBBON_EDGE_ALPHA)
        )
    }
}
