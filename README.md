# TunnelMessengerLinux — v15

Настольный клиент Tunnel Messenger на **Kotlin + Compose Desktop**. Всё общение с сервером идёт **поверх собственного
туннеля** (AmneziaWG или FreeTurn-релей): клиент поднимает userspace-движок и
заворачивает в него собственный HTTP/WebSocket-трафик через SOCKS5, без
системного VPN и без прав администратора.

Версия продукта — **15** (`APP_VERSION` в коде, `version 15.0` в Gradle,
packageVersion `15.0.0`).

Совместимость с сервером: **минимально сборка 13 и новее; рекомендуется версия 15**
(токены сессий со сроком 100 лет — перелогиниваться не придётся).

---

## Стек

| Компонент | Версия | Назначение |
|---|---|---|
| Kotlin | 2.0.21 | язык + kotlinx-serialization (JSON протокол сервера) |
| Compose Desktop | 1.7.3 (Skiko 0.8.18) | весь UI (Material 3) |
| JDK | 17 (toolchain `jvmToolchain(17)`) | целевая платформа, jpackage |
| Gradle | 8.10.2 (wrapper включён) | сборка без установки Gradle |
| OkHttp | 4.12.0 | REST + WebSocket через SOCKS5 |
| sqlite-jdbc | 3.45.3.0 | локальная БД (кэш истории) |
| lazysodium-java + JNA | 5.1.4 / 5.13.0 | X25519, NaCl box, AES-GCM (E2E) |
| JavaFX (win+linux) | 21.0.12 | WebView — показ капчи FreeTurn в окне приложения |
| kotlinx-coroutines | 1.8.1 (+swing) | асинхронность, Swing EDT для диалогов |

Вшитые шрифты: **Inter** (Regular/Medium/SemiBold/Bold, кириллица) и
**NotoColorEmoji** (цветные эмодзи) — в `src/main/resources/fonts`, попадают
внутрь fat-jar и в установщики.

## Что умеет (v15)

- **Чаты**: личные и групповые, ответы (reply), реакции-эмодзи, набор текста
  (typing), статусы доставки/прочтения, поиск людей, контакты, блокировки.
- **E2E-шифрование**: текст — NaCl box (X25519 + XSalsa20-Poly1305),
  файлы — потоковый формат TME1 (AES-256-GCM чанками по 64 КиБ); ключи не
  покидают устройство, приватный ключ хранится запечатанным мастер-ключом.
- **Файлы, фото и голосовые**: параллельная загрузка/скачивание (Range-запросы),
  миниатюры изображений, голосовые WAV 16 кГц с огибающей, P2P-передача без
  серверной копии.
- **Звонки 1:1**: аудио через WS (E2E1F-кадры PCM 16 кГц), рингтоны,
  джиттер-буфер; микрофон через `javax.sound.sampled` (разрешения не нужны).
- **Туннель**: AmneziaWG / FreeTurn-релей / режим АВТО с мгновенным
  переключением, health-проба `/api/health` раз в 30 с, индикатор в UI.
- **Трей и уведомления**: закрытие окна сворачивает в трей (WS живёт),
  системные уведомления (Linux — notify-send, Windows — toast/balloon),
  автозапуск (HKCU\Run / XDG autostart), одиночный экземпляр (FileLock).
- **Темы v15**: тёмная, **светлая** и **авто по системе** — переключение в
  Настройках, применяется сразу.
- **Сохранение медиа v15**: кнопка «Сохранить как…» — скачивает/расшифровывает
  и сохраняет файл через **системный диалог** в любое место.
- **Автообновления**: проверка `/api/updates/check`, многопоточное скачивание
  с проверкой SHA-256, чейнжлог «Что нового?».

## Темы (v15)

`ui/theme/Theme.kt` — две полные схемы:

- **Тёмная** (как была): фон `#0E1512`, поверхности в зелёных тонах,
  акцент **Mint `#2BD9A8`** (primary).
- **Светлая** (новая): фон **Paper `#F7F9F8`**, текст **Ink `#152420`**,
  акцент — тот же **Mint `#2BD9A8`** (primary одинаков в обеих темах —
  кнопки, свитчи, точка онлайна выглядят одинаково). Свои пузыри —
  `BubbleMine #D7F3E8` с текстом Teal900, чужие — `BubbleTheirs #F0F2F1`
  с текстом `#4A5A54`.

