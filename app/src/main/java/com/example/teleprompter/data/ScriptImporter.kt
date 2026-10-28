package com.example.teleprompter.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.zip.ZipInputStream

/** Imports .txt, .pdf and .docx into plain text. */
class ScriptImporter(private val context: Context) {

    suspend fun import(uri: Uri): String = withContext(Dispatchers.IO) {
        val name = displayName(uri).lowercase()
        val mime = context.contentResolver.getType(uri).orEmpty()
        when {
            name.endsWith(".pdf") || mime == "application/pdf" -> readPdf(uri)
            name.endsWith(".docx") || mime.contains("wordprocessingml") -> readDocx(uri)
            else -> readTxt(uri)
        }
    }

    private fun readTxt(uri: Uri) =
        context.contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) }

    private fun readPdf(uri: Uri): String {
        PDFBoxResourceLoader.init(context)
        return context.contentResolver.openInputStream(uri)!!.use { s ->
            PDDocument.load(s).use { PDFTextStripper().getText(it).trim() }
        }
    }

    private fun readDocx(uri: Uri): String {
        context.contentResolver.openInputStream(uri)!!.use { raw ->
            ZipInputStream(raw).use { zip ->
                var e = zip.nextEntry
                while (e != null) {
                    if (e.name == "word/document.xml") {
                        val xml = zip.readBytes().toString(Charsets.UTF_8)
                        return xml.replace("</w:p>", "\n").replace(Regex("<w:(br|tab)\\s*/>"), " ")
                            .replace(Regex("<[^>]+>"), "")
                            .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                            .replace("&quot;", "\"").replace("&apos;", "'").trim()
                    }
                    e = zip.nextEntry
                }
            }
        }
        return ""
    }

    private fun displayName(uri: Uri): String =
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (i >= 0 && c.moveToFirst()) c.getString(i) else null
        } ?: uri.lastPathSegment.orEmpty()
}
