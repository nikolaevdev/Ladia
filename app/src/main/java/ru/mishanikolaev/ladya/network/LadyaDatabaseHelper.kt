package ru.mishanikolaev.ladya.network

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import ru.mishanikolaev.ladya.ui.models.ChatThreadUi
import ru.mishanikolaev.ladya.ui.models.ContactUi
import ru.mishanikolaev.ladya.ui.models.MessageDeliveryStatus
import ru.mishanikolaev.ladya.ui.models.MessageType
import ru.mishanikolaev.ladya.ui.models.MessageUi
import ru.mishanikolaev.ladya.ui.models.TrustStatus

class LadyaDatabaseHelper(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE contacts (
                id TEXT PRIMARY KEY,
                peer_id TEXT,
                display_name TEXT NOT NULL,
                host TEXT,
                port INTEGER NOT NULL,
                public_nick TEXT,
                avatar_emoji TEXT,
                trust_status TEXT NOT NULL,
                fingerprint TEXT,
                trust_warning TEXT,
                public_key_base64 TEXT,
                key_id TEXT
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_contacts_peer_id ON contacts(peer_id)")
        db.execSQL("CREATE INDEX idx_contacts_key_id ON contacts(key_id)")

        db.execSQL(
            """
            CREATE TABLE threads (
                thread_key TEXT PRIMARY KEY,
                title TEXT NOT NULL,
                preview TEXT,
                timestamp TEXT,
                last_message_epoch INTEGER NOT NULL,
                unread_count INTEGER NOT NULL,
                is_saved_contact INTEGER NOT NULL,
                peer_id TEXT,
                host TEXT,
                port INTEGER NOT NULL,
                avatar_emoji TEXT
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE messages (
                id TEXT PRIMARY KEY,
                thread_key TEXT NOT NULL,
                message_id TEXT NOT NULL,
                text TEXT NOT NULL,
                timestamp TEXT NOT NULL,
                type TEXT NOT NULL,
                sender_label TEXT,
                sent_at_millis INTEGER NOT NULL,
                delivery_status TEXT,
                is_edited INTEGER NOT NULL,
                is_deleted INTEGER NOT NULL,
                attachment_name TEXT,
                attachment_mime_type TEXT,
                attachment_uri TEXT,
                attachment_size_bytes INTEGER,
                is_image_attachment INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_messages_thread_key ON messages(thread_key)")
        db.execSQL("CREATE INDEX idx_messages_message_id ON messages(message_id)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("DROP TABLE IF EXISTS messages")
            db.execSQL("DROP TABLE IF EXISTS threads")
            db.execSQL("DROP TABLE IF EXISTS contacts")
            onCreate(db)
        }
    }

    fun loadContacts(): List<ContactUi> = readableDatabase.query(
        "contacts", null, null, null, null, null, "display_name COLLATE NOCASE ASC"
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                add(
                    ContactUi(
                        id = cursor.getStringOrEmpty("id"),
                        peerId = cursor.getStringOrEmpty("peer_id"),
                        displayName = cursor.getStringOrEmpty("display_name"),
                        host = cursor.getStringOrEmpty("host"),
                        port = cursor.getIntOrDefault("port", LadyaNodeRepository.DEFAULT_PORT),
                        isOnline = false,
                        publicNick = cursor.getNullableString("public_nick"),
                        avatarEmoji = cursor.getNullableString("avatar_emoji"),
                        trustStatus = cursor.getNullableString("trust_status")?.let { runCatching { TrustStatus.valueOf(it) }.getOrDefault(TrustStatus.Unverified) } ?: TrustStatus.Unverified,
                        fingerprint = cursor.getNullableString("fingerprint"),
                        trustWarning = cursor.getNullableString("trust_warning"),
                        publicKeyBase64 = cursor.getNullableString("public_key_base64"),
                        keyId = cursor.getNullableString("key_id")
                    )
                )
            }
        }
    }

    fun saveContacts(contacts: List<ContactUi>) {
        writableDatabase.beginTransaction()
        try {
            writableDatabase.delete("contacts", null, null)
            contacts.forEach { contact ->
                writableDatabase.insert("contacts", null, ContentValues().apply {
                    put("id", contact.id)
                    put("peer_id", contact.peerId)
                    put("display_name", contact.displayName)
                    put("host", contact.host)
                    put("port", contact.port)
                    put("public_nick", contact.publicNick)
                    put("avatar_emoji", contact.avatarEmoji)
                    put("trust_status", contact.trustStatus.name)
                    put("fingerprint", contact.fingerprint)
                    put("trust_warning", contact.trustWarning)
                    put("public_key_base64", contact.publicKeyBase64)
                    put("key_id", contact.keyId)
                })
            }
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
    }

    fun loadThreadSnapshots(): List<ChatThreadUi> = readableDatabase.query(
        "threads", null, null, null, null, null, "last_message_epoch DESC"
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                add(
                    ChatThreadUi(
                        key = cursor.getStringOrEmpty("thread_key"),
                        title = cursor.getStringOrEmpty("title"),
                        preview = cursor.getStringOrEmpty("preview"),
                        timestamp = cursor.getStringOrEmpty("timestamp"),
                        lastMessageEpoch = cursor.getLongOrDefault("last_message_epoch", 0L),
                        unreadCount = cursor.getIntOrDefault("unread_count", 0),
                        isOnline = false,
                        isTyping = false,
                        isSavedContact = cursor.getIntOrDefault("is_saved_contact", 0) == 1,
                        peerId = cursor.getNullableString("peer_id"),
                        host = cursor.getNullableString("host"),
                        port = cursor.getIntOrDefault("port", LadyaNodeRepository.DEFAULT_PORT),
                        avatarEmoji = cursor.getNullableString("avatar_emoji")
                    )
                )
            }
        }
    }

    fun saveThreadSnapshots(threads: Collection<ChatThreadUi>) {
        writableDatabase.beginTransaction()
        try {
            writableDatabase.delete("threads", null, null)
            threads.forEach { thread ->
                writableDatabase.insert("threads", null, ContentValues().apply {
                    put("thread_key", thread.key)
                    put("title", thread.title)
                    put("preview", thread.preview)
                    put("timestamp", thread.timestamp)
                    put("last_message_epoch", thread.lastMessageEpoch)
                    put("unread_count", thread.unreadCount)
                    put("is_saved_contact", if (thread.isSavedContact) 1 else 0)
                    put("peer_id", thread.peerId)
                    put("host", thread.host)
                    put("port", thread.port)
                    put("avatar_emoji", thread.avatarEmoji)
                })
            }
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
    }

    fun loadThreadHistories(): Map<String, List<MessageUi>> {
        val result = linkedMapOf<String, MutableList<MessageUi>>()
        readableDatabase.query("messages", null, null, null, null, null, "sent_at_millis ASC").use { cursor ->
            while (cursor.moveToNext()) {
                val key = cursor.getStringOrEmpty("thread_key")
                val list = result.getOrPut(key) { mutableListOf() }
                list += MessageUi(
                    id = cursor.getStringOrEmpty("id"),
                    messageId = cursor.getStringOrEmpty("message_id"),
                    text = cursor.getStringOrEmpty("text"),
                    timestamp = cursor.getStringOrEmpty("timestamp"),
                    type = cursor.getNullableString("type")?.let { runCatching { MessageType.valueOf(it) }.getOrDefault(MessageType.System) } ?: MessageType.System,
                    senderLabel = cursor.getNullableString("sender_label"),
                    sentAtMillis = cursor.getLongOrDefault("sent_at_millis", System.currentTimeMillis()),
                    deliveryStatus = cursor.getNullableString("delivery_status")?.let { runCatching { MessageDeliveryStatus.valueOf(it) }.getOrNull() },
                    isEdited = cursor.getIntOrDefault("is_edited", 0) == 1,
                    isDeleted = cursor.getIntOrDefault("is_deleted", 0) == 1,
                    attachmentName = cursor.getNullableString("attachment_name"),
                    attachmentMimeType = cursor.getNullableString("attachment_mime_type"),
                    attachmentUri = cursor.getNullableString("attachment_uri"),
                    attachmentSizeBytes = if (cursor.isNull(cursor.getColumnIndexOrThrow("attachment_size_bytes"))) null else cursor.getLong(cursor.getColumnIndexOrThrow("attachment_size_bytes")),
                    isImageAttachment = cursor.getIntOrDefault("is_image_attachment", 0) == 1
                )
            }
        }
        return result
    }

    fun replaceThreadMessages(threadKey: String, messages: List<MessageUi>) {
        val normalizedMessages = messages
            .takeLast(500)
            .asReversed()
            .distinctBy { storageMessageRowId(threadKey, it) }
            .asReversed()
            .takeLast(300)

        writableDatabase.beginTransaction()
        try {
            writableDatabase.delete("messages", "thread_key = ?", arrayOf(threadKey))
            normalizedMessages.forEach { message ->
                writableDatabase.insertWithOnConflict(
                    "messages",
                    null,
                    ContentValues().apply {
                        put("id", storageMessageRowId(threadKey, message))
                        put("thread_key", threadKey)
                        put("message_id", message.messageId.ifBlank { message.id })
                        put("text", message.text)
                        put("timestamp", message.timestamp)
                        put("type", message.type.name)
                        put("sender_label", message.senderLabel)
                        put("sent_at_millis", message.sentAtMillis)
                        put("delivery_status", message.deliveryStatus?.name)
                        put("is_edited", if (message.isEdited) 1 else 0)
                        put("is_deleted", if (message.isDeleted) 1 else 0)
                        put("attachment_name", message.attachmentName)
                        put("attachment_mime_type", message.attachmentMimeType)
                        put("attachment_uri", message.attachmentUri)
                        put("attachment_size_bytes", message.attachmentSizeBytes)
                        put("is_image_attachment", if (message.isImageAttachment) 1 else 0)
                    },
                    SQLiteDatabase.CONFLICT_REPLACE
                )
            }
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
    }

    fun deleteThread(threadKey: String) {
        writableDatabase.beginTransaction()
        try {
            writableDatabase.delete("messages", "thread_key = ?", arrayOf(threadKey))
            writableDatabase.delete("threads", "thread_key = ?", arrayOf(threadKey))
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
    }


    private fun storageMessageRowId(threadKey: String, message: MessageUi): String {
        val baseId = message.messageId.ifBlank { message.id }.ifBlank {
            "msg_${'$'}{message.sentAtMillis}_${'$'}{message.text.hashCode()}"
        }
        return "${'$'}threadKey::${'$'}baseId"
    }

    private fun android.database.Cursor.getNullableString(column: String): String? {
        val index = getColumnIndex(column)
        if (index < 0 || isNull(index)) return null
        return getString(index)?.takeIf { it.isNotBlank() }
    }

    private fun android.database.Cursor.getStringOrEmpty(column: String): String = getNullableString(column).orEmpty()
    private fun android.database.Cursor.getIntOrDefault(column: String, default: Int): Int {
        val index = getColumnIndex(column)
        return if (index < 0 || isNull(index)) default else getInt(index)
    }
    private fun android.database.Cursor.getLongOrDefault(column: String, default: Long): Long {
        val index = getColumnIndex(column)
        return if (index < 0 || isNull(index)) default else getLong(index)
    }

    companion object {
        private const val DB_NAME = "ladya.db"
        private const val DB_VERSION = 2
    }
}
