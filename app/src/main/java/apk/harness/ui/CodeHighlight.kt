package apk.harness.ui

/**
 * A small syntax highlighter: keywords, strings, comments and numbers.
 *
 * Ours rather than a library's. The highlighter the markdown renderer's own code
 * module uses tokenizes by repeated whole-string scans, one of which is
 * quadratic in the input, and it knows fewer languages than
 * [apk.harness.ui.LANGUAGES] already labels fences with -- with the ones it
 * cannot colour falling back to its slowest path. The alternative with wider
 * coverage is unpublished to Maven Central and unmeasured.
 *
 * What is here is one linear pass, it covers every tag that map emits, and it is
 * plain Kotlin over a `String`, which is what makes it testable at all.
 *
 * It is deliberately shallow. There are no types, no function names, no
 * attributes; four kinds of token over a monospace font is the whole of what
 * reading code on a phone needs.
 */

/** What a run of characters is. */
enum class TokenKind { Comment, Text, Number, Keyword }

/** A run of [kind] covering `[start, end)` of the source. */
data class Token(val start: Int, val end: Int, val kind: TokenKind)

/**
 * What one language looks like.
 *
 * [quotes] are the characters that open and close a string, each closed by
 * itself. [escape] is the character that stops the one after it from closing a
 * string, or null in a language with none.
 */
data class Grammar(
    val keywords: Set<String>,
    val lineComments: List<String>,
    val blockComment: Pair<String, String>?,
    val quotes: Set<Char>,
    val escape: Char?,
)

/**
 * The grammar for a fence's info string, or null for a tag with none.
 *
 * Null renders plain, which is also what an unlabelled fence gets. Guessing a
 * language from the contents is a different piece of work, and a wrong guess
 * colours the file as something it is not.
 */
fun grammarFor(language: String?): Grammar? = GRAMMARS[language?.lowercase()]

/**
 * The tokens of [source] under [grammar], in order, non-overlapping.
 *
 * One pass, left to right, first match wins. The order the cases are tried in is
 * what makes a `#` inside a string not a comment and a keyword inside a comment
 * not a keyword: whichever construct starts first swallows the rest of itself.
 *
 * Runs that are none of the four kinds produce no token rather than a plain one,
 * so the result is as short as the colouring needs it to be.
 */
fun tokenize(source: String, grammar: Grammar): List<Token> {
    val tokens = mutableListOf<Token>()
    var index = 0

    while (index < source.length) {
        val character = source[index]

        val lineComment = grammar.lineComments.firstOrNull { source.startsWith(it, index) }
        if (lineComment != null) {
            val end = source.indexOf('\n', index).takeIf { it >= 0 } ?: source.length
            tokens += Token(index, end, TokenKind.Comment)
            index = end
            continue
        }

        val block = grammar.blockComment
        if (block != null && source.startsWith(block.first, index)) {
            val closed = source.indexOf(block.second, index + block.first.length)
            val end = if (closed < 0) source.length else closed + block.second.length
            tokens += Token(index, end, TokenKind.Comment)
            index = end
            continue
        }

        if (character in grammar.quotes) {
            index = readString(source, index, character, grammar.escape, tokens)
            continue
        }

        if (character.isDigit()) {
            var end = index
            // What follows a digit that could belong to a literal: other digits,
            // a radix prefix, a fraction, an exponent, a type suffix. Reading
            // them as one run is enough to colour it; nothing here decides
            // whether the literal is valid.
            while (end < source.length && (source[end].isLetterOrDigit() ||
                    source[end] == '.' || source[end] == '_')
            ) {
                end++
            }
            tokens += Token(index, end, TokenKind.Number)
            index = end
            continue
        }

        if (character.isWord()) {
            var end = index
            while (end < source.length && source[end].isWord()) end++
            // The bounds are what the scan already gives: a word runs to the
            // first character that cannot be in one, so `information` is one
            // word and never the keyword `for`.
            if (source.substring(index, end) in grammar.keywords) {
                tokens += Token(index, end, TokenKind.Keyword)
            }
            index = end
            continue
        }

        index++
    }

    return tokens
}

/**
 * Past the string starting at [start], having recorded it.
 *
 * A string ends at its closing quote or at the end of its line, whichever comes
 * first. Nothing here tracks a string that spans lines: an unterminated quote
 * that coloured the rest of the file would be a worse answer than one that
 * coloured the rest of its line.
 */
private fun readString(
    source: String,
    start: Int,
    quote: Char,
    escape: Char?,
    into: MutableList<Token>,
): Int {
    var index = start + 1
    while (index < source.length) {
        val character = source[index]
        if (character == '\n') break
        if (character == escape) {
            index += 2
            continue
        }
        if (character == quote) {
            index++
            break
        }
        index++
    }
    val end = minOf(index, source.length)
    into += Token(start, end, TokenKind.Text)
    return end
}

