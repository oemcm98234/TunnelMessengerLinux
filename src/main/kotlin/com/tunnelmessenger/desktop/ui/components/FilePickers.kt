package com.tunnelmessenger.desktop.ui.components

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import java.io.File
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

/**
 * Системные файловые диалоги — замена android-лаунчеров
 * (rememberLauncherForActivityResult + ActivityResultContracts).
 *
 * Все функции suspend и выполняются на Dispatchers.Swing (AWT EDT): модальный
 * JFileChooser качает события через вторичный цикл Swing, поэтому UI не
 * замирает, а корутина возобновляется после закрытия диалога. Потокобезопасно:
 * диалог открывается только на EDT, результаты читаются в корутине вызывающего
 * контекста.
 */
object FilePickers {

    /** Открыть ОДИН файл. ext — допустимые расширения (пусто/пустая строка — любые). */
    suspend fun pickFile(title: String, vararg ext: String): File? = withContext(Dispatchers.Swing) {
        val chooser = JFileChooser()
        chooser.dialogTitle = title
        chooser.isMultiSelectionEnabled = false
        chooser.fileSelectionMode = JFileChooser.FILES_ONLY
        applyFilter(chooser, ext)
        if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
    }

    /** Открыть НЕСКОЛЬКО файлов (аналог OpenMultipleDocuments). */
    suspend fun pickFiles(title: String, vararg ext: String): List<File> = withContext(Dispatchers.Swing) {
        val chooser = JFileChooser()
        chooser.dialogTitle = title
        chooser.isMultiSelectionEnabled = true
        chooser.fileSelectionMode = JFileChooser.FILES_ONLY
        applyFilter(chooser, ext)
        if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
            chooser.selectedFiles?.toList().orEmpty()
        } else {
            emptyList()
        }
    }

    /**
     * Сохранить файл (аналог CreateDocument): возвращает выбранный путь или null.
     * v15: если пользователь не дописал расширение — дописываем его из
     * ПРЕДЛОЖЕННОГО имени (раньше жёстко лепился .json — для сохранения
     * медиа это ломало бы имена файлов).
     */
    suspend fun saveFile(title: String, suggestedName: String): File? = withContext(Dispatchers.Swing) {
        val chooser = JFileChooser()
        chooser.dialogTitle = title
        chooser.selectedFile = File(suggestedName)
        if (chooser.showSaveDialog(null) != JFileChooser.APPROVE_OPTION) return@withContext null
        var f = chooser.selectedFile ?: return@withContext null
        val dot = suggestedName.lastIndexOf('.')
        if (dot > 0 && !f.name.contains('.')) {
            f = File(f.parentFile, f.name + suggestedName.substring(dot))
        }
        f
    }

    /** Открыть каталог (выбор папки). */
    suspend fun pickDirectory(title: String): File? = withContext(Dispatchers.Swing) {
        val chooser = JFileChooser()
        chooser.dialogTitle = title
        chooser.fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        chooser.isMultiSelectionEnabled = false
        if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
    }

    private fun applyFilter(chooser: JFileChooser, ext: Array<out String>) {
        val real = ext.filter { it.isNotBlank() }
        if (real.isNotEmpty()) {
            chooser.fileFilter = FileNameExtensionFilter(
                "Файлы (" + real.joinToString(", ") { "*.$it" } + ")",
                *real.toTypedArray(),
            )
        }
    }
}
