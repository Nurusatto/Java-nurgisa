# REST API

Префикс `/api/v1`. JSON-ошибки соответствуют ApiError. Денежные значения имеют два
десятичных знака. Моменты времени передаются в ISO-8601 UTC. Списки возвращают
`content`, `totalElements`, `page` (с нуля), `size` (1–100).

## Вход и сессия

1. `GET /auth/csrf`, сохранить cookie и поля token/headerName.
2. `POST /auth/login`, Content-Type `application/x-www-form-urlencoded`, поля email/password;
   передать CSRF-заголовок и cookie.
3. Повторно получить `/auth/csrf` после входа. Все изменяющие запросы используют новый токен.
4. `/auth/me`, `POST /auth/logout`, `POST /auth/change-password`.

Роль и active проверяются сервером. Смена пароля/роли и деактивация отзывают сессии.
При изменении версионируемой записи передаётся её `version`; устаревшая версия даёт 409.

## Ресурсы

- customers, devices, users, parts, suppliers: GET список/карточка, POST, PUT карточки.
- repair-orders: GET/POST/PUT; команды assign, status, diagnostics, works, approvals.
- `PUT /repair-orders/{id}/works/{work}/price`: подтверждение цены менеджером.
- parts/{id}/receipts, adjustments; repair-orders/{id}/parts и parts/{usage}/return.
- purchase-orders: создание, карточка, items, order, receive, cancel.
- repair-orders/{id}: complete, payments, adjustments, deliver, cancel, balance.
- payments/{id}/refunds, expenses, warranties/{id}/claims и revoke.
- notifications: список, создание, unread-count, PATCH {id}/read.
- reports/dashboard, repairs, inventory, financial и `{type}/csv`.
- audit-logs, shop-settings; настройки изменяет только OWNER.
- repair-orders/{id}/tracking: POST выдаёт новую ссылку, DELETE отзывает ссылки.
- public/repair-status/{token}: только безопасная публичная проекция.
- repair-orders/{id}/documents/intake|completion|payment: реальные печатные данные.

Создание клиентов/устройств/деталей/поставщиков/заказов, записей диагностики/работ/согласований,
изменяющие складские/финансовые команды, создание закупок и гарантийных обращений
требуют `Idempotency-Key` длиной 8–100 символов. Повтор с тем же телом возвращает
прежний результат; другое тело с тем же ключом даёт 409. Клиент сохраняет ключ при
неопределённом сетевом результате. Секрет tracking не сохраняется в журнале идемпотентности.

Фильтры списков: q, page, size, а также статус/категория/мастер/приоритет и связи,
где они применимы. Временные списки принимают from/to (нижняя граница включительно,
верхняя исключительно). Отчёты также принимают календарные даты; верхняя дата включается
в выбранном часовом поясе мастерской. Сортировка задаётся sort и direction, поля разрешены явно.

OpenAPI: `/v3/api-docs`, Swagger UI: `/swagger-ui/index.html`, доступны после входа.
В `prod` они отключены; явное включение — `FIXFLOW_OPENAPI_ENABLED=true`.
Фильтры `low_stock=true` у parts и `overdue=true` у repair-orders доступны в интерфейсе.
