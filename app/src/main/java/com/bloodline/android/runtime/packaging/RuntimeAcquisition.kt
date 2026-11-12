package com.bloodline.android.runtime.packaging

/**
 * Installs every package the runtime needs, one at a time, with progress.
 *
 * The sequencing is the point. Downloads are hundreds of megabytes over a
 * mobile connection, so interruption is the normal case rather than the
 * exception, and the rules below are what make an interrupted install
 * recoverable rather than mysterious:
 *
 * * **Largest first.** Wine is 226 MB of a 227 MB total. If it is going to
 *   fail, the user should find out immediately, not after everything else has
 *   downloaded.
 * * **Stop on the first failure.** A Wine that failed to unpack is not
 *   improved by also unpacking WOWBox64, and a partial runtime is harder to
 *   diagnose than a clean failure.
 * * **Report what is left.** The caller shows "2 of 3" rather than a bare
 *   percentage, because a percentage gives no sense of how much longer this
 *   will take.
 *
 * Nothing here touches the network directly: that is
 * [RuntimePackageManager]'s job, and keeping it there is what makes this
 * class testable without a socket.
 */
class RuntimeAcquisition(
    private val manager: RuntimePackageManager,
    private val abi: String,
) {

    /**
     * Where an attempt got to.
     *
     * [Outcome.Installed] carries how many packages were **skipped as already
     * present**, so a second run does not look like it downloaded 226 MB again.
     */
    sealed interface Outcome {
        data class Installed(val downloaded: Int, val alreadyPresent: Int) : Outcome
        data class Failed(val packageName: String, val reason: String) : Outcome
        data object NothingToDo : Outcome
        data object Cancelled : Outcome
    }

    /** Progress across the whole set, not just the current package. */
    data class Progress(
        val pack: RuntimePackage,
        val index: Int,
        val total: Int,
        val phase: RuntimePackageManager.Phase,
        val percent: Int,
    ) {
        /** "Downloading Wine 11.3 (ARM64EC) — 2 of 3, 41%" */
        override fun toString(): String =
            "${phase.name.lowercase().replaceFirstChar { it.uppercase() }} ${pack.name} " +
                "— ${index + 1} of $total" +
                if (percent > 0) ", $percent%" else ""
    }

    fun installAll(
        packages: List<RuntimePackage> = RuntimePackageCatalog.REQUIRED,
        onProgress: (Progress) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): Outcome {
        val pending = packages.filter { manager.stateOf(it) !is RuntimePackageManager.State.Installed }
        val alreadyPresent = packages.size - pending.size

        if (pending.isEmpty()) {
            return if (packages.isEmpty()) Outcome.NothingToDo
            else Outcome.Installed(downloaded = 0, alreadyPresent = alreadyPresent)
        }

        var downloaded = 0
        pending.forEachIndexed { index, pack ->
            if (isCancelled()) return Outcome.Cancelled

            // The State install() returns is the only record of *why* a package
            // failed, so it is the thing that gets reported.
            //
            // This used to re-read the install marker instead and report
            // whatever state it found. No failure writes a marker, so every
            // failure — a refused socket, a wrong length, a bad checksum, a
            // broken archive — came back as the same uninformative
            // "The package did not install (Missing)", and the reason install()
            // had actually worked out was thrown away. On a phone that is the
            // whole difference between "the download was refused" and "no idea".
            val result = manager.install(pack, abi) { progress ->
                onProgress(
                    Progress(
                        pack = pack,
                        index = index,
                        total = pending.size,
                        phase = progress.phase,
                        percent = progress.percent,
                    ),
                )
            }

            when (result) {
                is RuntimePackageManager.State.Installed -> downloaded++

                is RuntimePackageManager.State.Failed ->
                    return Outcome.Failed(pack.name, result.reason)

                is RuntimePackageManager.State.Stale ->
                    return Outcome.Failed(
                        pack.name,
                        "${result.installed} is installed but ${result.expected} is required.",
                    )

                RuntimePackageManager.State.Missing ->
                    return Outcome.Failed(
                        pack.name,
                        "The package was not installed and gave no reason.",
                    )
            }
        }

        return Outcome.Installed(downloaded = downloaded, alreadyPresent = alreadyPresent)
    }

    /** How many bytes [packages] will take, for the confirmation the UI shows. */
    fun totalBytes(packages: List<RuntimePackage> = RuntimePackageCatalog.REQUIRED): Long =
        packages.filter { manager.stateOf(it) !is RuntimePackageManager.State.Installed }
            .sumOf { it.sizeBytes }
}
