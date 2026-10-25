package com.example.offlinetranscription.model

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

private fun move(source: File, target: File) {
    Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
}

/** A leftover rollback file means activation did not commit before process death. */
internal fun recoverModel(destination: File) {
    val backup = File(destination.parentFile, destination.name + ".rollback")
    if (backup.exists()) {
        if (destination.exists() && Files.isSameFile(backup.toPath(), destination.toPath())) Files.delete(backup.toPath())
        else move(backup, destination)
    }
    Files.deleteIfExists(File(destination.parentFile, destination.name + ".rollback.tmp").toPath())
}

/** Validate native loading at its permanent path, with a recoverable previous file. */
internal suspend fun installModel(candidate: File, destination: File, activate: suspend (File) -> Unit) =
    withContext(NonCancellable) {
        recoverModel(destination)
        val backup = File(destination.parentFile, destination.name + ".rollback")
        if (destination.exists()) {
            val preparing = File(destination.parentFile, destination.name + ".rollback.tmp")
            try {
                try { Files.createLink(preparing.toPath(), destination.toPath()) }
                catch (_: UnsupportedOperationException) { Files.copy(destination.toPath(), preparing.toPath()) }
                catch (_: java.io.IOException) { Files.copy(destination.toPath(), preparing.toPath()) }
                java.io.FileOutputStream(preparing, true).use { it.fd.sync() }
                move(preparing, backup)
            } catch (e: Exception) { preparing.delete(); throw e }
        }
        var promoted = false
        try {
            move(candidate, destination)
            promoted = true
            activate(destination)
            Files.deleteIfExists(backup.toPath())
        } catch (failure: Exception) {
            try {
                if (promoted && destination.exists()) move(destination, candidate)
                if (backup.exists()) {
                    recoverModel(destination)
                    if (promoted) activate(destination)
                }
            } catch (rollback: Exception) { failure.addSuppressed(rollback) }
            throw failure
        }
    }
