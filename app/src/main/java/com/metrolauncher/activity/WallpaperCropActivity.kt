package com.metrolauncher.activity

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import com.metrolauncher.R
import com.metrolauncher.view.WallpaperCropView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class WallpaperCropActivity : AppCompatActivity() {

    private lateinit var cropView: WallpaperCropView
    private val scope = MainScope()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_wallpaper_crop)

        cropView = findViewById(R.id.crop_view)
        val uriStr = intent.getStringExtra("uri")
        if (uriStr == null) {
            finish()
            return
        }

        val uri = uriStr.toUri()
        loadBitmap(uri)

        findViewById<Button>(R.id.btn_cancel).setOnClickListener { finish() }
        findViewById<Button>(R.id.btn_apply).setOnClickListener { applyCrop() }
    }

    private fun loadBitmap(uri: Uri) {
        scope.launch {
            val bmp = withContext(Dispatchers.IO) {
                runCatching {
                    contentResolver.openInputStream(uri)?.use { 
                        BitmapFactory.decodeStream(it)
                    }
                }.getOrNull()
            }
            if (bmp != null) {
                cropView.setBitmap(bmp)
            } else {
                Toast.makeText(this@WallpaperCropActivity, "Error loading image", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    private fun applyCrop() {
        val cropped = cropView.getCroppedBitmap() ?: return
        scope.launch {
            val savedUri = withContext(Dispatchers.IO) {
                runCatching {
                    val file = File(filesDir, "custom_wallpaper.jpg")
                    FileOutputStream(file).use { 
                        cropped.compress(Bitmap.CompressFormat.JPEG, 90, it)
                    }
                    Uri.fromFile(file).toString()
                }.getOrNull()
            }
            if (savedUri != null) {
                val data = Intent().apply { putExtra("cropped_uri", savedUri) }
                setResult(RESULT_OK, data)
                finish()
            } else {
                Toast.makeText(this@WallpaperCropActivity, "Error saving image", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
