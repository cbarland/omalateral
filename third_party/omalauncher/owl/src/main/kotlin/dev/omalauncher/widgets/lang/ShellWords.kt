package dev.omalauncher.widgets.lang

/**
 * Finds the programs a shell command runs, well enough to notice a widget
 * using `jq` without declaring it. A heuristic, not a shell parser: it skips
 * quoted text, heredoc bodies, variables, builtins and functions the script
 * defines, and looks at the first word of each command.
 */
object ShellWords {
    private val KEYWORDS =
        setOf(
            "if", "then", "else", "elif", "fi", "for", "while", "until", "do", "done", "case", "esac", "in",
            "function", "select", "time", "!", "[[", "]]", "{", "}",
        )

    private val BUILTINS =
        setOf(
            "cd", "echo", "printf", "read", "local", "export", "return", "exit", "set", "unset", "shift", "trap",
            "test", "[", "true", "false", "source", ".", "eval", "wait", "type", "declare", "typeset", "let", ":",
            "mapfile", "readarray", "pwd", "kill", "alias", "unalias", "break", "continue", "hash", "shopt", "umask",
            "ulimit", "getopts", "jobs", "fg", "bg", "disown", "builtin", "command", "exec", "caller", "compgen", "complete",
        )

    /** Words that run the next word: `timeout 5 curl`, `exec jq`, `env X=1 foo`. */
    private val WRAPPERS = setOf("exec", "command", "builtin", "time", "nice", "nohup", "sudo", "env", "timeout", "xargs", "stdbuf")

    private val NAME = Regex("[A-Za-z_][A-Za-z0-9_.+-]*")
    private val FUNCTION = Regex("""(?m)^\s*(?:function\s+)?([A-Za-z_][A-Za-z0-9_-]*)\s*\(\s*\)""")
    private val HEREDOC = Regex("""<<-?\s*['"]?([A-Za-z_][A-Za-z0-9_]*)['"]?""")

    fun programs(script: String): Set<String> {
        val functions = FUNCTION.findAll(script).map { it.groupValues[1] }.toSet()
        val out = LinkedHashSet<String>()
        for (segment in segments(withoutHeredocs(script))) {
            val word = firstProgram(segment) ?: continue
            val name = word.substringAfterLast('/')
            if (name !in functions && name !in BUILTINS && name !in KEYWORDS && NAME.matches(name)) out += name
        }
        return out
    }

    private fun withoutHeredocs(script: String): String {
        val out = StringBuilder()
        var end: String? = null
        for (line in script.lines()) {
            if (end != null) {
                if (line.trim() == end) end = null
                continue
            }
            out.appendLine(line)
            HEREDOC.find(line)?.let { end = it.groupValues[1] }
        }
        return out.toString()
    }

    /**
     * Splits into simple commands at ; & | newlines and brackets, dropping
     * single-quoted text and comments. `$(...)` and backticks start commands of their own.
     */
    private fun segments(s: String): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        fun cut() {
            if (cur.isNotBlank()) out += cur.toString()
            cur.clear()
        }
        var i = 0
        var inDouble = false
        // Quote state outside each open $( ... ): "a $(b "c") d" has quotes of its own inside.
        val outer = ArrayDeque<Boolean>()
        while (i < s.length) {
            val c = s[i]
            when {
                c == '\\' -> {
                    cur.append(' ')
                    i += 2
                    continue
                }
                !inDouble && c == '\'' -> {
                    val close = s.indexOf('\'', i + 1).let { if (it < 0) s.length else it }
                    cur.append(" _ ")
                    i = close + 1
                    continue
                }
                c == '"' -> {
                    inDouble = !inDouble
                    cur.append(" _ ")
                }
                !inDouble && c == '#' && (i == 0 || s[i - 1].isWhitespace()) -> {
                    while (i < s.length && s[i] != '\n') i++
                    cut()
                    continue
                }
                // $(( arithmetic )) is a value, not a command: skip to its closing ))
                c == '$' && s.startsWith("((", i + 1) -> {
                    var depth = 0
                    i++
                    while (i < s.length) {
                        if (s[i] == '(') depth++ else if (s[i] == ')' && --depth == 0) break
                        i++
                    }
                    cur.append(" _ ")
                    i++
                    continue
                }
                c == '$' && s.getOrNull(i + 1) == '(' -> {
                    cut()
                    outer.addLast(inDouble)
                    inDouble = false
                    i += 2
                    continue
                }
                !inDouble && c == ')' && outer.isNotEmpty() -> {
                    cut()
                    inDouble = outer.removeLast()
                }
                c == '`' -> cut()
                // Inside double quotes only $(...) matters; everything else is text.
                inDouble -> {}
                c in ";&|\n(){}" -> cut()
                else -> cur.append(c)
            }
            i++
        }
        cut()
        return out
    }

    private fun firstProgram(segment: String): String? {
        val words = segment.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        var i = 0
        var afterWrapper = false
        while (i < words.size) {
            val w = words[i].let { if (it.startsWith("/")) it.substringAfterLast('/') else it }
            when {
                w == "_" -> return null // quoted text in command position: can't tell
                w.contains('=') && !w.startsWith("=") -> {} // FOO=bar prefix
                w.startsWith(">") || w.startsWith("<") || w.first().isDigit() && (w.contains('>') || w.contains('<')) -> {}
                // `for x in a b c`, `case $x in`: a header, not a command.
                w == "for" || w == "select" || w == "case" -> return null
                w in KEYWORDS -> {}
                afterWrapper && (w.startsWith("-") || w.first().isDigit()) -> {} // timeout 5, nice -n 10
                w in WRAPPERS -> afterWrapper = true
                w.startsWith("$") -> return null
                else -> return w
            }
            i++
        }
        return null
    }
}
