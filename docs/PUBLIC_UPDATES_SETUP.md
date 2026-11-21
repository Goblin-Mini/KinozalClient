# Публикация обновлений в публичный репозиторий

Исходный репозиторий `Goblin-Mini/KinozalClient` остаётся местом разработки. Готовые подписанные APK публикуются в `Goblin-Mini/KinozalTV-Updates`.

## GitHub Actions secret

В настройках исходного репозитория откройте **Settings → Secrets and variables → Actions → New repository secret** и добавьте:

- Name: `KINOZAL_UPDATE_REPO_TOKEN`
- Value: fine-grained personal access token с доступом только к `Goblin-Mini/KinozalTV-Updates` и разрешением **Contents: Read and write**.

Не добавляйте токен в исходники, README публичного репозитория или APK. Секрет нужен только GitHub Actions.

## Публикация

Workflow собирает release APK, проверяет SHA-256 сертификата `C3CEC28612A951F18A0016CD193B5CAD5404905712EE0A4BFC593FDFE0E83281`, затем при push в `main` публикует `KinozalClient.apk` в GitHub Release публичного репозитория. Параллельно сохраняется текущая публикация релиза в исходном репозитории для переходного периода.

## Переход существующих установок

Старые версии приложения обращаются к Releases исходного репозитория. Поэтому нельзя делать исходный репозиторий приватным до публикации переходной версии приложения, которая уже проверяет `KinozalTV-Updates`, и до обновления существующих установок. Иначе старые версии не смогут получить переходный APK.

После перехода новые версии проверяют `https://api.github.com/repos/Goblin-Mini/KinozalTV-Updates/releases/latest` и скачивают asset с именем `KinozalClient.apk`.

## Защита

Не менять `AppSignatureGuard`, `NativeSignatureGuard`, открытый ключ Plus или release keystore ради переноса хранилища. Публичный репозиторий содержит только README и релизные APK.
