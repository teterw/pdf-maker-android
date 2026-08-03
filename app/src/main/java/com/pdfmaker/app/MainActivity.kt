package com.pdfmaker.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.content.IntentCompat
import com.pdfmaker.app.ui.HomeScreen
import com.pdfmaker.app.ui.PdfMakerTheme

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PdfMakerTheme {
                HomeScreen(viewModel)
            }
        }
        consumeSharedFiles(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeSharedFiles(intent)
    }

    /** Pulls URIs out of a share-sheet intent so "Share to PDF Maker" works. */
    private fun consumeSharedFiles(intent: Intent?) {
        if (intent == null) return
        val uris: List<Uri> = when (intent.action) {
            Intent.ACTION_SEND ->
                listOfNotNull(
                    IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                )

            Intent.ACTION_SEND_MULTIPLE ->
                IntentCompat.getParcelableArrayListExtra(
                    intent,
                    Intent.EXTRA_STREAM,
                    Uri::class.java
                ).orEmpty()

            else -> emptyList()
        }

        if (uris.isNotEmpty()) {
            viewModel.addUris(uris, persist = false)
            // Don't re-import the same files after a configuration change.
            intent.action = Intent.ACTION_MAIN
            intent.removeExtra(Intent.EXTRA_STREAM)
        }
    }
}
