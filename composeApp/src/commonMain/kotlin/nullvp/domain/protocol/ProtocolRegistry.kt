package nullvp.domain.protocol

import nullvp.domain.model.Profile
import nullvp.domain.model.ProtocolType

/**
 * Singleton registry for all registered protocol handlers.
 */
object ProtocolRegistry {
    private val handlersByType = mutableMapOf<ProtocolType, ProtocolHandler>()
    private val handlersByScheme = mutableMapOf<String, MutableList<ProtocolHandler>>()

    fun register(handler: ProtocolHandler) {
        handlersByType[handler.protocolType] = handler
        val mainList = handlersByScheme.getOrPut(handler.scheme.lowercase()) { mutableListOf() }
        if (!mainList.contains(handler)) {
            mainList.add(handler)
        }
        for (alt in handler.alternativeSchemes) {
            val altList = handlersByScheme.getOrPut(alt.lowercase()) { mutableListOf() }
            if (!altList.contains(handler)) {
                altList.add(handler)
            }
        }
    }

    fun getHandler(type: ProtocolType): ProtocolHandler? = handlersByType[type]

    fun getHandlerForScheme(scheme: String): ProtocolHandler? = handlersByScheme[scheme.lowercase()]?.firstOrNull()

    /**
     * Attempts to parse any supported URI by matching its scheme against registered handlers.
     */
    fun parse(uri: String): Profile? {
        val trimmed = uri.trim()
        val colonIdx = trimmed.indexOf("://")
        if (colonIdx == -1) return null
        val scheme = trimmed.substring(0, colonIdx).lowercase()
        val handlers = handlersByScheme[scheme] ?: return null
        for (handler in handlers) {
            val profile = handler.parseUri(trimmed)
            if (profile != null) return profile
        }
        return null
    }

    fun allHandlers(): List<ProtocolHandler> = handlersByType.values.toList()
}

