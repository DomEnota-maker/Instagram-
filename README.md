# MediaLoader 0.1.0-alpha

Android-приложение «Загрузчик». Рабочая ветка `medialoader-alpha-integration`.

## Сейчас есть

- Kotlin + Jetpack Compose + Material 3;
- пакет `com.domenota.medialoader`;
- нижняя навигация: «Главная», «Загрузки», «Настройки»;
- отдельный transient-экран предпросмотра;
- тёмная тема и базовый UI-каркас;
- версия `1.0.0` (релиз Instagram), `versionCode 8`.

## Функциональный слой (интеграция из донора)

- `core/` — модели, `InstagramProvider` (ссылки p/reel/tv/stories, публичная попытка → авторизованный fallback), `InstagramApiClient`, `StorageNaming`, Room-схема. Разделение пакетами, Gradle-модуль один (`:app`).
- `data/` — `MediaRepository` (координатор), `HistoryRepository`, `SessionStore`, `StorageManager`/`StorageSettings`, `DownloadQueue` (до трёх небольших фото одновременно; видео и аудио последовательно), `DownloadEngine` (загрузка внутри приложения и перекодирование MP3).
- `ui/` — существующие экраны подключены через `MediaLoaderViewModel`; `LoginActivity` — вход в WebView без JS bridge.

## Статус авторизации

Публичная попытка анализа ссылки выполняется без входа. Вход во встроенном WebView реализован, но проверка на телефоне 30.09.2026 **не завершилась успешно**: после CAPTCHA Instagram вернул страницу «Открыть Instagram», не выдав приложению подтверждённую сессию. Версия 3 пробует мобильный User-Agent без маркеров WebView, распознаёт публичный редирект на вход и позволяет вручную сохранить собственный `sessionid` в настройках. Эти изменения ещё не прошли проверку на телефоне; Stories и закрытые публикации пока не считаются рабочими. Пароль приложение не сохраняет.

Не пытайтесь считать успешную Gradle-сборку подтверждением работы входа. Для приёмки нужны проверка Instagram-сессии и полный ручной прогон на Android-устройстве.

## Сборка

JDK 17, Android SDK 35, Gradle 8.9.

```sh
gradle :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```
