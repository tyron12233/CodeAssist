package dev.ide.desktop.store

import dev.ide.platform.log.Log
import dev.ide.store.impl.StoreTokenStore
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions

/**
 * Where the store's sign-in survives a restart on desktop: the refresh token in one file that only the user can
 * read, the way `gh`, `gcloud` and `npm` keep theirs.
 *
 * The desktop has nothing like the Android Keystore that works the same on all three systems, so this protects
 * the token from other users of the machine, not from the user's own account. The permissions are set on the
 * file before the token is written into it, and the write lands through a rename, so the token is never readable
 * by anyone else, not even for the moment between creating the file and restricting it.
 *
 * A file that cannot be written means no persistence: the user signs in again next launch, which is logged
 * rather than surfaced.
 */
internal class DesktopTokenStore(private val file: Path) : StoreTokenStore {

    private val log = Log.logger("ide.store.auth")

    override fun read(): String? = runCatching {
        if (!Files.isRegularFile(file)) return null
        Files.readString(file).trim().ifEmpty { null }
    }.getOrNull()

    override fun write(refreshToken: String?) {
        if (refreshToken.isNullOrBlank()) {
            runCatching { Files.deleteIfExists(file) }
            return
        }
        runCatching {
            val dir = file.toAbsolutePath().parent
            Files.createDirectories(dir)
            val tmp = createOwnerOnly(dir)
            try {
                Files.writeString(tmp, refreshToken)
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } finally {
                Files.deleteIfExists(tmp)
            }
        }.onFailure {
            log.warn("Sign-in could not be saved (${it.javaClass.simpleName}); it will not survive a restart")
        }
    }

    /** An empty file in [dir] that only its owner can read or write, restricted before anything goes in it. */
    private fun createOwnerOnly(dir: Path): Path {
        if (Files.getFileStore(dir).supportsFileAttributeView("posix")) {
            // Created with the permissions in one step, so there is no window where it is readable.
            val ownerOnly = PosixFilePermissions.asFileAttribute(
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            )
            return Files.createTempFile(dir, ".store-session", ".tmp", ownerOnly)
        }
        // Windows: replace the inherited ACL with a single entry for the owner. The file is still empty here.
        val tmp = Files.createTempFile(dir, ".store-session", ".tmp")
        val acl = Files.getFileAttributeView(tmp, AclFileAttributeView::class.java)
            ?: return tmp.also { Files.delete(it); error("no permission model to restrict the sign-in file with") }
        acl.acl = listOf(
            AclEntry.newBuilder()
                .setType(AclEntryType.ALLOW)
                .setPrincipal(acl.owner)
                .setPermissions(AclEntryPermission.entries.toSet())
                .build(),
        )
        return tmp
    }
}
