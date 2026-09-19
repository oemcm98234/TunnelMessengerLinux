import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.0.21"
    kotlin("plugin.serialization") version "2.0.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"
    id("org.jetbrains.compose") version "1.7.3"
    // ВНИМАНИЕ: плагин `application` НЕ подключён — его задача `run` конфликтует
    // с задачей `run` от compose.desktop.application (ошибка конфигурации
    // «Cannot add task 'run' as a task with that name already exists»).
}

group = "com.tunnelmessenger"
version = "15.0"

repositories {
    mavenCentral()
    google()
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation(compose.foundation)
    implementation(compose.animation)
    // СБОРКА 12: kotlin-reflect УДАЛЁН — ни приложение, ни зависимости его не
    // используют (kotlinx-serialization генерирует сериализаторы на этапе
    // компиляции), а jar весил 7,5 МБ распакованных / 2,5 МБ в jar.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.8.1")

    // СБОРКА 9: JavaFX (WebView) — ВСТРОЕННЫЙ БРАУЗЕР ДЛЯ КАПЧИ VK (FreeTurn).
    // Ядро FreeTurn поднимает reverse-proxy капчи на 127.0.0.1:8765 и ждёт токен
    // от страницы — встроенный WebView может её показать (как WebView-диалог на
    // Android). СБОРКА 11: классификаторы win+linux нужны ОБА (каждый платформенный
    // fat-jar берёт из runtimeClasspath нативы только своей ОС — см. fatJar* ниже).
    val fxVersion = "21.0.12"
    for (fxClassifier in listOf("win", "linux")) {
        implementation("org.openjfx:javafx-base:$fxVersion:$fxClassifier")
        implementation("org.openjfx:javafx-graphics:$fxVersion:$fxClassifier")
        implementation("org.openjfx:javafx-controls:$fxVersion:$fxClassifier")
        // javafx-web тянет javafx-media (плеер внутри WebKit): без него в рантайме
        // NoClassDefFoundError com/sun/media/jfxmedia/events/PlayerStateListener.
        implementation("org.openjfx:javafx-media:$fxVersion:$fxClassifier")
        implementation("org.openjfx:javafx-web:$fxVersion:$fxClassifier")
        // javafx.swing — JFXPanel для встраивания WebView в Compose (SwingPanel)
        implementation("org.openjfx:javafx-swing:$fxVersion:$fxClassifier")
    }
    // СБОРКА 11: compose.desktop.currentOs подтягивает skiko-натив ТОЛЬКО под
    // ОС сборочной машины (на Linux в fat-jar попадал libskiko-linux-x64.so,
    // а skiko-windows-x64.dll ОТСУТСТВОВАЛ — на Windows java -jar падал с
    // UnsatisfiedLinkError). Явно добавляем runtime-натив Windows, чтобы
    // win-сборка fat-jar была рабочей. Версия = та же, что тянет compose 1.7.3.
    implementation("org.jetbrains.skiko:skiko-awt-runtime-windows-x64:0.8.18")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.xerial:sqlite-jdbc:3.45.3.0")
    // E2E-крипто: тот же libsodium, что в Android-клиенте (lazysodium-android),
    // но JVM-вариант. Форматы (NaCl box, TME1) байт-в-байт совпадают с Android/веб.
    implementation("com.goterl:lazysodium-java:5.1.4")
    implementation("net.java.dev.jna:jna:5.13.0")
}

kotlin {
    jvmToolchain(17)
}

// Точка входа задана в compose.desktop.application.mainClass выше:
// "com.tunnelmessenger.desktop.MainKt" (класс main() из Main.kt).

compose.desktop {
    application {
        mainClass = "com.tunnelmessenger.desktop.MainKt"

        nativeDistributions {
            // Ресурсы приложения (нативные libsodium, бинарники туннельного движка):
            // в рантайме доступны через System.getProperty("compose.application.resources.dir")
            appResourcesRootDir.set(layout.projectDirectory.dir("app-resources"))

            // СБОРКА 12: includeAllModules = true тянет ПОЛНЫЙ JDK в AppImage/DEB/
            // MSI (сотни мегабайт). Заменено на явный минимальный список модулей:
            //   java.base, java.desktop (AWT/Swing для Compose/JavaFX-встраивания),
            //   java.logging, java.management, java.naming, java.net.http (OkHttp,
            //   JavaFX WebView), java.sql (sqlite-jdbc), java.xml, java.scripting,
            //   java.prefs, jdk.crypto.ec + jdk.crypto.cryptoki (TLS к серверу),
            //   jdk.unsupported (VarHandle/Unsafe), jdk.zipfs, jdk.localedata
            //   (русские форматы дат/чисел).
            modules(
                "java.base", "java.desktop", "java.logging", "java.management",
                "java.naming", "java.net.http", "java.sql", "java.xml",
                "java.scripting", "java.prefs", "jdk.crypto.ec", "jdk.crypto.cryptoki",
                "jdk.unsupported", "jdk.zipfs", "jdk.localedata"
            )

            targetFormats(TargetFormat.Msi, TargetFormat.Exe, TargetFormat.AppImage, TargetFormat.Deb)

            // Версия пакета в формате MAJOR.MINOR.BUILD — требование плагина
            // compose для Msi/Exe (project version "15.0" не проходит валидацию
            // и ломает конфигурацию сборки на любой ОС).
            packageVersion = "15.0.0"

            windows {
                menu = true
                shortcut = true
                dirChooser = true
                perUserInstall = true
                packageName = "TunnelMessenger"
                // СБОРКА 7: фирменная иконка — тот же знак, что у Android-клиента
                iconFile.set(layout.projectDirectory.file("icons/icon.ico"))
            }
            linux {
                packageName = "tunnelmessenger"
                shortcut = true
                // СБОРКА 7: фирменная иконка — тот же знак, что у Android-клиента
                iconFile.set(layout.projectDirectory.file("icons/icon_512.png"))
            }
        }
    }
}

