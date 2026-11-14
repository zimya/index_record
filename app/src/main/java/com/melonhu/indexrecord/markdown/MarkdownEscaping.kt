package com.melonhu.indexrecord.markdown

/** For plain API text embedded alongside generated Markdown media. */
fun escapeMarkdownText(text: String): String = buildString {
    text.forEach { char ->
        if (char in "\\`*_{}[]<>#!|~&.()+-=") append('\\')
        append(char)
    }
}
