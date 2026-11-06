package com.vibes.only

/** A locally selected attachment. Only extracted text is sent to the text gateway. */
data class ChatAttachment(
    val name: String,
    val mimeType: String,
    val base64Data: String,
    val extractedText: String = ""
)