/** Whether a character can be part of a word. `$` and `@` are, in the languages here. */
private fun Char.isWord(): Boolean = isLetterOrDigit() || this == '_' || this == '$' || this == '@'

private fun words(vararg groups: String): Set<String> =
    groups.flatMap { it.trim().split(Regex("\\s+")) }.toSet()

private val C_LINE = listOf("//")
private val C_BLOCK = "/*" to "*/"
private val HASH = listOf("#")
private val QUOTES = setOf('"', '\'')
private val BACKTICK_QUOTES = setOf('"', '\'', '`')

/**
 * The languages this colours, keyed by the tags `FileDocument.kt` emits.
 *
 * The keyword sets are the ones worth seeing in a phone-sized window -- control
 * flow, declarations, the literals -- rather than every reserved word each
 * language has. A word missing from a set costs its colour and nothing else.
 */
private val GRAMMARS: Map<String, Grammar> = buildMap {
    val c = Grammar(
        keywords = words(
            "auto break case char const continue default do double else enum extern",
            "float for goto if inline int long register restrict return short signed",
            "sizeof static struct switch typedef union unsigned void volatile while",
            "NULL true false",
        ),
        lineComments = C_LINE,
        blockComment = C_BLOCK,
        quotes = QUOTES,
        escape = '\\',
    )
    put("c", c)
    put("h", c)

    val cpp = c.copy(
        keywords = c.keywords + words(
            "class namespace template typename public private protected virtual",
            "override final new delete this nullptr using constexpr explicit friend",
            "operator try catch throw noexcept mutable static_cast dynamic_cast",
        ),
    )
    put("cpp", cpp)
    put("hpp", cpp)

    val java = Grammar(
        keywords = words(
            "abstract assert boolean break byte case catch char class const continue",
            "default do double else enum extends final finally float for goto if",
            "implements import instanceof int interface long native new package",
            "private protected public return short static strictfp super switch",
            "synchronized this throw throws transient try void volatile while var",
            "record sealed permits yield true false null",
        ),
        lineComments = C_LINE,
        blockComment = C_BLOCK,
        quotes = QUOTES,
        escape = '\\',
    )
    put("java", java)

    put("kotlin", Grammar(
        keywords = words(
            "as break by catch class companion const constructor continue crossinline",
            "data do else enum external false field file finally for fun get if import",
            "in infix init inline interface internal is lateinit noinline null object",
            "open operator out override package private protected public reified return",
            "sealed set super suspend tailrec this throw true try typealias val value",
            "var vararg when where while annotation abstract final",
        ),
        lineComments = C_LINE,
        blockComment = C_BLOCK,
        quotes = QUOTES,
        escape = '\\',
    ))

    val javascript = Grammar(
        keywords = words(
            "async await break case catch class const continue debugger default delete",
            "do else export extends finally for from function get if import in",
            "instanceof let new of return set static super switch this throw try",
            "typeof var void while with yield true false null undefined",
        ),
        lineComments = C_LINE,
        blockComment = C_BLOCK,
        quotes = BACKTICK_QUOTES,
        escape = '\\',
    )
    put("javascript", javascript)
    put("typescript", javascript.copy(
        keywords = javascript.keywords + words(
            "abstract as any boolean declare enum implements interface is keyof",
            "namespace never number object private protected public readonly require",
            "string symbol type unknown",
        ),
    ))

    put("python", Grammar(
        keywords = words(
            "and as assert async await break class continue def del elif else except",
            "finally for from global if import in is lambda match nonlocal not or pass",
            "raise return try while with yield True False None self",
        ),
        lineComments = HASH,
        blockComment = null,
        quotes = QUOTES,
        escape = '\\',
    ))

    put("go", Grammar(
        keywords = words(
            "break case chan const continue default defer else fallthrough for func go",
            "goto if import interface map package range return select struct switch",
            "type var nil true false make new len cap append",
        ),
        lineComments = C_LINE,
        blockComment = C_BLOCK,
        quotes = BACKTICK_QUOTES,
        escape = '\\',
    ))

    put("rust", Grammar(
        keywords = words(
            "as async await break const continue crate dyn else enum extern false fn",
            "for if impl in let loop match mod move mut pub ref return self Self static",
            "struct super trait true type unsafe use where while",
        ),
        lineComments = C_LINE,
        blockComment = C_BLOCK,
        quotes = QUOTES,
        escape = '\\',
    ))

    put("ruby", Grammar(
        keywords = words(
            "alias and begin break case class def defined do else elsif end ensure",
            "false for if in module next nil not or redo rescue retry return self",
            "super then true undef unless until when while yield require",
        ),
        lineComments = HASH,
        blockComment = null,
        quotes = QUOTES,
        escape = '\\',
    ))

    val shell = Grammar(
        keywords = words(
            "if then elif else fi for while until do done case esac function return",
            "in break continue local export readonly unset shift eval exec exit set",
            "trap source echo cd test",
        ),
        lineComments = HASH,
        blockComment = null,
        quotes = QUOTES,
        escape = '\\',
    )
    put("bash", shell)
    put("sh", shell)
    put("zsh", shell)

    put("sql", Grammar(
        keywords = words(
            "SELECT FROM WHERE INSERT INTO VALUES UPDATE SET DELETE CREATE TABLE INDEX",
            "VIEW DROP ALTER ADD JOIN LEFT RIGHT INNER OUTER FULL ON GROUP BY ORDER",
            "HAVING LIMIT OFFSET UNION ALL DISTINCT AS AND OR NOT NULL IS IN LIKE",
            "BETWEEN EXISTS CASE WHEN THEN ELSE END PRIMARY KEY FOREIGN REFERENCES",
            "select from where insert into values update set delete create table index",
            "view drop alter add join left right inner outer full on group by order",
            "having limit offset union all distinct as and or not null is in like",
            "between exists case when then else end primary key foreign references",
        ),
        lineComments = listOf("--"),
        blockComment = C_BLOCK,
        quotes = QUOTES,
        escape = null,
    ))

    put("yaml", Grammar(
        keywords = words("true false null yes no on off"),
        lineComments = HASH,
        blockComment = null,
        quotes = QUOTES,
        escape = '\\',
    ))

    put("toml", Grammar(
        keywords = words("true false"),
        lineComments = HASH,
        blockComment = null,
        quotes = QUOTES,
        escape = '\\',
    ))

    // No keywords, no comments: what there is to colour is the strings and the
    // numbers, which is most of the file.
    put("json", Grammar(
        keywords = words("true false null"),
        lineComments = emptyList(),
        blockComment = null,
        quotes = setOf('"'),
        escape = '\\',
    ))

    val markup = Grammar(
        keywords = emptySet(),
        lineComments = emptyList(),
        blockComment = "<!--" to "-->",
        quotes = QUOTES,
        escape = null,
    )
    put("xml", markup)
    put("html", markup)

    put("css", Grammar(
        keywords = emptySet(),
        lineComments = emptyList(),
        blockComment = C_BLOCK,
        quotes = QUOTES,
        escape = '\\',
    ))

    put("zig", Grammar(
        keywords = words(
            "align allowzero and anyframe anytype asm async await break catch comptime",
            "const continue defer else enum errdefer error export extern fn for if",
            "inline noalias nosuspend or orelse packed pub resume return struct suspend",
            "switch test threadlocal try union unreachable usingnamespace var volatile",
            "while true false null undefined",
        ),
        lineComments = C_LINE,
        blockComment = null,
        quotes = QUOTES,
        escape = '\\',
    ))

    put("swift", Grammar(
        keywords = words(
            "associatedtype class deinit enum extension fileprivate func import init",
            "inout internal let open operator private protocol public rethrows static",
            "struct subscript typealias var break case continue default defer do else",
            "fallthrough for guard if in repeat return switch where while as catch",
            "is nil super self Self throw throws try true false",
        ),
        lineComments = C_LINE,
        blockComment = C_BLOCK,
        quotes = setOf('"'),
        escape = '\\',
    ))

    put("php", Grammar(
        keywords = words(
            "abstract and array as break callable case catch class clone const continue",
            "declare default do echo else elseif empty enddeclare endfor endforeach",
            "endif endswitch endwhile extends final finally fn for foreach function",
            "global goto if implements include include_once instanceof insteadof",
            "interface isset list namespace new or print private protected public",
            "require require_once return static switch throw trait try unset use var",
            "while xor yield true false null",
        ),
        lineComments = listOf("//", "#"),
        blockComment = C_BLOCK,
        quotes = QUOTES,
        escape = '\\',
    ))

    put("groovy", Grammar(
        keywords = java.keywords + words("def it in as trait"),
        lineComments = C_LINE,
        blockComment = C_BLOCK,
        quotes = BACKTICK_QUOTES,
        escape = '\\',
    ))

    put("gradle", requireNotNull(get("groovy")))
    put("properties", Grammar(
        keywords = emptySet(),
        lineComments = HASH,
        blockComment = null,
        quotes = emptySet(),
        escape = null,
    ))
    put("ini", requireNotNull(get("properties")))

    // A cell body is TOML, and a cell that does not check renders as its own
    // source. The label is what CodeFence has to colour it by.
    put("harness-map", requireNotNull(get("toml")))
    put("harness-table", requireNotNull(get("toml")))
}
