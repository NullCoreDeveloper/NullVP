package nullvp.platform

expect object ClipboardHelper {
    fun getText(): String?
    fun setText(text: String)
}

