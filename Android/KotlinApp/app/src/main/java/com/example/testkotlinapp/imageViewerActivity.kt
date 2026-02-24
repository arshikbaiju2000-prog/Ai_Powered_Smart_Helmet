package com.example.testkotlinapp

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity

class ImageViewerActivity : AppCompatActivity() {
    private fun decodeSampledBitmap(
        path: String,
        reqWidth: Int,
        reqHeight: Int
    ): Bitmap {
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeFile(path, options)

        options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight)
        options.inJustDecodeBounds = false
        options.inPreferredConfig = Bitmap.Config.RGB_565

        return BitmapFactory.decodeFile(path, options)
            ?: throw IllegalStateException("Failed to decode bitmap: $path")

    }

    private fun calculateInSampleSize(
        options: BitmapFactory.Options,
        reqWidth: Int,
        reqHeight: Int
    ): Int {
        val height = options.outHeight
        val width = options.outWidth
        var inSampleSize = 1

        if (height > reqHeight || width > reqWidth) {
            var halfHeight = height / 2
            var halfWidth = width / 2

            while (halfHeight / inSampleSize >= reqHeight &&
                halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }


    override fun onCreate(savedInstanceState: Bundle?)  {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_image_viewer)

        val imageView: ImageView = findViewById(R.id.fullImageView)
        val imagePath = intent.getStringExtra("imagePath")

        if (imagePath != null) {
            val metrics = resources.displayMetrics
            val bitmap = decodeSampledBitmap(
                imagePath,
                metrics.widthPixels,
                metrics.heightPixels
            )
            imageView.setImageBitmap(bitmap)
        }

    }
}