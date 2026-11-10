package fr.smartenergy.android

/** Navigation covers every configured reader, never just the selected ESP32. */
data class JkBmsTab(val mac: String, val name: String, val reader: Int)

object JkBmsTabOrder {
    private val number = Regex("[0-9]+")
    /** Sort by the first number in the visible name, preserving order on ties. */
    fun sorted(items: List<JkBmsTab>): List<JkBmsTab> =
        items.withIndex()
            .sortedWith(compareBy<IndexedValue<JkBmsTab>> {
                number.find(it.value.name)?.value?.toLongOrNull() ?: Long.MAX_VALUE
            }.thenBy { it.index })
            .map { it.value }
}
