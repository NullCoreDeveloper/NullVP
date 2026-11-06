package nullvp.domain.protocol

import nullvp.domain.model.Profile
import nullvp.domain.model.ProtocolType

/**
 * Singleton registry for all registered protocol handlers.
 */
object ProtocolRegistry {
    private val handlersByType = mutableMapOf<ProtocolType, ProtocolHandler>()
    private val handlersByScheme = mutableMapOf<String, ProtocolHandler>()

    fun register(handler: ProtocolHandler) {
        handlersByType[handler.protocolType] = handler
        handlersByScheme[handler.scheme.lowercase()] = handler
        for (alt in handler.alternativeSchemes) {
            handlersByScheme[alt.lowercase()] = handler
        }
    }

    fun getHandler(type: ProtocolType): ProtocolHandler? = handlersByType[type]

    fun getHandlerForScheme(scheme: String): ProtocolHandler? = handlersByScheme[scheme.lowercase()]

    /**
     * Attempts to parse any supported URI by matching its scheme against registered handlers.
     */
    fun parse(uri: String): Profile? {
        val trimmed = uri.trim()
        val colonIdx = trimmed.indexOf("://")
        if (colonIdx == -1) return null
        val scheme = trimmed.substring(0, colonIdx).lowercase()
        val handler = getHandlerForScheme(scheme) ?: return null
        return handler.parseUri(trimmed)
    }

    fun allHandlers(): List<ProtocolHandler> = handlersByType.values.toList()
}

