package app.smspend.pipeline

import android.content.Context
import androidx.room.Room
import app.smspend.data.RawSms
import app.smspend.data.SecretCipher
import app.smspend.data.SmSpendDatabase
import app.smspend.data.SmsInboxReader
import app.smspend.data.SpendRepository
import app.smspend.data.UserPreferences

/** In-memory inbox: tests add SMS and the repository reads them like the content provider. */
internal class FakeInbox(context: Context) : SmsInboxReader(context) {
    val messages = mutableListOf<RawSms>()

    override suspend fun readSince(minDateMs: Long): List<RawSms> =
        synchronized(messages) { messages.filter { it.dateMs >= minDateMs }.sortedBy { it.dateMs } }

    override suspend fun readById(smsId: Long): RawSms? =
        synchronized(messages) { messages.firstOrNull { it.id == smsId } }

    override suspend fun readByIds(smsIds: Collection<Long>): Map<Long, RawSms> =
        synchronized(messages) { messages.filter { it.id in smsIds }.associateBy { it.id } }

    fun add(id: Long, body: String, dateMs: Long, sender: String = "VM-HDFCBK-S") {
        synchronized(messages) { messages += RawSms(id = id, body = body, sender = sender, dateMs = dateMs) }
    }
}

internal class PlainCipher : SecretCipher {
    override fun encrypt(plain: String) = "v1:$plain"
    override fun decrypt(encoded: String) = encoded.removePrefix("v1:")
}

internal class TestGraph(context: Context) {
    val db: SmSpendDatabase = Room.inMemoryDatabaseBuilder(context, SmSpendDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    val inbox = FakeInbox(context)
    val prefs = UserPreferences(context, PlainCipher())
    val repo = SpendRepository(db = db, smsReader = inbox, prefs = prefs)

    fun close() = db.close()
}
