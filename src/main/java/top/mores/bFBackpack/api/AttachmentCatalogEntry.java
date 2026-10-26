package top.mores.bFBackpack.api;

/** Catalog-compatible attachment and current player access; real runtime compatibility is separate. */
public record AttachmentCatalogEntry(AttachmentDefinition attachment, UnlockResult unlock) {}
