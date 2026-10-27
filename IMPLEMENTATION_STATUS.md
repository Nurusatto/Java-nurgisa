# FixFlow — состояние реализации

Функциональный MVP проверен локально. Обновление 28.10.2026: диагностика неготового backend в Windows launcher.

## Готово
- Три Maven-модуля; Java 21, Spring Boot REST, JavaFX/FXML/CSS, PostgreSQL, Flyway V1–V6.
- Сессии/CSRF/BCrypt, три роли, отзыв сессий, ограничение входа, аудит.
- Клиенты/устройства/сотрудники, ремонт от приёма до выдачи: диагностика, согласования,
  подтверждение цен, история, допустимые статусы и контроль версий.
- Склад со средневзвешенной себестоимостью и возвратами; частичные поступления закупок;
  блокировки остатков, транзакции и идемпотентность.
- Частичные оплаты/долг/возвраты/корректировки, расходы, гарантии/повторные обращения.
- Dashboard, три отчёта/CSV, квитанции/PrinterJob, уведомления, tracking, настройки.
- Основные экраны работают через асинхронный REST; JDBC/backend-зависимостей в desktop нет.
- Dev-seed, проверка его целостности, новая demo-БД без удаления прежней; документация.
- Dockerfile backend (JDK/JRE 21, UID 10001), Compose backend + PostgreSQL, readiness после seed.
- start.cmd / stop.cmd: Docker, ожидание готовности, автоматическая установка JDK 21/Maven/JavaFX
  и открытие окна Windows; существующие .env и том PostgreSQL сохраняются.
- start.sh / stop.sh: запуск из Linux/fish, проверка JDK 21 и readiness, отдельный запуск
  клиента для работающего backend; `.env` читается как данные без выполнения команд.

## Последние фактические проверки (28.10.2026)
- Причина Windows `unhealthy` установлена по присланному логу: SQL State `3D000`,
  `database "fixflow_demo_ready" does not exist`. `.env` перенесён с локальным именем БД,
  которого нет в PostgreSQL Docker. Документирована команда `createdb` с сохранением тома.
  Её нельзя считать выполненной на Windows до получения результата пользователя.
- Исправление проверено на PostgreSQL 18.6: подключение к собственной отсутствующей
  временной БД отклонено, `createdb` создал её, повторное подключение успешно. Удалена
  только созданная этой проверкой пустая БД; базы приложения не изменялись.
- Windows launcher при сбое Compose теперь выводит `compose ps --all` и 120 строк
  backend, затем сохраняет исходную ошибку. PowerShell 7.6.9 на Linux:
  `test-scripts/start-windows.Tests.ps1` — **11 проверок прошли**. Windows 5.1 и реальный
  контейнер этой среды не проверены; локальный daemon по-прежнему недоступен.
- Исправление Docker SHA-256: в build-stage установлен `unzip`. Без него Wrapper выбирал
  TAR.GZ, но сравнивал его с закреплённой суммой ZIP. Проверка целостности сохранена.
- Официальные Maven 3.9.16 ZIP/TAR.GZ скачаны и проверены: ZIP совпал с суммой проекта,
  TAR.GZ имеет другую сумму. `./mvnw --version` с новым пустым wrapper-кешем прошёл:
  Maven 3.9.16 / Java 21.0.13.0.2. Команда из Dockerfile
  `./mvnw -B -ntp -pl fixflow-backend -am -DskipTests package` и `javac --release 21`
  для Healthcheck выполнены локально успешно; в этой команде тесты намеренно пропущены.
- `python3 test-scripts/wrapper-download.py`: 3 проверки прошли — верный ZIP принимается,
  повреждённый ZIP и TAR.GZ с суммой ZIP отклоняются до запуска. Добавлены в Linux CI.
  Логи: /tmp/fixflow-wrapper-cold.log, /tmp/fixflow-docker-step.log.
- `python3 test-scripts/start-linux.py`: 8 проверок прошли, включая обработку сбоев,
  ожидание Compose/readiness, запрет исполнения `.env` и остановку с сохранением тома.
- `./start.sh --desktop-only`: desktop/контракты установлены, настоящее новое окно FixFlow
  подтверждено через X11. `fish --no-config -c './start.sh --help'` и Bash-синтаксис проверены.
  `./start.sh --services-only` корректно сообщает об отсутствии Docker daemon и завершает запуск.
- При локальной упаковке JAR потребовался перезапуск dev-backend: он запущен из отдельной
  копии JAR, readiness `UP`; в dev-БД 83 заказа / 8 пользователей / 27 выданных заказов.
