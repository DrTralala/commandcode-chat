package com.commandcode.chat.ui

data class MessageEdit(
    val conversationId: String,
    val messageId: String,
    val text: String,
)

sealed interface EditNavigation {
    data class Tab(val route: String) : EditNavigation
    data class Conversation(val id: String) : EditNavigation
    data class Branch(val messageId: String) : EditNavigation
    data class EditAnother(val messageId: String) : EditNavigation
    data object NewChat : EditNavigation
    data object Back : EditNavigation
}
