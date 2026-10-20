package org.jellyfin.mobile.downloads

enum class DownloadFileType {
    /**
     * The main file for an item (e.g. the video file for episodes/movies, or the audio file for music).
     */
    ITEM,

    /**
     * The primary image for the item.
     */
    IMAGE_PRIMARY,

    /**
     * KinoLel: Untertitel als eigene Datei (externe Untertitel; bei verkleinerter Qualität alle Text-Untertitel).
     * Dateiname „untertitel-<Index>.<Format>“, Index = Stream-Index im gespeicherten Item.
     */
    SUBTITLE,
}
