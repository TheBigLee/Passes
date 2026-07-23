package ch.bigli.passes.update

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import ch.bigli.passes.PassApp
import ch.bigli.passes.domain.SourceFormat
import kotlinx.coroutines.flow.first

/**
 * Periodically re-fetches every pkpass that carries update info, so gate/seat/balance changes
 * surface without a manual refresh. Per-pass failures are swallowed (logged via [runCatching])
 * so one bad pass doesn't stop the rest from being checked; they're simply retried next cycle.
 */
class PassUpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val repo = (applicationContext as PassApp).repository
        val passes = repo.observeAll().first()
        passes
            .filter { it.sourceFormat == SourceFormat.PKPASS && it.updateInfo != null && !it.voided && it.autoUpdateEnabled }
            .forEach { runCatching { repo.refreshPass(it.id) } }
        return Result.success()
    }
}
