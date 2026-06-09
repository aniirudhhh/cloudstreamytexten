// Extension version — bump this integer to trigger CloudStream's update checker.
version = 1

cloudstream {
    /**
     * pluginClassName must exactly match the @CloudstreamPlugin-annotated class name.
     * CloudStream uses reflection to instantiate it; a mismatch silently fails to load.
     */
    pluginClassName = "YouTubeProviderPlugin"
}
