package app.booklauncher.data

/** Other ebook apps worth a handoff, most popular first. Only installed ones are shown. */
object ReaderApps {
    val PACKAGES = listOf(
        "com.amazon.kindle",                  // Kindle
        "com.google.android.apps.books",      // Google Play Books
        "com.kobobooks.android",              // Kobo
        "com.overdrive.mobile.android.libby", // Libby
        "com.scribd.app.reader0",             // Everand
        "com.pratilipi.mobile.android",       // Pratilipi
        "wp.wattpad",                         // Wattpad
        "com.flyersoft.moonreaderp",          // Moon+ Reader Pro
        "com.flyersoft.moonreader",           // Moon+ Reader
        "org.readera",                        // ReadEra
        "org.koreader.launcher",              // KOReader
        "com.foobnix.pdf.reader",             // Librera
        "com.faultexception.reader",          // Lithium
        "com.obreey.reader",                  // PocketBook
    )

    /** Installed reader apps from the catalog, in [PACKAGES] order. */
    fun installed(catalog: AppCatalog, hidden: Set<String>): List<AppCatalog.Entry> {
        val byPackage = catalog.apps.filter { it.key !in hidden }.groupBy { it.component.packageName }
        return PACKAGES.mapNotNull { byPackage[it]?.firstOrNull() }
    }
}