- `./mvnw -B -ntp verify`: BUILD SUCCESS, **37 тестов, 0 failures/errors/skipped**.
  Проверка предыдущего этапа: выполнена с загруженным dev .env; тестовый профиль изолирован,
  seed-маркеров в тестовой БД нет. После исправления Dockerfile Java-код не менялся.
- PowerShell 7.6.9 на Linux: 9 проверок функций launcher прошли; 3 HTTP-проверки Docker healthcheck прошли.
- Настоящий PowerShell прочитал порт из Compose и проверил официальный Windows JDK ZIP по SHA-256.
  В архиве JDK проверено наличие java.exe/javac.exe. Метаданные и образы Java 21 доступны.
- `./mvnw -B -ntp -DskipTests install` был успешен на предыдущем этапе.
- V1–V6 применены на чистых dev/prod базах; health UP.
- Seed: 8 сотрудников, 30 клиентов, 60 устройств, 80 заказов/10 статусов,
  72 диагностики, 122 работы, 40 запчастей, 5 поставщиков, 10 закупок,
  60 платежей, 25 расходов, 24 гарантии; SeedVerifier прошёл до commit.
- Повторный seed: количества совпали; созданный через GUI выданный заказ сохранён после рестарта.
  GUI-проверки добавляют свои явно помеченные записи сверх исходного seed.
- Production: bootstrap создал одного OWNER; клиентов/заказов/seed-маркеров — 0 даже с demo-флагом.
  Реальный вход, Secure cookie и отключённый OpenAPI проверены HTTP-запросами; TLS не проверялся.
- HTTP: dev-вход/OpenAPI, три CSV, квитанции приёма/работ/оплаты.
- `scripts/gui-smoke.sh`: GUI SMOKE SUCCESS на окончательной сборке: 20 экранов владельца,
  три роли, сортировка/фильтры, десятичная запятая, полный ремонт/оплата/выдача/гарантия.
- `scripts/dev-start.sh desktop`: настоящее окно FixFlow подтверждено через X11.
- Compose config, YAML трёх CI jobs, bash-синтаксис, git diff --check и ссылки документации проверены.
  CRLF-версия mvnw нормализована командой Dockerfile и фактически запущена: Maven 3.9.16 / Java 21.
  Исходные 77 956 байт README сохранены, SHA-256 совпал. .env игнорируется Git.
- Manifest официального postgres:18.6 доступен (HTTP 200); Java 21.0.13.0.2 / PostgreSQL 18.6.

## Ограничения и следующий шаг
- Новый `docker compose up -d --build --wait` фактически запускался, но завершился ошибкой:
  отсутствует /var/run/docker.sock, UID 1000 не может запустить daemon. Сборка/запуск образа не подтверждены.
  После установки `unzip` в Dockerfile команда `docker compose --env-file .env.example build backend`
  повторена с тем же результатом: нет сокета daemon. Полный Docker-запуск Linux также не подтверждён.
- Полный запуск start.cmd и настоящее окно на Windows не проверены; функции протестированы на Linux.
  В CI добавлены Windows PowerShell 5.1 и Docker smoke; удалённый GitHub Actions не запускался.
- Физический принтер и production TLS не проверены. Отдельный PDF-генератор и установщики ОС
  отложены согласно границам первой поставки; ограничения поддержки Boot описаны в архитектуре.
- Следующий шаг на Windows: создать отсутствующую `fixflow_demo_ready` через `createdb`
  (либо выбрать существующую БД с нужными заказами в `.env`), затем повторить `start.cmd`.
  Подтвердить health/миграции/seed и окно на Windows; успешный повтор пока не подтверждён.
  Инструкция: docs/windows.md. Docker/Testcontainers требуют работающего daemon.

## Текущая локальная среда
PostgreSQL: 127.0.0.1:55432, кластер /tmp/fixflow-pgdata, dev-БД fixflow_demo_ready.
Backend на http://127.0.0.1:18082, запущен из .local/runtime/fixflow-backend-docker-fix.jar;
первоначальное окно JavaFX сохранено. Открыть ещё одно: `./start.sh --desktop-only`.
Локальный .env сохранён; новые launcher не требуют исполнения его содержимого.
Maven-кэш этой среды в /tmp; его параметры сохранены только в игнорируемом .env.
Логи: /tmp/fixflow-verify-final-checked.log, /tmp/fixflow-gui-final-checked.log,
/tmp/fixflow-backend-final.log. Запуск с нуля и demo-сценарий — раздел 33 README.

Логи обновления: /tmp/fixflow-verify-docker.log, /tmp/fixflow-launcher-checks.log.
Архив обновлённого исходного проекта для Windows: .local/releases/FixFlow-windows.zip (без .env, target и кешей).