Режим хранится в `config.json` (`theme_mode`): `"auto"` (по умолчанию) /
`"dark"` / `"light"`. `TunnelMessengerTheme` вызывает `isAppInDarkTheme()`,
который читает живой `StateFlow` из Repository — **смена в Настройках
применяется немедленно**, без перезапуска. Акцентный *текст* —
`accentTextColor()`: Mint в тёмной, Teal600 в светлой (контраст на белом);
логотип ▚▞ на экране входа — `#34D399` / Teal600.

**Авто-режим** (`ui/theme/SystemTheme.kt`):

- **Linux**: `gsettings get org.gnome.desktop.interface color-scheme`
  (`prefer-dark`/`prefer-light`), иначе `gtk-theme` с «dark» в имени;
- результат кэшируется на **10 с** (запрос реестра/gsettings — внешний
  процесс); не удалось определить → тёмная.

Фон AWT-окна в `Main.kt` задаётся до первой композиции тем же правилом:
`#0E1512` (тёмная) / `#F7F9F8` (светлая) — без «мелькания» чужого кадра.

## Сохранение медиа (v15)

`ui/components/MediaSave.kt` — `SaveMediaAsButton`: если файл ещё не скачан,
сначала скачивает и расшифровывает (`Repository.downloadMessageFile`), затем
**каждый раз** открывает системный диалог сохранения (`FilePickers.saveFile`
на `Dispatchers.Swing`, JFileChooser; расширение дописывается из предложенного
имени), копирует файл в выбранное место и показывает результат снекбаром
`Repository.postNotice` (внизу окна, гаснет через 6 с). Кнопка стоит в
ChatScreen (файлы, изображения, голосовые; подсказка HoverTooltip
«Сохранить как…») и в MediaDialog (строки списка и оверлей на миниатюрах
фото). Раньше расшифрованные файлы жили только во внутреннем кэше
`~/.tunnelmessenger/downloads`, который стирается при выходе из аккаунта.

## Сборка
### Linux — `scripts/build-linux.sh`

```bash
bash scripts/build-linux.sh              # AppImage (packageAppImage) — по умолчанию
bash scripts/build-linux.sh --deb        # DEB-пакет (packageDeb)
```

Скрипт сам находит JDK 17+ (`org.gradle.java.home` → `JAVA_HOME` → типовые
пути), безусловно ставит SSL-фикс trustStore через `_JAVA_OPTIONS` (cacerts
JDK, JKS) и перезапускает демоны Gradle. Требуется `zip` для портативного
архива; установщики требуют `jpackage` (входит в JDK 17+).

## Структура кода

`src/main/kotlin/com/tunnelmessenger/desktop/` — 40 Kotlin-файлов (~17 400 строк):

- **`Main.kt`** — точка входа, окно (1180×780, минимум 940×600), master-detail
  оболочка, Esc-навигация, снекбар, фон окна по теме, скрытие в трей.
- **`data/`** — ядро:
  - `Repository.kt` — состояние приложения (StateFlow), синк, E2E,
    передача файлов, кэш, `theme_mode` и настройки;
  - `api/Api.kt` — REST-клиент (Bearer-токен, HTTP через туннель),
    последовательное и **параллельное** скачивание (Range, 8–32 потока);
  - `ws/WsClient.kt` — WebSocket `GET /ws?token=…`, автопереподключение,
    heartbeat;
  - `local/AppDirs.kt` — каталоги `~/.tunnelmessenger` + конфиг `config.json`
    (секреты запечатаны SecureStore); `local/Db.kt` — SQLite-кэш,
    поля БД зашифрованы DEK (AES-GCM);
  - `crypto/` — `SecureStore.kt` (мастер-ключ 32 Б, `enc1:` формат, DEK),
    `E2eCrypto.kt` (E2E1/E2E1G/E2E1F, X25519+XSalsa20-Poly1305),
    `Tme1.kt` (файлы: заголовок 36 Б + чанки 64 КиБ AES-256-GCM);
  - `update/UpdateManager.kt` — проверка/скачивание обновлений;
  - `model/Models.kt` — DTO протокола сервера.
