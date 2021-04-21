package io.nekohasekai.sagernet.widget

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.WriterException
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.ui.MainActivity
import java.nio.charset.StandardCharsets

class QRCodeDialog() : DialogFragment() {

    companion object {
        private const val KEY_URL = "io.nekohasekai.sagernet.QRCodeDialog.KEY_URL"
        private val iso88591 = StandardCharsets.ISO_8859_1.newEncoder()
    }

    constructor(url: String) : this() {
        arguments = bundleOf(Pair(KEY_URL, url))
    }

    /**
     * Based on:
     * https://android.googlesource.com/platform/packages/apps/Settings/+/0d706f0/src/com/android/settings/wifi/qrcode/QrCodeGenerator.java
     * https://android.googlesource.com/platform/packages/apps/Settings/+/8a9ccfd/src/com/android/settings/wifi/dpp/WifiDppQrCodeGeneratorFragment.java#153
     */
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?) = try {
        val url = arguments?.getString(KEY_URL)!!
        val size = resources.getDimensionPixelSize(R.dimen.qrcode_size)
        val hints = mutableMapOf<EncodeHintType, Any>()
        if (!iso88591.canEncode(url)) hints[EncodeHintType.CHARACTER_SET] = StandardCharsets.UTF_8.name()
        val qrBits = MultiFormatWriter().encode(url, BarcodeFormat.QR_CODE, size, size, hints)
        ImageView(context).apply {
            layoutParams = ViewGroup.LayoutParams(size, size)
            setImageBitmap(Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565).apply {
                for (x in 0 until size) for (y in 0 until size) {
                    setPixel(x, y, if (qrBits.get(x, y)) Color.BLACK else Color.WHITE)
                }
            })
        }
    } catch (e: WriterException) {
        Logs.w(e)
        (activity as MainActivity).snackbar().setText(e.readableMessage).show()
        dismiss()
        null
    }
}