// Удобная задача: прогнать lint-подобную проверку ресурсов (docs в docs/)
tasks.register("printVersion") {
    doLast { println("TunnelMessenger Desktop v15 (сборка 17)") }
}

/**
 * СБОРКА 6→12: самодостаточные jar для портативного запуска «java -jar».
 * Внутри — все зависимости (Compose/Skiko с нативами, OkHttp, sqlite-jdbc,
 * lazysodium с libsodium внутри) И шрифты (fonts/ в корне jar: Inter +
 * NotoColorEmoji, из src/main/resources).
 *
 * СБОРКА 11: ОДИН универсальный jar заменён на ДВА платформенных —
 *   fatJarLinux64 → TunnelMessengerDesktop-15.0-linux64.jar
 *   fatJarWin64   → TunnelMessengerDesktop-15.0-win64.jar
 * Причина — размер: универсальный jar тащил нативы ОБЕИХ платформ (JavaFX
 * WebKit для капчи: libjfxwebkit.so 121 МБ + jfxwebkit.dll 97 МБ), а также
 * sqlite-нативы всех архитектур (Android/ARM/Musl/FreeBSD/ppc64), libsodium
 * для Mac/ARM, JNA под два десятка платформ. Платформенный jar содержит
 * нативы только своей ОС → архив вдвое-втрое меньше.
 *
 * СБОРКА 12 — третье (самое крупное после платформенности) сокращение:
 *  1. material-icons-extended (11 122 записи, 84 МБ распакованных, 34,7 МБ
 *     в jar) сокращён до явного «белого списка» реально используемых иконок
 *     (29 классов; все ссылки Icons.X.Y в коде статические — список составлен
 *     автоматическим проходом по исходникам). Базовые объекты Icons.Filled и
 *     пр. живут в material-icons-core — тот попадает в jar целиком.
 *  2. kotlin-reflect убран из зависимостей и из jar (не используется ни кодом,
 *     ни зависимостями).
 *  3. Мелочь: darwin-JNA .jnilib, каталог OSGI-OPT (исходники JNA),
 *     32-битные libsodium (lazysodium для x86_64 грузит только linux64/win64).
 *
 * Что вырезается у ОБЕИХ платформ (по-прежнему):
 *  - javafx-swt.jar (не используется);
 *  - нативы JavaFX Media (gstreamer/jfxmedia/fxplugins/avplugin) — классы
 *    media остаются (их требует javafx-web), капча VK не проигрывает медиа;
 *  - sqlite: Mac/FreeBSD/Linux-Android/Linux-Musl и все архитектуры, кроме
 *    x86_64 целевой ОС;
 *  - lazysodium: каталоги mac, arm64, armv6;
 *  - JNA: все платформы, кроме linux-x86-64 (linux) / win32-x86-64 (win).
 * Запуск:
 *   Linux: java -jar prebuilt/TunnelMessengerDesktop-15.0-linux64.jar
 *   Windows: java -jar prebuilt/TunnelMessengerDesktop-15.0-win64.jar
 */

// СБОРКА 12: белый список иконок «пакет → имена» (класс androidx.../NameKt.class).
// При добавлении новой иконки в UI — добавить её имя сюда, иначе будет
// NoClassDefFoundError в рантайме (на этапе компиляции ничего не сломается:
// компиляция идёт по полному runtimeClasspath).
val usedIcons = mapOf(
    "androidx/compose/material/icons/automirrored/filled" to listOf(
        "OpenInNew", "Reply", "Send", "VolumeDown", "VolumeUp"
    ),
    "androidx/compose/material/icons/filled" to listOf(
        "Add", "AttachFile", "Block", "Call", "CallEnd", "Check", "Close",
        "ContentCopy", "Delete", "Download", "EmojiEmotions",
        "KeyboardArrowDown", "Link", "Mic", "MicOff", "MoreVert", "People",
        "PlayArrow", "SaveAlt", "Search", "Settings", "Shield"
    ),
    "androidx/compose/material/icons/outlined" to listOf(
        "Block", "Delete", "SystemUpdate"
    )
)
// Отдельный from() для material-icons-extended: ТОЛЬКО используемые иконки.
fun Jar.iconsExtFrom(iconsExtJar: File?) {
    if (iconsExtJar == null) return
    from(zipTree(iconsExtJar)) {
        usedIcons.forEach { (pkg, names) ->
            names.forEach { name -> include("$pkg/${name}Kt.class") }
        }
    }
}

