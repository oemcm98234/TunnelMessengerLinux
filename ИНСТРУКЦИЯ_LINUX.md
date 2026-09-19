# Инструкция: сборка и запуск TunnelMessengerLinux v15

В Linux-дереве в `prebuilt/engines/` лежат движки `tunnel-core-linux64`.
Версия — 15.

## 1. Требования

- **JDK 17+** (полноценный, с `javac` — нужен Kotlin-компилятору и jpackage):

  ```bash
  sudo apt install openjdk-17-jdk
  java -version
  ```

- **Gradle ставить не нужно**: в проекте есть wrapper (`./gradlew` +
  `gradle/wrapper/`), при первом запуске он сам скачает Gradle 8.10.2.
- `zip` — для портативного архива (обычно уже есть).
- `libsodium` в системе — НЕ обязательно: клиент сначала пробует системную
  библиотеку, затем вшитую в jar (lazysodium-java).
- Для DEB-пакета нужен `dpkg-deb` (есть в любом Debian/Ubuntu).
- Интернет при первой сборке (зависимости с mavenCentral).

## 2. Официальный скрипт — `scripts/build-linux.sh`

Скрипт работает из любого каталога (сам переходит в корень проекта) и делает
всё сам:

```bash
bash scripts/build-linux.sh              # AppImage (по умолчанию, ./gradlew packageAppImage)
bash scripts/build-linux.sh --deb        # DEB-пакет (./gradlew packageDeb)
bash scripts/build-linux.sh --portable   # fat-jar + портативный zip
bash scripts/build-linux.sh что-угодно   # подсказка «Использование: …» и выход 1
```

Что именно делает скрипт:

1. **GRADLE_USER_HOME** — если переменная не задана и существует
   `/home/z/tools/gradle-home`, использует его как прогретый кэш.
2. **Ищет JDK 17+** в порядке: `org.gradle.java.home` из `gradle.properties`
   (проект → `$GRADLE_USER_HOME/gradle.properties` → `~/.gradle/gradle.properties`)
   → `JAVA_HOME` → типовые пути (`~/.jdks/*`, `/usr/lib/jvm/java-17-*`,
   `java-21-*`, `default-java`, `/opt/java/*`, `/opt/jdk*`). Предпочитает
   JDK с `javac`; если нет ни одного `java` — ошибка и подсказка
   `sudo apt install openjdk-17-jdk`. Найденный JDK экспортируется в
   `JAVA_HOME` и `PATH`.
3. **SSL-фикс trustStore (безусловный)**: подменяет trustStore на штатный
   `cacerts` найденного JDK через `_JAVA_OPTIONS`
   (`-Djavax.net.ssl.trustStore=… -Djavax.net.ssl.trustStoreType=JKS
   -Djavax.net.ssl.trustStorePassword=changeit`). JVM применяет эти опции
   последними — они перекрывают PEM-бандл и `systemProp` из любых
   gradle.properties (иначе сборка падала с «toDerInputStream rejects tag
   type 35»), и наследуются демоном Gradle.
4. **Перезапускает демоны Gradle** (`./gradlew --stop`) — чтобы не
   переиспользовать демоны со старыми опциями.
5. Запускает нужную задачу (см. команды выше) и печатает артефакты.

### Артефакты

| Команда | Задача | Артефакт |
|---|---|---|
| (пусто) | `packageAppImage` | `build/compose/binaries/main-app/**/TunnelMessenger*` (AppImage) |
| `--deb` | `packageDeb` | `build/compose/binaries/main-deb/*.deb` |
| `--portable` | `fatJarLinux64` + `package-portable.sh` | `prebuilt/TunnelMessengerDesktop-15.0-linux64.jar` и `dist/TunnelMessengerDesktop_v15_linux64.zip` |

**Релизное имя установщика** (то, что ждут сервер обновлений и админ в
каталоге `Updates/`): `TunnelMessengerLinux(v15).appimage` — при
переименовании AppImage в это имя клиент увидит его как обновление.

## 5. Установщики AppImage / DEB

- **AppImage** (`packageAppImage`): самодостаточный образ — поставить не
  нужно, сделать исполняемым и запускать. Плагин Compose использует
  минимальный набор JDK-модулей (java.base, java.desktop, java.sql,
  jdk.crypto.ec и др. — перечислены в build.gradle.kts), поэтому образ
  заметно меньше полного JDK.
- **DEB** (`packageDeb`): пакет `tunnelmessenger`, устанавливается
  `sudo apt install ./<файл>.deb`; ярлык в меню приложений, иконка —
  `icons/icon_512.png`. Версия пакета — `15.0.0`.
- В обоих случаях движки и шрифты упакованы как ресурсы приложения
  (`app-resources/`), извлекаются автоматически при первом запуске.

## 6. Первый запуск и проверка

1. Запустите приложение (любым способом выше).
2. На экране входа укажите адрес сервера (по умолчанию `http://10.10.10.1:80`),
   при необходимости откройте меню туннеля и подключитесь (импорт
   AmneziaWG-конфига или FreeTurn).
3. Войдите или зарегистрируйтесь. Сервер — сборка 13 или новее; для релиза
  v15 рекомендуется сервер версии 15.
4. Проверьте версию: Настройки → раздел обновлений показывает текущую
   версию 15; `./gradlew printVersion` в корне проекта печатает
   «v15».

## 7. Частые проблемы

- **«JDK 17+ не найден»** — поставьте `openjdk-17-jdk` или задайте
  `org.gradle.java.home` в `gradle.properties`.
- **SSL-ошибки при скачивании зависимостей** — уже лечится скриптом
  (безусловный trustStore-фикс); при ручном запуске `./gradlew` задайте
  `_JAVA_OPTIONS` как в п.2.
- **Нет трея в GNOME** — расширение AppIndicator не установлено: закрытие
  окна завершает приложение (это штатное поведение), уведомления идут
  через notify-send.
- **Две копии не запускаются** — так задумано: одиночный экземпляр
  держит FileLock на `~/.tunnelmessenger/app.lock`.
