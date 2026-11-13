package com.bloodline.android.storage

import android.content.Context
import android.net.Uri
import android.os.Environment
import com.bloodline.android.game.GameLocation
import com.bloodline.android.game.LocationKind
import java.io.File

/**
 * Turns a game location into a path a native process can open.
 *
 * App-storage installs already carry a plain path. A folder picked through the
 * Storage Access Framework carries a `content://` tree URI, which a native
 * `wine` process cannot read. Android encodes the real location in the tree's
 * document id (`primary:Games/Bloodlines` or `XXXX-XXXX:Games`), so the real
 * path can be recovered without copying anything.
 */
object SafPathResolver {

    /**
     * @return a filesystem path the runtime can use, or null when the location
     *         cannot be turned into one (a revoked URI, or a provider whose
     *         document id is not a plain volume path).
     */
    fun resolve(context: Context, location: GameLocation): File? {
        return when (location.kind) {
            LocationKind.APP_STORAGE -> File(location.reference)

            LocationKind.EXTERNAL_FOLDER -> {
                val uri = Uri.parse(location.reference)
                val id = uri.lastPathSegment ?: return null
                val colon = id.indexOf(':')
                if (colon <= 0) return null

                val volume = id.substring(0, colon)
                val relative = id.substring(colon + 1).trim('/')
                val root: File = when (volume) {
                    // "primary" is the device's shared storage, which is the
                    // same volume resolve() is asked about here.
                    "primary" -> Environment.getExternalStorageDirectory()
                    // A removable volume keeps its UUID as the folder name.
                    else -> File("/storage/$volume")
                }
                File(root, relative)
            }
        }
    }
}
