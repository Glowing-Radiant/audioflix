package app.audioflix.provider

class ProviderRegistry(providers: List<CatalogProvider>) {
  val all: List<CatalogProvider> = providers
  private val byId = providers.associateBy { it.id }

  val default: CatalogProvider get() = all.first()

  /** The providers browsed by opening a link, in the order Browse offers them. */
  val folderProviders: List<FolderProvider> = providers.filterIsInstance<FolderProvider>()

  operator fun get(id: String): CatalogProvider =
    byId[id] ?: error("Unknown provider '$id'")

  fun folders(id: String): FolderProvider =
    get(id) as? FolderProvider ?: error("Provider '$id' isn't browsed by link")
}
