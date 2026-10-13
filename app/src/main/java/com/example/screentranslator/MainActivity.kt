package com.example.screentranslator

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private val OVERLAY_REQ_CODE = 1001
    private val CAPTURE_REQ_CODE = 1002
    private lateinit var mediaProjectionManager: MediaProjectionManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)

        val edtApiKey = findViewById<EditText>(R.id.edtApiKey)
        val btnStart = findViewById<Button>(R.id.btnStart)

        edtApiKey.setText(prefs.getString("api_key", ""))

        btnStart.setOnClickListener {
            val key = edtApiKey.text.toString().trim()
            if (key.isEmpty()) {
                Toast.makeText(this, "لطفاً API Key جمینای را وارد کنید", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            prefs.edit().putString("api_key", key).apply()

            if (!Settings.canDrawOverlays(this)) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivityForResult(intent, OVERLAY_REQ_CODE)
            } else {
                requestScreenCapture()
            }
        }
    }

    private fun requestScreenCapture() {
        startActivityForResult(
            mediaProjectionManager.createScreenCaptureIntent(),
            CAPTURE_REQ_CODE
        )
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == OVERLAY_REQ_CODE) {
            if (Settings.canDrawOverlays(this)) {
                requestScreenCapture()
            } else {
                Toast.makeText(this, "مجوز نمایش روی سایر برنامه‌ها تایید نشد", Toast.LENGTH_SHORT).show()
            }
        } else if (requestCode == CAPTURE_REQ_CODE && resultCode == RESULT_OK && data != null) {
            val serviceIntent = Intent(this, FloatingService::class.java).apply {
                putExtra("RESULT_CODE", resultCode)
                putExtra("DATA", data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            finish()
        }
    }
}
