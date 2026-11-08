# Аналитика задач Jira

Программа на Kotlin загружает задачи проекта через Jira REST API и создаёт HTML-отчёт. В нём есть шесть видов графиков: время до разрешения задачи, время в каждом статусе, задачи по дням и накопительный итог, топ 30 исполнителей и репортёров, залогированное время и распределение по приоритетам.

## Требования

JDK 22 и сетевой доступ к Jira. Для публичной Apache Jira учётная запись не нужна. Kotlin и Gradle отдельно устанавливать не нужно: сборка использует Gradle Wrapper. При первой сборке нужен интернет для загрузки зависимостей.

## Настройка

Измените `config/app.properties`:

- `jira.project` — ключ проекта, например `KAFKA` или `HDFS`.
- `jira.maxIssues` — число задач для отчёта; `0` означает весь проект.
- `jira.newestFirst` — `true` для новых задач сначала, `false` для старых; при `maxIssues=0` не используется.
- `report.output` — каталог отчёта. Для разных проектов укажите разные каталоги, чтобы отчёты не перезаписывались.

Для закрытой Jira задайте переменную окружения `JIRA_TOKEN`. Для Basic Auth дополнительно задайте `JIRA_USER`; без неё токен отправляется как Bearer. Не сохраняйте токен в Git.

## Запуск

На macOS с Apple Silicon из корня проекта:

```bash
bash macos.sh build
bash macos.sh
open report/index.html
```

При первом вызове `macos.sh` скачивает JDK 22 в `runtime/`, проверяет SHA-256 и использует его для Gradle. Системная Java не изменяется. Дополнительные задачи Gradle передаются аргументами, например `bash macos.sh test` или `bash macos.sh distZip`.

Из корня проекта в Windows:

```powershell
.\gradlew.bat run --args="config/app.properties"
```

При уже выбранном JDK 22 в Linux или macOS используйте `bash gradlew run --args="config/app.properties"`. После завершения откройте `report/index.html` или `index.html` в каталоге `report.output`. Для строгой проверки установленного JDK на macOS используйте `/usr/libexec/java_home -F -v 22`: без `-F` при отсутствии версии 22 утилита может вернуть другую Java.

Тесты запускаются отдельно: `./gradlew.bat test` в Windows или `bash gradlew test` в Linux/macOS.

Для сборки дистрибутива выполните `./gradlew.bat distZip` (или `bash gradlew distZip`). Архив появится в `build/distributions/`. После распаковки запускайте `run.bat` в Windows или `./run.sh` в Linux/macOS.

## Как считаются данные

Закрытой считается задача с заполненным `resolutiondate`; длительность и время в статусах считаются до этой даты. Залогированное время берётся из суммарного поля задачи `timespent`, без разделения по авторам worklog. Дневные показатели считаются в часовом поясе `report.zone`. При `jira.maxIssues > 0` отчёт отражает только выбранную часть проекта.
