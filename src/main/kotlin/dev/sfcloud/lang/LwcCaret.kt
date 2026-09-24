package dev.sfcloud.lang

sealed interface LwcCaret {
    val prefix: String

    class TagName(override val prefix: String, val closing: Boolean) : LwcCaret

    class Attribute(override val prefix: String, val tag: String) : LwcCaret

    class Expression(override val prefix: String) : LwcCaret

    object Nowhere : LwcCaret {
        override val prefix: String = ""
    }

    companion object {
        fun at(text: CharSequence, offset: Int): LwcCaret {
            if (offset < 0 || offset > text.length) return Nowhere
            val openTag = text.lastIndexOf('<', offset - 1)
            val closeTag = text.lastIndexOf('>', offset - 1)
            if (openTag > closeTag) return inTag(text.subSequence(openTag + 1, offset))
            return expression(text, offset)
        }

        private fun inTag(segment: CharSequence): LwcCaret {
            val name = segment.takeWhile { !it.isWhitespace() }.toString()
            if (name.length == segment.length) {
                return TagName(name.removePrefix("/"), name.startsWith("/"))
            }
            val tail = segment.takeLastWhile { !it.isWhitespace() }.toString()
            if (tail.contains('=')) {
                val value = tail.substringAfter('=')
                val open = value.indexOf('{')
                if (open < 0 || value.contains('}')) return Nowhere
                return Expression(value.substring(open + 1))
            }
            return Attribute(tail, name)
        }

        private fun expression(text: CharSequence, offset: Int): LwcCaret {
            val open = text.lastIndexOf('{', offset - 1)
            if (open < 0) return Nowhere
            val inside = text.subSequence(open + 1, offset)
            if (inside.any { it == '}' || it == '<' || it == '>' || it == '\n' }) return Nowhere
            return Expression(inside.toString())
        }
    }
}
