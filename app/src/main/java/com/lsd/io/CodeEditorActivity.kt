package com.lsd.io

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Editable
import android.text.InputType
import android.text.Layout
import android.text.Spannable
import android.text.TextUtils
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.ActionMode
import android.view.Gravity
import android.view.MotionEvent
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * Lightweight local text/code editor: toolbar (menu, undo, redo, save, edit, jump start/end, more), a file / cursor
 * info row, line numbers with wrap markers, a current-line band, an extra-keys row and Gruvbox highlighting.
 * Files open in read-only "view" mode; the keyboard only appears after tapping the pencil icon.
 */
class CodeEditorActivity : Activity() {
    private lateinit var file: File
    private lateinit var editor: CodeEditText
    private lateinit var scroll: ScrollView
    private lateinit var nameView: TextView
    private lateinit var posView: TextView
    private lateinit var undoBtn: ImageButton
    private lateinit var redoBtn: ImageButton
    private lateinit var saveBtn: ImageButton
    private lateinit var editBtn: ImageButton
    private lateinit var jumpBtn: ImageButton
    private lateinit var keyBar: View

    private var savedText = ""
    private var loaded = false
    private var editMode = false
    private var highlighting = false
    private var lastDirty = false
    private var jumpAtEnd = false
    private var jumpShown: Boolean? = null
    private var softWrap = true
    private var languageOverride: String? = null
    private val positionHistory = ArrayList<Int>()
    private var positionHistoryIndex = -1
    private var lastTapAt = 0L

    // ---- undo / redo ----
    private class Edit(var start: Int, var removed: String, var added: String)

    private val undoStack = ArrayList<Edit>()
    private val redoStack = ArrayList<Edit>()
    private var pendingRemoved: String? = null
    private var historyBusy = false
    private var lastEditTime = 0L

    private val highlightHandler = Handler(Looper.getMainLooper())
    private val highlightTask = Runnable { highlight(editor.text) }

    private val language: String get() = languageOverride ?: languageFor(file)
    private val baseTitle: String by lazy { file.name + if (hasNoExtension(file.name)) " (.sh)" else "" }

    private val cComment by lazy { getColor(R.color.syntax_comment) }
    private val cString by lazy { getColor(R.color.syntax_string) }
    private val cKeyword by lazy { getColor(R.color.syntax_keyword) }
    private val cNumber by lazy { getColor(R.color.syntax_number) }
    private val cFunction by lazy { getColor(R.color.syntax_function) }
    private val cOperator by lazy { getColor(R.color.syntax_operator) }

    private val keywords = mapOf(
        "python" to "and as assert async await break class continue def del elif else except False finally for from global if import in is lambda None nonlocal not or pass raise return True try while with yield match case",
        "shell" to "case coproc do done elif else esac fi for function if in select then time until while",
        "js" to "async await break case catch class const continue debugger default delete do else export extends false finally for from function if import in instanceof let new null return static super switch this throw true try typeof var void while with yield",
        "java" to "abstract assert boolean break byte case catch char class const continue default do double else enum extends final finally float for if implements import instanceof int interface long native new package private protected public return short static strictfp super switch synchronized this throw throws transient try void volatile while",
        "kotlin" to "as break class continue do else false for fun if in interface is null object package return super this throw true try typealias typeof val var when while",
        "c" to "auto break case char const continue default do double else enum extern float for goto if inline int long register restrict return short signed sizeof static struct switch typedef union unsigned void volatile while",
        "rust" to "as async await break const continue crate dyn else enum extern false fn for if impl in let loop match mod move mut pub ref return self Self static struct super trait true type unsafe use where while",
        "cpp" to "alignas alignof asm auto bool break case catch char class const constexpr continue default delete do double else enum explicit export extern false float for friend goto if inline int long namespace new nullptr operator private protected public register return short signed sizeof static struct switch template this throw try typedef typename union unsigned using virtual void volatile while",
        "go" to "break case chan const continue default defer else fallthrough for func go goto if import interface map package range return select struct switch type var",
        "sql" to "select from where insert into values update set delete create table alter drop join left right inner outer on as and or not null primary key foreign references group by order having limit offset",
        "json" to "true false null",
        "ruby" to "alias and begin BEGIN break case class def defined do else elsif end END ensure false for if in module next nil not or redo rescue retry return self super then true undef unless until when while yield",
        "php" to "abstract and array as break callable case catch class clone const continue declare default do else elseif empty endfor endforeach endif endswitch endwhile eval exit extends final finally for foreach function global goto if implements include include_once instanceof interface isset list namespace new or print private protected public require require_once return static switch throw trait try unset use var while xor yield",
        "powershell" to "begin break catch class continue data define do dynamicparam else elseif end enum exit filter finally for foreach from function if in param process return switch throw trap try until using var while workflow",
        "lua" to "and break do else elseif end false for function goto if in local nil not or repeat return then true until while",
        "csharp" to "abstract as base bool break byte case catch char checked class const continue decimal default delegate do double else enum event explicit extern false finally fixed float for foreach goto if implicit in int interface internal is lock long namespace new null object operator out override params private protected public readonly ref return sbyte sealed short sizeof stackalloc static string struct switch this throw true try typeof uint ulong unchecked unsafe ushort using virtual void volatile while",
        "dart" to "abstract as assert async await break case catch class const continue default deferred do dynamic else enum export extends external factory false final finally for Function get if implements import in is library new null on operator part rethrow return set static super switch sync this throw true try typedef var void while with yield",
        "typescript" to "any as async await boolean break case catch class const continue debugger declare default delete do else enum export extends false finally for from function get if implements import in instanceof interface keyof let module namespace never new null number object package private protected public readonly require return set static string super switch symbol this throw true try type typeof var void while with yield",
        "tsx" to "any as async await boolean break case catch class const continue debugger declare default delete do else enum export extends false finally for from function get if implements import in instanceof interface keyof let module namespace never new null number object package private protected public readonly require return set static string super switch symbol this throw true try type typeof var void while with yield",
        "swift" to "associatedtype class deinit enum extension fileprivate func import init inout internal let open operator private protocol public rethrows static struct subscript typealias var break case continue default defer do else fallthrough for guard if repeat return switch where while as false is nil self Self super throw throws true try",
        "groovy" to "as assert break case catch class const continue def default do else enum extends false finally for goto if implements import in instanceof interface new null package return super switch this throw throws trait true try while",
        "vb" to "addhandler and as boolean byref byte byval call case catch class const continue date decimal declare default delegate dim do double each else elseif end enum erase error event exit false finally for friend function get global gosub goto handles if implements imports in inherits integer interface is let lib like long loop me module mustinherit mustoverride mybase myclass namespace new next not nothing object on operator option optional or overloads overrides paramarray partial private property protected public raiseevent read redim removehandler resume return sbyte select set shadows shared short single static step stop string structure sub synclock then throw to true try typeof uinteger ulong ushort using variant wend when while with withevents writeonly xor",
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Vfs.init(this)
        // Edge-to-edge window (always the case on Android 15+): the toolbar must not sit under the status bar.
        window.setDecorFitsSystemWindows(false)
        window.isNavigationBarContrastEnforced = false
        val path = intent.getStringExtra("path")
        if (path == null) {
            finish()
            return
        }
        file = Vfs.file(path)
        languageOverride = getSharedPreferences("editor", MODE_PRIVATE).getString("syntax:${file.absolutePath}", null)
        if (!file.isFile || file.length() > MAX_EDIT_BYTES) {
            Toast.makeText(this, "File is missing or larger than 2 MB", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        buildUi()
        loadFile()
    }

    // ------------------------------------------------------------------ UI

    private fun buildUi() {
        val match = LinearLayout.LayoutParams.MATCH_PARENT
        val wrap = LinearLayout.LayoutParams.WRAP_CONTENT

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // shows behind the status / navigation bars, so it uses the toolbar colour
            setBackgroundColor(getColor(R.color.header))
        }

        // ---- top toolbar: menu | undo redo save edit jump more ----
        val toolbar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, dp(4), 0)
            setBackgroundColor(getColor(R.color.header))
        }
        val menuBtn = iconButton(R.drawable.ic_menu, "Menu") { showFileMenu(it) }
        undoBtn = iconButton(R.drawable.ic_undo, "Undo") { undo() }
        redoBtn = iconButton(R.drawable.ic_redo, "Redo") { redo() }
        saveBtn = iconButton(R.drawable.ic_save, "Save") { saveFile() }
        editBtn = iconButton(R.drawable.ic_edit, "Edit") { setEditing(!editMode) }
        jumpBtn = iconButton(R.drawable.ic_jump_end, "Go to end of file") { jump() }
        val moreBtn = iconButton(R.drawable.ic_more, "More") { showMoreMenu(it) }
        toolbar.addView(menuBtn)
        toolbar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        toolbar.addView(undoBtn)
        toolbar.addView(redoBtn)
        toolbar.addView(saveBtn)
        toolbar.addView(editBtn)
        toolbar.addView(jumpBtn)
        toolbar.addView(moreBtn)
        root.addView(toolbar, LinearLayout.LayoutParams(match, dp(52)))

