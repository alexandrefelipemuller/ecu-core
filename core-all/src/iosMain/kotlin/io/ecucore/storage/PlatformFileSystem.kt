package io.ecucore.storage

import io.ecucore.shared.Logger
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.Foundation.*

private const val TAG = "PlatformFileSystem"

@OptIn(ExperimentalForeignApi::class)
object PlatformFileSystem {
    fun getAppBaseDir(): PlatformDirectory {
        return IosPlatformDirectory(getDocumentsDirectory())
    }

    fun getSessionsDir(): PlatformDirectory {
        return IosPlatformDirectory(getDocumentsDirectory()).createSubdirectory("sessions")
    }

    fun getLogsDir(): PlatformDirectory {
        return IosPlatformDirectory(getDocumentsDirectory()).createSubdirectory("logs")
    }

    fun getPreferencesDir(): PlatformDirectory {
        return IosPlatformDirectory(getDocumentsDirectory()).createSubdirectory("preferences")
    }

    private fun getDocumentsDirectory(): String {
        val paths = NSSearchPathForDirectoriesInDomains(
            directory = NSDocumentDirectory,
            domainMask = NSUserDomainMask,
            expandTilde = true
        )
        return paths.firstOrNull() as? String ?: "${NSHomeDirectory()}/Documents"
    }
}

@OptIn(ExperimentalForeignApi::class)
class IosPlatformDirectory(
    override val path: String
) : PlatformDirectory {

    private val nsFileManager = NSFileManager.defaultManager

    init {
        Logger.d(TAG, "IosPlatformDirectory created: $path")
    }

    override fun createIfNotExists() {
        nsFileManager.createDirectoryAtPath(
            path,
            withIntermediateDirectories = true,
            attributes = null,
            error = null
        )
    }

    override fun createSubdirectory(name: String): PlatformDirectory {
        createIfNotExists()
        val newPath = path + "/$name"
        return IosPlatformDirectory(newPath)
    }

    override fun listFiles(): List<PlatformFile> {
        createIfNotExists()
        return nsFileManager.contentsOfDirectoryAtPath(path, error = null)
            ?.mapNotNull { item ->
                val itemName = item as? String ?: return@mapNotNull null
                IosPlatformFile("$path/$itemName")
            }
            .orEmpty()
    }

    override fun exists(): Boolean {
        return nsFileManager.fileExistsAtPath(path, isDirectory = null)
    }

    override fun toString(): String = "IosPlatformDirectory($path)"
}

@OptIn(ExperimentalForeignApi::class)
class IosPlatformFile(
    override val path: String
) : PlatformFile {

    private val nsFileManager = NSFileManager.defaultManager
    private val nsPath = path

    override val name: String
        get() = nsPath.substringAfterLast("/")

    override val size: Long
        get() = nsFileManager.attributesOfItemAtPath(nsPath, error = null)?.get(NSFileSize)
            ?.let { (it as? NSNumber)?.longValue } ?: -1

    override val lastModified: Long
        get() = nsFileManager.attributesOfItemAtPath(nsPath, error = null)
            ?.get(NSFileModificationDate)
            ?.let { (it as? NSDate)?.timeIntervalSince1970?.toLong() } ?: 0

    override fun exists(): Boolean = nsFileManager.fileExistsAtPath(nsPath, isDirectory = null)

    override fun readBytes(): ByteArray {
        val nsData = NSData.dataWithContentsOfFile(nsPath)
        return nsData?.bytes?.reinterpret<ByteVar>()?.readBytes(nsData.length.toInt()) ?: ByteArray(0)
    }

    override fun writeBytes(bytes: ByteArray) {
        parent().createIfNotExists()
        val nsData = if (bytes.isEmpty()) {
            NSData.data()
        } else {
            bytes.usePinned { pinned ->
                NSData.dataWithBytes(pinned.addressOf(0), bytes.size.toULong())
            }
        }
        val written = nsData.writeToFile(nsPath, atomically = true)
        if (!written) {
            throw IOException("Failed to write file: $nsPath")
        }
    }

    override fun appendText(text: String) {
        val existing = if (exists()) readBytes() else ByteArray(0)
        writeBytes(existing + text.encodeToByteArray())
    }

    override fun writeText(text: String) {
        parent().createIfNotExists()
        val nsString = text as NSString
        val nsData = nsString.dataUsingEncoding(NSUTF8StringEncoding)
        val written = nsData?.writeToFile(nsPath, atomically = true)
        if (written != true) {
            throw IOException("Failed to write text file: $nsPath")
        }
    }

    override fun delete() {
        nsFileManager.removeItemAtPath(nsPath, error = null)
    }

    override fun parent(): PlatformDirectory {
        val parentPath = nsPath.substringBeforeLast("/")
        return IosPlatformDirectory(parentPath)
    }

    override fun toString(): String = "IosPlatformFile($path)"
}

// Simple IOException for native code
class IOException(message: String) : Exception(message)
