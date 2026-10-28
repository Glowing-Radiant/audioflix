package app.audioflix.provider

class ProviderRegistry(providers: List<CatalogProvider>) {
  val all: List<CatalogProvider> = providers
  private val byId = providers.associateBy { it.id }

  val default: CatalogProvider get() = all.first()

  operator fun get(id: String): CatalogProvider =
    byId[id] ?: error("Unknown provider '$id'")
}
