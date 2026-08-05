package com.speeduino.manager.storage

interface PlatformDirectory {
    val path: String
    fun createIfNotExists()
    fun createSubdirectory(name: String): PlatformDirectory
    fun listFiles(): List<PlatformFile>
    fun exists(): Boolean
}

interface PlatformFile {
    val path: String
    val name: String
    val size: Long
    val lastModified: Long
    fun exists(): Boolean
    fun readBytes(): ByteArray
    fun writeBytes(bytes: ByteArray)
    fun appendText(text: String)
    fun writeText(text: String)
    fun delete()
    fun parent(): PlatformDirectory
}
