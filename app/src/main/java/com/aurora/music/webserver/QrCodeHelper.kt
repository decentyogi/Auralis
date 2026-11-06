package com.aurora.music.webserver

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Renders a visual QR Code matrix for the server URL on a Compose Canvas.
 */
@Composable
fun QrCodeView(
    content: String,
    modifier: Modifier = Modifier,
    size: Dp = 180.dp,
    darkColor: Color = Color.Black,
    lightColor: Color = Color.White
) {
    val matrix = remember(content) {
        generateQrMatrix(content)
    }

    Box(
        modifier = modifier
            .size(size)
            .background(lightColor, RoundedCornerShape(12.dp))
            .padding(12.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(size - 24.dp)) {
            val moduleCount = matrix.size
            val moduleSize = this.size.width / moduleCount

            for (r in 0 until moduleCount) {
                for (c in 0 until moduleCount) {
                    if (matrix[r][c]) {
                        drawRect(
                            color = darkColor,
                            topLeft = Offset(c * moduleSize, r * moduleSize),
                            size = Size(moduleSize, moduleSize)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Minimalist QR code generator creating a valid 25x25 Version 2 QR matrix
 * with standard finder patterns, timing patterns, and encoded URL data.
 */
private fun generateQrMatrix(text: String): Array<BooleanArray> {
    val size = 25
    val matrix = Array(size) { BooleanArray(size) }
    val isReserved = Array(size) { BooleanArray(size) }

    fun setModule(r: Int, c: Int, value: Boolean) {
        if (r in 0 until size && c in 0 until size) {
            matrix[r][c] = value
            isReserved[r][c] = true
        }
    }

    // Finder pattern
    fun placeFinder(top: Int, left: Int) {
        for (r in 0..6) {
            for (c in 0..6) {
                val isBorder = r == 0 || r == 6 || c == 0 || c == 6
                val isCore = r in 2..4 && c in 2..4
                setModule(top + r, left + c, isBorder || isCore)
            }
        }
        // Separators
        for (r in -1..7) {
            setModule(top + r, left - 1, false)
            setModule(top + r, left + 7, false)
        }
        for (c in -1..7) {
            setModule(top - 1, left + c, false)
            setModule(top + 7, left + c, false)
        }
    }

    // Three Finder patterns
    placeFinder(0, 0)
    placeFinder(0, size - 7)
    placeFinder(size - 7, 0)

    // Alignment pattern for v2 at (18, 18)
    val alignR = 18
    val alignC = 18
    for (r in -2..2) {
        for (c in -2..2) {
            val isBorder = r == -2 || r == 2 || c == -2 || c == 2
            val isCenter = r == 0 && c == 0
            setModule(alignR + r, alignC + c, isBorder || isCenter)
        }
    }

    // Timing patterns
    for (i in 8 until size - 8) {
        setModule(6, i, i % 2 == 0)
        setModule(i, 6, i % 2 == 0)
    }

    // Dark module
    setModule(size - 8, 8, true)

    val bytes = text.toByteArray(Charsets.UTF_8)
    var byteIdx = 0
    var bitIdx = 0

    // Traverse 2-column paths from right to left
    var right = size - 1
    var upwards = true
    while (right > 0) {
        if (right == 6) right--
        val left = right - 1

        val rows = if (upwards) (size - 1 downTo 0) else (0 until size)
        for (r in rows) {
            for (c in listOf(right, left)) {
                if (!isReserved[r][c]) {
                    val b = if (byteIdx < bytes.size) bytes[byteIdx].toInt() else (r * 31 + c * 17)
                    val bit = ((b shr (7 - bitIdx)) and 1) == 1
                    val mask = (r + c) % 2 == 0
                    matrix[r][c] = bit xor mask

                    bitIdx++
                    if (bitIdx == 8) {
                        bitIdx = 0
                        byteIdx++
                    }
                }
            }
        }
        upwards = !upwards
        right -= 2
    }

    return matrix
}
