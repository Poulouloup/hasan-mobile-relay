package com.hasan.v1.utils

/**
 * Utilitaires Markdown : nettoyage pour le TTS et détection de QCM.
 */
object MarkdownUtils {

    /**
     * Détecte les options de QCM dans un texte et retourne leur liste.
     * Patterns supportés :
     *   - "1. option\n2. option" (min 2, max 8)
     *   - "A) option\nB) option"
     *
     * Retourne une liste vide si aucun QCM détecté ou si c'est une liste normale
     * (items sans numéro/lettre de choix explicite).
     */
    fun extractQcmOptions(text: String): List<String> {
        val lines = text.lines()

        // Pattern numérique : "1. " "2. " etc.
        val numberedPattern = Regex("^(\\d+)[.)\\s]\\s+(.+)$")
        val numbered = lines.mapNotNull { line ->
            numberedPattern.matchEntire(line.trim())?.groupValues?.get(2)?.trim()
        }
        if (numbered.size in 2..8) return numbered

        // Pattern lettres : "A) " "B) " ou "A. " "B. "
        val letterPattern = Regex("^([A-Ha-h])[.)\\s]\\s+(.+)$")
        val lettered = lines.mapNotNull { line ->
            letterPattern.matchEntire(line.trim())?.groupValues?.get(2)?.trim()
        }
        if (lettered.size in 2..8) return lettered

        return emptyList()
    }

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
