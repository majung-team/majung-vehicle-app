package app.majung.vehicle

/** A failed SDK command is reported once. Mutating commands are never retried here. */
internal object NavigationRequest {
    fun execute(action: () -> Unit, onFailure: (NavigationFailure) -> Unit): Boolean {
        val reason = try {
            action()
            return true
        } catch (_: SecurityException) {
            NavigationFailure.PERMISSION_DENIED
        } catch (_: IllegalArgumentException) {
            NavigationFailure.INVALID_REQUEST
        } catch (_: IllegalStateException) {
            NavigationFailure.DISCONNECTED
        } catch (_: Exception) {
            NavigationFailure.SDK_REQUEST_FAILED
        }
        onFailure(reason)
        return false
    }
}