        // ---- info row: file name | line:col  UTF-8 ----
        val info = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), 0, dp(16), 0)
            setBackgroundColor(getColor(R.color.header))
        }
        nameView = TextView(this).apply {
            setTextColor(getColor(R.color.text))
            textSize = 15f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.MIDDLE
            text = baseTitle
        }
        posView = TextView(this).apply {
            setTextColor(getColor(R.color.subtext))
            textSize = 14f
            maxLines = 1
            text = "1:1  UTF-8"
        }
        info.addView(nameView, LinearLayout.LayoutParams(0, wrap, 1f))
        info.addView(posView, LinearLayout.LayoutParams(wrap, wrap))
        root.addView(info, LinearLayout.LayoutParams(match, dp(34)))
        root.addView(
            View(this).apply { setBackgroundColor(getColor(R.color.selected)) },
            LinearLayout.LayoutParams(match, 1)
        )

        // ---- editor ----
        scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(getColor(R.color.panel))
            isVerticalScrollBarEnabled = true
            isScrollbarFadingEnabled = true
            overScrollMode = View.OVER_SCROLL_NEVER
            verticalScrollbarThumbDrawable = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(2).toFloat()
                setColor(0x99A89984.toInt())
                setSize(dp(4), dp(48))
            }
        }
        editor = CodeEditText(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 14f
            gravity = Gravity.TOP or Gravity.START
            setTextColor(getColor(R.color.text))
            setHintTextColor(getColor(R.color.hint))
            setBackgroundColor(getColor(R.color.panel))
            setPadding(dp(8), dp(8), dp(20), dp(24))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            isSingleLine = false
            setHorizontallyScrolling(false) // word wrap
            editing = false
        }
        editor.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_UP) {
                if (lastTapAt != 0L && event.eventTime - lastTapAt <= 350L && !editMode) {
                    lastTapAt = 0L
                    setEditing(true)
                } else lastTapAt = event.eventTime
            }
            false
        }
        editor.setOnLongClickListener { view ->
            if (editMode) showLineActions(view) else false
        }
        // view mode: tapping the text never opens the keyboard (only the pencil icon does)
        editor.setShowSoftInputOnFocus(false)
        editor.refreshGutter()
        scroll.addView(editor, FrameLayout.LayoutParams(match, wrap))
        root.addView(scroll, LinearLayout.LayoutParams(match, 0, 1f))

        // ---- extra keys (only while the keyboard is up) ----
        keyBar = buildKeyBar().also { it.visibility = View.GONE }
        root.addView(keyBar, LinearLayout.LayoutParams(match, dp(44)))

        setContentView(root)

        // Keep everything clear of the status bar, display cutout, navigation bar and the keyboard.
        root.setOnApplyWindowInsetsListener { v, ins ->
            val b = ins.getInsets(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime()
            )
            v.setPadding(b.left, b.top, b.right, b.bottom)
            val imeUp = ins.isVisible(WindowInsets.Type.ime())
            keyBar.visibility = if (imeUp && editMode) View.VISIBLE else View.GONE
            ins
        }
        root.requestApplyInsets()

        scroll.setOnScrollChangeListener { _, _, _, _, _ -> updateJumpIcon() }
        editor.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateJumpIcon() }
        editor.onCaretMoved = {
            updatePos()
            updateJumpIcon()
            rememberPosition()
        }
        editor.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
                if (historyBusy || !loaded || s == null) return
                pendingRemoved = if (count > 0) s.subSequence(start, start + count).toString() else ""
            }

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (historyBusy || !loaded || s == null) return
                val removed = pendingRemoved ?: return
                pendingRemoved = null
                val added = if (count > 0) s.subSequence(start, start + count).toString() else ""
                recordEdit(start, removed, added)
            }

            override fun afterTextChanged(s: Editable?) {
                if (highlighting || s == null) return
                editor.refreshGutter()
                highlightHandler.removeCallbacks(highlightTask)
                highlightHandler.postDelayed(highlightTask, 120)
                updateDirty()
                updateHistoryButtons()
                updatePos()
            }
        })

        window.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
            WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN
        )
        updateHistoryButtons()
        updateDirty()
    }

    private fun selectableBg(): Drawable? {
        val tv = TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true)
        return if (tv.resourceId != 0) getDrawable(tv.resourceId) else null
    }

    private fun iconButton(res: Int, desc: String, action: (View) -> Unit): ImageButton = ImageButton(this).apply {
        setImageResource(res)
        setColorFilter(getColor(R.color.icon))
        contentDescription = desc
        scaleType = ImageView.ScaleType.CENTER
        background = selectableBg()
        setOnClickListener { action(it) }
        layoutParams = LinearLayout.LayoutParams(dp(44), dp(48))
    }

    private fun buildKeyBar(): View {
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, dp(4), 0)
        }
        // label to inserted text; the arrow inserts a real tab
        val keys = listOf(
            "→" to "\t", "/" to "/", "+" to "+", "-" to "-", "*" to "*", "=" to "=", "<" to "<", ">" to ">",
            "(" to "(", ")" to ")", "[" to "[", "]" to "]", "{" to "{", "}" to "}", "\"" to "\"", "'" to "'",
            "#" to "#", "$" to "$", "&" to "&", "|" to "|", "\\" to "\\", ";" to ";", ":" to ":", "_" to "_",
            "~" to "~", "!" to "!", "?" to "?", "%" to "%", "@" to "@", "^" to "^", "`" to "`", "," to ",", "." to ".",
        )
        for (key in keys) row.addView(keyView(key.first, key.second))
        return HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            setBackgroundColor(getColor(R.color.header))
            addView(row, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
    }

    private fun keyView(label: String, insert: String): TextView = TextView(this).apply {
        text = label
        textSize = 18f
        typeface = Typeface.MONOSPACE
        gravity = Gravity.CENTER
        setTextColor(getColor(R.color.icon))
        minWidth = dp(44)
        setPadding(dp(6), 0, dp(6), 0)
        background = selectableBg()
        isClickable = true // never takes focus, so the keyboard stays up
        setOnClickListener { insertText(insert) }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT)
    }

    private fun insertText(s: String) {
        if (!editMode || !loaded) return
        val a = minOf(editor.selectionStart, editor.selectionEnd).coerceAtLeast(0)
        val b = maxOf(editor.selectionStart, editor.selectionEnd).coerceAtLeast(0)
        editor.text.replace(a, b, s)
        editor.setSelection(a + s.length)
    }

    // ------------------------------------------------------------------ menus

    private fun showFileMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add(0, 1, 0, "Go to line…")
            menu.add(0, 2, 1, "Syntax: ${syntaxLabel(language)}")
            menu.add(0, 3, 2, "Previous position")
            menu.add(0, 4, 3, "Next position")
            menu.add(0, 5, 4, "Soft wrap").apply { isCheckable = true; isChecked = softWrap }
            menu.add(0, 6, 5, if (editMode) "Read-only mode" else "Edit mode")
            menu.add(0, 7, 6, "Close")
            setOnMenuItemClickListener {
                when (it.itemId) {
                    1 -> showGoToLine()
                    2 -> showSyntaxPicker()
                    3 -> movePosition(-1)
                    4 -> movePosition(1)
                    5 -> setSoftWrap(!softWrap)
                    6 -> setEditing(!editMode)
                    7 -> requestClose()
                }
                true
            }
            show()
        }
    }

    private fun showSyntaxPicker() {
        val modes = listOf(
            "text" to "Text", "abnf" to "ABNF", "asm" to "Asm", "shell" to "Shell", "batch" to "Batch",
            "brainfuck" to "Brainfuck", "c" to "C", "csharp" to "C#", "cpp" to "C++", "css" to "CSS",
            "csv" to "CSV", "dts" to "DTS", "dart" to "Dart", "diff" to "Diff", "elixir" to "Elixir", "erlang" to "Erlang", "fsharp" to "F#", "glsl" to "GLSL",
            "go" to "Golang", "groovy" to "Groovy", "haskell" to "Haskell", "hlsl" to "HLSL", "xml" to "HTML / XML", "json" to "JSON",
            "java" to "Java", "jasm" to "Jasm", "js" to "JavaScript", "kotlin" to "Kotlin", "latex" to "LaTeX", "lua" to "Lua", "lyrics" to "Lyrics",
            "markdown" to "Markdown", "mhtml" to "MHTML", "nix" to "Nix", "objectivec" to "Objective-C", "perl" to "Perl", "php" to "PHP", "prop" to "Prop", "powershell" to "PowerShell",
            "python" to "Python", "rust" to "Rust", "scala" to "Scala", "sql" to "SQL", "smali" to "Smali", "svg" to "SVG",
            "swift" to "Swift", "toml" to "TOML", "typescript" to "TypeScript", "tsx" to "TypeScript JSX",
            "vb" to "Visual Basic", "vue" to "Vue", "svelte" to "Svelte", "graphql" to "GraphQL", "hcl" to "HCL", "yaml" to "YAML", "zig" to "Zig"
        )
        val checked = modes.indexOfFirst { it.first == language }.coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("Syntax")
            .setSingleChoiceItems(modes.map { it.second }.toTypedArray(), checked) { dialog, which ->
                languageOverride = modes[which].first
                getSharedPreferences("editor", MODE_PRIVATE).edit()
                    .putString("syntax:${file.absolutePath}", languageOverride).apply()
                highlight(editor.text)
                dialog.dismiss()
            }
            .setNeutralButton("Manage") { _, _ ->
                languageOverride = null
                getSharedPreferences("editor", MODE_PRIVATE).edit().remove("syntax:${file.absolutePath}").apply()
                highlight(editor.text)
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun syntaxLabel(mode: String): String = when (mode) {
        "js" -> "JavaScript"
        "go" -> "Golang"
        "text", "none" -> "Text"
        else -> mode.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
    }

    private fun setSoftWrap(enabled: Boolean) {
        softWrap = enabled
        editor.setHorizontallyScrolling(!enabled)
        editor.refreshGutter()
        editor.requestLayout()
        editor.invalidate()
    }

    private fun rememberPosition() {
        if (!loaded) return
        val p = editor.selectionEnd.coerceAtLeast(0)
        if (positionHistoryIndex >= 0 && positionHistory.getOrNull(positionHistoryIndex) == p) return
        while (positionHistory.lastIndex > positionHistoryIndex) positionHistory.removeAt(positionHistory.lastIndex)
        positionHistory.add(p)
        if (positionHistory.size > 100) positionHistory.removeAt(0)
        positionHistoryIndex = positionHistory.lastIndex
    }

    private fun movePosition(direction: Int) {
        if (positionHistory.isEmpty()) return
        positionHistoryIndex = (positionHistoryIndex + direction).coerceIn(0, positionHistory.lastIndex)
        val offset = positionHistory[positionHistoryIndex].coerceIn(0, editor.length())
        editor.setSelection(offset)
        revealOffset(offset)
    }

    private fun showLineActions(anchor: View): Boolean {
        val actions = listOf("Copy line", "Cut line", "Delete line", "Empty line", "Replace line…", "Duplicate line",
            "Convert to uppercase", "Convert to lowercase", "Increase indent", "Decrease indent", "Toggle comment")
        PopupMenu(this, anchor).apply {
            actions.forEachIndexed { index, label -> menu.add(0, index + 1, index, label) }
            setOnMenuItemClickListener { item -> applyLineAction(item.itemId); true }
            show()
        }
        return true
    }

    private fun applyLineAction(action: Int) {
        val text = editor.text
        val caret = editor.selectionStart.coerceAtLeast(0)
        val start = text.lastIndexOf('\n', (caret - 1).coerceAtLeast(0)).let { if (it < 0) 0 else it + 1 }
        val next = text.indexOf('\n', caret)
        val end = if (next < 0) text.length else next
        val line = text.subSequence(start, end).toString()
        when (action) {
            1 -> getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("line", line))
            2 -> {
                getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("line", line))
                text.delete(start, if (next < 0) end else end + 1)
            }
            3 -> text.delete(start, if (next < 0) end else end + 1)
            4 -> text.replace(start, end, "")
            5 -> {
                val input = EditText(this).apply { setText(line); setSelection(length()) }
                AlertDialog.Builder(this).setTitle("Replace line").setView(input)
                    .setPositiveButton("Replace") { _, _ -> text.replace(start, end, input.text) }
                    .setNegativeButton("Cancel", null).show()
            }
            6 -> text.insert(end, "\n$line")
            7 -> text.replace(start, end, line.uppercase(Locale.ROOT))
            8 -> text.replace(start, end, line.lowercase(Locale.ROOT))
            9 -> text.insert(start, "    ")
            10 -> when {
                line.startsWith("    ") -> text.delete(start, start + 4)
                line.startsWith("\t") -> text.delete(start, start + 1)
            }
            11 -> {
                val prefix = if (language in setOf("shell", "python", "yaml", "toml", "ini")) "#" else "//"
                val trimmed = line.trimStart()
                if (trimmed.startsWith(prefix)) {
                    val p = start + line.indexOf(prefix)
                    val cut = prefix.length + if (line.getOrNull(p - start + prefix.length) == ' ') 1 else 0
                    text.delete(p, p + cut)
                } else text.insert(start, "$prefix ")
            }
        }
    }

    private fun showMoreMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add(0, 1, 0, "Select all")
            menu.add(0, 2, 1, "Copy all")
            setOnMenuItemClickListener {
                when (it.itemId) {
                    1 -> {
                        editor.requestFocus()
                        editor.selectAll()
                    }
                    2 -> getSystemService(ClipboardManager::class.java)
                        .setPrimaryClip(ClipData.newPlainText("text", editor.text.toString()))
                }
                true
            }
            show()
        }
    }

    private fun showGoToLine() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "1 - ${editor.logicalLineCount()}"
            setSingleLine()
        }
        val holder = FrameLayout(this).apply {
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(input)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Go to line")
            .setView(holder)
            .setPositiveButton("Go") { _, _ ->
                input.text.toString().toIntOrNull()?.let { goToLine(it) }
            }
            .setNegativeButton("Cancel", null)
            .create()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
        input.requestFocus()
    }

    private fun goToLine(n: Int) {
        val off = editor.offsetOfLine(n)
        editor.requestFocus()
        editor.setSelection(off)
        scroll.post { revealOffset(off) }
    }

    private fun revealOffset(off: Int) {
        val l = editor.layout ?: return
        val y = l.getLineTop(l.getLineForOffset(off)) + editor.totalPaddingTop
        scroll.scrollTo(0, maxOf(0, y - scroll.height / 3))
    }

    // ------------------------------------------------------------------ edit mode / keyboard

    private fun setEditing(on: Boolean) {
        if (!loaded) return
        editMode = on
        editor.editing = on
        editor.setShowSoftInputOnFocus(on)
        editBtn.setColorFilter(getColor(if (on) R.color.accent else R.color.icon))
        val imm = getSystemService(InputMethodManager::class.java)
        if (on) {
            editor.requestFocus()
            imm.restartInput(editor)
            editor.post { imm.showSoftInput(editor, 0) }
        } else {
            imm.hideSoftInputFromWindow(editor.windowToken, 0)
            imm.restartInput(editor)
            keyBar.visibility = View.GONE
        }
    }

    // ------------------------------------------------------------------ jump start / end

    private fun updateJumpIcon() {
        if (!::jumpBtn.isInitialized || !::scroll.isInitialized) return
        val content = editor.height
        val scrollable = content > scroll.height
        val atEnd = if (scrollable) {
            scroll.scrollY + scroll.height >= content - dp(8)
        } else {
            editor.length() > 0 && editor.selectionEnd >= editor.length()
        }
        jumpAtEnd = atEnd
        if (jumpShown == atEnd) return
        jumpShown = atEnd
        jumpBtn.setImageResource(if (atEnd) R.drawable.ic_jump_start else R.drawable.ic_jump_end)
        jumpBtn.contentDescription = if (atEnd) "Go to start of file" else "Go to end of file"
    }

    private fun jump() {
        if (!loaded) return
        if (jumpAtEnd) {
            editor.setSelection(0)
            scroll.scrollTo(0, 0)
        } else {
            editor.setSelection(editor.length())
            scroll.scrollTo(0, editor.height)
            scroll.post { scroll.scrollTo(0, editor.height) }
        }
        updateJumpIcon()
    }

    // ------------------------------------------------------------------ status

    private fun updatePos() {
        if (!::posView.isInitialized) return
        val lc = editor.caretLineCol()
        posView.text = "${lc.first}:${lc.second}  UTF-8"
    }

    private fun updateDirty() {
        val dirty = loaded && (editor.length() != savedText.length || editor.text.toString() != savedText)
        saveBtn.alpha = if (dirty) 1f else 0.45f
        if (dirty != lastDirty || nameView.text.isNullOrEmpty()) {
            lastDirty = dirty
            nameView.text = baseTitle + if (dirty) " •" else ""
        }
    }

    private fun updateHistoryButtons() {
        undoBtn.isEnabled = undoStack.isNotEmpty()
        undoBtn.alpha = if (undoStack.isNotEmpty()) 1f else 0.35f
        redoBtn.isEnabled = redoStack.isNotEmpty()
        redoBtn.alpha = if (redoStack.isNotEmpty()) 1f else 0.35f
    }

    // ------------------------------------------------------------------ undo / redo

    private fun recordEdit(start: Int, removed: String, added: String) {
        if (removed.isEmpty() && added.isEmpty()) return
        val now = SystemClock.uptimeMillis()
        redoStack.clear()
        val prev = undoStack.lastOrNull()
        if (prev != null && now - lastEditTime < 1200 && mergeEdit(prev, start, removed, added)) {
            lastEditTime = now
            return
        }
        undoStack.add(Edit(start, removed, added))
        if (undoStack.size > 400) undoStack.removeAt(0)
        lastEditTime = now
    }

    /** Folds typing / deleting runs into one undo step. Every rule keeps the net effect exact. */
    private fun mergeEdit(prev: Edit, start: Int, removed: String, added: String): Boolean {
        // composing text growing in place
        if (added.isNotEmpty() && start == prev.start && removed == prev.added && !added.contains('\n')) {
            prev.added = added
            return true
        }
        // typing forward
        if (removed.isEmpty() && added.isNotEmpty() && start == prev.start + prev.added.length &&
            !added.contains('\n') && !prev.added.contains('\n') && prev.added.length < 80
        ) {
            prev.added += added
            return true
        }
        // backspace run
        if (added.isEmpty() && prev.added.isEmpty() && start + removed.length == prev.start) {
            prev.start = start
            prev.removed = removed + prev.removed
            return true
        }
        // forward-delete run
        if (added.isEmpty() && prev.added.isEmpty() && start == prev.start) {
            prev.removed += removed
            return true
        }
        return false
    }

    private fun applyHistory(start: Int, replaceLen: Int, text: String) {
        historyBusy = true
        try {
            editor.text.replace(start, start + replaceLen, text)
            editor.setSelection(start + text.length)
        } catch (_: Throwable) {
            undoStack.clear()
            redoStack.clear()
        } finally {
            historyBusy = false
        }
    }

    private fun undo() {
        if (!loaded || undoStack.isEmpty()) return
        val e = undoStack.removeAt(undoStack.lastIndex)
        applyHistory(e.start, e.added.length, e.removed)
        redoStack.add(e)
        lastEditTime = 0L
        updateHistoryButtons()
        updateDirty()
    }

    private fun redo() {
        if (!loaded || redoStack.isEmpty()) return
        val e = redoStack.removeAt(redoStack.lastIndex)
        applyHistory(e.start, e.removed.length, e.added)
        undoStack.add(e)
        lastEditTime = 0L
        updateHistoryButtons()
        updateDirty()
    }

    // ------------------------------------------------------------------ load / save

    private fun loadFile() {
        io.execute {
            val content = try { Vfs.openIn(file).use { it.readBytes().toString(Charsets.UTF_8) } } catch (_: Throwable) { null }
            runOnUiThread {
                if (content == null) {
                    Toast.makeText(this, "Can't read this text file", Toast.LENGTH_LONG).show()
                    finish()
                } else {
                    savedText = content
                    highlighting = true
                    editor.setText(content)
                    editor.setSelection(0)
                    editor.refreshGutter()
                    highlight(editor.text)
                    highlighting = false
                    undoStack.clear()
                    redoStack.clear()
                    loaded = true
                    lastDirty = false
                    updateDirty()
                    updateHistoryButtons()
                    updatePos()
                    updateJumpIcon()
                    // no keyboard here: it only opens when the pencil icon is tapped
                }
            }
        }
    }

    private fun saveFile() {
        if (!loaded) return
        val content = editor.text.toString()
        saveBtn.isEnabled = false
        io.execute {
            val ok = try {
                Vfs.openOut(file).bufferedWriter(Charsets.UTF_8).use { it.write(content) }
                true
            } catch (_: Throwable) { false }
            runOnUiThread {
                saveBtn.isEnabled = true
                if (ok) {
                    savedText = content
                    updateDirty()
                    Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
                } else Toast.makeText(this, "Couldn't save file", Toast.LENGTH_LONG).show()
            }
        }
    }

    // ------------------------------------------------------------------ syntax highlighting

    private class Rules(val comment: String, val string: String, val escape: String = "(?!)")

    private class Styled(val m: Matcher, val color: Int, val bold: Boolean)

    private val rules: Rules get() = when (language) {
            "text", "csv", "none" -> Rules(NEVER, NEVER)
            "shell" -> Rules(HASH_WORD, SH_DQ + "|" + SH_SQ + "|" + BACKTICK, SH_ESC)
            "python" -> Rules(HASH_ANY, TRIPLE_DQ + "|" + TRIPLE_SQ + "|" + DQ + "|" + SQ)
            "yaml", "toml", "ini" -> Rules(HASH_WORD, DQ + "|" + SQ)
            "prop" -> Rules("(?m)^[\\t ]*[#!][^\\n]*", DQ + "|" + SQ)
            "jasm" -> Rules("(?m);[^\\n]*", DQ + "|" + SQ)
            "mhtml" -> Rules(HTML_COMMENT + "|" + BLOCK, DQ + "|" + SQ)
            "ruby", "powershell" -> Rules(HASH_ANY, DQ + "|" + SQ)
            "sql" -> Rules(DASH + "|" + BLOCK, SQL_SQ + "|" + DQ)
            "lua" -> Rules(LUA_BLOCK + "|" + DASH, DQ + "|" + SQ)
            "xml", "css" -> Rules(HTML_COMMENT + "|" + BLOCK, DQ + "|" + SQ)
            "kotlin", "java" -> Rules(LINE_SLASH + "|" + BLOCK, TRIPLE_DQ + "|" + DQ + "|" + SQ)
            "php" -> Rules(LINE_SLASH + "|" + BLOCK + "|" + HASH_ANY, DQ + "|" + SQ)
            else -> Rules(LINE_SLASH + "|" + BLOCK, DQ + "|" + SQ + "|" + BACKTICK)
    }

    /** group 1 = comment, group 2 = string, group 3 = escape (left unstyled). Leftmost match wins, so
     *  a '#' inside a string or a quote inside a comment can never start the wrong token. */
    private val skipPattern: Pattern
        get() = Pattern.compile("(" + rules.comment + ")|(" + rules.string + ")|(" + rules.escape + ")")

    private val keywordPattern: Pattern?
        get() = keywords[language]?.let { words ->
            val flags = if (language in setOf("sql", "vb")) Pattern.CASE_INSENSITIVE else 0
            Pattern.compile("\\b(?:" + words.split(' ').joinToString("|") { Pattern.quote(it) } + ")\\b", flags)
        }

    private fun highlight(editable: Editable) {
        highlighting = true
        try {
            for (s in editable.getSpans(0, editable.length, ForegroundColorSpan::class.java)) editable.removeSpan(s)
            for (s in editable.getSpans(0, editable.length, StyleSpan::class.java)) editable.removeSpan(s)
            if (editable.length > MAX_HIGHLIGHT_CHARS) return
            val text = editable.toString()
            when (language) {
                "markdown" -> highlightMarkdown(editable, text)
                "diff" -> highlightDiff(editable, text)
                "lyrics" -> highlightLyrics(editable, text)
                "text", "csv", "none" -> Unit
                else -> highlightCode(editable, text)
            }
        } finally {
            highlighting = false
        }
    }

    private fun highlightMarkdown(editable: Editable, text: String) {
        applyRegex(editable, text, "<!--[\\s\\S]*?-->", cComment)
        applyRegex(editable, text, "(?m)^#{1,6}\\s+.*$", cKeyword, bold = true)
        applyRegex(editable, text, "(```[\\s\\S]*?```|`[^`]*`)", cString)
        applyRegex(editable, text, "(\\*\\*[^*]+\\*\\*|__[^_]+__)", cNumber, bold = true)
        applyRegex(editable, text, "(?m)^\\s*[-*+]\\s+", cOperator)
    }

    private fun highlightDiff(editable: Editable, text: String) {
        applyRegex(editable, text, "(?m)^\\+[^\\n]*", getColor(R.color.syntax_string))
        applyRegex(editable, text, "(?m)^-[^\\n]*", getColor(R.color.syntax_keyword))
        applyRegex(editable, text, "(?m)^@@[^\\n]*", getColor(R.color.syntax_function))
    }

    private fun highlightLyrics(editable: Editable, text: String) {
        applyRegex(editable, text, "(?m)^\\[[0-9]{1,2}:[0-9]{2}(?:\\.[0-9]+)?\\]", cNumber)
        applyRegex(editable, text, "(?m)^\\[[A-Za-z]+:[^\\]]*\\]", cKeyword)
        applyRegex(editable, text, "(?m)^#[^\\n]*", cComment)
    }

    private fun highlightCode(editable: Editable, text: String) {
        val styled = ArrayList<Styled>(4)
        styled.add(Styled(prepared(functionPattern, text), cFunction, false))
        styled.add(Styled(prepared(numberPattern, text), cNumber, false))
        keywordPattern?.let { styled.add(Styled(prepared(it, text), cKeyword, true)) } // after function: keywords win
        styled.add(Styled(prepared(operatorPattern, text), cOperator, false))

        val skip = skipPattern.matcher(text)
        var pos = 0
        while (skip.find()) {
            val s = skip.start()
            val e = skip.end()
            if (e <= s) continue
            codeRun(editable, styled, pos, s)
            if (skip.start(1) >= 0) paint(editable, s, e, cComment, false, italic = true)
            else if (skip.start(2) >= 0) paint(editable, s, e, cString, false)
            pos = e
        }
        codeRun(editable, styled, pos, text.length)
    }

    private fun prepared(p: Pattern, text: String): Matcher =
        p.matcher(text).useTransparentBounds(true).useAnchoringBounds(false)

    /** Styles only the plain-code stretch [from, to) between comments / strings. */
    private fun codeRun(editable: Editable, styled: List<Styled>, from: Int, to: Int) {
        if (to <= from) return
        for (st in styled) {
            st.m.region(from, to)
            while (st.m.find()) paint(editable, st.m.start(), st.m.end(), st.color, st.bold)
        }
    }

    private fun applyRegex(editable: Editable, text: String, regex: String, color: Int, bold: Boolean = false) {
        val m = Pattern.compile(regex).matcher(text)
        while (m.find()) paint(editable, m.start(), m.end(), color, bold)
    }

    private fun paint(editable: Editable, start: Int, end: Int, color: Int, bold: Boolean, italic: Boolean = false) {
        if (end <= start) return
        editable.setSpan(ForegroundColorSpan(color), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (bold) editable.setSpan(StyleSpan(Typeface.BOLD), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (italic) editable.setSpan(StyleSpan(Typeface.ITALIC), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private fun languageFor(file: File): String {
        val name = file.name.lowercase(Locale.ROOT)
        if (name in SHELL_FILENAMES) return "shell"
        if (name in TEXT_FILENAMES) return "text"
        if (hasNoExtension(name)) return "shell"
        return when (file.extension.lowercase(Locale.ROOT)) {
            "md", "markdown", "mdown", "mkd" -> "markdown"
            "txt", "text", "log", "csv", "tsv", "rst", "adoc", "asciidoc", "readme", "todo", "license" -> "text"
            "py", "pyw" -> "python"
            "sh", "bash", "zsh", "ksh", "fish", "csh", "tcsh", "ash", "bats", "command", "rc" -> "shell"
            "js", "jsx", "mjs", "cjs" -> "js"
            "ts" -> "typescript"
            "tsx" -> "tsx"
            "cs" -> "csharp"
            "fs", "fsi", "fsx" -> "fsharp"
            "hs", "lhs" -> "haskell"
            "pl", "pm" -> "perl"
            "ex", "exs" -> "elixir"
            "erl", "hrl" -> "erlang"
            "clj", "cljs", "cljc", "edn" -> "clojure"
            "scala", "sc" -> "scala"
            "vue" -> "vue"
            "svelte" -> "svelte"
            "ipynb" -> "json"
            "tex", "latex", "sty", "cls" -> "latex"
            "asm", "s", "S" -> "asm"
            "m", "mm" -> "objectivec"
            "plsql", "psql" -> "sql"
            "prisma" -> "graphql"
            "graphql", "gql" -> "graphql"
            "mhtml" -> "mhtml"
            "jasm" -> "jasm"
            "lrc", "lyrics" -> "lyrics"
            "prop" -> "prop"
            "tf", "tfvars" -> "hcl"
            "dockerfile" -> "shell"
            "conf", "config", "cfg", "ini", "properties", "prop", "service", "socket", "timer", "target", "mount", "automount", "path", "slice", "scope", "desktop", "directory", "gitconfig", "gitmodules", "editorconfig", "npmrc", "yarnrc", "curlrc" -> "ini"
            "dart" -> "dart"
            "swift" -> "swift"
            "groovy", "gvy", "gy", "gradle" -> "groovy"
            "vb", "vbs" -> "vb"
            "diff", "patch" -> "diff"
            "java" -> "java"
            "kt", "kts" -> "kotlin"
            "c", "h" -> "c"
            "cc", "cpp", "cxx", "hpp", "hh", "hxx" -> "cpp"
            "rs" -> "rust"
            "go" -> "go"
            "sql" -> "sql"
            "rb" -> "ruby"
            "php" -> "php"
            "ps1", "psm1" -> "powershell"
            "lua" -> "lua"
            "bat", "cmd" -> "shell"
            "html", "htm", "xml", "svg" -> "xml"
            "css", "scss", "sass", "less" -> "css"
            "json", "jsonc" -> "json"
            "yaml", "yml" -> "yaml"
            "toml" -> "toml"
            "env" -> "ini"
            else -> "none"
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()

    // ------------------------------------------------------------------ closing

    private fun requestClose() {
        if (loaded && (editor.length() != savedText.length || editor.text.toString() != savedText)) {
            AlertDialog.Builder(this)
                .setTitle("Discard changes?")
                .setMessage("This file has unsaved changes.")
                .setPositiveButton("Discard") { _, _ -> finish() }
                .setNegativeButton("Keep editing", null)
                .show()
        } else finish()
    }

    override fun onBackPressed() {
        requestClose()
    }

    override fun onDestroy() {
        highlightHandler.removeCallbacks(highlightTask)
        super.onDestroy()
    }

    companion object {
        private const val MAX_EDIT_BYTES = 2L * 1024 * 1024
        private const val MAX_HIGHLIGHT_CHARS = 400_000
        private val io = Executors.newSingleThreadExecutor()
        private val SHELL_FILENAMES = setOf(
            ".bash_history", ".bash_aliases", ".bash_functions", ".bash_logout", ".bash_profile", ".bashrc",
            ".profile", ".bash_login", ".zlogin", ".zlogout", ".zprofile", ".zshenv", ".zshrc", ".zsh_history",
            ".kshrc", ".cshrc", ".tcshrc", ".envrc", ".env", ".flaskenv", ".shellcheckrc", ".rc",
            "bash_history", "bash_aliases", "bash_functions", "bash_logout", "bash_profile", "bashrc",
            "profile", "bash_login", "zlogin", "zlogout", "zprofile", "zshenv", "zshrc", "kshrc", "cshrc",
            "tcshrc", "pkgbuild", "apkbuild", "brewfile", "procfile", "justfile", "taskfile",
        ).map { it.lowercase(Locale.ROOT) }.toSet()
        private val TEXT_FILENAMES = setOf(
            "readme", "license", "copying", "authors", "contributors", "changelog", "changes", "news",
            "todo", "install", "notice", "credits", "history", "makefile", "gnumakefile", "dockerfile",
            "containerfile", "gemfile", "rakefile", "guardfile", "podfile", "fastfile", "appfile", "matchfile",
            ".vimrc", ".gvimrc", ".inputrc", ".netrc", ".curlrc", ".wgetrc", ".gitconfig", ".tmux.conf",
            ".npmrc", ".yarnrc", ".editorconfig", ".gitignore", ".gitattributes", ".gitmodules", ".htaccess",
            ".htpasswd", ".browserslistrc", ".prettierrc", ".babelrc", ".eslintrc", ".stylelintrc",
            ".python_history", ".sqlite_history", ".psql_history", ".lesshst", ".node_repl_history",
        ).map { it.lowercase(Locale.ROOT) }.toSet()

        // ---- regex building blocks (comments / strings), written out as plain regex ----
        private const val NEVER = "(?!)"
        private const val HASH_ANY = "#[^\\n]*"
        private const val HASH_WORD = "(?<![^\\s;&|(])#[^\\n]*" // '#' only at a word start, so $# and ${#x} are not comments
        private const val LINE_SLASH = "//[^\\n]*"
        private const val DASH = "--[^\\n]*"
        private const val BLOCK = "/\\*[\\s\\S]*?\\*/"
        private const val LUA_BLOCK = "--\\[\\[[\\s\\S]*?\\]\\]"
        private const val HTML_COMMENT = "<!--[\\s\\S]*?-->"
        private const val DQ = "\"(?:\\\\.|[^\"\\\\\\n])*\""
        private const val SQ = "'(?:\\\\.|[^'\\\\\\n])*'"
        private const val SQL_SQ = "'(?:''|[^'])*'"
        private const val SH_DQ = "\"(?:\\\\[\\s\\S]|[^\"\\\\])*\""
        private const val SH_SQ = "'[^']*'"
        private const val SH_ESC = "\\\\[\\s\\S]"
        private const val BACKTICK = "`(?:\\\\[\\s\\S]|[^`\\\\])*`"
        private const val TRIPLE_DQ = "\"\"\"[\\s\\S]*?\"\"\""
        private const val TRIPLE_SQ = "'''[\\s\\S]*?'''"

        private val numberPattern: Pattern = Pattern.compile("\\b(?:0[xX][0-9a-fA-F]+|\\d+(?:\\.\\d+)?)\\b")
        private val functionPattern: Pattern = Pattern.compile("\\b[A-Za-z_][A-Za-z0-9_]*(?=\\s*\\()")
        private val operatorPattern: Pattern = Pattern.compile("[{}()\\[\\].,:;=+*/%<>!&|?-]")

        private fun hasNoExtension(name: String): Boolean = name.lastIndexOf('.') < 0

        fun supports(file: File): Boolean {
            if (!file.isFile) return false
            val name = file.name.lowercase(Locale.ROOT)
            if (name in SHELL_FILENAMES || name in TEXT_FILENAMES) return true
            if (hasNoExtension(file.name)) return true
            return file.extension.lowercase(Locale.ROOT) in setOf(
                "txt", "text", "log", "md", "markdown", "mdown", "mkd", "rst", "adoc", "asciidoc", "org", "lrc", "lyrics",
                "sh", "bash", "zsh", "ksh", "fish", "csh", "tcsh", "ash", "bats", "command",
                "py", "pyw", "pyi", "ipynb", "js", "jsx", "mjs", "cjs", "ts", "tsx", "java", "kt", "kts",
                "ktm", "scala", "sc", "groovy", "gvy", "gy", "clj", "cljs", "cljc", "edn", "ex", "exs",
                "erl", "hrl", "hs", "lhs", "fs", "fsi", "fsx", "pl", "pm", "r", "rmd", "jl", "nim", "zig",
                "c", "h", "cc", "cpp", "cxx", "hpp", "hh", "hxx", "cs", "vb", "vbs", "fs", "fsi", "fsx",
                "rs", "go", "dart", "swift", "m", "mm", "asm", "s", "sql", "plsql", "psql", "rb", "php",
                "phtml", "lua", "ps1", "psm1", "psd1", "bat", "cmd", "html", "htm", "xhtml", "xml", "svg",
                "css", "scss", "sass", "less", "json", "jsonc", "json5", "yaml", "yml", "toml", "ini", "cfg",
                "conf", "config", "properties", "prop", "env", "dotenv", "tf", "tfvars", "hcl", "nix", "proto",
                "graphql", "gql", "prisma", "vue", "svelte", "astro", "ejs", "pug", "jade", "handlebars", "hbs",
                "mustache", "twig", "erb", "jsp", "jspx", "asp", "aspx", "razor", "cshtml", "mhtml", "jasm", "gradle", "gradle.kts",
                "cmake", "mk", "make", "meson", "bazel", "bzl", "star", "dockerfile", "containerfile", "tex",
                "latex", "sty", "cls", "bib", "csv", "tsv", "diff", "patch", "gitignore", "gitattributes",
                "gitmodules", "editorconfig", "prettierrc", "babelrc", "npmrc", "yarnrc", "lock", "sum", "mod",
                "xmls", "xsd", "xsl", "xslt", "dtd", "plist", "desktop", "service", "socket", "timer", "target",
                "mount", "automount", "path", "slice", "scope", "crontab", "awk", "sed", "tcl", "expect", "spec",
                "feature", "robot", "rbt", "sol", "vy", "cob", "cbl", "adb", "ads", "f", "f90", "for", "pas",
                "pp", "d", "di", "elm", "purs", "idr", "lean", "v", "sv", "vh", "vhd", "vhdl", "psl",
            )
        }
    }
}

/**
 * EditText that draws its own line numbers (logical lines only), wrap markers and a current-line band, and that can
 * be switched between read-only "view" mode (no keyboard, no cut / paste) and edit mode.
 */
private class CodeEditText(context: Context) : EditText(context) {
    /** false = view mode: no soft keyboard, no typing, no cut / paste. */
    var editing = false
    var onCaretMoved: (() -> Unit)? = null

    private var newlines = IntArray(256)
    private var newlineCount = 0
    private var linesDirty = true
    private var gutterLeft = -1

    private val density = context.resources.displayMetrics.density
    private val numColor = context.getColor(R.color.hint)
    private val curNumColor = context.getColor(R.color.subtext)
    private val numberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        textAlign = Paint.Align.RIGHT
    }
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = density * 1.3f
        color = context.getColor(R.color.hint)
    }
    private val bandPaint = Paint().apply { color = context.getColor(R.color.dialog) }
    private val arrowPath = Path()

    init {
        val callback = object : ActionMode.Callback {
            override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                trimMenu(menu)
                return true
            }

            override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
                trimMenu(menu)
                return true
            }

            override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean = false
            override fun onDestroyActionMode(mode: ActionMode) {}
        }
        customSelectionActionModeCallback = callback
        customInsertionActionModeCallback = callback
    }

    private fun trimMenu(menu: Menu) {
        if (!editing) {
            menu.removeItem(android.R.id.cut)
            menu.removeItem(android.R.id.paste)
            menu.removeItem(android.R.id.pasteAsPlainText)
        }
    }

    // ---- view mode guards ----

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? =
        if (editing) super.onCreateInputConnection(outAttrs) else null

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (!editing && !event.isCtrlPressed && (
                event.isPrintingKey || keyCode == KeyEvent.KEYCODE_DEL || keyCode == KeyEvent.KEYCODE_FORWARD_DEL ||
                    keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_TAB
                )
        ) return true
        return super.onKeyDown(keyCode, event)
    }

    override fun onTextContextMenuItem(id: Int): Boolean {
        if (!editing && (id == android.R.id.cut || id == android.R.id.paste ||
                id == android.R.id.pasteAsPlainText || id == android.R.id.undo || id == android.R.id.redo)
        ) return true
        return super.onTextContextMenuItem(id)
    }

    // ---- change tracking ----

    override fun onTextChanged(text: CharSequence?, start: Int, lengthBefore: Int, lengthAfter: Int) {
        super.onTextChanged(text, start, lengthBefore, lengthAfter)
        linesDirty = true
    }

    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        super.onSelectionChanged(selStart, selEnd)
        onCaretMoved?.invoke()
        invalidate()
    }

    // ---- logical-line bookkeeping (positions of '\n') ----

    private fun ensureLines() {
        if (!linesDirty) return
        val t = text
        val n = t.length
        val buf = CharArray(n)
        t.getChars(0, n, buf, 0)
        var arr = newlines
        var c = 0
        for (i in 0 until n) {
            if (buf[i] == '\n') {
                if (c == arr.size) arr = arr.copyOf(arr.size * 2)
                arr[c++] = i
            }
        }
        newlines = arr
        newlineCount = c
        linesDirty = false
    }

    /** Number of '\n' characters strictly before [offset]. */
    private fun newlinesBefore(offset: Int): Int {
        var lo = 0
        var hi = newlineCount
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (newlines[mid] < offset) lo = mid + 1 else hi = mid
        }
        return lo
    }

    fun logicalLineCount(): Int {
        ensureLines()
        return newlineCount + 1
    }

    /** Text offset where logical line [n] (1-based, clamped) starts. */
    fun offsetOfLine(n: Int): Int {
        ensureLines()
        val line = n.coerceIn(1, newlineCount + 1)
        return if (line == 1) 0 else newlines[line - 2] + 1
    }

    /** (line, column), both 1-based, of the caret. */
    fun caretLineCol(): Pair<Int, Int> {
        ensureLines()
        val sel = maxOf(0, selectionEnd)
        val idx = newlinesBefore(sel)
        val lineStart = if (idx == 0) 0 else newlines[idx - 1] + 1
        return Pair(idx + 1, sel - lineStart + 1)
    }

    /** Re-measures the gutter (grows with the digit count of the last line number). */
    fun refreshGutter() {
        ensureLines()
        val digits = maxOf(2, (newlineCount + 1).toString().length)
        numberPaint.textSize = textSize
        val left = (numberPaint.measureText("0") * digits + density * 18f).toInt()
        if (left != gutterLeft) {
            gutterLeft = left
            setPadding(left, paddingTop, paddingRight, paddingBottom)
        }
    }

    // ---- drawing ----

    override fun onDraw(canvas: Canvas) {
        val l = layout
        if (l != null && l.lineCount > 0 && width > 0) drawDecor(canvas, l)
        super.onDraw(canvas)
    }

    private fun drawDecor(canvas: Canvas, l: Layout) {
        ensureLines()
        val buf = text
        val top = totalPaddingTop
        val clip = canvas.clipBounds
        val first = l.getLineForVertical(maxOf(0, clip.top - top))
        val last = l.getLineForVertical(maxOf(0, clip.bottom - top))

        // current-line band: every wrapped row of the caret's logical line
        var curA = -1
        var curB = -1
        val sel = selectionStart
        if (isFocused && sel >= 0 && sel == selectionEnd) {
            val idx = newlinesBefore(sel)
            val startOff = if (idx == 0) 0 else newlines[idx - 1] + 1
            val endOff = if (idx < newlineCount) newlines[idx] else buf.length
            curA = l.getLineForOffset(startOff)
            curB = l.getLineForOffset(endOff)
            canvas.drawRect(
                0f, (l.getLineTop(curA) + top).toFloat(),
                width.toFloat(), (l.getLineBottom(curB) + top).toFloat(), bandPaint
            )
        }

        numberPaint.textSize = textSize
        val numRight = compoundPaddingLeft - density * 10f
        val arrowS = textSize * 0.22f
        for (row in first..last) {
            val start = l.getLineStart(row)
            val baseline = (l.getLineBaseline(row) + top).toFloat()
            val cy = baseline - textSize * 0.28f
            val continuation = row > 0 && start > 0 && buf[start - 1] != '\n'
            if (!continuation) {
                numberPaint.color = if (row in curA..curB) curNumColor else numColor
                canvas.drawText((newlinesBefore(start) + 1).toString(), numRight, baseline, numberPaint)
            } else {
                drawArrow(canvas, numRight - arrowS - density * 2f, cy, arrowS, false)
            }
            if (row + 1 < l.lineCount) {
                val next = l.getLineStart(row + 1)
                if (next > 0 && buf[next - 1] != '\n') {
                    drawArrow(canvas, width - paddingRight / 2f, cy, arrowS, true)
                }
            }
        }
    }

    /** Small hooked arrow: [endOfRow] = wrap marker at the end of a row, otherwise at the start of the continuation. */
    private fun drawArrow(c: Canvas, cx: Float, cy: Float, s: Float, endOfRow: Boolean) {
        val h = s * 0.7f
        arrowPath.reset()
        if (endOfRow) {
            arrowPath.moveTo(cx + s, cy - s)
            arrowPath.lineTo(cx + s, cy)
            arrowPath.lineTo(cx - s, cy)
            arrowPath.moveTo(cx - s + h, cy - h)
            arrowPath.lineTo(cx - s, cy)
            arrowPath.lineTo(cx - s + h, cy + h)
        } else {
            arrowPath.moveTo(cx - s, cy - s)
            arrowPath.lineTo(cx - s, cy)
            arrowPath.lineTo(cx + s, cy)
            arrowPath.moveTo(cx + s - h, cy - h)
            arrowPath.lineTo(cx + s, cy)
            arrowPath.lineTo(cx + s - h, cy + h)
        }
        c.drawPath(arrowPath, arrowPaint)
    }
}
