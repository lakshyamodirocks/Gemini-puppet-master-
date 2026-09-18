package `in`.aasmaan.puppetmaster

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

class ShareActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sharedText = intent?.getStringExtra(Intent.EXTRA_TEXT) ?: ""
        val mainIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(Intent.EXTRA_TEXT, sharedText)
        }
        startActivity(mainIntent)
        finish()
    }
}
