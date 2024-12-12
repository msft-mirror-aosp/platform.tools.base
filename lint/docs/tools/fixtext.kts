#!kotlin
// Perform misc text cleanup in the .md.html files:
// remove trailing spaces, remove repeated blank
// lines, and switch from straight quotes to smart
// quotes

import java.io.File
import kotlin.math.min
import kotlin.system.exitProcess

Main().run(args)

class Main {
  /**
   * Maximum number of quoted characters before we believe we're looking at an unterminated quote.
   */
  private val MAX_QUOTE_LENGTH = 120
  private val QUOTE_START = '\u201C'
  private val QUOTE_END = '\u201D'

  private var modified = 0
  private var stripDuplicateNewlines = false
  private var smartQuotes = false

  fun run(args: Array<String>) {
    if (args.isEmpty() || args[0] == "--help") {
      println("Usage: fixspace [--strip-duplicate-newlines] [--smart-quotes] <files>")
      exitProcess(0)
    }
    val files = mutableListOf<File>()
    for (arg in args) {
      when {
        arg == "--strip-duplicate-newlines" -> {
          stripDuplicateNewlines = true
        }
        arg == "--smart-quotes" -> {
          smartQuotes = true
        }
        arg.startsWith("--") -> {
          println("Unknown option $arg")
          exitProcess(-1)
        }
        else -> {
          val file = File(arg).canonicalFile
          if (!file.exists()) {
            System.err.println("$file does not exist")
            exitProcess(-1)
          }
          files.add(file)
        }
      }
    }

    for (file in files) {
      fix(file, file)
    }

    println("Formatted $modified files")
  }

  private fun fix(root: File, file: File) {
    val name = file.name
    if (
      name == ".git" || name.endsWith(".class") || name.endsWith(".png") || name.endsWith(".db")
    ) {
      return
    }
    if (name.endsWith(".min.js") || name == "node_modules") {
      return
    }
    if (name.endsWith(".html") && !name.endsWith(".md.html")) {
      return
    }
    if (file.isDirectory) {
      file.listFiles()?.forEach { fix(root, it) }
    } else if (file.isFile) {
      reformat(root, file)
    }
  }

  private fun reformat(root: File, file: File) {

    val text = file.readText()
    var formatted = text

    if (
      smartQuotes &&
        (file.name.endsWith(".md") || file.name.endsWith(".html") || file.name.endsWith(".txt"))
    ) {
      formatted = fixQuotes(root, file, formatted)
    }

    val sb = StringBuilder()
    for (line in formatted.lines()) {
      if (addSeparator(sb)) {
        sb.append('\n')
      }
      sb.append(line.trimEnd().replace("\t", "    "))
    }
    formatted = sb.toString()

    if (formatted != text) {
      modified++
      file.writeText(formatted)
    }
  }

  private fun fixQuotes(root: File, file: File, formatted: String): String {
    fun lineNumber(offset: Int): Int {
      return formatted.substring(0, offset).count { it == '\n' } + 1
    }

    fun warnUnterminated(message: String, startIndex: Int) {
      val text =
        formatted.substring(startIndex, min(formatted.length, startIndex + 40)).replace("\n", "\\n")
      val path =
        if (root.path != file.path) file.path.removePrefix(root.path).removePrefix(File.separator)
        else file.path
      println(
        "WARNING: $message in $path; started at line ${lineNumber(startIndex)}, offset $startIndex: $text..."
      )
    }

    fun isLineStart(offset: Int): Boolean {
      var i = offset
      while (i > 0) {
        i--
        if (formatted[i] == '\n') {
          break
        } else if (!formatted[i].isWhitespace()) {
          return false
        }
      }
      return true
    }

    fun Char.isQuoteChar(): Boolean {
      val c = this
      return c == '"' ||
        c == '\u201C' ||
        c == '\u201D' ||
        c == '«' ||
        c == '»' ||
        c == QUOTE_START ||
        c == QUOTE_END
    }

    var inCode = false
    var inQuote = false
    var column = 0
    var inPre = false
    val sb = StringBuilder(formatted.length)
    var skipLine = false
    var warnedQuoteLength = false
    var warnedCodeQuoteLength = false
    var quoteStart = 0
    var codeStart = 0
    var preStart = 0

    var i = 0
    while (i < formatted.length) {
      var c = formatted[i]
      if (c == '`' && !formatted.startsWith("```", i) && !inPre) {
        inCode = !inCode
        if (inCode) {
          codeStart = i
        } else {
          if (!warnedCodeQuoteLength && (i - codeStart) > MAX_QUOTE_LENGTH) {
            warnUnterminated(
              "Suspiciously long code quoted text (${i - codeStart} chars)",
              codeStart,
            )
            warnedCodeQuoteLength = true
          }
        }
      } else if (
        column == 0 &&
          (formatted.startsWith("<style", i) ||
            formatted.startsWith("<meta", i) ||
            formatted.startsWith("<!-- Markdeep:", i))
      ) {
        skipLine = true
      } else if (
        (c == '`' && formatted.startsWith("```", i) ||
          c == '~' && formatted.startsWith("~~~", i)) && isLineStart(i)
      ) {
        inPre = !inPre
        if (inPre) {
          preStart = i
        }
        while (i < formatted.length) {
          val d = formatted[i]
          sb.append(d)
          i++
          if (d == '\n') {
            column = 0
            skipLine = false
            break
          }
        }
        continue
      } else if (!inPre && !inCode && !skipLine && c.isQuoteChar()) {
        inQuote = !inQuote
        if (inQuote) {
          quoteStart = i
          c = QUOTE_START
        } else {
          c = QUOTE_END
          if (!warnedQuoteLength && (i - quoteStart) > MAX_QUOTE_LENGTH) {
            warnUnterminated("Suspiciously long quoted text (${i - quoteStart} chars)", quoteStart)
            warnedQuoteLength = true
          }
        }
      }

      if (c == '\n') {
        column = 0
        skipLine = false
        if (inCode && formatted.startsWith("\n\n", i)) {
          warnUnterminated(
            "Suspicious newline in quoted code block; missing termination? (started on line ${lineNumber(codeStart)})",
            i,
          )
        }
      } else {
        column++
      }
      sb.append(c)
      i++
    }

    if (inQuote) {
      warnUnterminated("Unterminated quote", quoteStart)
    }
    if (inCode) {
      formatted.substring(codeStart)
      warnUnterminated("Unterminated code quote (`)", codeStart)
    }
    if (inPre) {
      warnUnterminated("Unterminated fenced block (```)", preStart)
    }
    return sb.toString()
  }

  private fun addSeparator(sb: StringBuilder): Boolean {
    if (sb.isEmpty()) {
      return false
    }

    if (!stripDuplicateNewlines) {
      return true
    }

    if (sb.length < 2) {
      return true
    }

    return sb[sb.length - 1] != '\n' || sb[sb.length - 2] != '\n'
  }
}
