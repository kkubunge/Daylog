package com.aiutil.daylog

import android.app.Application
import com.aiutil.daylog.ai.AiDiaryComposer
import com.aiutil.daylog.collector.PhoneDataCollector
import com.aiutil.daylog.data.DaylogStore
import com.aiutil.daylog.data.SettingsStore
import com.aiutil.daylog.data.SnsCaptureStore
import java.io.File

/**
 * 데이로그 전역 스토어와 수집 엔진을 초기화하는 Application 클래스.
 */
class App : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        settings = SettingsStore(this)
        snsCaptures = SnsCaptureStore(File(filesDir, "sns_captures.json"))
        daylogs = DaylogStore(File(filesDir, "daylog_entries.json"))
        collector = PhoneDataCollector(this, snsCaptures)
        aiComposer = AiDiaryComposer()

        // 첫 실행 시 앱 컨셉과 과거 회상 경험을 즉시 확인할 수 있도록 샘플 일기를 1회 시딩
        daylogs.seedSampleEntriesIfNeeded(settings)
    }

    companion object {
        lateinit var instance: App
            private set

        lateinit var settings: SettingsStore
            private set

        lateinit var snsCaptures: SnsCaptureStore
            private set

        lateinit var daylogs: DaylogStore
            private set

        lateinit var collector: PhoneDataCollector
            private set

        lateinit var aiComposer: AiDiaryComposer
            private set
    }
}
