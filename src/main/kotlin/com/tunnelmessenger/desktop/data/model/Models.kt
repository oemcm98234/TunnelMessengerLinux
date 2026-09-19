package com.tunnelmessenger.desktop.data.model

import kotlinx.serialization.Serializable

/**
 * Модели сервера Tunnel Messenger (docs/API.md).
 * Все поля nullable/с дефолтами — сервер может добавлять новые поля.
 */

@Serializable
data class AuthResp(val token: String? = null, val user: UserShort? = null)

/** И пользователь, и peer чата (у peer поле address). */
@Serializable
data class UserShort(
    val username: String? = null,
    val address: String? = null,
    val nickname: String? = null,
    val bio: String? = null,
    val online: Boolean? = null,
    val last_seen: Double? = null,
)

@Serializable
data class ServerInfo(
    val domain: String? = null,
    val federation: Boolean? = null,
    val max_file_mb: Long? = null,
)

@Serializable
data class MeResp(val user: UserShort? = null, val server: ServerInfo? = null)

@Serializable
data class FileMeta(
    val id: String? = null,
    val name: String? = null,
    val size: Long? = null,
    val voice: VoiceMeta? = null,
)

@Serializable
data class Reaction(val emoji: String = "", val count: Int = 0, val users: List<String> = emptyList())

@Serializable
data class GroupMember(
    val username: String? = null,
    val nickname: String? = null,
    val online: Boolean? = null,
    val owner: Boolean = false,
)

@Serializable
data class Msg(
    val id: Long = 0,
    val client_id: String? = null,
    val chat_id: Long = 0,
    val sender: String? = null,
    val type: String = "text",
    val kind: String = "user",
    val body: String? = null,
    val file: FileMeta? = null,
    val created_at: Double = 0.0,
    val edited_at: Double? = null,
    val status: String? = null,
    val reactions: List<Reaction>? = null,
)

@Serializable
data class Chat(
    val id: Long = 0,
    val peer: UserShort? = null,
    val is_group: Boolean = false,
    val is_p2p: Boolean = false,
    val history: Boolean = true,
    val title: String? = null,
    val owner: String? = null,
    val members: List<GroupMember> = emptyList(),
    val last_msg_id: Long = 0,
    val last_read: Long = 0,
    val last_delivered: Long = 0,
    val unread: Int = 0,
    val updated_at: Double = 0.0,
    val last_message: Msg? = null,
) {
    val peerAddress: String?
        get() = peer?.address ?: peer?.username

    val displayName: String
        get() = when {
            is_group -> (title ?: "Группа")
            else -> peer?.nickname?.takeIf { it.isNotBlank() } ?: peerAddress ?: "Чат"
        }
}

@Serializable
data class ChatsResp(val chats: List<Chat> = emptyList())

@Serializable
data class ChatResp(val chat: Chat? = null)

@Serializable
data class MessagesResp(val messages: List<Msg> = emptyList(), val last_msg_id: Long = 0)

@Serializable
data class MsgResp(val msg: Msg? = null, val delivered: Boolean? = null)

@Serializable
data class SearchResp(val users: List<UserShort> = emptyList())

@Serializable
data class ContactsResp(val contacts: List<UserShort> = emptyList())

@Serializable
data class KeyResp(val username: String? = null, val public_key: String? = null)

@Serializable
data class OkResp(val ok: Boolean? = null)

@Serializable
data class ReactResp(
    val ok: Boolean? = null,
    val added: Boolean? = null,
    val reactions: List<Reaction> = emptyList(),
)

@Serializable
data class HealthResp(
    val ok: Boolean? = null,
    val domain: String? = null,
    val register_open: Boolean? = null,
    val e2e: Boolean? = null,
)

/** Внутреннее содержимое E2E-меты файла (формат TME1): {"k","sz","fn","c"} */
@Serializable
data class FileSecret(
    val k: String = "", val sz: Long = 0, val fn: String = "", val c: String = "",
    val voice: VoiceMeta? = null,
)

/**
 * То, что хранится в локальной БД в поле file_json для обычных чатов:
 * секрет TME1 + серверный id файла (нужен для скачивания).
 * Обратно совместимо со старыми строками (там лежал чистый FileSecret).
 */
@Serializable
data class StoredFile(
    val id: String? = null,
    val k: String = "",
    val sz: Long = 0,
    val fn: String = "",
    val c: String = "",
    val voice: VoiceMeta? = null,
)

/** P2P-мета файла чата без истории: {"p2pfile","k","sz","fn","c"} */
@Serializable
data class P2pFileSecret(
    val p2pfile: String = "", val k: String = "", val sz: Long = 0, val fn: String = "", val c: String = "",
    val voice: VoiceMeta? = null,
)

/**
 * Голосовое сообщение — расширение меты файла: {"dur": сек, "wave": [0..100]}
 * (wave — необязательная огибающая для отрисовки; клиенты без неё рисуют
 * плоскую полоску). Мета уезжает внутри E2E-конверта — сервер её не видит.
 */
@Serializable
data class VoiceMeta(val dur: Int = 0, val wave: List<Int>? = null)


// =============================================================== v11: блокировки

/** Запись в блок-листе текущего пользователя: {address, created_at}. */
@Serializable
data class BlockEntry(
    val address: String = "",
    val created_at: Double = 0.0,
)

/** Ответ /api/blocks/check?address=... — статус блокировки в обе стороны. */
@Serializable
data class BlockCheckResp(
    val address: String = "",
    /** Заблокировал ли ТЕКУУЩИЙ пользователь этого адресата. */
    val i_blocked: Boolean = false,
    /** Есть ли блокировка в любую сторону (тебя заблокировали ИЛИ ты заблокировал). */
    val blocked_any_direction: Boolean = false,
)


// =============================================================== v11: обновления

/** Метаданные последней доступной версии клиента. */
@Serializable
data class UpdateInfo(
    val available: Boolean = false,
    val platform: String = "android",
    val version: String = "",
    val version_code: Int = 0,
    val filename: String = "",
    val size: Long = 0,
    val mtime: Double = 0.0,
    val download_url: String = "",
    val changelog: String = "",
)

/** Результат сравнения текущей версии клиента с серверной. */
@Serializable
data class UpdateCheckResp(
    val update_available: Boolean = false,
    val current_version: Int = 0,
    val latest_version: Int = 0,
    val filename: String = "",
    val size: Long = 0,
    val download_url: String = "",
    val platform: String = "android",
    /** v13: текст чейнжлога релиза (Updates/<имя>.txt); пусто — файла нет. */
    val changelog: String? = null,
    /** v13 (аудит): SHA-256 файла релиза (hex); пусто — сервер старый, проверка
     *  по сумме недоступна (остаются размер). */
    val sha256: String? = null,
)
