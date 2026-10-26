package top.mores.bFBackpack.storage;

import java.sql.SQLException;

/** A failed compare-and-swap, never eligible for unconditional overwrite. */
public final class RevisionConflictException extends SQLException {
    public RevisionConflictException() { super("Stored revision differs from expected revision", "40001"); }
}
