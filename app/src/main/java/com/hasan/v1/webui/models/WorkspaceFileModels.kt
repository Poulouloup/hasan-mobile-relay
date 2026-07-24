package com.hasan.v1.webui.models

/** Une entrée du workspace de session (fichier ou dossier) — voir WebUiWorkspaceClient.listFiles. */
data class WorkspaceEntry(
    val name: String,
    val path: String,
    val isDir: Boolean,
    val size: Long?,
    val mtimeNs: Long?
)
