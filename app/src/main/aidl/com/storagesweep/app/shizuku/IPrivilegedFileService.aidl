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
     * Returns an empty array (not null) if the path is unreadable even at shell UID —
     * callers must record that as Protected, not silently skip it.
     */
    String[] listDirectory(String path);

    /** Real byte size, or -1 if the path can't be stat'd even at shell UID. */
    long statSize(String path);

    /** True only if the shell-UID process actually observes the path existing right now. */
    boolean exists(String path);

    /** Deletes a single file/empty directory. Returns the real outcome — never assumed. */
    boolean deletePath(String path);

    /** Lets the client confirm the service is alive and report which UID it's running as. */
    int getServiceUid();

    void destroy();
}
