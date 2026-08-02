package com.pdfmaker.app

import android.app.Application
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

class PdfMakerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // PDFBox-Android ships its font metrics as assets and must be pointed at them
        // before any PDDocument is touched.
        PDFBoxResourceLoader.init(applicationContext)
    }
}
