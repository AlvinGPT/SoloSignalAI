package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.ui.theme.CardWhite
import com.example.ui.theme.InkBlack
import com.example.ui.theme.InkCharcoal
import com.example.ui.theme.MutedSlate
import com.example.ui.theme.PastelLavenderSoft

import androidx.compose.foundation.isSystemInDarkTheme

import androidx.compose.ui.layout.ContentScale

/**
 * Single source of truth composable rendering the official SoloSignal logo
 * (@drawable/ic_solosignal_logo_light / @drawable/ic_solosignal_logo_dark)
 * matching the user's active theme or explicit surface requirement.
 */
@Composable
fun SoloSignalLogo(
    size: Dp = 40.dp,
    contentDescription: String? = "SoloSignal logo",
    modifier: Modifier = Modifier,
    isDark: Boolean = isSystemInDarkTheme()
) {
    val resId = if (isDark) R.drawable.ic_solosignal_logo_dark else R.drawable.ic_solosignal_logo_light
    Image(
        painter = painterResource(id = resId),
        contentDescription = contentDescription,
        contentScale = ContentScale.Fit,
        modifier = modifier
            .size(size)
            .testTag("solosignal_official_logo")
    )
}

/**
 * Rounded pastel card (24.dp corners) matching the SoloSignal visual style.
 */
@Composable
fun PastelCard(
    modifier: Modifier = Modifier,
    backgroundColor: Color = CardWhite,
    containerColor: Color = backgroundColor,
    cornerRadius: Dp = 24.dp,
    borderColor: Color = InkBlack.copy(alpha = 0.08f),
    contentPadding: PaddingValues = PaddingValues(18.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(cornerRadius),
        colors = CardDefaults.cardColors(
            containerColor = containerColor,
            contentColor = InkBlack
        ),
        border = BorderStroke(1.dp, borderColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier.padding(contentPadding),
            content = content
        )
    }
}

@Composable
fun SectionEyebrow(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MutedSlate
) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall.copy(
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.9.sp
        ),
        color = color,
        modifier = modifier
    )
}

@Composable
fun StatusPillBadge(
    label: String,
    containerColor: Color,
    contentColor: Color = InkBlack,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.semantics(mergeDescendants = true) {
            contentDescription = "Status: $label"
        },
        shape = CircleShape,
        color = containerColor,
        border = BorderStroke(1.dp, contentColor.copy(alpha = 0.15f))
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = contentColor,
            maxLines = 1,
            softWrap = false,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }
}

/**
 * Status pill that pairs an icon + text label + background color so status is never
 * communicated by color alone.
 */
@Composable
fun StatusBadgePill(
    icon: ImageVector,
    text: String,
    containerColor: Color,
    contentColor: Color = InkBlack,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.semantics(mergeDescendants = true) {
            contentDescription = "Status: $text"
        },
        shape = CircleShape,
        color = containerColor,
        border = BorderStroke(1.dp, contentColor.copy(alpha = 0.15f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = contentColor,
                maxLines = 1,
                softWrap = false,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * High-contrast pill primary action button with >= 52.dp touch target height.
 */
@Composable
fun PrimaryPillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    containerColor: Color = InkBlack,
    contentColor: Color = Color.White,
    enabled: Boolean = true,
    tag: String? = null
) {
    val taggedModifier = if (tag != null) modifier.testTag(tag) else modifier
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = taggedModifier.defaultMinSize(minHeight = 52.dp),
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = containerColor,
            contentColor = contentColor,
            disabledContainerColor = InkBlack.copy(alpha = 0.5f),
            disabledContentColor = Color.White.copy(alpha = 0.9f)
        ),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp)
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            softWrap = false,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
        )
    }
}

/**
 * Secondary outlined pill button with >= 48.dp touch target height.
 */
@Composable
fun SecondaryPillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    containerColor: Color = CardWhite,
    contentColor: Color = InkBlack,
    enabled: Boolean = true,
    tag: String? = null
) {
    val taggedModifier = if (tag != null) modifier.testTag(tag) else modifier
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = taggedModifier.defaultMinSize(minHeight = 48.dp),
        shape = CircleShape,
        border = BorderStroke(1.5.dp, InkBlack.copy(alpha = 0.25f)),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = containerColor,
            contentColor = contentColor
        ),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp)
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            softWrap = false,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
        )
    }
}

/**
 * Concise safety disclaimer card.
 */
@Composable
fun ExperimentalDisclaimerCard(
    modifier: Modifier = Modifier,
    compact: Boolean = false
) {
    PastelCard(
        backgroundColor = PastelLavenderSoft,
        cornerRadius = 20.dp,
        contentPadding = PaddingValues(if (compact) 14.dp else 16.dp),
        modifier = modifier
            .fillMaxWidth()
            .testTag("experimental_disclaimer_card")
    ) {
        Row(
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(InkBlack),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Outlined.Info,
                    contentDescription = "Safety limitation notice",
                    tint = CardWhite,
                    modifier = Modifier.size(16.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "SoloSignal can miss falls or raise false alarms.",
                    style = MaterialTheme.typography.titleSmall,
                    color = InkBlack
                )
                if (!compact) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "SoloSignal is a personal-safety check-in aid, not a certified medical device or guaranteed emergency response service.",
                        style = MaterialTheme.typography.bodySmall,
                        color = InkCharcoal
                    )
                }
            }
        }
    }
}
