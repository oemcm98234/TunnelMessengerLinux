package com.sun.glass.utils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * СБОРКА 9: shim-загрузчик нативов JavaFX для fat-jar.
 *
 * Зачем: официальный NativeLibLoader умеет грузить нативы только из каталога
 * рядом с javafx-jar'ами (exploded layout) или из java.library.path. Наша
 * поставка — ОДИН самодостаточный fat-jar (TunnelMessengerDesktop-13.0.jar),
 * внутри которого нативы JavaFX лежат в корне (libjfxwebkit.so, WebView.dll,
 * libglass.so, prism_es2.dll, …). Без этого класса Platform.startup() падал бы
 * с UnsatisfiedLinkError, и встроенная капча VK не работала бы.
 *
 * Как это работает: класс ПРЕДНАМЕРЕННО лежит в пакете com.sun.glass.utils —
 * том же, что и оригинал в javafx-graphics. В fat-jar наша версия находится
 * вместо оригинала (классы javafx-graphics распакованы в тот же jar; наш
 * ресурс-класс записан последним → DuplicatesStrategy.EXCLUDE сохранит первый,
 * поэтому задача fatJar исключает наш класс из перезаписи и кладёт его поверх —
 * см. задачу fatJar в build.gradle.kts), поэтому JavaFX вызывает именно его.
 *
 * Стратегия loadLibrary:
 *  1) найти ресурс «/<имя-натива>» в корне classpath (fat-jar), извлечь в
 *     ~/.tunnelmessenger/cache/javafx и сделать System.load (абсолютный путь);
 *  2) если натива в classpath нет (запуск из IDE/exploded) — обычный
 *     System.loadLibrary (java.library.path), как в оригинале.
 *
 * Отображение имён повторяет оригинал: «webview» → «jfxwebkit»
 * (файл libjfxwebkit.so / WebView.dll зовётся иначе, чем java-имя библиотеки).
 */
public class NativeLibLoader {

    /** Каталог кэша для извлечённых нативов. */
    private static File cacheDir() {
        File dir = new File(new File(System.getProperty("user.home"), ".tunnelmessenger"), "cache/javafx");
        if (!dir.isDirectory()) dir.mkdirs();
        return dir;
    }

    /** java-имя библиотеки → имя файла натива (повторяет мапу оригинала). */
    private static String fileNameFor(String libname) {
        String name = libname;
        if ("webview".equals(libname)) name = "jfxwebkit";
        return System.mapLibraryName(name);
    }

    public static void loadLibrary(final String libname) {
        loadLibrary(libname, false);
    }

    public static void loadLibrary(final String libname, final boolean quiet) {
        // 1) натив внутри fat-jar → кэш → System.load
        try {
            String fn = fileNameFor(libname);
            InputStream in = NativeLibLoader.class.getResourceAsStream("/" + fn);
            if (in != null) {
                File target = new File(cacheDir(), fn);
                File tmp = new File(target.getParentFile(), fn + ".part");
                byte[] buf = new byte[8192];
                try (FileOutputStream out = new FileOutputStream(tmp)) {
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                } finally {
                    try { in.close(); } catch (Exception ignored) { }
                }
                if (!tmp.renameTo(target)) {
                    // rename мог не удаться поверх существующего файла — просто
                    // перезаписываем целиком
                    try {
                        java.nio.file.Files.move(
                                tmp.toPath(), target.toPath(),
                                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    } catch (Exception ignored) { }
                }
                try {
                    System.load(target.getAbsolutePath());
                    return;
                } catch (UnsatisfiedLinkError e) {
                    // битый/чужой файл — сотрём и попробуем ещё раз при следующем запуске
                    try { target.delete(); } catch (Exception ignored) { }
                    if (quiet) return;
                }
            }
        } catch (Throwable ignored) {
            // ниже — обычный путь
        }
        // 2) обычная загрузка (exploded-запуск, java.library.path)
        try {
            System.loadLibrary(libname);
        } catch (UnsatisfiedLinkError e) {
            if (!quiet) throw e;
        }
    }
}