fun Jar.configurePortableJar(jarName: String, platform: String) {
    group = "build"
    description = "Самодостаточный jar для портативного запуска ($platform)"
    archiveFileName.set(jarName)
    destinationDirectory.set(layout.projectDirectory.dir("prebuilt"))
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        attributes("Main-Class" to "com.tunnelmessenger.desktop.MainKt")
    }
    // классы + ресурсы (шрифты src/main/resources/fonts → fonts/, иконки → icons/)
    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath)

    // СБОРКА 12: material-icons-extended обрабатывается ОТДЕЛЬНО (ниже) —
    // только используемые иконки; остальные jar — целиком, с общими exclude.
    val cpJars = configurations.runtimeClasspath.get().filter { it.name.endsWith(".jar") }
    val iconsExtJar = cpJars.firstOrNull { it.name.contains("material-icons-extended") }

    from(cpJars.filter { iconsExtJar == null || it.absolutePath != iconsExtJar.absolutePath }
        .map { zipTree(it) }) {
        // общий список вырезаемого (см. док сверху)
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
        exclude("module-info.class")
        exclude("META-INF/versions/*/module-info.class")
        exclude("javafx-swt.jar")
        exclude("*gstreamer*", "*jfxmedia*", "*fxplugins*", "*avplugin*", "*glib-lite*")
        exclude("org/sqlite/native/Mac/**", "org/sqlite/native/FreeBSD/**")
        exclude("org/sqlite/native/Linux-Android/**", "org/sqlite/native/Linux-Musl/**")
        exclude("mac/**", "arm64/**", "armv6/**")
        // СБОРКА 12: JNA под macOS (.jnilib), исходники JNA, 32-битные libsodium
        // (lazysodium для x86_64 использует только linux64/win64)
        exclude("**/*.jnilib")
        exclude("OSGI-OPT/**")
        exclude("linux/libsodium.so", "windows/libsodium.dll")
        if (platform == "linux64") {
            // всё Windows/Mac — мимо; в Linux оставляем только x86_64
            exclude("**/*.dll", "**/*.dylib")
            exclude("skiko-windows*")
            exclude("org/sqlite/native/Windows/**")
            exclude("org/sqlite/native/Linux/aarch64/**", "org/sqlite/native/Linux/arm/**")
            exclude("org/sqlite/native/Linux/armv6/**", "org/sqlite/native/Linux/armv7/**")
            exclude("org/sqlite/native/Linux/x86/**", "org/sqlite/native/Linux/ppc64/**")
            for (p in listOf("aix-ppc", "aix-ppc64", "freebsd-x86", "freebsd-x86-64",
                             "openbsd-x86", "openbsd-x86-64", "sunos-sparc", "sunos-sparcv9",
                             "sunos-x86", "sunos-x86-64", "linux-aarch64", "linux-arm",
                             "linux-armel", "linux-loongarch64", "linux-mips64el", "linux-ppc",
                             "linux-ppc64le", "linux-riscv64", "linux-s390x", "linux-x86")) {
                exclude("com/sun/jna/$p/**")
            }
        } else {
            // всё Linux/Mac — мимо; в Windows оставляем только x86_64
            exclude("**/*.so", "**/*.dylib")
            exclude("libskiko-linux*")
            exclude("org/sqlite/native/Linux/**")
            exclude("org/sqlite/native/Windows/aarch64/**", "org/sqlite/native/Windows/x86/**")
            exclude("org/sqlite/native/Windows/armv7/**")
            for (p in listOf("win32", "win32-aarch64", "win32-x86")) {
                exclude("com/sun/jna/$p/**")
            }
        }
    }
    // СБОРКА 12: material-icons-extended — только используемые иконки (см. док выше)
    iconsExtFrom(iconsExtJar)
}

val fatJarLinux64 = tasks.register<Jar>("fatJarLinux64") {
    configurePortableJar("TunnelMessengerDesktop-15.0-linux64.jar", "linux64")
}
val fatJarWin64 = tasks.register<Jar>("fatJarWin64") {
    configurePortableJar("TunnelMessengerDesktop-15.0-win64.jar", "win64")
}
// совместимость со старыми командами/доками: собирает обе платформы
tasks.register("fatJar") {
    group = "build"
    description = "Собрать платформенные fat-jar для обеих ОС (linux64 + win64)"
    dependsOn(fatJarLinux64, fatJarWin64)
}

