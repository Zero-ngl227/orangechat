/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

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
        Log.i(TAG, "testConnection: Connection successful")
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

        Log.i(TAG, "backup: Uploaded ${file.name} (${file.length().fileSizeToString()})")
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
            Log.i(TAG, "restore: Downloading ${item.displayName}")
            client.downloadToFile(item.displayName, backupFile).getOrThrow()
            Log.i(TAG, "restore: Downloaded ${backupFile.length().fileSizeToString()}")
            restoreFromBackupFile(backupFile, config, includePlugins = false)
        } finally {
            if (backupFile.exists()) {
                backupFile.delete()
                Log.i(TAG, "restore: Cleaned up temporary backup file")
            }
        }
    }

    suspend fun deleteBackupFile(config: WebDavConfig, item: WebDavBackupItem) = withContext(Dispatchers.IO) {
        val client = getClient(config)
        client.delete(item.displayName).getOrThrow()
        Log.i(TAG, "deleteBackupFile: Deleted ${item.displayName}")
    }

    suspend fun restoreFromLocalFile(file: File, config: WebDavConfig) = withContext(Dispatchers.IO) {
        Log.i(TAG, "restoreFromLocalFile: Starting restore from ${file.absolutePath}")

        if (!file.exists()) {
            throw Exception("Backup file does not exist")
        }

        if (!file.canRead()) {
            throw Exception("Cannot read backup file")
        }

        try {
            restoreFromBackupFile(file, config, includePlugins = true)
            Log.i(TAG, "restoreFromLocalFile: Restore completed successfully")
        } catch (e: Exception) {
            Log.e(TAG, "restoreFromLocalFile: Failed to restore from local file", e)
            throw Exception("Restore failed: ${e.message}")
        }
    }

    suspend fun prepareBackupFile(
        config: WebDavConfig,
        includePlugins: Boolean = true
    ): File = withContext(Dispatchers.IO) {
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
        val backupFile = File(context.cacheDir, "backup_$timestamp.zip")

        if (backupFile.exists()) {
            backupFile.delete()
        }

        ZipOutputStream(FileOutputStream(backupFile)).use { zipOut ->
            addVirtualFileToZip(
                zipOut = zipOut,
                name = "settings.json",
                content = json.encodeToString(settingsStore.settingsFlow.value)
            )

            if (config.items.contains(WebDavConfig.BackupItem.DATABASE)) {
                val dbFile = context.getDatabasePath("rikka_hub")
                if (dbFile.exists()) {
                    addFileToZip(zipOut, dbFile, "rikka_hub.db")
                }

                val walFile = File(dbFile.parentFile, "rikka_hub-wal")
                if (walFile.exists()) {
                    addFileToZip(zipOut, walFile, "rikka_hub-wal")
                }

                val shmFile = File(dbFile.parentFile, "rikka_hub-shm")
                if (shmFile.exists()) {
                    addFileToZip(zipOut, shmFile, "rikka_hub-shm")
                }
            }

            if (config.items.contains(WebDavConfig.BackupItem.FILES)) {
                val uploadFolder = File(context.filesDir, FileFolders.UPLOAD)
                if (uploadFolder.exists() && uploadFolder.isDirectory) {
                    uploadFolder.listFiles()?.forEach { file ->
                        if (file.isFile) {
                            addFileToZip(zipOut, file, "${FileFolders.UPLOAD}/${file.name}")
                        }
                    }
                }

                val skillsFolder = File(context.filesDir, FileFolders.SKILLS)
                if (skillsFolder.exists() && skillsFolder.isDirectory) {
                    addDirectoryToZip(
                        zipOut = zipOut,
                        rootDir = skillsFolder,
                        currentDir = skillsFolder,
                        entryPrefix = "${FileFolders.SKILLS}/"
                    )
                }

                if (includePlugins) {
                    try {
                        val pluginSettings = pluginRepository.exportPluginSettings()
                        addVirtualFileToZip(
                            zipOut = zipOut,
                            name = "plugin_settings.json",
                            content = json.encodeToString(pluginSettings)
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "prepareBackupFile: Failed to back up plugin settings", e)
                    }

                    val pluginsFolder = PluginScanner(context).pluginsDir
                    if (pluginsFolder.exists() && pluginsFolder.isDirectory) {
                        addDirectoryToZip(
                            zipOut = zipOut,
                            rootDir = pluginsFolder,
                            currentDir = pluginsFolder,
                            entryPrefix = "${PluginScanner.PLUGINS_DIR}/"
                        )
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
        Log.i(TAG, "restoreFromBackupFile: Starting restore from ${backupFile.absolutePath}")

        // 临时存放解压出来的数据库文件，避免直接覆盖引发 Room 破坏性重置
        val tempDbDir = File(context.cacheDir, "restore_db_${System.currentTimeMillis()}").apply { mkdirs() }

        try {
            ZipInputStream(FileInputStream(backupFile)).use { zipIn ->
                var entry: ZipEntry?
                while (zipIn.nextEntry.also { entry = it } != null) {
                    entry?.let { zipEntry ->
                        Log.i(TAG, "restoreFromBackupFile: Processing entry ${zipEntry.name}")

                        when (zipEntry.name) {
                            "settings.json" -> {
                                val settingsJson = zipIn.readBytes().toString(Charsets.UTF_8)
                                Log.i(TAG, "restoreFromBackupFile: Restoring settings")
                                try {
                                    val migratedJson = SettingsJsonMigrator.migrate(settingsJson)
                                    val IgnoreUnknownJson = Json(json) { ignoreUnknownKeys = true }
                                    val settings = IgnoreUnknownJson.decodeFromString<Settings>(migratedJson)
                                    settingsStore.update(settings)
                                    Log.i(TAG, "restoreFromBackupFile: Settings restored successfully")
                                } catch (e: Exception) {
                                    Log.e(TAG, "restoreFromBackupFile: Failed to restore settings", e)
                                    // 若仅导出聊天记录等情况，不阻断后续数据库恢复
                                }
                            }

                            "rikka_hub.db", "rikka_hub-wal", "rikka_hub-shm" -> {
                                if (config.items.contains(WebDavConfig.BackupItem.DATABASE)) {
                                    val outName = when (zipEntry.name) {
                                        "rikka_hub.db" -> "rikka_hub"
                                        "rikka_hub-wal" -> "rikka_hub-wal"
                                        "rikka_hub-shm" -> "rikka_hub-shm"
                                        else -> zipEntry.name
                                    }
                                    val tempTarget = File(tempDbDir, outName)
                                    FileOutputStream(tempTarget).use { outputStream ->
                                        zipIn.copyTo(outputStream)
                                    }
                                    Log.i(TAG, "restoreFromBackupFile: Extracted ${zipEntry.name} to temp (${tempTarget.length()} bytes)")
                                }
                            }

                            else -> {
                                if (config.items.contains(WebDavConfig.BackupItem.FILES) &&
                                    zipEntry.name.startsWith("${FileFolders.UPLOAD}/")
                                ) {
                                    val fileName = zipEntry.name.substringAfter("${FileFolders.UPLOAD}/")
                                    if (fileName.isNotEmpty()) {
                                        val uploadFolder = File(context.filesDir, FileFolders.UPLOAD)
                                        if (!uploadFolder.exists()) {
                                            uploadFolder.mkdirs()
                                        }
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
                                    try {
                                        val pluginSettingsJson = zipIn.readBytes().toString(Charsets.UTF_8)
                                        val pluginSettings = json.decodeFromString<PluginSettingsExport>(pluginSettingsJson)
                                        pluginRepository.importPluginSettings(pluginSettings)
                                    } catch (e: Exception) {
                                        Log.e(TAG, "restoreFromBackupFile: Failed to restore plugin settings", e)
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

            // 如果解压出了数据库文件，执行跨版本表数据无缝合并
            val tempDbFile = File(tempDbDir, "rikka_hub")
            if (tempDbFile.exists() && tempDbFile.length() > 0L) {
                mergeBackupDatabaseIntoCurrent(tempDbFile)
            }
        } finally {
            tempDbDir.deleteRecursively()
        }

        Log.i(TAG, "restoreFromBackupFile: Restore completed successfully")
    }

    /**
     * 将备份数据库中的所有会话、消息节点、记忆、文件夹、收藏等表数据无缝合并进当前数据库，
     * 不破坏当前 Room 的 identity_hash 与表结构，彻底解决跨版本（如 RikkaHub 3.2.0 -> 橘瓣）导入后被清空的问题。
     */
    private fun mergeBackupDatabaseIntoCurrent(tempDbFile: File) {
        // 1. 先以读写模式打开临时库并执行 checkpoint，确保备份里的 -wal 数据全部合并进主库文件
        runCatching {
            SQLiteDatabase.openDatabase(
                tempDbFile.absolutePath,
                null,
                SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING
            ).use { backupDb ->
                backupDb.rawQuery("PRAGMA wal_checkpoint(FULL)", null).use { it.moveToFirst() }
            }
        }.onFailure {
            Log.w(TAG, "mergeBackupDatabaseIntoCurrent: WAL checkpoint on temp db skipped: ${it.message}")
        }

        val targetDbFile = context.getDatabasePath("rikka_hub")
        if (!targetDbFile.exists()) {
            // 如果当前连数据库文件都还没生成，才直接复制过去
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
                // 获取备份库中的所有业务表（跳过系统表、Room 元数据表和 FTS 虚拟表）
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
                                // 如果橘瓣新增了非空且无默认值的列，自动补默认值防报错
                                insertCols.add("`${col.name}`")
                                val upperType = col.type.uppercase()
                                val fallbackLiteral = when {
                                    upperType.contains("INT") || upperType.contains("BOOL") -> "0"
                                    upperType.contains("REAL") || upperType.contains("FLOA") || upperType.contains("DOUB") -> "0.0"
                                    upperType.contains("BLOB") -> "X''"
                                    else -> "''"
                                }
                                selectExprs.add(fallbackLiteral)
                            }
                        }

                        if (insertCols.isNotEmpty()) {
                            val sql = "INSERT OR REPLACE INTO main.`$table` (${insertCols.joinToString(", ")}) " +
                                "SELECT ${selectExprs.joinToString(", ")} FROM backup_db.`$table`"
                            runCatching {
                                targetDb.execSQL(sql)
                                Log.i(TAG, "mergeBackupDatabaseIntoCurrent: Merged table $table successfully")
                            }.onFailure { e ->
                                Log.e(TAG, "mergeBackupDatabaseIntoCurrent: Failed to merge table $table", e)
                            }
                        }
                    }

                    // 顺手重建全文搜索索引（如果存在 FTS 表）
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
                addDirectoryToZip(
                    zipOut = zipOut,
                    rootDir = rootDir,
                    currentDir = file,
                    entryPrefix = entryPrefix,
                )
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

        FileOutputStream(targetFile).use { outputStream ->
            zipIn.copyTo(outputStream)
        }
    }

    private fun restoreSkillEntry(zipIn: ZipInputStream, entryName: String) {
        val relativePath = entryName.substringAfter("${FileFolders.SKILLS}/")
        val skillName = relativePath.substringBefore('/', missingDelimiterValue = "")
        val skillRelativePath = relativePath.substringAfter('/', missingDelimiterValue = "")

        if (skillName.isBlank() || skillRelativePath.isBlank()) return

        val skillsRoot = File(context.filesDir, FileFolders.SKILLS).apply { mkdirs() }
        val skillDir = SkillPaths.resolveSkillDir(skillsRoot, skillName)
            ?: throw Exception("Invalid skill directory: $entryName")
        val targetFile = SkillPaths.resolveSkillFile(skillDir, skillRelativePath)
            ?: throw Exception("Invalid skill file path: $entryName")

        skillDir.mkdirs()
        targetFile.parentFile?.mkdirs()

        FileOutputStream(targetFile).use { outputStream ->
            zipIn.copyTo(outputStream)
        }
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