- **`tunnel/`** — `TunnelManager.kt` (движок tunnel-core, SOCKS5-креды),
  `ProtocolMode.kt` (AMNEZIA / FREELAY / AUTO), `FreeTurnManager.kt`
  (UDP-релей + капча), `AwgConfig.kt` (парсер .conf), `TunnelHealth.kt`.
- **`call/`** — `CallManager.kt` (сигналинг + аудио), `CallRinger.kt`
  (синтезированные сигналы звонка).
- **`net/`** — `Socks5.kt` (RFC1928/1929, DNS через туннель),
  `HttpRouter.kt` (единая фабрика OkHttpClient).
- **`tray/DesktopIntegrations.kt`** — трей, уведомления, автозапуск,
  FileLock-одиночный экземпляр.
- **`ui/theme/`** — `Theme.kt` (палитра, обе схемы, шрифты, эмодзи-фолбэк),
  `SystemTheme.kt` (определение системной темы).
- **`ui/screens/`** — 11 экранов: Login, Chats, Chat, NewChat, Contacts,
  Settings, Tunnel, Media, CallOverlay, Captcha, BlockedUsers.
- **`ui/components/`** — `Common.kt` (APP_VERSION, Avatar, форматтеры),
  `FilePickers.kt` (системные диалоги JFileChooser), `MediaSave.kt`
  («Сохранить как…»), `ImageThumb.kt` (миниатюры + LRU), `VoiceAudio.kt`
  (запись голоса), `Anim.kt` (анимации).

Прочее: `icons/` (фирменная иконка .ico/.png), `scripts/` (сборка/упаковка),
`gradle/` (wrapper 8.10.2), `build.gradle.kts`, `settings.gradle.kts`.

## Данные приложения

Всё в одном корне `~/.tunnelmessenger` (Linux права 700; AppDirs.kt):

| Путь | Что лежит |
|---|---|
| `config.json` | сессия и настройки (base_url, theme_mode, флаги; секреты — `enc1:…`) |
| `tunnel_messenger.db` | кэш чатов/сообщений (SQLite, поля зашифрованы DEK) |
| `master.key` / `security.json` | мастер-ключ и запечатанный DEK (SecureStore) |
| `engine/` | извлечённые движки `tunnel-core` + `freeturn-client` (+ штамп версии) |
| `updates/` | скачанные релизы обновлений |
| `downloads/` | кэш расшифрованных медиа (стирается при выходе/очистке) |
| `tmp/`, `cache/fonts/`, `app.lock` | временные файлы, шрифты из jar, лок экземпляра |

## Трей и системные уведомления

`tray/DesktopIntegrations.kt`: закрытие окна при включённом фоне **прячет
приложение в трей** (WebSocket живёт — аналог foreground-службы Android).
Клик по иконке — показать окно; меню — «Открыть» / «Выход». Уведомления
показываются только когда окно скрыто/свернуто/без фокуса: Linux —
`notify-send` (DBus; фолбэк — balloon трея), Windows — balloon/toast.
Автозапуск: HKCU\…\Run (без прав администратора) / XDG-файл
`~/.config/autostart/tunnelmessenger.desktop`. Единственный экземпляр
гарантирует FileLock на `app.lock` (двух копий с одним SQLite не бывает).
В GNOME без AppIndicator трей недоступен — там закрытие окна завершает
приложение, уведомления идут через notify-send.

## Автообновления

`data/update/UpdateManager.kt`: `GET /api/updates/check?platform=linux&version=15`
— **сравнение выполняет сервер**, клиент шлёт свой `version=15`
(`currentVersionCode`/`currentVersionName` из кода). Ответ содержит имя/размер/
SHA-256 релиза и чейнжлог («Что нового?»). Скачивание — сначала
**многопоточное** (Range-сегменты 4 МиБ, 8–32 потока; фолбэк на
последовательное, если сервер не даёт 206) во временный каталог, затем проверка
SHA-256 и размера (несовпадение — файл удаляется). «Установка» = открыть папку
с файлом (`java.awt.Desktop`); автоматический запуск НЕ выполняется. Проверка
доступна даже без входа в аккаунт (base_url из config.json, дефолт
`http://10.10.10.1:80`).

## Документация

- `ИНСТРУКЦИЯ_LINUX.md` — подробные инструкции

