package nullvp.platform

expect object StorageHelper {
    fun readStateJson(): String?
    fun writeStateJson(json: String)
}

