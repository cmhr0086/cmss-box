package io.nekohasekai.sagernet.ui

import android.content.Intent
import android.os.Bundle

class SimpleSettingsActivity : ThemedActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(
            Intent(this, SimpleMainActivity::class.java).apply {
                putExtra(SimpleMainActivity.EXTRA_START_TAB, SimpleMainActivity.START_TAB_SETTINGS)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
        )
        finish()
    }
}
