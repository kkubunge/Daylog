package com.aiutil.daylog

import android.app.Application
import com.aiutil.daylog.ai.GeminiClient
import com.aiutil.daylog.collector.PhoneDataCollector
import com.aiutil.daylog.data.DaylogStore
import com.aiutil.daylog.data.LocationVisitStore
import com.aiutil.daylog.data.SettingsStore
import com.aiutil.daylog.data.SnsCaptureStore
import com.aiutil.daylog.location.GeocodingHelper
import java.io.File

/**
 * 데이로그 전역 데이터 스토어 및 수집/위치/AI 엔진을 초기화한다.
 */
class App : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        settings = SettingsStore(this)
        snsCaptures = SnsCaptureStore(File(filesDir, "sns_captures_v2.json"))
        locationVisits = LocationVisitStore(
            visitsFile = File(filesDir, "gps_visits.json"),
            aliasesFile = File(filesDir, "place_aliases.json"),
        )
        daylogs = DaylogStore(File(filesDir, "daylog_records_v2.json"))
        geocoder = GeocodingHelper(this)
        collector = PhoneDataCollector(
            context = this,
            snsCaptureStore = snsCaptures,
            locationVisitStore = locationVisits,
            geocoder = geocoder,
        )
        gemini = GeminiClient()
    }

    companion object {
        lateinit var instance: App
            private set

        lateinit var settings: SettingsStore
            private set

        lateinit var snsCaptures: SnsCaptureStore
            private set

        lateinit var locationVisits: LocationVisitStore
            private set

        lateinit var daylogs: DaylogStore
            private set

        lateinit var geocoder: GeocodingHelper
            private set

        lateinit var collector: PhoneDataCollector
            private set

        lateinit var gemini: GeminiClient
            private set
    }
}
