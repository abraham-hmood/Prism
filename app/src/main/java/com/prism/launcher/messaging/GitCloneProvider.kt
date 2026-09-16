package com.prism.launcher.messaging

import org.eclipse.jgit.api.Git
import java.io.File

/**
 * Real git-clone implementation behind [DatasetDownloader.gitCloneProvider] -- JGit, a pure-JVM
 * git client, since Android has no native `git` binary to shell out to. Wired in
 * `PrismApp.onCreateMainProcess`, the same "install a real implementation of a capability :core
 * can't own the dependency for" pattern `AgenticP2pHost`/`AgenticMessagingTools` already use.
 *
 * A shallow (`--depth 1`) single-branch clone: [DatasetDownloader] only ever reads a repo's
 * current file contents, never its history, so nothing more is fetched than that needs.
 */
object GitCloneProvider {
    fun clone(repoUrl: String, targetDir: File): Boolean {
        return try {
            Git.cloneRepository()
                .setURI(repoUrl)
                .setDirectory(targetDir)
                .setDepth(1)
                .setCloneAllBranches(false)
                .call()
                .use { }
            true
        } catch (e: Exception) {
            false
        }
    }
}
