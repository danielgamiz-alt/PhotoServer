package com.photosync.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton

/**
 * The phone → computer bridge. A brand-new user opens the phone app and learns
 * they also need the free PhotoSync Server on their home PC — but they're on the
 * phone, not the computer. This screen lets them send the install link to
 * themselves (email / WhatsApp / etc.) so they can open it on the computer and
 * tap "Download for Windows", or copy/read the address directly.
 *
 * Reached from the overflow menu and from the "no server found" help in Settings.
 */
class ComputerSetupActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_computer_setup)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val url = getString(R.string.landing_url)
        findViewById<TextView>(R.id.setupUrlText).text = url

        findViewById<MaterialButton>(R.id.sendLinkButton).setOnClickListener {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, getString(R.string.invite_subject))
                putExtra(Intent.EXTRA_TEXT, getString(R.string.invite_text, url))
            }
            startActivity(Intent.createChooser(send, getString(R.string.setup_computer_send_chooser)))
        }

        findViewById<MaterialButton>(R.id.copyLinkButton).setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("PhotoSync", url))
            Toast.makeText(this, R.string.setup_computer_link_copied, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
