package androidx.compose.ui.graphics

/**
 * DASH-AA platform shim — `android.graphics.Bitmap.asImageBitmap()`, the Android-only bridge from a
 * decoded bitmap to Compose. On the desktop the shim [android.graphics.Bitmap] already holds a Skia
 * image, which is exactly what a desktop `ImageBitmap` wraps.
 */
fun android.graphics.Bitmap.asImageBitmap(): ImageBitmap = skiaImage.toComposeImageBitmap()
