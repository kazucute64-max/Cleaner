// Runs inside the process Shizuku spawns (shell UID on ADB-authorized Shizuku, or the
// Shizuku-manager's UID on root-authorized Shizuku) — never our app's own restricted UID.
// This is what actually gives Power Scan reach beyond scoped storage: shell UID can read
// several paths (e.g. other apps' /Android/data on many OS/OEM combinations) our app cannot,
// without this ever being "root". Every method here does real, synchronous file I/O — nothing
// is mocked, and callers must treat a -1 size / false result as "inaccessible", not zero.
package com.storagesweep.app.shizuku;

interface IPrivilegedFileService {

    /**
     * Lists one directory. Each entry is "name|isDir(0/1)|sizeBytes|lastModifiedMs".
     * Returns null if the path could not be read at shell UID (SecurityException or any other
     * read failure) — this is the signal callers must treat as Protected. Returns an empty
     * (zero-length, non-null) array if the directory genuinely has no entries. These two cases
     * are NOT the same thing and callers must check for null explicitly, not just emptiness.
     */
    String[] listDirectory(String path);

    /** Real byte size, or -1 if the path can't be stat'd even at shell UID. */
    long statSize(String path);

    /** True only if the shell-UID process actually observes the path existing right now. */
    boolean exists(String path);

    /** Deletes a single file/empty directory. Returns the real outcome — never assumed. */
    boolean deletePath(String path);

    /**
     * Resolves the real canonical path at shell UID (symlinks/`.`/`..` resolved), the same way
     * ScannerEngine's local walk uses File.canonicalPath for its cycle guard. Returns null if
     * canonicalization itself fails (e.g. SecurityException, or the path vanished) — callers
     * must fall back to a normalized-string comparison for that single path rather than treat
     * a null as "no cycle risk here", since a resolution failure tells you nothing about
     * whether a cycle exists.
     */
    String canonicalPath(String path);

    /** Lets the client confirm the service is alive and report which UID it's running as. */
    int getServiceUid();

    void destroy();
}
