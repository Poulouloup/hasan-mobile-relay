package com.hasan.v1.utils

/**
 * Utilitaires pour nettoyer le Markdown avant envoi au TTS.
 * Le TTS ne doit recevoir que du texte naturel sans balises.
 */
object MarkdownUtils {

    /**
     * Supprime les balises Markdown d'un texte pour le rendre lisible par le TTS.
     *
     * Transformations appliquées :
     *  - Blocs code   : ```...``` → contenu brut
     *  - Code inline  : `code`   → code
     *  - Gras/italique: **x**, *x*, __x__, _x_ → x
     *  - Barré        : ~~x~~    → x
     *  - Titres       : # Titre  → Titre
     *  - Liens        : [texte](url) → texte
     *  - Images       : ![alt](url) → alt
     *  - HTML         : <tag> → supprimé
     *  - Listes       : "- item" / "* item" / "1. item" → item
     *  - Lignes vides multiples → une seule
     */
    fun stripMarkdown(text: String): String {
        var result = text

        // Blocs code multi-lignes — supprime les backticks et conserve le contenu
        result = result.replace(Regex("```[\\w]*\\n?"), "")
        result = result.replace("```", "")

        // Code inline
        result = result.replace(Regex("`([^`]+)`"), "$1")

        // Gras + italique combinés ***x***
        result = result.replace(Regex("\\*{3}(.+?)\\*{3}"), "$1")

        // Gras **x** ou __x__
        result = result.replace(Regex("\\*{2}(.+?)\\*{2}"), "$1")
        result = result.replace(Regex("_{2}(.+?)_{2}"), "$1")

        // Italique *x* ou _x_ (ne pas toucher les underscores dans les mots)
        result = result.replace(Regex("(?<![\\w])\\*(.+?)\\*(?![\\w])"), "$1")
        result = result.replace(Regex("(?<![\\w])_(.+?)_(?![\\w])"), "$1")

        // Barré ~~x~~
        result = result.replace(Regex("~~(.+?)~~"), "$1")

        // Titres # ## ###
        result = result.replace(Regex("^#{1,6}\\s+", RegexOption.MULTILINE), "")

        // Liens [texte](url) → texte
        result = result.replace(Regex("!?\\[([^\\]]*)]\\([^)]*\\)"), "$1")

        // Balises HTML résiduelles
        result = result.replace(Regex("<[^>]+>"), "")

        // Marqueurs de liste → texte seul
        result = result.replace(Regex("^[-*+]\\s+", RegexOption.MULTILINE), "")
        result = result.replace(Regex("^\\d+\\.\\s+", RegexOption.MULTILINE), "")

        // Lignes de séparation ---
        result = result.replace(Regex("^[-*_]{3,}\\s*$", RegexOption.MULTILINE), "")

        // Lignes vides multiples → une seule
        result = result.replace(Regex("\\n{3,}"), "\n\n")

        return result.trim()
    }
}
