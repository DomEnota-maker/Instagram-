# Сборка alpha01

Проверочная ветка запускает `:core:testDebugUnitTest`, `:app:lintDebug` и `:app:assembleDebug` в GitHub Actions. Debug APK подписывается стандартным отладочным ключом Android Gradle Plugin и предназначен для установки и проверки Foundation. Релизный ключ хранится вне репозитория; выпуск релизного APK потребует отдельной безопасной настройки подписи.
