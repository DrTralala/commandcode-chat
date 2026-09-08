package com.commandcode.chat.data.attachments

/** In-memory AttachmentFiles fake (Task 4 provides the encrypted production store). */
class FakeAttachmentFiles : com.commandcode.chat.data.attachments.AttachmentFiles {
    val stored = linkedMapOf<String, ByteArray>()
    var removed = mutableListOf<String>()
    private val open = mutableSetOf<String>()
    private var failWrite = false

    override fun initialise(referencedIds: Set<String>) {
        stored.keys.retainAll(referencedIds)
    }

    override fun write(id: String, plaintext: ByteArray): Long {
        if (failWrite) throw java.io.IOException("injected write failure")
        val copy = plaintext.copyOf()
        stored[id] = copy
        return copy.size.toLong() + 33L // magic+version+nonce+GCM overhead convention from Task 4.
    }

    override fun <T> withPlaintext(id: String, block: (ByteArray) -> T): T {
        require(id in stored) { "no stored bytes for $id" }
        open += id
        try {
            return block(stored.getValue(id).copyOf())
        } finally {
            open -= id
        }
    }

    override fun remove(id: String) {
        stored.remove(id)
        removed += id
    }

    fun assertStored(id: String, bytes: ByteArray) {
        check(stored.getValue(id).contentEquals(bytes)) { "stored bytes differ for $id" }
    }

    fun assertNoPlaintextOutstanding() {
        check(open.isEmpty()) { "plaintext callback still active: $open" }
    }
}
