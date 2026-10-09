package app.chatlens.asr

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract

/**
 * Reads the folder chosen via folder grant (ACTION_OPEN_DOCUMENT_TREE). Works without a storage permission.
 * Expects "WhatsApp Voice Notes" or a parent folder. Searches subfolders (week folders, Sent) up to [maxDepth]
 * and, to stay fast, only the [maxFoldersPerLevel] youngest subfolders by name at each level (week folders are named YYYYWW).
 */
class SafVoiceSource(
    private val ctx: Context, private val treeUri: Uri,
    private val maxDepth: Int = 3, private val maxFoldersPerLevel: Int = 16, private val maxFiles: Int = 4000,
) : VoiceFileSource {
    override val label: String = "Ordnerfreigabe"
    private val cr: ContentResolver get() = ctx.contentResolver

    private class Child(val docId: String, val name: String, val mime: String, val modified: Long, val size: Long)

    private fun children(parentDocId: String): List<Child> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED, DocumentsContract.Document.COLUMN_SIZE,
        )
        val out = ArrayList<Child>()
        cr.query(uri, cols, null, null, null)?.use { c ->
            while (c.moveToNext()) out.add(Child(c.getString(0), c.getString(1) ?: "", c.getString(2) ?: "", if (c.isNull(3)) 0L else c.getLong(3), if (c.isNull(4)) 0L else c.getLong(4)))
        }
        return out
    }

    override fun list(): List<VoiceFile> {
        val root = DocumentsContract.getTreeDocumentId(treeUri)
        val out = ArrayList<VoiceFile>()
        fun walk(docId: String, depth: Int) {
            val ch = children(docId)
            for (f in ch) {
                if (f.mime != DocumentsContract.Document.MIME_TYPE_DIR && out.size < maxFiles) {
                    out.add(VoiceFile(DocumentsContract.buildDocumentUriUsingTree(treeUri, f.docId).toString(), f.name, f.modified, f.size))
                }
            }
            if (depth < maxDepth) {
                ch.filter { it.mime == DocumentsContract.Document.MIME_TYPE_DIR }.sortedByDescending { it.name }.take(maxFoldersPerLevel).forEach { walk(it.docId, depth + 1) }
            }
        }
        walk(root, 0)
        return out
    }

    override fun read(f: VoiceFile): ByteArray =
        cr.openInputStream(Uri.parse(f.id))?.use { it.readBytes() } ?: throw java.io.IOException("Datei nicht zu oeffnen")
}
