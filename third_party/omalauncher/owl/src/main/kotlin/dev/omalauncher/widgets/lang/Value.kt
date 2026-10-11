package dev.omalauncher.widgets.lang

/**
 * Runtime values of the widget language. Plain data with structural equality,
 * so a command that prints the same output twice invalidates nothing.
 */
sealed interface Value {
    val truthy: Boolean get() = true
}

data object Null : Value {
    override val truthy get() = false

    override fun toString() = "null"
}

@JvmInline
value class Num(
    val v: Double,
) : Value {
    override val truthy get() = v != 0.0 && !v.isNaN()

    override fun toString() = if (v == Math.rint(v) && kotlin.math.abs(v) < 1e15) v.toLong().toString() else v.toString()
}

@JvmInline
value class Str(
    val v: String,
) : Value {
    override val truthy get() = v.isNotEmpty()

    override fun toString() = v
}

@JvmInline
value class Bool(
    val v: Boolean,
) : Value {
    override val truthy get() = v

    override fun toString() = v.toString()
}

/** A color: either a theme name (resolved when drawn, so it follows theme changes) or a fixed ARGB. */
data class Col(
    val theme: String?,
    val argb: Int,
    val alpha: Float = 1f,
) : Value {
    override fun toString() = theme ?: "#%08x".format(argb)
}

data class Lst(
    val items: List<Value>,
) : Value {
    override val truthy get() = items.isNotEmpty()

    override fun toString() = items.joinToString(", ", "[", "]")
}

data class Obj(
    val fields: Map<String, Value>,
) : Value {
    override val truthy get() = fields.isNotEmpty()

    override fun toString() = fields.entries.joinToString(", ", "{", "}") { "${it.key}: ${it.value}" }
}

/** A gradient for fills: linear from (x0,y0) to (x1,y1), or radial around (x0,y0) with radius x1. */
data class Gradient(
    val radial: Boolean,
    val coords: List<Float>,
    val colors: List<Col>,
) : Value

/** An arrow function, only ever passed to built-ins like map and filter. */
class Fn(
    val arity: Int,
    val call: (List<Value>) -> Value,
) : Value {
    override fun toString() = "<fn>"
}

val TRUE = Bool(true)
val FALSE = Bool(false)

fun bool(b: Boolean) = if (b) TRUE else FALSE

/** Numbers from numbers, numeric strings ("42%" too) and booleans; anything else is null. */
fun Value.num(): Double? =
    when (this) {
        is Num -> v
        is Str -> v.trim().removeSuffix("%").trim().toDoubleOrNull()
        is Bool -> if (v) 1.0 else 0.0
        else -> null
    }

/**
 * Text for display. Null shows as "…": a source that hasn't produced anything yet.
 * Trailing whitespace and surrounding blank lines go (command output ends in a
 * newline); leading spaces stay, so aligned output like tables and figlet keeps its shape.
 */
fun Value.text(): String =
    when (this) {
        Null -> "…"
        is Str -> v.trimEnd().trimStart('\n', '\r')
        else -> toString()
    }

class WidgetError(
    message: String,
) : RuntimeException(message)

/** Parses #rgb, #rrggbb and #rrggbbaa (CSS order) into ARGB. */
fun parseHex(hex: String): Int? {
    val h = hex.removePrefix("#")
    val full =
        when (h.length) {
            3 -> h.map { "$it$it" }.joinToString("") + "ff"
            6 -> h + "ff"
            8 -> h
            else -> return null
        }
    val rgba = full.toLongOrNull(16) ?: return null
    val a = (rgba and 0xff).toInt()
    return (a shl 24) or ((rgba ushr 8).toInt() and 0x00ffffff)
}
