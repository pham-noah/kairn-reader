package app.kairn.data

import android.content.Context
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile

/**
 * An opaque handle to one document inside a granted tree: the SAF uri as a string.
 *
 * A value class over String rather than an `Uri`, because the JVM's `android.net.Uri` is a stub - `Uri.parse`
 * throws in a unit test - and the whole point of this seam is that the backup path can run without a device.
 */
@JvmInline
value class DocHandle(val value: String)

/** One entry in a granted tree: the name the picker shows, a handle to write or delete, and whether it is a file. */
data class FolderDoc(val name: String?, val handle: DocHandle, val isFile: Boolean = true)

/**
 * The slice of SAF an automatic backup needs, and nothing else.
 *
 * An interface rather than `DocumentFile` directly: a tree can only be granted from a device's own picker, so
 * before this the write-and-prune path could be compiled and reviewed but never exercised - which is exactly
 * what the 0.8.3 notes said about it. With the folder behind this interface the path runs in [AutoBackupTest].
 */
interface BackupFolder {
    fun canWrite(): Boolean
    fun create(name: String, mime: String): FolderDoc?
    fun list(): List<FolderDoc>
    fun delete(handle: DocHandle)
}

/** The real folder: the granted tree, through DocumentFile, with every call failing rather than throwing. */
class SafBackupFolder(private val ctx: Context, private val tree: String) : BackupFolder {

    private fun dir(): DocumentFile? = runCatching { DocumentFile.fromTreeUri(ctx, tree.toUri()) }.getOrNull()

    override fun canWrite(): Boolean = runCatching { dir()?.canWrite() == true }.getOrDefault(false)

    override fun create(name: String, mime: String): FolderDoc? =
        runCatching { dir()?.createFile(mime, name) }.getOrNull()
            ?.let { FolderDoc(it.name, DocHandle(it.uri.toString())) }

    override fun list(): List<FolderDoc> = runCatching {
        dir()?.listFiles().orEmpty().map { FolderDoc(it.name, DocHandle(it.uri.toString()), it.isFile) }
    }.getOrDefault(emptyList())

    override fun delete(handle: DocHandle) {
        runCatching { DocumentFile.fromSingleUri(ctx, handle.value.toUri())?.delete() }
    }
}
