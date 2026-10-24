package com.smartstudent.planner.data.repo

import retrofit2.HttpException

/**
 * Should a failed push stop the whole sync (and be retried later)?
 * Permanent rejections (e.g. 400 validation) are skipped so one bad row can't block the rest.
 */
fun isRetryable(e: HttpException): Boolean =
    e.code() == 401 || e.code() == 408 || e.code() == 429 || e.code() >= 500
