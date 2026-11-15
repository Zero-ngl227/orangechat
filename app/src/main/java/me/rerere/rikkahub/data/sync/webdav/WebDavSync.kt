package me.rerere.rikkahub.data.sync.webdav

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import io.ktor.client.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.files.SkillPaths
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.WebDavConfig
import me.rerere.rikkahub.data.datastore.migration.SettingsJsonMigrator
import me.rerere.rikkahub.plugin.repository.PluginRepository
import me.rerere.rikkahub.plugin.repository.PluginSettingsExport
import me.rerere.rikkahub.plugin.scanner.PluginScanner
import me.rerere.rikkahub.utils.fileSizeToString
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

private const val TAG = "WebDavSync"

class WebDavSync(
    private val settingsStore: SettingsStore,
    private val json: Json,
    private val context: Context,
    private val httpClient: HttpClient,
    private val pluginRepository: PluginRepository,
) {
    private fun getClient(config: WebDavConfig): WebDavClient {
        return WebDavClient(config, httpClient)
    }

    suspend fun testConnection(config: WebDavConfig) = withContext(Dispatchers.IO) {
        val client = getClient(config)
        client.propfind(depth = 0).getOrThrow()
    }

    suspend fun backup(config: WebDavConfig) = withContext(Dispatchers.IO) {
        val file = prepareBackupFile(config, includePlugins = false)
        val client = getClient(config)
        client.ensureCollectionExists().getOrThrow()
        client.put(
            path = file.name,
            file = file,
            contentType = "application/zip"
        ).getOrThrow()
        file.delete()
    }

    suspend fun listBackupFiles(config: WebDavConfig): List<WebDavBackupItem> = withContext(Dispatchers.IO) {
        val client = getClient(config)
        client.ensureCollectionExists().getOrThrow()
        val resources = client.list().getOrThrow()

        resources
            .filter { !it.isCollection && it.displayName.startsWith("backup_") && it.displayName.endsWith(".zip") }
            .map { resource ->
                WebDavBackupItem(
                    href = resource.href,
                    displayName = resource.displayName,
                    size = resource.contentLength,
                    lastModified = resource.lastModified ?: Instant.EPOCH
                )
            }
            .sortedByDescending { it.lastModified }
    }

    suspend fun restore(config: WebDavConfig, item: WebDavBackupItem) = withContext(Dispatchers.IO) {
        val client = getClient(config)
        val backupFile = File(context.cacheDir, item.displayName)
        try {
            client.downloadToFile(item.displayName, backupFile).getOrThrow()
            restoreFromBackupFile(backupFile, config, includePlugins = false)
        } finally {
            if (backupFile.exists()) {
                backupFile.delete()
            }
        }
    }

    suspend fun deleteBackupFile(config: WebDavConfig, item: WebDavBackupItem) = withContext(Dispatchers.IO) {
        val client = getClient(config)
        client.delete(item.displayName).getOrThrow()
    }

    suspend fun restoreFromLocalFile(file: File, config: WebDavConfig) = withContext(Dispatchers.IO) {
        if (!file.exists()) throw Exception("Backup file does not exist")
        if (!file.canRead()) throw Exception("Cannot read backup file")
        try {
            restoreFromBackupFile(file, config, includePlugins = true)
        } catch (e: Exception) {
            Log.e(TAG, "restoreFromLocalFile failed", e)
            throw Exception("Restore failed: ${e.message}")
        }
    }

    suspend fun prepareBackupFile(
        config: WebDavConfig,
        includePlugins: Boolean = true
    ): File = withContext(Dispatchers.IO) {
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
        val backupFile = File(context.cacheDir, "backup_$timestamp.zip")
        if (backupFile.exists()) backupFile.delete()

        ZipOutputStream(FileOutputStream(backupFile)).use { zipOut ->
            addVirtualFileToZip(
                zipOut = zipOut,
                name = "settings.json",
                content = json.encodeToString(settingsStore.settingsFlow.value)
            )

            if (config.items.contains(WebDavConfig.BackupItem.DATABASE)) {
                val dbFile = context.getDatabasePath("rikka_hub")
                if (dbFile.exists()) addFileToZip(zipOut, dbFile, "rikka_hub.db")
                val walFile = File(dbFile.parentFile, "rikka_hub-wal")
                if (walFile.exists()) addFileToZip(zipOut, walFile, "rikka_hub-wal")
                val shmFile = File(dbFile.parentFile, "rikka_hub-shm")
                if (shmFile.exists()) addFileToZip(zipOut, shmFile, "rikka_hub-shm")
            }

            if (config.items.contains(WebDavConfig.BackupItem.FILES)) {
                val uploadFolder = File(context.filesDir, FileFolders.UPLOAD)
                if (uploadFolder.exists() && uploadFolder.isDirectory) {
                    uploadFolder.listFiles()?.forEach { file ->
                        if (file.isFile) addFileToZip(zipOut, file, "${FileFolders.UPLOAD}/${file.name}")
                    }
                }

                val skillsFolder = File(context.filesDir, FileFolders.SKILLS)
                if (skillsFolder.exists() && skillsFolder.isDirectory) {
                    addDirectoryToZip(zipOut, skillsFolder, skillsFolder, "${FileFolders.SKILLS}/")
                }

                if (includePlugins) {
                    runCatching {
                        val pluginSettings = pluginRepository.exportPluginSettings()
                        addVirtualFileToZip(zipOut, "plugin_settings.json", json.encodeToString(pluginSettings))
                    }
                    val pluginsFolder = PluginScanner(context).pluginsDir
                    if (pluginsFolder.exists() && pluginsFolder.isDirectory) {
                        addDirectoryToZip(zipOut, pluginsFolder, pluginsFolder, "${PluginScanner.PLUGINS_DIR}/")
                    }
                }
            }
        }
        backupFile
    }

    private suspend fun restoreFromBackupFile(
        backupFile: File,
        config: WebDavConfig,
        includePlugins: Boolean = true
    ) = withContext(Dispatchers.IO) {
        val tempDbDir = File(context.cacheDir, "restore_db_${System.currentTimeMillis()}").apply { mkdirs() }
        val relaxedJson = Json(json) { ignoreUnknownKeys = true }

        try {
            ZipInputStream(FileInputStream(backupFile)).use { zipIn ->
                var entry: ZipEntry?
                while (zipIn.nextEntry.also { entry = it } != null) {
                    entry?.let { zipEntry ->
                        when (zipEntry.name) {
                            "settings.json" -> {
                                val settingsJson = zipIn.readBytes().toString(Charsets.UTF_8)
                                runCatching {
                                    val migratedJson = SettingsJsonMigrator.migrate(settingsJson)
                                    val settings = relaxedJson.decodeFromString<Settings>(migratedJson)
                                    settingsStore.update(settings)
                                }.onFailure { e ->
                                    Log.e(TAG, "Failed to restore settings", e)
                                }
                            }

                            "rikka_hub.db", "rikka_hub-wal", "rikka_hub-shm" -> {
                                if (config.items.contains(WebDavConfig.BackupItem.DATABASE)) {
                                    val outName = if (zipEntry.name == "rikka_hub.db") "rikka_hub" else zipEntry.name
                                    val tempTarget = File(tempDbDir, outName)
                                    FileOutputStream(tempTarget).use { outputStream ->
                                        zipIn.copyTo(outputStream)
                                    }
                                }
                            }

                            else -> {
                                if (config.items.contains(WebDavConfig.BackupItem.FILES) &&
                                    zipEntry.name.startsWith("${FileFolders.UPLOAD}/")
                                ) {
                                    val fileName = zipEntry.name.substringAfter("${FileFolders.UPLOAD}/")
                                    if (fileName.isNotEmpty()) {
                                        val uploadFolder = File(context.filesDir, FileFolders.UPLOAD).apply { mkdirs() }
                                        val targetFile = File(uploadFolder, fileName)
                                        FileOutputStream(targetFile).use { outputStream ->
                                            zipIn.copyTo(outputStream)
                                        }
                                    }
                                } else if (config.items.contains(WebDavConfig.BackupItem.FILES) &&
                                    zipEntry.name.startsWith("${FileFolders.SKILLS}/")
                                ) {
                                    restoreSkillEntry(zipIn, zipEntry.name)
                                } else if (includePlugins && zipEntry.name == "plugin_settings.json") {
                                    runCatching {
                                        val pluginSettingsJson = zipIn.readBytes().toString(Charsets.UTF_8)
                                        val pluginSettings = relaxedJson.decodeFromString<PluginSettingsExport>(pluginSettingsJson)
                                        pluginRepository.importPluginSettings(pluginSettings)
                                    }
                                } else if (includePlugins &&
                                    config.items.contains(WebDavConfig.BackupItem.FILES) &&
                                    zipEntry.name.startsWith("${PluginScanner.PLUGINS_DIR}/")
                                ) {
                                    restorePluginEntry(zipIn, zipEntry.name)
                                }
                            }
                        }
                        zipIn.closeEntry()
                    }
                }
            }

            val tempDbFile = File(tempDbDir, "rikka_hub")
            if (tempDbFile.exists() && tempDbFile.length() > 0L) {
                mergeBackupDatabaseIntoCurrent(tempDbFile)
            }
        } finally {
            tempDbDir.deleteRecursively()
        }
    }

    private fun mergeBackupDatabaseIntoCurrent(tempDbFile: File) {
        runCatching {
            SQLiteDatabase.openDatabase(
                tempDbFile.absolutePath,
                null,
                SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING
            ).use { backupDb ->
                backupDb.rawQuery("PRAGMA wal_checkpoint(FULL)", null).use { it.moveToFirst() }
            }
        }

        val targetDbFile = context.getDatabasePath("rikka_hub")
        if (!targetDbFile.exists()) {
            targetDbFile.parentFile?.mkdirs()
            tempDbFile.copyTo(targetDbFile, overwrite = true)
            return
        }

        SQLiteDatabase.openDatabase(
            targetDbFile.absolutePath,
            null,
            SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING
        ).use { targetDb ->
            val escapedPath = tempDbFile.absolutePath.replace("'", "''")
            targetDb.execSQL("ATTACH DATABASE '$escapedPath' AS backup_db")
            targetDb.execSQL("PRAGMA foreign_keys = OFF")

            try {
                val backupTables = mutableListOf<String>()
                targetDb.rawQuery(
                    "SELECT name FROM backup_db.sqlite_master WHERE type='table' " +
                        "AND name NOT LIKE 'sqlite_%' " +
                        "AND name NOT LIKE 'room_%' " +
                        "AND name NOT LIKE '%fts%' " +
                        "AND name NOT LIKE '%Fts%' " +
                        "AND name != 'android_metadata'",
                    null
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        backupTables.add(cursor.getString(0))
                    }
                }

                targetDb.beginTransaction()
                try {
                    for (table in backupTables) {
                        val mainCols = getTableColumnInfo(targetDb, "main", table)
                        if (mainCols.isEmpty()) continue
                        val backupColNames = getTableColumnInfo(targetDb, "backup_db", table).map { it.name }.toSet()

                        val insertCols = mutableListOf<String>()
                        val selectExprs = mutableListOf<String>()

                        for (col in mainCols) {
                            if (backupColNames.contains(col.name)) {
                                insertCols.add("`${col.name}`")
                                selectExprs.add("`${col.name}`")
                            } else if (col.notNull && col.defaultValue == null) {
                                insertCols.add("`${col.name}`")
                                val upperType = col.type.uppercase()
                                val fallback = when {
                                    upperType.contains("INT") || upperType.contains("BOOL") -> "0"
                                    upperType.contains("REAL") || upperType.contains("FLOA") || upperType.contains("DOUB") -> "0.0"
                                    upperType.contains("BLOB") -> "X''"
                                    else -> "''"
                                }
                                selectExprs.add(fallback)
                            }
                        }

                        if (insertCols.isNotEmpty()) {
                            val sql = "INSERT OR REPLACE INTO main.`$table` (${insertCols.joinToString(", ")}) " +
                                "SELECT ${selectExprs.joinToString(", ")} FROM backup_db.`$table`"
                            runCatching { targetDb.execSQL(sql) }
                        }
                    }

                    runCatching {
                        targetDb.rawQuery(
                            "SELECT name FROM main.sqlite_master WHERE type='table' AND (name LIKE '%fts%' OR name LIKE '%Fts%')",
                            null
                        ).use { c ->
                            while (c.moveToNext()) {
                                val ftsName = c.getString(0)
                                runCatching {
                                    targetDb.execSQL("INSERT INTO main.`$ftsName`(`$ftsName`) VALUES('rebuild')")
                                }
                            }
                        }
                    }
                    targetDb.setTransactionSuccessful()
                } finally {
                    targetDb.endTransaction()
                }
            } finally {
                runCatching { targetDb.execSQL("PRAGMA foreign_keys = ON") }
                runCatching { targetDb.execSQL("DETACH DATABASE backup_db") }
                runCatching {
                    targetDb.rawQuery("PRAGMA wal_checkpoint(FULL)", null).use { it.moveToFirst() }
                }
            }
        }
    }

    private data class ColumnMeta(
        val name: String,
        val type: String,
        val notNull: Boolean,
        val defaultValue: String?,
    )

    private fun getTableColumnInfo(
        db: SQLiteDatabase,
        schema: String,
        table: String,
    ): List<ColumnMeta> {
        val result = mutableListOf<ColumnMeta>()
        runCatching {
            db.rawQuery("PRAGMA $schema.table_info(`$table`)", null).use { cursor ->
                val nameIdx = cursor.getColumnIndex("name")
                val typeIdx = cursor.getColumnIndex("type")
                val notNullIdx = cursor.getColumnIndex("notnull")
                val dfltIdx = cursor.getColumnIndex("dflt_value")
                while (cursor.moveToNext()) {
                    if (nameIdx >= 0) {
                        result.add(
                            ColumnMeta(
                                name = cursor.getString(nameIdx),
                                type = if (typeIdx >= 0 && !cursor.isNull(typeIdx)) cursor.getString(typeIdx) else "",
                                notNull = if (notNullIdx >= 0) cursor.getInt(notNullIdx) != 0 else false,
                                defaultValue = if (dfltIdx >= 0 && !cursor.isNull(dfltIdx)) cursor.getString(dfltIdx) else null,
                            )
                        )
                    }
                }
            }
        }
        return result
    }

    private fun addFileToZip(zipOut: ZipOutputStream, file: File, entryName: String) {
        FileInputStream(file).use { fis ->
            val zipEntry = ZipEntry(entryName)
            zipOut.putNextEntry(zipEntry)
            fis.copyTo(zipOut)
            zipOut.closeEntry()
        }
    }

    private fun addDirectoryToZip(
        zipOut: ZipOutputStream,
        rootDir: File,
        currentDir: File,
        entryPrefix: String,
    ) {
        currentDir.listFiles()?.forEach { file ->
            if (file.isDirectory) {
                addDirectoryToZip(zipOut, rootDir, file, entryPrefix)
            } else if (file.isFile) {
                val relativePath = file.relativeTo(rootDir).invariantSeparatorsPath
                addFileToZip(zipOut, file, "$entryPrefix$relativePath")
            }
        }
    }

    private fun restorePluginEntry(zipIn: ZipInputStream, entryName: String) {
        val relativePath = entryName.substringAfter("${PluginScanner.PLUGINS_DIR}/")
        if (relativePath.isBlank()) return
        val pluginsRoot = PluginScanner(context).pluginsDir.apply { mkdirs() }
        val targetFile = File(pluginsRoot, relativePath)
        targetFile.parentFile?.mkdirs()
        FileOutputStream(targetFile).use { outputStream -> zipIn.copyTo(outputStream) }
    }

    private fun restoreSkillEntry(zipIn: ZipInputStream, entryName: String) {
        val relativePath = entryName.substringAfter("${FileFolders.SKILLS}/")
        val skillName = relativePath.substringBefore('/', missingDelimiterValue = "")
        val skillRelativePath = relativePath.substringAfter('/', missingDelimiterValue = "")
        if (skillName.isBlank() || skillRelativePath.isBlank()) return
        val skillsRoot = File(context.filesDir, FileFolders.SKILLS).apply { mkdirs() }
        val skillDir = SkillPaths.resolveSkillDir(skillsRoot, skillName) ?: return
        val targetFile = SkillPaths.resolveSkillFile(skillDir, skillRelativePath) ?: return
        skillDir.mkdirs()
        targetFile.parentFile?.mkdirs()
        FileOutputStream(targetFile).use { outputStream -> zipIn.copyTo(outputStream) }
    }

    private fun addVirtualFileToZip(zipOut: ZipOutputStream, name: String, content: String) {
        val zipEntry = ZipEntry(name)
        zipOut.putNextEntry(zipEntry)
        zipOut.write(content.toByteArray())
        zipOut.closeEntry()
    }
}

data class WebDavBackupItem(
    val href: String,
    val displayName: String,
    val size: Long,
    val lastModified: Instant,
)
