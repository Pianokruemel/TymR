package com.example.tymr.service

import android.content.Context
import android.content.Intent
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.WorkManager
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Constraints
import androidx.work.NetworkType
import java.util.concurrent.TimeUnit

class UpdateWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        // Start the foreground service with daily update
        val serviceIntent = Intent(applicationContext, ForegroundService::class.java)
        // Don't set force update, as this is the regular daily update
        applicationContext.startForegroundService(serviceIntent)
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "CalendarUpdateWorker"

        fun schedulePeriodicWork(context: Context) {
            // Run once per day when network is available
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val repeatingRequest = PeriodicWorkRequestBuilder<UpdateWorker>(
                1, TimeUnit.DAYS
            )
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                repeatingRequest
            )

            // Also run immediately
            val oneTimeRequest = OneTimeWorkRequestBuilder<UpdateWorker>()
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueue(oneTimeRequest)
        }

        // For immediate full update
        fun runImmediateUpdate(context: Context) {
            val serviceIntent = Intent(context, ForegroundService::class.java)
            serviceIntent.action = "FORCE_UPDATE"
            context.startForegroundService(serviceIntent)
        }

        // For updating a specific calendar source
        fun updateCalendarSource(context: Context, url: String) {
            val serviceIntent = Intent(context, ForegroundService::class.java)
            serviceIntent.action = "FORCE_UPDATE"
            serviceIntent.putExtra("url", url)
            context.startForegroundService(serviceIntent)
        }

        fun cancelPeriodicWork(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}