package com.hasan.v1.webui.models

/**
 * Résultat de POST /api/upload (multipart session_id+file) — voir
 * api/upload.py `handle_upload`. Le `path` retourné est celui à renvoyer
 * tel quel dans `attachments[]` de POST /api/chat/start (api/routes.py
 * `_normalize_chat_attachments` lit name/path/mime/size/is_image).
 */
data class UploadedAttachment(
    val name: String,
    val path: String,
    val size: Long,
    val mime: String,
    val isImage: Boolean
)
