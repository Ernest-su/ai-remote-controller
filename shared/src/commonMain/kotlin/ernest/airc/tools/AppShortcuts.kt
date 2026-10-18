package ernest.airc

import kotlinx.serialization.Serializable

@Serializable
data class AppShortcuts(val favorites: List<String> = emptyList(), val recent: List<String> = emptyList()) {
    fun toggle(name: String) = copy(favorites = if (name in favorites) favorites - name else (favorites + name).take(12))
    fun launched(name: String) = copy(recent = (listOf(name) + recent.filterNot { it == name }).take(12))
    fun move(name: String, direction: Int): AppShortcuts {
        val index = favorites.indexOf(name)
        val next = index + direction
        if (index < 0 || next !in favorites.indices || direction !in listOf(-1, 1)) return this
        val reordered = favorites.toMutableList()
        reordered[index] = reordered[next]; reordered[next] = name
        return copy(favorites = reordered)
    }
}
